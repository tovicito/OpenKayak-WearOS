package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleStraightLine() {
        // Points in a straight vertical line moving north
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.01, -5.0, 0.0, 0L)
        val p3 = GpsPoint(43.02, -5.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.01)
    }

    @Test
    fun testCalculateTurnAngleRightAngle() {
        // Going north from p1 to p2, then east from p2 to p3 -> 90 degree turn
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.01, -5.0, 0.0, 0L)
        val p3 = GpsPoint(43.01, -4.99, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testCalculateTurnAngleUTurn() {
        // Going north from p1 to p2, then back south from p2 to p3 -> 180 degree turn
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.01, -5.0, 0.0, 0L)
        val p3 = GpsPoint(43.0, -5.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }

    @Test
    fun testLearnedCircuitNameFormatting() {
        val circuit = LearnedCircuit(
            id = 1L,
            name = "Circuito 1 (3 boyas)",
            startLat = 43.0,
            startLon = -5.0,
            turnLat = 43.01,
            turnLon = -5.0,
            totalLaps = 5
        )

        assertEquals("Circuito 1 (3 boyas)", circuit.name)
        assertTrue(circuit.name.contains("Circuito 1"))
    }
}
