package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegrees_straightLine() {
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.0, 0.0, 0L)
        val p3 = GpsPoint(43.002, -5.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.1)
    }

    @Test
    fun testCalculateTurnAngleDegrees_rightAngle() {
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.001, -5.0, 0.0, 0L)
        val p3 = GpsPoint(43.001, -5.001, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testLearnedCircuitDataClassDefaults() {
        val circuit = LearnedCircuit(
            id = 100L,
            name = "Circuito 1 (3 boyas)",
            startLat = 43.36,
            startLon = -5.85,
            turnLat = 43.37,
            turnLon = -5.86,
            totalLaps = 5
        )

        assertEquals(100L, circuit.id)
        assertEquals("Circuito 1 (3 boyas)", circuit.name)
        assertFalse(circuit.isDeleted)
        assertEquals(0L, circuit.deletedAt)
        assertTrue(circuit.outerPolyline.isEmpty())
        assertTrue(circuit.buoyPoints.isEmpty())
    }
}
