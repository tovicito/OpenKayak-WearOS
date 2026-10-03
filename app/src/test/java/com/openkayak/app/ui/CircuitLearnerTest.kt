package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Test
import org.junit.Assert.assertEquals

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegrees_straightLine() {
        // Points going straight North
        val p1 = GpsPoint(43.000, -5.000, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.000, 0.0, 0L)
        val p3 = GpsPoint(43.002, -5.000, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.01)
    }

    @Test
    fun testCalculateTurnAngleDegrees_rightTurn90Degrees() {
        // Going North then East
        val p1 = GpsPoint(43.000, -5.000, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.000, 0.0, 0L)
        val p3 = GpsPoint(43.001, -4.999, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testCalculateTurnAngleDegrees_uTurn180Degrees() {
        // Going North then South
        val p1 = GpsPoint(43.000, -5.000, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.000, 0.0, 0L)
        val p3 = GpsPoint(43.000, -5.000, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }
}
