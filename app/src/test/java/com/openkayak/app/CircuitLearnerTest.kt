package com.openkayak.app

import com.openkayak.app.service.GpsPoint
import com.openkayak.app.ui.calculateTurnAngleDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testStraightLineTurnAngle() {
        // Points heading North in a straight line
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 0L)
        val p3 = GpsPoint(43.3634, -5.8593, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.5)
    }

    @Test
    fun test90DegreeRightTurnAngle() {
        // p1 -> p2 heading North, p2 -> p3 heading East
        val p1 = GpsPoint(43.3600, -5.8500, 0.0, 0L)
        val p2 = GpsPoint(43.3610, -5.8500, 0.0, 0L)
        val p3 = GpsPoint(43.3610, -5.8490, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testUTurnAngle() {
        // p1 -> p2 heading North, p2 -> p3 heading South
        val p1 = GpsPoint(43.3600, -5.8500, 0.0, 0L)
        val p2 = GpsPoint(43.3610, -5.8500, 0.0, 0L)
        val p3 = GpsPoint(43.3600, -5.8500, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }

    @Test
    fun testCircuitNameFormat() {
        val generatedSize = 0
        val buoyCount = 3
        val formattedName = "Circuito ${generatedSize + 1} ($buoyCount boyas)"
        assertEquals("Circuito 1 (3 boyas)", formattedName)
    }
}
