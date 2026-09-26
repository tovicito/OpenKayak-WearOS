package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeDetectorTest {

    @Test
    fun testStrokeState_initialValues() {
        val strokeState = StrokeState()
        assertEquals(0, strokeState.strokeRateSpm)
        assertEquals(0, strokeState.totalStrokes)
    }

    @Test
    fun testStrokeState_customValues() {
        val strokeState = StrokeState(strokeRateSpm = 54, totalStrokes = 120)
        assertEquals(54, strokeState.strokeRateSpm)
        assertEquals(120, strokeState.totalStrokes)
    }
}
