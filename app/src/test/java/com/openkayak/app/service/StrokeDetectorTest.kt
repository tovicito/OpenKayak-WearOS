package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeDetectorTest {

    @Test
    fun testInitialStrokeState() {
        val state = StrokeState()
        assertEquals(0, state.strokeRateSpm)
        assertEquals(0, state.totalStrokes)
    }

    @Test
    fun testStrokeStateCopy() {
        val state = StrokeState(strokeRateSpm = 45, totalStrokes = 120)
        val updated = state.copy(strokeRateSpm = 50, totalStrokes = 121)
        assertEquals(50, updated.strokeRateSpm)
        assertEquals(121, updated.totalStrokes)
    }

    @Test
    fun testGpsPointState() {
        val point = GpsPoint(latitude = 43.3614, longitude = -5.8593, altitude = 10.0, timestamp = 1000L)
        assertEquals(43.3614, point.latitude, 0.0001)
        assertEquals(-5.8593, point.longitude, 0.0001)
        assertEquals(10.0, point.altitude, 0.0001)
        assertEquals(1000L, point.timestamp)
    }
}
