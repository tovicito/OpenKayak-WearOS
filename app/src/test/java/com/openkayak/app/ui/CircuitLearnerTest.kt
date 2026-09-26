package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegrees_straightLine() {
        // Point moving straight north
        val p1 = GpsPoint(40.0, -3.0, 0.0, 0L)
        val p2 = GpsPoint(40.01, -3.0, 0.0, 0L)
        val p3 = GpsPoint(40.02, -3.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.01)
    }

    @Test
    fun testCalculateTurnAngleDegrees_rightAngleTurn() {
        // North then East
        val p1 = GpsPoint(40.0, -3.0, 0.0, 0L)
        val p2 = GpsPoint(40.01, -3.0, 0.0, 0L)
        val p3 = GpsPoint(40.01, -2.99, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testCalculateTurnAngleDegrees_uTurn() {
        // North then South
        val p1 = GpsPoint(40.0, -3.0, 0.0, 0L)
        val p2 = GpsPoint(40.01, -3.0, 0.0, 0L)
        val p3 = GpsPoint(40.0, -3.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }
}
