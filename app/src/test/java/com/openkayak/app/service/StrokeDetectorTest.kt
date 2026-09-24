package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Field

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
    fun testSpmCalculationWindowAndCoercion() {
        val dummyContext = DummyContext()
        val detector = StrokeDetector(dummyContext)

        val now = 100_000L
        // Access private strokeTimestamps for testing via reflection
        val field: Field = StrokeDetector::class.java.getDeclaredField("strokeTimestamps")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val timestamps = field.get(detector) as ArrayDeque<Long>

        synchronized(timestamps) {
            // Add 10 strokes within last 10 seconds
            timestamps.addLast(now - 8_000L)
            timestamps.addLast(now - 7_000L)
            timestamps.addLast(now - 6_000L)
            timestamps.addLast(now - 5_000L)
            timestamps.addLast(now - 4_000L)
            timestamps.addLast(now - 3_000L)
            timestamps.addLast(now - 2_000L)
            timestamps.addLast(now - 1_000L)
            timestamps.addLast(now - 500L)
            timestamps.addLast(now - 100L)

            // Add an old stroke (> 10 sec ago)
            timestamps.addFirst(now - 15_000L)
        }

        val spm = detector.calculateSpm(now)
        // Old stroke should be removed, 10 strokes in window * 6 = 60 SPM
        assertEquals(60, spm)
    }

    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
    }
}
