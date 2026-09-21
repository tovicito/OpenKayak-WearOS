package com.openkayak.app

import com.openkayak.app.service.GpsPoint
import com.openkayak.app.ui.LearnedCircuit
import com.openkayak.app.ui.calculateTurnAngleDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testStraightLineTurnAngle() {
        val p1 = GpsPoint(43.3600, -5.8500, 0.0, 0L)
        val p2 = GpsPoint(43.3610, -5.8500, 0.0, 0L)
        val p3 = GpsPoint(43.3620, -5.8500, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.1)
    }

    @Test
    fun testRightAngleTurnDegrees() {
        val p1 = GpsPoint(43.3600, -5.8500, 0.0, 0L)
        val p2 = GpsPoint(43.3610, -5.8500, 0.0, 0L)
        val p3 = GpsPoint(43.3610, -5.8400, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testUturnAngleDegrees() {
        val p1 = GpsPoint(43.3600, -5.8500, 0.0, 0L)
        val p2 = GpsPoint(43.3610, -5.8500, 0.0, 0L)
        val p3 = GpsPoint(43.3600, -5.8500, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }

    @Test
    fun testLearnedCircuitDataStructure() {
        val circuit = LearnedCircuit(
            id = 1001L,
            name = "Circuito 1 (3 boyas)",
            startLat = 43.3614,
            startLon = -5.8593,
            turnLat = 43.3650,
            turnLon = -5.8550,
            totalLaps = 5,
            signature = "0-1-2"
        )

        assertEquals("Circuito 1 (3 boyas)", circuit.name)
        assertFalse(circuit.isDeleted)
        assertEquals(5, circuit.totalLaps)
        assertEquals("0-1-2", circuit.signature)
    }
}
