package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class LocationServiceLogicTest {

    @Test
    fun testGpsPointDataClass() {
        val pt = GpsPoint(43.56, -5.87, 10.0, 1600000000000L)
        assertEquals(43.56, pt.latitude, 0.0001)
        assertEquals(-5.87, pt.longitude, 0.0001)
        assertEquals(10.0, pt.altitude, 0.0001)
        assertEquals(1600000000000L, pt.timestamp)
    }

    @Test
    fun testWorkoutStateDefaults() {
        val state = WorkoutState()
        assertFalse(state.isTracking)
        assertFalse(state.isPaused)
        assertNull(state.currentPoint)
        assertEquals(0.0f, state.speedKmh, 0.001f)
        assertEquals(0.0f, state.maxSpeedKmh, 0.001f)
        assertEquals(0.0f, state.distanceMeters, 0.001f)
        assertEquals(0L, state.elapsedTimeSeconds)
        assertEquals(0, state.strokeRateSpm)
        assertEquals(0, state.totalStrokes)
        assertEquals(0, state.lapCount)
    }

    @Test
    fun testSpeedWindowAveraging() {
        val speedWindow = ArrayDeque<Float>(5)
        val sampleSpeeds = listOf(10.0f, 12.0f, 11.0f, 13.0f, 14.0f)

        for (s in sampleSpeeds) {
            if (speedWindow.size >= 5) {
                speedWindow.removeFirst()
            }
            speedWindow.addLast(s)
        }

        val avg = speedWindow.average().toFloat()
        assertEquals(12.0f, avg, 0.001f)
    }

    @Test
    fun testSpeedWindowAveragingSliding() {
        val speedWindow = ArrayDeque<Float>(5)
        val initialSpeeds = listOf(10.0f, 10.0f, 10.0f, 10.0f, 10.0f)
        for (s in initialSpeeds) {
            speedWindow.addLast(s)
        }

        // Add 6th speed, 1st (10.0f) should be removed, new standard value added
        if (speedWindow.size >= 5) {
            speedWindow.removeFirst()
        }
        speedWindow.addLast(20.0f)

        val avg = speedWindow.average().toFloat()
        assertEquals(12.0f, avg, 0.001f)
    }

    @Test
    fun testTurnAngleDegreesCalculation() {
        val p1 = GpsPoint(43.00, -5.00, 0.0, 0L)
        val p2 = GpsPoint(43.01, -5.00, 0.0, 0L) // heading north
        val p3 = GpsPoint(43.01, -4.99, 0.0, 0L) // turn east 90 deg

        val b1 = Math.toDegrees(Math.atan2(p2.longitude - p1.longitude, p2.latitude - p1.latitude))
        val b2 = Math.toDegrees(Math.atan2(p3.longitude - p2.longitude, p3.latitude - p2.latitude))
        var diff = Math.abs(b2 - b1)
        if (diff > 180.0) diff = 360.0 - diff

        assertEquals(90.0, diff, 0.1)
        assertTrue(diff >= 20.0)
    }

    @Test
    fun testAutomaticLapTriggerDistanceThresholds() {
        var isLapArmed = false
        var lapCount = 0

        // User moves > 60 meters away from origin
        val dist1 = 70.0f
        if (!isLapArmed && dist1 > 60f) {
            isLapArmed = true
        }
        assertTrue(isLapArmed)

        // User returns to within <= 25 meters of origin
        val dist2 = 15.0f
        if (isLapArmed && dist2 <= 25f) {
            isLapArmed = false
            lapCount += 1
        }
        assertFalse(isLapArmed)
        assertEquals(1, lapCount)
    }
}
