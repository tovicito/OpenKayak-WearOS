package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StrokeDetectorTest {

    @Test
    fun testSpmCalculationWindow() {
        val strokeTimestamps = ArrayDeque<Long>()
        val now = 100_000L

        fun calculateSpm(currentTime: Long): Int {
            val tenSecondsAgo = currentTime - 10_000L
            synchronized(strokeTimestamps) {
                while (strokeTimestamps.isNotEmpty() && strokeTimestamps.first() < tenSecondsAgo) {
                    strokeTimestamps.removeFirst()
                }
                val strokesInWindow = strokeTimestamps.size
                return (strokesInWindow * 6).coerceIn(0, 180)
            }
        }

        synchronized(strokeTimestamps) {
            strokeTimestamps.addLast(now - 12_000L) // Outside 10s window
            strokeTimestamps.addLast(now - 8_000L)  // Inside
            strokeTimestamps.addLast(now - 4_000L)  // Inside
            strokeTimestamps.addLast(now - 1_000L)  // Inside
        }

        val spm = calculateSpm(now)
        // 3 strokes in window * 6 = 18 SPM
        assertEquals(18, spm)
        assertEquals(3, strokeTimestamps.size)
    }

    @Test
    fun testThreadSafetyConcurrentAccess() {
        val strokeTimestamps = ArrayDeque<Long>()
        val executor = Executors.newFixedThreadPool(8)
        val now = System.currentTimeMillis()

        for (i in 0 until 1000) {
            executor.submit {
                synchronized(strokeTimestamps) {
                    strokeTimestamps.addLast(now - (i % 15_000))
                    val tenSecondsAgo = now - 10_000L
                    while (strokeTimestamps.isNotEmpty() && strokeTimestamps.first() < tenSecondsAgo) {
                        strokeTimestamps.removeFirst()
                    }
                }
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(true, finished)
    }
}
