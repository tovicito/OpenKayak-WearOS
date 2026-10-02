package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsPointTest {

    @Test
    fun testGpsPointCreation() {
        val point = GpsPoint(
            latitude = 43.3614,
            longitude = -5.8593,
            altitude = 12.5,
            timestamp = 1600000000000L
        )

        assertEquals(43.3614, point.latitude, 0.00001)
        assertEquals(-5.8593, point.longitude, 0.00001)
        assertEquals(12.5, point.altitude, 0.00001)
        assertEquals(1600000000000L, point.timestamp)
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
    fun testWorkoutStateCopy() {
        val initial = WorkoutState(
            isTracking = true,
            speedKmh = 8.5f,
            distanceMeters = 1500f,
            elapsedTimeSeconds = 600L
        )

        val updated = initial.copy(
            speedKmh = 9.2f,
            maxSpeedKmh = 11.0f,
            distanceMeters = 1550f
        )

        assertTrue(updated.isTracking)
        assertEquals(9.2f, updated.speedKmh, 0.001f)
        assertEquals(11.0f, updated.maxSpeedKmh, 0.001f)
        assertEquals(1550f, updated.distanceMeters, 0.001f)
        assertEquals(600L, updated.elapsedTimeSeconds)
    }
}
