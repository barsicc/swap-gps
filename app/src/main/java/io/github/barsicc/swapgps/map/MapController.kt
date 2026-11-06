package io.github.barsicc.swapgps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import com.yandex.mapkit.Animation
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.CameraUpdateReason
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.map.VisibleRegionUtils
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import io.github.barsicc.swapgps.engine.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Состояние камеры: куда смотрит центр карты и двигается ли она сейчас. */
data class CameraState(val center: GeoPoint, val zoom: Float, val moving: Boolean)

/**
 * Обёртка над картой Яндекса. Центр карты — точка, куда будем подменять
 * (над центром рисуется метка). Отдельно на карте отмечается активная подмена.
 */
class MapController(private val mapView: MapView) {

    companion object {
        val MOSCOW = GeoPoint(55.7558, 37.6173)
        const val DEFAULT_ZOOM = 16f
        const val CLOSE_ZOOM = 17f
    }

    private val map: Map get() = mapView.mapWindow.map

    private val _camera = MutableStateFlow(CameraState(MOSCOW, DEFAULT_ZOOM, moving = false))
    val camera: StateFlow<CameraState> = _camera.asStateFlow()

    private var spoofMarker: PlacemarkMapObject? = null
    private var routeLine: com.yandex.mapkit.map.PolylineMapObject? = null
    private var finishMarker: PlacemarkMapObject? = null

    /** Когда пользователь последний раз сам двигал карту — тогда не следим за машиной. */
    private var lastGestureAt = 0L

    // MapKit хранит слушателей по слабым ссылкам — держим сильную ссылку здесь
    private val cameraListener = CameraListener { _, position: CameraPosition, reason: CameraUpdateReason, finished ->
        if (reason == CameraUpdateReason.GESTURES) lastGestureAt = android.os.SystemClock.elapsedRealtime()
        _camera.value = CameraState(
            center = GeoPoint(position.target.latitude, position.target.longitude),
            zoom = position.zoom,
            moving = !finished,
        )
    }

    init {
        map.addCameraListener(java.lang.ref.WeakReference(cameraListener))
        map.isRotateGesturesEnabled = false
    }

    fun setNightMode(night: Boolean) {
        map.isNightModeEnabled = night
    }

    fun moveTo(point: GeoPoint, zoom: Float = CLOSE_ZOOM, animated: Boolean = true) {
        val position = CameraPosition(Point(point.latitude, point.longitude), zoom, 0f, 0f)
        if (animated) map.move(position, Animation(Animation.Type.SMOOTH, 0.6f), null)
        else map.move(position)
    }

    /** Показать весь маршрут целиком (с запасом под поиск и нижнюю панель). */
    fun showWhole(points: List<GeoPoint>) {
        if (points.size < 2) return
        val polyline = com.yandex.mapkit.geometry.Polyline(points.map { Point(it.latitude, it.longitude) })
        val fit = map.cameraPosition(com.yandex.mapkit.geometry.Geometry.fromPolyline(polyline))
        val position = CameraPosition(fit.target, fit.zoom - 0.9f, 0f, 0f)
        map.move(position, Animation(Animation.Type.SMOOTH, 0.8f), null)
    }

    /** Вести камеру за машиной, пока пользователь сам не двигает карту. */
    fun follow(point: GeoPoint) {
        if (android.os.SystemClock.elapsedRealtime() - lastGestureAt < 8_000) return
        val zoom = map.cameraPosition.zoom
        map.move(CameraPosition(Point(point.latitude, point.longitude), zoom, 0f, 0f), Animation(Animation.Type.LINEAR, 0.45f), null)
    }

    /** Сбросить «пользователь двигал карту», чтобы снова следить за машиной. */
    fun resumeFollow() {
        lastGestureAt = 0L
    }

    /** Нарисовать маршрут (или убрать, если null). */
    fun showRoute(points: List<GeoPoint>?, color: Int) {
        routeLine?.let { runCatching { map.mapObjects.remove(it) } }
        finishMarker?.let { runCatching { map.mapObjects.remove(it) } }
        routeLine = null
        finishMarker = null
        if (points == null || points.size < 2) return
        routeLine = map.mapObjects.addPolyline(
            com.yandex.mapkit.geometry.Polyline(points.map { Point(it.latitude, it.longitude) }),
        ).apply {
            setStrokeColor(color)
            strokeWidth = 5f
            outlineColor = android.graphics.Color.WHITE
            outlineWidth = 1.5f
            zIndex = 5f
        }
        val end = points.last()
        finishMarker = map.mapObjects.addPlacemark().apply {
            geometry = Point(end.latitude, end.longitude)
            setIcon(ImageProvider.fromBitmap(finishBitmap(color)), IconStyle().setAnchor(PointF(0.5f, 0.5f)).setZIndex(9f))
        }
    }

    /** Финиш — кольцо цвета маршрута. */
    private fun finishBitmap(color: Int): Bitmap {
        val size = 56
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 6f, paint)
        return bitmap
    }

    /** Область, которая сейчас видна на экране (для подсказок поиска). */
    fun visibleGeometry() = VisibleRegionUtils.toPolygon(map.visibleRegion)

    fun visibleBounds() = VisibleRegionUtils.getBounds(map.visibleRegion)

    /** Показать на карте точку активной подмены (или убрать, если null). */
    fun showSpoofPoint(point: GeoPoint?, color: Int) {
        if (point == null) {
            spoofMarker?.let { runCatching { map.mapObjects.remove(it) } }
            spoofMarker = null
            return
        }
        val target = Point(point.latitude, point.longitude)
        val marker = spoofMarker
        if (marker != null && marker.isValid) {
            marker.geometry = target
        } else {
            spoofMarker = map.mapObjects.addPlacemark().apply {
                geometry = target
                setIcon(ImageProvider.fromBitmap(dotBitmap(color)), IconStyle().setAnchor(PointF(0.5f, 0.5f)).setZIndex(10f))
            }
        }
    }

    /** Кружок с белой обводкой — «вы здесь» для подменённой точки. */
    private fun dotBitmap(color: Int): Bitmap {
        val size = 72
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.alpha = 60
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.alpha = 255
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 4.2f, paint)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 6f, paint)
        return bitmap
    }
}
