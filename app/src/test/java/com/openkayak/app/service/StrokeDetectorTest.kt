package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeDetectorTest {

    @Test
    fun testStrokeStateDefaults() {
        val defaultState = StrokeState()
        assertEquals(0, defaultState.strokeRateSpm)
        assertEquals(0, defaultState.totalStrokes)
    }

    @Test
    fun testStrokeStateCopy() {
        val state = StrokeState(strokeRateSpm = 45, totalStrokes = 120)
        val updated = state.copy(totalStrokes = 121)
        assertEquals(45, updated.strokeRateSpm)
        assertEquals(121, updated.totalStrokes)
    }
}
