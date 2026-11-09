package io.github.barsicc.swapgps.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.github.barsicc.swapgps.tile.SpoofTileService

/**
 * Движок подмены. Подменяет местоположение сразу на всех уровнях:
 *  1. Системные источники Android: gps, network и (с Android 12) fused.
 *  2. Сервисы Google (Fused Location Provider) — через них местоположение получают
 *     почти все приложения. В режиме подмены Google не подмешивает Wi-Fi, поэтому
 *     точка не скачет.
 *
 * Сброс (reset) убирает подмену со всех уровней и сам по себе не зависит от сервиса:
 * он сработает, даже если систему убила подмену раньше.
 */
object MockEngine {

    /** Высота над уровнем моря для подменённой точки. Пользователю не показывается. */
    private const val ALTITUDE = 150.0
    private const val GPS_ACCURACY = 3.9f
    private const val NETWORK_ACCURACY = 12f

    /** Сколько ждать свежих реальных координат после сброса. */
    private const val FRESH_FIX_TIMEOUT_MS = 45_000L

    private val providers: List<String>
        get() = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }

    @Volatile
    private var googleMockOn = false

    // ---------- Проверки ----------

    /** Выбрано ли приложение в «Для разработчиков» → «Приложение для фиктивных местоположений». */
    fun isSelectedMockApp(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------- Запуск ----------

    /** Запустить подмену в точке (или перенести уже идущую подмену в новую точку). */
    /** Поехать по маршруту (точки маршрута по дорогам, первая — откуда едем). */
    fun startRoute(context: Context, points: List<GeoPoint>) {
        if (points.size < 2) return
        val app = context.applicationContext
        SpoofService.pendingRoute = points
        ContextCompat.startForegroundService(app, Intent(app, SpoofService::class.java).setAction(SpoofService.ACTION_ROUTE))
    }

    /** Пауза / продолжить / остановиться здесь / сменить скорость во время поездки. */
    fun routeCommand(context: Context, action: String, speedKmh: Int? = null) {
        val app = context.applicationContext
        val intent = Intent(app, SpoofService::class.java).setAction(action)
        speedKmh?.let { intent.putExtra(SpoofService.EXTRA_SPEED, it) }
        runCatching { app.startService(intent) }
    }

    fun start(context: Context, point: GeoPoint) {
        val app = context.applicationContext
        val intent = Intent(app, SpoofService::class.java)
            .putExtra(SpoofService.EXTRA_LAT, point.latitude)
            .putExtra(SpoofService.EXTRA_LON, point.longitude)
        ContextCompat.startForegroundService(app, intent)
    }

    /** Установить подменённые источники. Возвращает false, если система не разрешила. */
    fun installProviders(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        for (name in providers) {
            try {
                // Остаток от прошлого запуска помешал бы добавить источник заново
                runCatching { lm.removeTestProvider(name) }
                addTestProvider(lm, name)
                lm.setTestProviderEnabled(name, true)
            } catch (e: SecurityException) {
                Diag.log("Нет права подменять «$name» — приложение не выбрано для фиктивных местоположений", e)
                return false
            } catch (e: Exception) {
                // Например, на старых прошивках нет отдельного fused — это не критично
                Diag.log("Не удалось подменить источник «$name»", e)
            }
        }
        Diag.log("Источники подменены: ${providers.joinToString()}")
        return true
    }

    private fun addTestProvider(lm: LocationManager, name: String) {
        val fine = name != LocationManager.NETWORK_PROVIDER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = ProviderProperties.Builder()
                .setHasNetworkRequirement(false)
                .setHasSatelliteRequirement(false)
                .setHasCellRequirement(false)
                .setHasMonetaryCost(false)
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(if (fine) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE)
                .build()
            lm.addTestProvider(name, props)
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                name, false, false, false, false, true, true, true,
                Criteria.POWER_LOW, if (fine) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE,
            )
        }
    }

    /** Включить режим подмены в сервисах Google. */
    @SuppressLint("MissingPermission")
    fun enableGoogleMock(context: Context) {
        if (!SpoofState.isGoogleMockEnabled(context)) return
        try {
            LocationServices.getFusedLocationProviderClient(context.applicationContext)
                .setMockMode(true)
                .addOnSuccessListener { Diag.log("Сервисы Google: режим подмены включён") }
                .addOnFailureListener { Diag.log("Сервисы Google: не удалось включить подмену", it) }
            googleMockOn = true
        } catch (e: SecurityException) {
            Diag.log("Сервисы Google: нет прав на подмену", e)
        }
    }

    /** Отправить точку во все подменённые источники. Вызывается сервисом раз в секунду. */
    @SuppressLint("MissingPermission")
    fun push(context: Context, point: GeoPoint, speed: Float = 0f, bearing: Float = 0f) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        for (name in providers) {
            try {
                lm.setTestProviderLocation(name, buildLocation(name, point, speed, bearing))
            } catch (e: Exception) {
                // Источник могли убрать сбросом между тиками — ничего страшного
            }
        }
        if (googleMockOn) {
            try {
                LocationServices.getFusedLocationProviderClient(context.applicationContext)
                    .setMockLocation(buildLocation(LocationManager.GPS_PROVIDER, point, speed, bearing))
            } catch (e: SecurityException) {
                googleMockOn = false
                Diag.log("Сервисы Google перестали принимать подмену", e)
            }
        }
    }

    private fun buildLocation(provider: String, point: GeoPoint, speed: Float, bearing: Float): Location =
        Location(provider).apply {
            latitude = point.latitude
            longitude = point.longitude
            altitude = ALTITUDE
            accuracy = if (provider == LocationManager.NETWORK_PROVIDER) NETWORK_ACCURACY else GPS_ACCURACY
            this.speed = speed
            this.bearing = bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            verticalAccuracyMeters = 4f
            speedAccuracyMetersPerSecond = 0.3f
            bearingAccuracyDegrees = 30f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) elapsedRealtimeUncertaintyNanos = 100_000.0
            // Некоторые приложения смотрят, сколько спутников «видит» GPS
            if (provider != LocationManager.NETWORK_PROVIDER) {
                extras = android.os.Bundle().apply { putInt("satellites", 11 + (point.latitude * 1e4).toInt().mod(4)) }
            }
        }

    // ---------- Сброс ----------

    /**
     * Полный сброс: остановить подмену, убрать подменённые источники, выключить режим
     * подмены Google и сразу запросить свежие реальные координаты.
     */
    fun reset(context: Context) {
        val app = context.applicationContext
        Diag.log("Сброс…")
        app.stopService(Intent(app, SpoofService::class.java))

        // Подменённые источники только удаляем, НЕ выключая их перед этим: выключение
        // рассылает приложениям «GPS отключён», и многие (счётчики расстояния, навигаторы)
        // после этого перестают следить за местоположением. Удаление же незаметно
        // возвращает настоящий источник, и приложения продолжают получать координаты.
        val lm = app.getSystemService(LocationManager::class.java)
        if (lm != null) {
            for (name in providers) {
                runCatching { lm.removeTestProvider(name) }
                    .onFailure { if (it is SecurityException) Diag.log("Не удалось убрать «$name»", it) }
            }
            Diag.log("Включены источники: ${runCatching { lm.getProviders(true).joinToString() }.getOrDefault("?")}")
        }

        // Выключаем подмену Google всегда, даже если не мы её включали в этом запуске:
        // именно из-за неё местоположение «залипает»
        googleMockOn = false
        try {
            val flp = LocationServices.getFusedLocationProviderClient(app)
            flp.setMockMode(false)
                .addOnSuccessListener {
                    Diag.log("Сервисы Google: режим подмены выключен")
                    // Отдать приложениям накопленные реальные точки сразу, не дожидаясь пакета
                    runCatching { flp.flushLocations() }
                }
                .addOnFailureListener { Diag.log("Сервисы Google: не удалось выключить подмену", it) }
        } catch (e: SecurityException) {
            Diag.log("Сервисы Google: нет прав выключить подмену", e)
        }

        // Помочь GPS быстрее поймать спутники: обновить время и прогноз орбит.
        // Данные спутников НЕ стираем (холодный старт), иначе реальная точка найдётся дольше.
        if (lm != null) {
            for (command in listOf("force_time_injection", "force_psds_injection", "force_xtra_injection")) {
                runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, command, null) }
            }
        }

        SpoofState.setInactive(app)
        SpoofTileService.requestUpdate(app)
        Diag.log("Подмена снята")
        SpoofState.notify("Подмена выключена. Возвращаем ваше реальное местоположение…")
        requestFreshFix(app)
    }

    /**
     * Запросить свежие реальные координаты, чтобы приложения сразу получили настоящую
     * точку, а не держались за последнюю подменённую.
     */
    @SuppressLint("MissingPermission")
    private fun requestFreshFix(context: Context) {
        if (!hasLocationPermission(context)) {
            Diag.log("Нет разрешения на геолокацию — свежие координаты не запрошены")
            SpoofState.notify("Подмена выключена")
            return
        }
        val started = SystemClock.elapsedRealtime()
        // Сообщаем пользователю один раз — когда первым откликнется Google или GPS
        val reported = java.util.concurrent.atomic.AtomicBoolean(false)
        fun reportReal() {
            if (reported.compareAndSet(false, true)) {
                SpoofState.notify("Готово: ваше реальное местоположение вернулось")
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (reported.compareAndSet(false, true)) {
                SpoofState.notify(
                    "Подмена выключена. Телефон ещё ищет вас — на улице или у окна это быстрее",
                    success = false,
                )
            }
        }, FRESH_FIX_TIMEOUT_MS)

        // 1. Сервисы Google
        val cts = CancellationTokenSource()
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc == null) Diag.log("Google: реальные координаты пока не получены")
                else {
                    Diag.log("Google: реальная точка через ${SystemClock.elapsedRealtime() - started} мс, точность ${loc.accuracy.toInt()} м")
                    reportReal()
                }
            }
            .addOnFailureListener { Diag.log("Google: ошибка получения координат", it) }
        Handler(Looper.getMainLooper()).postDelayed({ cts.cancel() }, FRESH_FIX_TIMEOUT_MS)

        // 2. Будим GPS, чтобы он быстрее поймал спутники
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        val handler = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Diag.log("GPS: реальная точка через ${SystemClock.elapsedRealtime() - started} мс")
                reportReal()
                lm.removeUpdates(this)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            handler.postDelayed({ lm.removeUpdates(listener) }, FRESH_FIX_TIMEOUT_MS)
        } catch (e: Exception) {
            Diag.log("GPS: не удалось запросить координаты", e)
        }
    }
}
