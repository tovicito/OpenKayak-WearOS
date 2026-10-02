package com.openkayak.app.service

import org.junit.Test
import org.junit.Assert.assertEquals

class StrokeDetectorTest {

    @Test
    fun testStrokeStateDefaultValues() {
        val state = StrokeState()
        assertEquals(0, state.strokeRateSpm)
        assertEquals(0, state.totalStrokes)
    }

    @Test
    fun testStrokeStateCustomValues() {
        val state = StrokeState(strokeRateSpm = 45, totalStrokes = 120)
        assertEquals(45, state.strokeRateSpm)
        assertEquals(120, state.totalStrokes)
    }
}
