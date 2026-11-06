package io.github.barsicc.swapgps.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMovementTest {

    /** Прямая ~1 км на север, затем поворот направо на 90° и ещё ~500 м на восток. */
    private val start = GeoPoint(55.75, 37.60)
    private val corner = GeoPoint(55.759, 37.60)
    private val end = GeoPoint(55.759, 37.608)
    private val route = listOf(start, corner, end)

    private fun drive(movement: RouteMovement, dt: Double = 0.5, maxSteps: Int = 100_000): List<Pair<Double, MoveSample>> {
        val log = mutableListOf<Pair<Double, MoveSample>>()
        var steps = 0
        while (!movement.finished && steps++ < maxSteps) {
            val s = movement.step(dt)
            log += movement.traveled to s
        }
        return log
    }

    @Test
    fun reachesEndAndStops() {
        val m = RouteMovement(route, 60)
        val log = drive(m)
        assertTrue("должен доехать", m.finished)
        assertEquals(end.latitude, log.last().second.point.latitude, 1e-9)
        assertEquals(end.longitude, log.last().second.point.longitude, 1e-9)
        assertEquals(0f, log.last().second.speed, 0.01f)
    }

    @Test
    fun neverExceedsChosenSpeed() {
        val m = RouteMovement(route, 60)
        val max = drive(m).maxOf { it.second.speed }
        assertTrue("скорость $max м/с выше 60 км/ч", max <= 60 / 3.6f + 0.01f)
        assertTrue("должна разогнаться почти до 60 км/ч", max > 55 / 3.6f)
    }

    @Test
    fun slowsDownBeforeSharpTurn() {
        val m = RouteMovement(route, 60)
        val cornerAt = RouteMovement.distance(start, corner)
        val log = drive(m)
        val atCorner = log.filter { kotlin.math.abs(it.first - cornerAt) < 10 }.minOf { it.second.speed }
        // Поворот на 90° при 60 км/ч — сбрасываем примерно до 25–30 км/ч
        assertTrue("в повороте ${atCorner * 3.6} км/ч", atCorner <= 30 / 3.6f)
    }

    @Test
    fun headingTurnsSmoothly() {
        val m = RouteMovement(route, 60)
        val log = drive(m)
        val maxJump = log.zipWithNext { a, b -> RouteMovement.angleBetween(a.second.bearing.toDouble(), b.second.bearing.toDouble()) }.max()
        // За шаг 0,5 с направление меняется не больше чем на ~22,5°
        assertTrue("скачок направления $maxJump°", maxJump <= 23.0)
    }

    @Test
    fun skipToEndFinishesImmediately() {
        val m = RouteMovement(route, 60)
        repeat(20) { m.step(0.5) }
        m.skipToEnd()
        assertTrue(m.finished)
        val last = m.step(0.5)
        assertEquals(end.latitude, last.point.latitude, 1e-9)
    }

    /** ~5 км с поворотами при 1800 км/ч должны проезжаться за 10–20 секунд. */
    @Test
    fun veryFastRouteTakesSeconds() {
        val zigzag = buildList {
            var lat = 55.75
            var lon = 37.60
            add(GeoPoint(lat, lon))
            repeat(10) { i ->
                if (i % 2 == 0) lat += 0.0045 else lon += 0.0072 // ~500 м отрезки с поворотами на 90°
                add(GeoPoint(lat, lon))
            }
        }
        val m = RouteMovement(zigzag, 1800)
        assertTrue("длина ${m.totalMeters}", m.totalMeters in 4500.0..5500.0)
        var seconds = 0.0
        while (!m.finished && seconds < 120) {
            m.step(0.25)
            seconds += 0.25
        }
        assertTrue("ехали $seconds с", m.finished && seconds <= 20.0)
    }

    @Test
    fun duplicatePointsAreIgnored() {
        val m = RouteMovement(listOf(start, start, corner, corner, end), 40)
        assertEquals(3, m.points.size)
        drive(m)
        assertTrue(m.finished)
    }
}
