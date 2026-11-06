package io.github.barsicc.swapgps.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.barsicc.swapgps.R
import io.github.barsicc.swapgps.tile.SpoofTileService
import io.github.barsicc.swapgps.ui.MainActivity
import kotlin.math.roundToInt

/**
 * Сервис, который держит подмену: стоит в точке или едет по маршруту и каждые
 * полсекунды отправляет положение во все подменённые источники.
 * Работает как foreground-сервис с уведомлением, чтобы система его не останавливала.
 */
class SpoofService : Service() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_SPEED = "speed"

        const val ACTION_ROUTE = "route"
        const val ACTION_FINISH = "finish"
        const val ACTION_STOP_HERE = "stop_here"
        const val ACTION_SPEED = "speed"

        private const val CHANNEL_ID = "spoof"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 500L
        private const val ROUTE_TICK_MS = 250L
        private const val SAVE_EVERY_MS = 5_000L

        /** Маршрут для следующего запуска (передаётся в памяти: точек может быть много). */
        @Volatile
        var pendingRoute: List<GeoPoint>? = null

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Точка, где стоим (когда не едем). */
    @Volatile
    private var point: GeoPoint? = null

    /** Маршрут, по которому едем. */
    @Volatile
    private var route: RouteMovement? = null

    private var lastTick = 0L
    private var lastSave = 0L
    private var lastNotified = ""

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastTick == 0L) ROUTE_TICK_MS / 1000.0 else (now - lastTick) / 1000.0
            lastTick = now
            step(dt.coerceIn(0.05, 2.0), now)
            // Во время поездки — чаще, чтобы точка двигалась плавно даже на большой скорости
            handler?.postDelayed(this, if (route != null) ROUTE_TICK_MS else TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val running = point != null || route != null

        // Управление уже идущей поездкой
        when (intent?.action) {
            ACTION_FINISH -> { route?.skipToEnd(); return START_STICKY }
            ACTION_SPEED -> {
                route?.setSpeedKmh(intent.getIntExtra(EXTRA_SPEED, 60))
                return START_STICKY
            }
            ACTION_STOP_HERE -> {
                route?.let { r ->
                    val here = r.pointAt(r.traveled)
                    route = null
                    point = here
                    SpoofState.setRouteLine(null)
                    SpoofState.setActive(this, here)
                    Diag.log("Поездка остановлена: ${here.format()}")
                    updateNotification()
                }
                return START_STICKY
            }
        }

        // Новый маршрут
        val newRoute = if (intent?.action == ACTION_ROUTE) pendingRoute?.also { pendingRoute = null } else null
        // Новая точка; intent == null — система сама перезапустила сервис: продолжаем с сохранённой
        val newPoint = when {
            newRoute != null -> newRoute.first()
            intent != null && intent.hasExtra(EXTRA_LAT) -> GeoPoint(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0))
            intent == null || !running -> SpoofState.savedPoint(this)
            else -> null
        }

        // startForeground обязателен сразу после запуска, даже если дальше будет ошибка
        startInForeground(newPoint ?: point)

        if (newPoint == null) {
            if (!running) stopSelf()
            return if (running) START_STICKY else START_NOT_STICKY
        }

        if (!MockEngine.isSelectedMockApp(this)) {
            Diag.log("Старт отменён: приложение не выбрано для фиктивных местоположений")
            SpoofState.setInactive(this, "Выберите Swap GPS в «Для разработчиков» → «Приложение для фиктивных местоположений»")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            if (!MockEngine.installProviders(this)) {
                SpoofState.setInactive(this, "Система не разрешила подмену. Проверьте настройки разработчика")
                stopSelf()
                return START_NOT_STICKY
            }
            MockEngine.enableGoogleMock(this)
            acquireWakeLock()
        }

        if (newRoute != null) {
            val movement = RouteMovement(newRoute, SpoofState.routeSpeed(this))
            route = movement
            point = null
            SpoofState.setRouteLine(movement.points)
            SpoofState.setActive(this, newPoint, progress(movement))
            Diag.log("Поездка: ${(movement.totalMeters / 1000).fmt()} км со скоростью ${SpoofState.routeSpeed(this)} км/ч")
        } else {
            route = null
            point = newPoint
            SpoofState.setRouteLine(null)
            SpoofState.setActive(this, newPoint)
            Diag.log(if (running) "Точка изменена: ${newPoint.format()}" else "Подмена запущена: ${newPoint.format()}")
        }
        SpoofTileService.requestUpdate(this)
        updateNotification()

        if (thread == null) {
            thread = HandlerThread("spoof").also { it.start() }
            handler = Handler(thread!!.looper)
        }
        lastTick = 0L
        handler?.removeCallbacks(tick)
        handler?.post(tick)
        return START_STICKY
    }

    private fun step(dt: Double, now: Long) {
        val r = route
        if (r == null) {
            point?.let { MockEngine.push(this, it) }
            return
        }

        val sample = r.step(dt)
        MockEngine.push(this, sample.point, sample.speed, sample.bearing)

        if (r.finished) {
            // Приехали: стоим в конечной точке, отсюда можно строить следующий маршрут
            route = null
            point = sample.point
            SpoofState.setRouteLine(null)
            SpoofState.setActive(this, sample.point)
            SpoofState.notify("Вы на месте")
            Diag.log("Поездка завершена: ${sample.point.format()}")
            updateNotification()
            return
        }

        val progress = progress(r)
        SpoofState.updateMoving(sample.point, progress)
        if (now - lastSave > SAVE_EVERY_MS) {
            lastSave = now
            SpoofState.setActive(this, sample.point, progress) // на случай, если систему перезапустит сервис
            updateNotification()
        }
    }

    private fun progress(r: RouteMovement) = RouteProgress(
        totalMeters = r.totalMeters,
        remainingMeters = r.remainingMeters,
        speedKmh = (r.maxSpeed * 3.6).roundToInt(),
        currentKmh = (r.currentSpeed * 3.6).roundToInt(),
    )

    override fun onDestroy() {
        handler?.removeCallbacks(tick)
        thread?.quitSafely()
        thread = null
        handler = null
        point = null
        route = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startInForeground(point: GeoPoint?) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(point?.format().orEmpty()), type)
        } catch (e: Exception) {
            // Например, на Android 14+ без разрешения на геолокацию
            Diag.log("Не удалось запустить сервис подмены", e)
        }
    }

    private fun updateNotification() {
        val r = route
        val text = if (r != null) {
            "В пути · осталось ${(r.remainingMeters / 1000).fmt()} км"
        } else {
            point?.format().orEmpty()
        }
        if (text == lastNotified) return
        lastNotified = text
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reset = PendingIntent.getBroadcast(
            this, 1, Intent(this, ResetReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_reset), reset)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        // Не даём телефону усыпить подмену при выключенном экране
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SwapGps:spoof").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun Double.fmt() = String.format(java.util.Locale("ru"), "%.1f", this)
}
