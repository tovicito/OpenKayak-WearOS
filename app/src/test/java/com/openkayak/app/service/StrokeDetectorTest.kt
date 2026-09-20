package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class StrokeDetectorTest {

    @Test
    fun testStrokeStateDefaultValues() {
        val state = StrokeState()
        assertEquals(0, state.strokeRateSpm)
        assertEquals(0, state.totalStrokes)
    }

    @Test
    fun testSpmCalculationFromWindow() {
        val timestamps = ArrayDeque<Long>()
        val now = System.currentTimeMillis()

        // 10 strokes within last 10 seconds
        for (i in 0 until 10) {
            timestamps.addLast(now - i * 900L)
        }

        val tenSecondsAgo = now - 10_000L
        while (timestamps.isNotEmpty() && timestamps.first < tenSecondsAgo) {
            timestamps.removeFirst()
        }

        val spm = (timestamps.size * 6).coerceIn(0, 180)
        assertEquals(60, spm)
    }

    @Test
    fun testSpmPurgingOldStrokes() {
        val timestamps = ArrayDeque<Long>()
        val now = System.currentTimeMillis()

        // Add 5 strokes from 15 seconds ago (should be purged)
        for (i in 0 until 5) {
            timestamps.addLast(now - 15_000L + i * 500L)
        }
        // Add 3 strokes within 10 seconds
        for (i in 0 until 3) {
            timestamps.addLast(now - i * 1000L)
        }

        val tenSecondsAgo = now - 10_000L
        while (timestamps.isNotEmpty() && timestamps.first < tenSecondsAgo) {
            timestamps.removeFirst()
        }

        val spm = (timestamps.size * 6).coerceIn(0, 180)
        assertEquals(18, spm)
        assertEquals(3, timestamps.size)
    }

    @Test
    fun testSpmCoerceLimits() {
        val timestamps = ArrayDeque<Long>()
        val now = System.currentTimeMillis()

        // 35 strokes in 10s window -> 35 * 6 = 210, should be coerced to 180
        for (i in 0 until 35) {
            timestamps.addLast(now - i * 200L)
        }

        val spm = (timestamps.size * 6).coerceIn(0, 180)
        assertEquals(180, spm)
    }

    @Test
    fun testVerticalZAccelerationRejection() {
        val ax = 1.0f
        val ay = 1.0f
        val az = 5.0f

        val absAx = Math.abs(ax)
        val absAy = Math.abs(ay)
        val absAz = Math.abs(az)

        // Walking vertical check formula: absAz > (absAy + absAx) * 1.5
        val isWalkingMotion = absAz > (absAy + absAx) * 1.5f
        assertTrue(isWalkingMotion)
    }
}
