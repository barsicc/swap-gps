package io.github.barsicc.swapgps.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Точка на карте. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun format(): String = String.format(java.util.Locale.US, "%.6f, %.6f", latitude, longitude)
}

/** Текущее состояние подмены, общее для экрана, уведомления и плитки в шторке. */
data class SpoofStatus(
    /** Подмена сейчас работает (сервис запущен и отправляет координаты). */
    val active: Boolean = false,
    val point: GeoPoint? = null,
    val startedAt: Long = 0L,
    /** Подмена была запущена, но процесс приложения убила система, а сброса не было. */
    val interrupted: Boolean = false,
    /** Последняя ошибка, понятная человеку. */
    val error: String? = null,
)

/** Короткое сообщение для пользователя (показывается плашкой и исчезает). */
data class Notice(val text: String, val success: Boolean, val id: Long = System.nanoTime())

object SpoofState {
    private const val PREFS = "spoof_state"
    private const val KEY_RUNNING = "running"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_LAST_LAT = "last_lat"
    private const val KEY_LAST_LON = "last_lon"
    private const val KEY_GOOGLE_MOCK = "google_mock"

    private val _status = MutableStateFlow(SpoofStatus())
    val status: StateFlow<SpoofStatus> = _status.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    fun notify(text: String, success: Boolean = true) {
        _notice.value = Notice(text, success)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Вызывается при старте процесса. Если в настройках записано, что подмена шла,
     * а сервис ещё не запущен — значит, система убила приложение. Сообщаем об этом.
     */
    fun restore(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_RUNNING, false) && !_status.value.active) {
            _status.value = _status.value.copy(interrupted = true, point = savedPoint(context))
        }
    }

    fun setActive(context: Context, point: GeoPoint) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_LAT, point.latitude.toRawBits())
            .putLong(KEY_LON, point.longitude.toRawBits())
            .putLong(KEY_LAST_LAT, point.latitude.toRawBits())
            .putLong(KEY_LAST_LON, point.longitude.toRawBits())
            .apply()
        val startedAt = if (_status.value.active) _status.value.startedAt else System.currentTimeMillis()
        _status.value = SpoofStatus(active = true, point = point, startedAt = startedAt)
    }

    fun setInactive(context: Context, error: String? = null) {
        prefs(context).edit().putBoolean(KEY_RUNNING, false).apply()
        _status.value = SpoofStatus(active = false, point = null, error = error)
    }

    fun setError(error: String?) {
        _status.value = _status.value.copy(error = error)
    }

    /** Точка активной подмены (нужна, чтобы продолжить после перезапуска сервиса системой). */
    fun savedPoint(context: Context): GeoPoint? {
        val p = prefs(context)
        if (!p.contains(KEY_LAT)) return null
        return GeoPoint(Double.fromBits(p.getLong(KEY_LAT, 0)), Double.fromBits(p.getLong(KEY_LON, 0)))
    }

    /** Последняя точка, куда подменяли, — чтобы не вводить её заново. */
    fun lastPoint(context: Context): GeoPoint? {
        val p = prefs(context)
        if (!p.contains(KEY_LAST_LAT)) return null
        return GeoPoint(Double.fromBits(p.getLong(KEY_LAST_LAT, 0)), Double.fromBits(p.getLong(KEY_LAST_LON, 0)))
    }

    /** Подменять ли координаты и в сервисах Google (Fused Location Provider). */
    fun isGoogleMockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_GOOGLE_MOCK, true)

    fun setGoogleMockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GOOGLE_MOCK, enabled).apply()
    }
}
