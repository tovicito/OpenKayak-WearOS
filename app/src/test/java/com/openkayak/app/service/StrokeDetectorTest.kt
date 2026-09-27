package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeDetectorTest {

    @Test
    fun testStrokeStateDefaultValues() {
        val state = StrokeState()
        assertEquals(0, state.strokeRateSpm)
        assertEquals(0, state.totalStrokes)
    }

    @Test
    fun testStrokeStateUpdate() {
        val state = StrokeState(strokeRateSpm = 45, totalStrokes = 120)
        val newState = state.copy(totalStrokes = state.totalStrokes + 1)
        assertEquals(45, newState.strokeRateSpm)
        assertEquals(121, newState.totalStrokes)
    }

    @Test
    fun testSpmCalculationLogic() {
        val timestamps = mutableListOf<Long>()
        val now = System.currentTimeMillis()
        for (i in 0 until 5) {
            timestamps.add(now - (i * 1500L))
        }

        val tenSecondsAgo = now - 10_000L
        val validStrokesInWindow = timestamps.count { it >= tenSecondsAgo }
        val spm = (validStrokesInWindow * 6).coerceIn(0, 180)

        assertEquals(30, spm)
    }
}
