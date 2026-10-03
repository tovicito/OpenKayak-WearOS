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
    fun testStrokeStateCustomValues() {
        val state = StrokeState(strokeRateSpm = 48, totalStrokes = 320)
        assertEquals(48, state.strokeRateSpm)
        assertEquals(320, state.totalStrokes)
    }
}
