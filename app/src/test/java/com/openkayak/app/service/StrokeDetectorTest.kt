package com.openkayak.app.service

class StrokeDetectorTest {

    @org.junit.Test
    fun testStrokeStateDefaults() {
        val strokeState = StrokeState()
        org.junit.Assert.assertEquals(0, strokeState.strokeRateSpm)
        org.junit.Assert.assertEquals(0, strokeState.totalStrokes)
    }

    @org.junit.Test
    fun testStrokeStateCustomValues() {
        val strokeState = StrokeState(strokeRateSpm = 45, totalStrokes = 320)
        org.junit.Assert.assertEquals(45, strokeState.strokeRateSpm)
        org.junit.Assert.assertEquals(320, strokeState.totalStrokes)
    }
}
