package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegrees() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3620, -5.8593, 0.0, 0L) // Moving North
        val p3 = GpsPoint(43.3620, -5.8580, 0.0, 0L) // Turning East (90 deg turn)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testStraightLineTurnAngle() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3620, -5.8593, 0.0, 0L)
        val p3 = GpsPoint(43.3626, -5.8593, 0.0, 0L) // Continuing North (0 deg turn)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 1.0)
    }
}
