package com.openkayak.app

import com.openkayak.app.service.GpsPoint
import com.openkayak.app.ui.calculateTurnAngleDegrees
import org.junit.Assert.assertEquals
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testStraightLineAngleIsZero() {
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.01, -5.0, 0.0, 0L)
        val p3 = GpsPoint(43.02, -5.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.001)
    }

    @Test
    fun testRightTurnAngleIs90() {
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.01, -5.0, 0.0, 0L)
        val p3 = GpsPoint(43.01, -4.99, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testUTurnAngleIs180() {
        val p1 = GpsPoint(43.0, -5.0, 0.0, 0L)
        val p2 = GpsPoint(43.01, -5.0, 0.0, 0L)
        val p3 = GpsPoint(43.0, -5.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }
}
