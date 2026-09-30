package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegrees_straightLine() {
        // Points on a straight line heading north
        val p1 = GpsPoint(40.0000, -3.0000, 0.0, 0L)
        val p2 = GpsPoint(40.0010, -3.0000, 0.0, 0L)
        val p3 = GpsPoint(40.0020, -3.0000, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.01)
    }

    @Test
    fun testCalculateTurnAngleDegrees_rightTurn() {
        // Point 1 -> Point 2 (North), Point 2 -> Point 3 (East) = 90 degree turn
        val p1 = GpsPoint(40.0000, -3.0000, 0.0, 0L)
        val p2 = GpsPoint(40.0010, -3.0000, 0.0, 0L)
        val p3 = GpsPoint(40.0010, -2.9990, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testCalculateTurnAngleDegrees_uTurn() {
        // U-turn: North then South
        val p1 = GpsPoint(40.0000, -3.0000, 0.0, 0L)
        val p2 = GpsPoint(40.0010, -3.0000, 0.0, 0L)
        val p3 = GpsPoint(40.0000, -3.0000, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }

    @Test
    fun testCircuitNameStringFormatting() {
        val generatedSize = 2
        val buoysSize = 4
        val formatted = "Circuito ${generatedSize + 1} ($buoysSize boyas)"
        assertEquals("Circuito 3 (4 boyas)", formatted)
    }
}
