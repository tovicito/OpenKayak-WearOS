package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegreesStraightLine() {
        // Straight line heading North: p1 -> p2 -> p3
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.0, 0.0, 0L)
        val p3 = GpsPoint(43.002, -5.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.1)
    }

    @Test
    fun testCalculateTurnAngleDegreesRightTurn() {
        // Heading North then turning East (90 degrees turn)
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.0, 0.0, 0L)
        val p3 = GpsPoint(43.001, -4.999, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testDistanceBetweenMeters() {
        // Point A and Point B separated by roughly 111 meters (0.001 deg lat)
        val p1 = GpsPoint(43.000, -5.000, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.000, 0.0, 0L)

        val dist = distanceBetweenMeters(p1, p2)
        assertTrue(dist > 100f && dist < 120f)
    }

    @Test
    fun testParseJsonRouteValidAndInvalid() {
        val validJson = "[{\"lat\":43.36,\"lon\":-5.85},{\"lat\":43.37,\"lon\":-5.86}]"
        val points = parseJsonRoute(validJson)
        assertEquals(2, points.size)
        assertEquals(43.36, points[0].latitude, 0.001)
        assertEquals(-5.85, points[0].longitude, 0.001)

        val invalidJson = "invalid_json"
        val emptyPoints = parseJsonRoute(invalidJson)
        assertTrue(emptyPoints.isEmpty())
    }
}
