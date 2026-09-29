package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleStraight() {
        val p1 = GpsPoint(43.00, -5.00, 0.0, 0L)
        val p2 = GpsPoint(43.01, -5.00, 0.0, 0L)
        val p3 = GpsPoint(43.02, -5.00, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 1.0)
    }

    @Test
    fun testCalculateTurnAngleUturn() {
        val p1 = GpsPoint(43.00, -5.00, 0.0, 0L)
        val p2 = GpsPoint(43.01, -5.00, 0.0, 0L)
        val p3 = GpsPoint(43.00, -5.00, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }

    @Test
    fun testLearnedCircuitDataClass() {
        val circuit = LearnedCircuit(
            id = 1001L,
            name = "Circuito 1 (3 boyas)",
            startLat = 43.3614,
            startLon = -5.8593,
            turnLat = 43.3620,
            turnLon = -5.8580,
            totalLaps = 5,
            evidenceCount = 5
        )
        assertEquals(1001L, circuit.id)
        assertEquals("Circuito 1 (3 boyas)", circuit.name)
        assertEquals(5, circuit.totalLaps)
        assertEquals(5, circuit.evidenceCount)
        assertTrue(!circuit.isDeleted)
    }
}
