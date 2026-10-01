package com.openkayak.app.ui

import com.openkayak.app.service.GpsPoint

class CircuitLearnerTest {

    @org.junit.Test
    fun testCalculateTurnAngleDegrees_straightLine() {
        val p1 = GpsPoint(0.0, 0.0, 0.0, 0L)
        val p2 = GpsPoint(1.0, 0.0, 0.0, 0L)
        val p3 = GpsPoint(2.0, 0.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        org.junit.Assert.assertEquals(0.0, angle, 0.01)
    }

    @org.junit.Test
    fun testCalculateTurnAngleDegrees_rightAngleTurn() {
        val p1 = GpsPoint(0.0, 0.0, 0.0, 0L)
        val p2 = GpsPoint(1.0, 0.0, 0.0, 0L)
        val p3 = GpsPoint(1.0, 1.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        org.junit.Assert.assertEquals(90.0, angle, 0.1)
    }

    @org.junit.Test
    fun testCalculateTurnAngleDegrees_uTurn() {
        val p1 = GpsPoint(0.0, 0.0, 0.0, 0L)
        val p2 = GpsPoint(1.0, 0.0, 0.0, 0L)
        val p3 = GpsPoint(0.0, 0.0, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        org.junit.Assert.assertEquals(180.0, angle, 0.1)
    }

    @org.junit.Test
    fun testLearnedCircuitNameFormatting() {
        val buoysCount = 4
        val generatedCount = 2
        val name = "Circuito ${generatedCount + 1} ($buoysCount boyas)"
        org.junit.Assert.assertEquals("Circuito 3 (4 boyas)", name)
    }
}
