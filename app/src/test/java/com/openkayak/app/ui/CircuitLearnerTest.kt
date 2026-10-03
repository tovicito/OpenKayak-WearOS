package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegrees_straightLine() {
        // Points in a straight horizontal line going East
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.0, -4.9, 0.0, 0L)
        val p3 = GpsPoint(43.0, -4.8, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 1e-4)
    }

    @Test
    fun testCalculateTurnAngleDegrees_rightAngle() {
        // Point moving East, then turning North (90 degree turn)
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.0, -4.9, 0.0, 0L)
        val p3 = GpsPoint(43.1, -4.9, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testCalculateTurnAngleDegrees_uTurn() {
        // Point moving East, then reversing back West (180 degree U-turn)
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.0, -4.9, 0.0, 0L)
        val p3 = GpsPoint(43.0, -5.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }

    @Test
    fun testDistanceBetweenMeters_samePoint() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val dist = distanceBetweenMeters(p1, p1)
        assertEquals(0f, dist, 0.001f)
    }
}
