package io.github.barsicc.swapgps.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Положение на маршруте в данный момент. */
data class MoveSample(
    val point: GeoPoint,
    /** Скорость, м/с. */
    val speed: Float,
    /** Направление движения, градусы (0 — север). */
    val bearing: Float,
)

/**
 * Езда по маршруту как на машине: плавный разгон, торможение перед поворотами
 * (чем круче поворот, тем медленнее), плавный поворот направления и остановка в конце.
 */
class RouteMovement(points: List<GeoPoint>, speedKmh: Int) {

    companion object {
        private const val EARTH_RADIUS = 6_371_000.0
        private const val ACCEL = 1.8          // м/с² — разгон
        private const val DECEL = 2.5          // м/с² — торможение
        private const val TURN_WINDOW = 15.0   // м — на каком отрезке до и после точки мерить поворот
        private const val MAX_TURN_RATE = 45f  // °/с — как быстро меняется направление
        private const val MIN_CRAWL = 1.0      // м/с — чтобы не «застрять» у финиша

        fun distance(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
            return 2 * EARTH_RADIUS * atan2(sqrt(h), sqrt(1 - h))
        }

        fun bearing(a: GeoPoint, b: GeoPoint): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        /** Разница направлений, 0..180°. */
        fun angleBetween(a: Double, b: Double): Double {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }

        /** Безопасная скорость (м/с) в повороте на заданный угол. */
        fun turnSpeedLimit(angle: Double): Double? {
            val kmh = when {
                angle < 15 -> return null
                angle < 35 -> 50.0
                angle < 60 -> 35.0
                angle < 100 -> 25.0
                else -> 18.0
            }
            return kmh / 3.6
        }
    }

    /** Точки маршрута без повторов (иначе нулевые отрезки ломают расчёты). */
    val points: List<GeoPoint> = points.fold(mutableListOf()) { acc, p ->
        if (acc.isEmpty() || distance(acc.last(), p) > 0.3) acc.add(p)
        acc
    }

    /** Расстояние от начала до каждой точки, м. */
    private val cumulative = DoubleArray(this.points.size).also { c ->
        for (i in 1 until this.points.size) c[i] = c[i - 1] + distance(this.points[i - 1], this.points[i])
    }

    val totalMeters: Double = cumulative.lastOrNull() ?: 0.0

    /** Ограничение скорости в каждой точке из-за поворота (м/с) или null. */
    private val turnLimits: Array<Double?> = Array(this.points.size) { i ->
        if (i == 0 || i == this.points.lastIndex) null
        else {
            val before = pointAt(cumulative[i] - TURN_WINDOW)
            val after = pointAt(cumulative[i] + TURN_WINDOW)
            val here = this.points[i]
            if (distance(before, here) < 1 || distance(here, after) < 1) null
            else turnSpeedLimit(angleBetween(bearing(before, here), bearing(here, after)))
        }
    }

    var maxSpeed: Double = speedKmh / 3.6
        private set

    var paused: Boolean = false

    /** Сколько уже проехали, м. */
    var traveled: Double = 0.0
        private set

    private var speed = 0.0
    private var heading: Float = if (this.points.size >= 2) bearing(this.points[0], this.points[1]).toFloat() else 0f

    val finished: Boolean get() = traveled >= totalMeters

    /** Скорость прямо сейчас, м/с. */
    val currentSpeed: Double get() = speed
    val remainingMeters: Double get() = max(0.0, totalMeters - traveled)

    fun setSpeedKmh(kmh: Int) {
        maxSpeed = kmh / 3.6
    }

    /** Сдвинуться на dt секунд. */
    fun step(dt: Double): MoveSample {
        if (points.size < 2 || finished) {
            speed = 0.0
            return sample()
        }
        if (paused) {
            speed = 0.0
            return sample()
        }

        // Сколько можно ехать, чтобы успеть затормозить перед поворотами и финишем
        var target = maxSpeed
        val lookahead = speed * speed / (2 * DECEL) + 60
        for (i in points.indices) {
            // До поворота тормозим заранее, а сам поворот (±TURN_WINDOW) проходим на сниженной скорости
            val ahead = cumulative[i] - traveled - TURN_WINDOW
            if (ahead < -2 * TURN_WINDOW) continue
            if (ahead > lookahead) break
            val limit = turnLimits[i] ?: continue
            target = min(target, sqrt(limit * limit + 2 * DECEL * max(0.0, ahead)))
        }
        target = min(target, sqrt(2 * DECEL * remainingMeters))

        speed = if (speed < target) min(target, speed + ACCEL * dt) else max(target, speed - DECEL * dt)
        speed = max(speed, MIN_CRAWL)

        traveled = min(totalMeters, traveled + speed * dt)
        if (finished) speed = 0.0

        // Направление поворачиваем плавно, а не рывком на изломе
        val wanted = bearing(pointAt(traveled), pointAt(traveled + 10)).toFloat()
        heading = turnTowards(heading, wanted, (MAX_TURN_RATE * dt).toFloat())
        return sample()
    }

    private fun sample() = MoveSample(pointAt(traveled), speed.toFloat(), heading)

    /** Точка на маршруте на расстоянии d от начала. */
    fun pointAt(d: Double): GeoPoint {
        if (points.isEmpty()) return GeoPoint(0.0, 0.0)
        if (d <= 0) return points.first()
        if (d >= totalMeters) return points.last()
        var lo = 0
        var hi = cumulative.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= d) lo = mid else hi = mid
        }
        val segment = cumulative[hi] - cumulative[lo]
        val t = if (segment > 0) (d - cumulative[lo]) / segment else 0.0
        val a = points[lo]
        val b = points[hi]
        return GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }

    private fun turnTowards(from: Float, to: Float, maxStep: Float): Float {
        var diff = (to - from) % 360
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        val step = diff.coerceIn(-maxStep, maxStep)
        return ((from + step) % 360 + 360) % 360
    }
}
