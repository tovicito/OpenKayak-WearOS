package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testStraightLineTurnAngle() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 0L)
        val p3 = GpsPoint(43.3634, -5.8593, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.1)
    }

    @Test
    fun testRightAngleTurn() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 0L)
        val p3 = GpsPoint(43.3624, -5.8583, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testUTurnAngle() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 0L)
        val p3 = GpsPoint(43.3614, -5.8593, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }

    @Test
    fun testLearnedCircuitNameFormatting() {
        val generatedSize = 1
        val buoysCount = 3
        val name = "Circuito ${generatedSize} (${buoysCount} boyas)"

        assertEquals("Circuito 1 (3 boyas)", name)
        assertTrue(!name.contains("\\$"))
    }
}
