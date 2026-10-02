package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegreesStraightLine() {
        val p1 = GpsPoint(40.0, -3.0, 0.0, 0L)
        val p2 = GpsPoint(40.001, -3.0, 0.0, 0L)
        val p3 = GpsPoint(40.002, -3.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 1e-4)
    }

    @Test
    fun testCalculateTurnAngleDegreesRightAngle() {
        val p1 = GpsPoint(40.0, -3.0, 0.0, 0L)
        val p2 = GpsPoint(40.001, -3.0, 0.0, 0L)
        val p3 = GpsPoint(40.001, -2.999, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }
}
