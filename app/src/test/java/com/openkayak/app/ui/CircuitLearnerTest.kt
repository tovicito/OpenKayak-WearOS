package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegrees_StraightLine() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 0L)
        val p3 = GpsPoint(43.3634, -5.8593, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.1)
    }

    @Test
    fun testCalculateTurnAngleDegrees_RightAngleTurn() {
        val p1 = GpsPoint(0.0, 0.0, 0.0, 0L)
        val p2 = GpsPoint(0.01, 0.0, 0.0, 0L)
        val p3 = GpsPoint(0.01, 0.01, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testCircuitNameInterpolation() {
        val generatedSize = 2
        val buoysSize = 4
        val name = "Circuito ${generatedSize + 1} ($buoysSize boyas)"
        assertEquals("Circuito 3 (4 boyas)", name)
    }
}
