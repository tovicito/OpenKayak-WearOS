package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class CircuitLearnerTest {

    @Test
    fun testCalculateTurnAngleDegreesStraightLine() {
        val p1 = GpsPoint(43.36, -5.85, 0.0, 1000L)
        val p2 = GpsPoint(43.37, -5.85, 0.0, 2000L)
        val p3 = GpsPoint(43.38, -5.85, 0.0, 3000L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.001)
    }

    @Test
    fun testCalculateTurnAngleDegrees90DegreeTurn() {
        val p1 = GpsPoint(43.36, -5.85, 0.0, 1000L)
        val p2 = GpsPoint(43.37, -5.85, 0.0, 2000L)
        val p3 = GpsPoint(43.37, -5.84, 0.0, 3000L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testCalculateTurnAngleDegrees180DegreeUturn() {
        val p1 = GpsPoint(43.36, -5.85, 0.0, 1000L)
        val p2 = GpsPoint(43.37, -5.85, 0.0, 2000L)
        val p3 = GpsPoint(43.36, -5.85, 0.0, 3000L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }
}
