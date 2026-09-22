package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegreesStraightLine() {
        // Points in a straight line heading North (latitude increasing)
        val p1 = GpsPoint(43.000, -5.000, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.000, 0.0, 0L)
        val p3 = GpsPoint(43.002, -5.000, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.001)
    }

    @Test
    fun testCalculateTurnAngleDegreesRightAngle() {
        // Point 1 -> Point 2 heading North, then Point 2 -> Point 3 heading East
        val p1 = GpsPoint(43.000, -5.000, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.000, 0.0, 0L)
        val p3 = GpsPoint(43.001, -4.999, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testCalculateTurnAngleDegreesHairpin() {
        // Heading North, then doubling back South
        val p1 = GpsPoint(43.000, -5.000, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.000, 0.0, 0L)
        val p3 = GpsPoint(43.000, -5.000, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 0.001)
    }

    @Test
    fun testLearnedCircuitNameFormatting() {
        val buoysCount = 4
        val circuitIndex = 1
        val expectedName = "Circuito $circuitIndex ($buoysCount boyas)"

        val circuit = LearnedCircuit(
            id = 100L,
            name = expectedName,
            startLat = 43.36,
            startLon = -5.85,
            turnLat = 43.37,
            turnLon = -5.86,
            totalLaps = 5
        )

        assertEquals("Circuito 1 (4 boyas)", circuit.name)
        assertTrue(circuit.name.contains("Circuito 1"))
        assertTrue(circuit.name.contains("4 boyas"))
    }
}
