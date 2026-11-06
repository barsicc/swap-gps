package io.github.barsicc.swapgps.places

import android.content.Context
import io.github.barsicc.swapgps.engine.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

/** Сохранённое место с коротким названием. */
data class Place(val id: Long, val name: String, val point: GeoPoint)

/** «Мои места»: хранятся на телефоне, в настройках приложения. */
object Places {
    private const val PREFS = "places"
    private const val KEY = "items"

    /** Насколько близко точка должна быть к месту, чтобы считаться им (метры). */
    private const val SAME_PLACE_METERS = 25.0

    private val _items = MutableStateFlow<List<Place>>(emptyList())
    val items: StateFlow<List<Place>> = _items.asStateFlow()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(context).getString(KEY, null) ?: return
        _items.value = runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Place(o.getLong("id"), o.getString("name"), GeoPoint(o.getDouble("lat"), o.getDouble("lon")))
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, name: String, point: GeoPoint) {
        save(context, _items.value + Place(System.currentTimeMillis(), name.trim(), point))
    }

    fun rename(context: Context, id: Long, name: String) {
        save(context, _items.value.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun delete(context: Context, id: Long) {
        save(context, _items.value.filterNot { it.id == id })
    }

    /** Сохранённое место в этой точке, если есть. */
    fun at(point: GeoPoint?): Place? {
        point ?: return null
        return _items.value.minByOrNull { distanceMeters(it.point, point) }
            ?.takeIf { distanceMeters(it.point, point) <= SAME_PLACE_METERS }
    }

    private fun save(context: Context, list: List<Place>) {
        _items.value = list
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("lat", it.point.latitude)
                    .put("lon", it.point.longitude),
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Расстояние между точками; для пары десятков метров достаточно плоского приближения. */
    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (a.latitude - b.latitude) * 111_320.0
        val dLon = (a.longitude - b.longitude) * 111_320.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2))
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
