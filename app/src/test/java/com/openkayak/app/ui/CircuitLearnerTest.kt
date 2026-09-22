package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegrees_straightLine() {
        val p1 = GpsPoint(40.0, -3.0, 0.0, 0L)
        val p2 = GpsPoint(40.001, -3.0, 0.0, 0L)
        val p3 = GpsPoint(40.002, -3.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.1)
    }

    @Test
    fun testCalculateTurnAngleDegrees_rightAngle() {
        val p1 = GpsPoint(40.0, -3.0, 0.0, 0L)
        val p2 = GpsPoint(40.001, -3.0, 0.0, 0L)
        val p3 = GpsPoint(40.001, -2.999, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testParseJsonRoute() {
        val json = "[{\"lat\":43.3614,\"lon\":-5.8593},{\"lat\":43.3615,\"lon\":-5.8594}]"
        val points = parseJsonRoute(json)

        assertEquals(2, points.size)
        assertEquals(43.3614, points[0].latitude, 0.0001)
        assertEquals(-5.8593, points[0].longitude, 0.0001)
    }

    @Test
    fun testParseJsonRoute_invalidJson() {
        val points = parseJsonRoute("invalid json")
        assertTrue(points.isEmpty())
    }
}
