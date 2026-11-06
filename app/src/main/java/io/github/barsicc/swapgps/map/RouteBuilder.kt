package io.github.barsicc.swapgps.map

import android.os.Handler
import android.os.Looper
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.DrivingRouterType
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.VehicleOptions
import com.yandex.mapkit.geometry.Point
import com.yandex.runtime.Error
import io.github.barsicc.swapgps.engine.Diag
import io.github.barsicc.swapgps.engine.GeoPoint
import io.github.barsicc.swapgps.engine.RouteMovement
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Построенный маршрут по автодорогам. */
data class BuiltRoute(val points: List<GeoPoint>, val meters: Double, val source: String)

/**
 * Строит маршрут для машины по реальным дорогам.
 * Сначала — маршрутизатор Яндекса; если он недоступен, — открытый сервис OSRM.
 */
class RouteBuilder {

    private val main = Handler(Looper.getMainLooper())
    private var session: DrivingSession? = null // держим, иначе запрос отменится
    private var requestId = 0

    fun build(from: GeoPoint, to: GeoPoint, onResult: (BuiltRoute?, String?) -> Unit) {
        val id = ++requestId
        val deliver: (BuiltRoute?, String?) -> Unit = { r, e -> main.post { if (id == requestId) onResult(r, e) } }

        val router = runCatching { DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.ONLINE) }.getOrNull()
        if (router == null) {
            osrm(from, to, deliver)
            return
        }
        val points = listOf(
            RequestPoint(Point(from.latitude, from.longitude), RequestPointType.WAYPOINT, null, null, null),
            RequestPoint(Point(to.latitude, to.longitude), RequestPointType.WAYPOINT, null, null, null),
        )
        session = router.requestRoutes(
            points,
            DrivingOptions().setRoutesCount(1),
            VehicleOptions(),
            object : DrivingSession.DrivingRouteListener {
                override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
                    val route = routes.firstOrNull()
                    val line = route?.geometry?.points?.map { GeoPoint(it.latitude, it.longitude) }.orEmpty()
                    if (line.size >= 2) {
                        val meters = route?.metadata?.weight?.distance?.value ?: length(line)
                        Diag.log("Маршрут Яндекса: ${(meters / 1000).format1()} км, ${line.size} точек")
                        deliver(BuiltRoute(line, meters, "Яндекс"), null)
                    } else {
                        Diag.log("Яндекс не нашёл маршрут — пробую OSRM")
                        osrm(from, to, deliver)
                    }
                }

                override fun onDrivingRoutesError(error: Error) {
                    Diag.log("Маршрутизатор Яндекса: ${error.javaClass.simpleName} — пробую OSRM")
                    osrm(from, to, deliver)
                }
            },
        )
    }

    fun cancel() {
        requestId++
        session?.cancel()
        session = null
    }

    /** Запасной вариант: открытый маршрутизатор OSRM по данным OpenStreetMap. */
    private fun osrm(from: GeoPoint, to: GeoPoint, deliver: (BuiltRoute?, String?) -> Unit) {
        thread(name = "osrm") {
            try {
                val url = URL(
                    "https://router.project-osrm.org/route/v1/driving/" +
                        "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
                        "?overview=full&geometries=geojson",
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "SwapGPS (personal Android app)")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val route = json.optJSONArray("routes")?.optJSONObject(0)
                    ?: return@thread deliver(null, "Маршрут по дорогам не найден")
                val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                val line = List(coords.length()) { i ->
                    val c = coords.getJSONArray(i)
                    GeoPoint(c.getDouble(1), c.getDouble(0))
                }
                val meters = route.optDouble("distance", length(line))
                Diag.log("Маршрут OSRM: ${(meters / 1000).format1()} км, ${line.size} точек")
                deliver(BuiltRoute(line, meters, "OSRM"), null)
            } catch (e: Exception) {
                Diag.log("OSRM: не удалось построить маршрут", e)
                deliver(null, "Не удалось построить маршрут. Проверьте интернет")
            }
        }
    }

    private fun length(line: List<GeoPoint>): Double =
        line.zipWithNext { a, b -> RouteMovement.distance(a, b) }.sum()

    private fun Double.format1() = String.format(java.util.Locale.US, "%.1f", this)
}
