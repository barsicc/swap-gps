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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.barsicc.swapgps.R
import io.github.barsicc.swapgps.tile.SpoofTileService
import io.github.barsicc.swapgps.ui.MainActivity

/**
 * Сервис, который держит подмену: раз в секунду отправляет одну и ту же точку
 * во все подменённые источники. Работает как foreground-сервис с уведомлением,
 * чтобы система его не останавливала.
 */
class SpoofService : Service() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        private const val CHANNEL_ID = "spoof"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 1000L

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

    @Volatile
    private var point: GeoPoint? = null

    private val tick = object : Runnable {
        override fun run() {
            point?.let { MockEngine.push(this@SpoofService, it) }
            handler?.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null — система сама перезапустила сервис: продолжаем с сохранённой точки
        val newPoint = if (intent != null && intent.hasExtra(EXTRA_LAT)) {
            GeoPoint(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0))
        } else {
            SpoofState.savedPoint(this)
        }

        // startForeground обязателен сразу после запуска, даже если дальше будет ошибка
        startInForeground(newPoint)

        if (newPoint == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!MockEngine.isSelectedMockApp(this)) {
            Diag.log("Старт отменён: приложение не выбрано для фиктивных местоположений")
            SpoofState.setInactive(this, "Выберите Swap GPS в «Для разработчиков» → «Приложение для фиктивных местоположений»")
            stopSelf()
            return START_NOT_STICKY
        }

        val wasRunning = point != null
        if (!wasRunning) {
            if (!MockEngine.installProviders(this)) {
                SpoofState.setInactive(this, "Система не разрешила подмену. Проверьте настройки разработчика")
                stopSelf()
                return START_NOT_STICKY
            }
            MockEngine.enableGoogleMock(this)
            acquireWakeLock()
        }

        point = newPoint
        SpoofState.setActive(this, newPoint)
        SpoofTileService.requestUpdate(this)
        Diag.log(if (wasRunning) "Точка изменена: ${newPoint.format()}" else "Подмена запущена: ${newPoint.format()}")

        if (thread == null) {
            thread = HandlerThread("spoof").also { it.start() }
            handler = Handler(thread!!.looper)
        }
        handler?.removeCallbacks(tick)
        handler?.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler?.removeCallbacks(tick)
        thread?.quitSafely()
        thread = null
        handler = null
        point = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startInForeground(point: GeoPoint?) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(point), type)
        } catch (e: Exception) {
            // Например, на Android 14+ без разрешения на геолокацию
            Diag.log("Не удалось запустить сервис подмены", e)
        }
    }

    private fun buildNotification(point: GeoPoint?): Notification {
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
            .setContentText(point?.format().orEmpty())
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
}
