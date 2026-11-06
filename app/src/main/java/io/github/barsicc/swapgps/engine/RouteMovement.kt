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
        // Минимальные значения — для обычной скорости; на больших скоростях всё
        // масштабируется от выбранной скорости, чтобы езда была быстрой, а не «пешком»
        private const val MIN_ACCEL = 1.8      // м/с² — разгон
        private const val MIN_DECEL = 2.5      // м/с² — торможение
        private const val TURN_WINDOW = 15.0   // м — на каком отрезке до и после точки мерить поворот
        private const val MIN_TURN_RATE = 45.0 // °/с — как быстро меняется направление
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

        /**
         * Скорость (м/с) в повороте на заданный угол при выбранной скорости maxSpeed.
         * Чем круче поворот, тем сильнее сбрасываем, но на больших скоростях — в разы быстрее,
         * чем настоящая машина.
         */
        fun turnSpeedLimit(angle: Double, maxSpeed: Double): Double? {
            val (kmh, share) = when {
                angle < 15 -> return null
                angle < 35 -> 50.0 to 0.8
                angle < 60 -> 35.0 to 0.6
                angle < 100 -> 25.0 to 0.45
                else -> 18.0 to 0.3
            }
            return max(kmh / 3.6, maxSpeed * share)
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

    /** Угол поворота в каждой точке маршрута, ° (0 — прямо). */
    private val turnAngles = DoubleArray(this.points.size) { i ->
        if (i == 0 || i == this.points.lastIndex) 0.0
        else {
            val before = pointAt(cumulative[i] - TURN_WINDOW)
            val after = pointAt(cumulative[i] + TURN_WINDOW)
            val here = this.points[i]
            if (distance(before, here) < 1 || distance(here, after) < 1) 0.0
            else angleBetween(bearing(before, here), bearing(here, after))
        }
    }

    var maxSpeed: Double = speedKmh / 3.6
        private set

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

    /** Сразу оказаться в конце маршрута. */
    fun skipToEnd() {
        traveled = totalMeters
        speed = 0.0
    }

    // Разгон до выбранной скорости — за ~2 с, торможение — за ~1,5 с
    private val accel get() = max(MIN_ACCEL, maxSpeed / 2)
    private val decel get() = max(MIN_DECEL, maxSpeed / 1.5)

    /** Сдвинуться на dt секунд. */
    fun step(dt: Double): MoveSample {
        if (points.size < 2 || finished) {
            speed = 0.0
            return sample()
        }

        // Сколько можно ехать, чтобы успеть затормозить перед поворотами и финишем
        var target = maxSpeed
        val dec = decel
        val lookahead = speed * speed / (2 * dec) + 60
        for (i in points.indices) {
            // До поворота тормозим заранее, а сам поворот (±TURN_WINDOW) проходим на сниженной скорости
            val ahead = cumulative[i] - traveled - TURN_WINDOW
            if (ahead < -2 * TURN_WINDOW) continue
            if (ahead > lookahead) break
            val limit = turnSpeedLimit(turnAngles[i], maxSpeed) ?: continue
            target = min(target, sqrt(limit * limit + 2 * dec * max(0.0, ahead)))
        }
        target = min(target, sqrt(2 * dec * remainingMeters))

        speed = if (speed < target) min(target, speed + accel * dt) else max(target, speed - dec * dt)
        speed = max(speed, MIN_CRAWL)

        traveled = min(totalMeters, traveled + speed * dt)
        if (finished) speed = 0.0

        // Направление поворачиваем плавно, а не рывком на изломе
        val wanted = bearing(pointAt(traveled), pointAt(traveled + 10)).toFloat()
        val turnRate = max(MIN_TURN_RATE, maxSpeed * 1.5)
        heading = turnTowards(heading, wanted, (turnRate * dt).toFloat())
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
