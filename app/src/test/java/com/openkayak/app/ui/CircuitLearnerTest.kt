package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegrees_straightLine() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 1000L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 2000L)
        val p3 = GpsPoint(43.3634, -5.8593, 0.0, 3000L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.5)
    }

    @Test
    fun testCalculateTurnAngleDegrees_rightTurn() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 1000L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 2000L)
        val p3 = GpsPoint(43.3624, -5.8583, 0.0, 3000L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 5.0)
    }

    @Test
    fun testCalculateTurnAngleDegrees_uTurn() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 1000L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 2000L)
        val p3 = GpsPoint(43.3614, -5.8593, 0.0, 3000L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 5.0)
    }

    @Test
    fun testParseJsonRoute() {
        val json = """[{"lat":43.3614,"lon":-5.8593},{"lat":43.3624,"lon":-5.8583}]"""
        val points = parseJsonRoute(json)

        assertEquals(2, points.size)
        assertEquals(43.3614, points[0].latitude, 0.0001)
        assertEquals(-5.8593, points[0].longitude, 0.0001)
        assertEquals(43.3624, points[1].latitude, 0.0001)
        assertEquals(-5.8583, points[1].longitude, 0.0001)
    }

    @Test
    fun testParseJsonRoute_invalidJsonReturnsEmpty() {
        val points = parseJsonRoute("invalid_json")
        assertTrue(points.isEmpty())
    }
}
