package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.*
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
    fun testCalculateTurnAngleDegrees_rightAngleTurn() {
        val p1 = GpsPoint(40.0, -3.0, 0.0, 0L)
        val p2 = GpsPoint(40.001, -3.0, 0.0, 0L)
        val p3 = GpsPoint(40.001, -2.999, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testDistanceBetweenMeters_knownPoints() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3614, -5.8593, 0.0, 0L)

        val distance = distanceBetweenMeters(p1, p2)
        assertEquals(0f, distance, 0.01f)
    }

    @Test
    fun testDistanceBetweenMeters_nonZero() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 0L) // ~111 meters north

        val distance = distanceBetweenMeters(p1, p2)
        assertTrue("Distance should be around 110m", distance in 100f..120f)
    }
}
