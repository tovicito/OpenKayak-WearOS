package com.openkayak.app

import com.openkayak.app.service.StrokeState
import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeDetectorTest {

    @Test
    fun testStrokeStateDefaults() {
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
}
