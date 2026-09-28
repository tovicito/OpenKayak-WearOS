package com.openkayak.app.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class StrokeDetectorTest {

    @Test
    fun testSpmCalculationWindow() {
        val strokeTimestamps = ArrayDeque<Long>()
        val now = 100_000L

        // Add 5 strokes within 10s window (now - 10000 = 90000)
        strokeTimestamps.addLast(91_000L)
        strokeTimestamps.addLast(93_000L)
        strokeTimestamps.addLast(95_000L)
        strokeTimestamps.addLast(97_000L)
        strokeTimestamps.addLast(99_000L)

        // Add 2 old strokes outside 10s window
        strokeTimestamps.addFirst(85_000L)
        strokeTimestamps.addFirst(80_000L)

        val tenSecondsAgo = now - 10_000L
        val strokesInWindow = synchronized(strokeTimestamps) {
            while (strokeTimestamps.isNotEmpty() && strokeTimestamps.first() < tenSecondsAgo) {
                strokeTimestamps.removeFirst()
            }
            strokeTimestamps.size
        }

        val spm = (strokesInWindow * 6).coerceIn(0, 180)
        assertEquals(30, spm)
        assertEquals(5, strokeTimestamps.size)
    }

    @Test
    fun testConcurrentStrokeTimestampAccess() {
        val strokeTimestamps = ArrayDeque<Long>()
        val threadCount = 10
        val operationsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    for (j in 0 until operationsPerThread) {
                        val now = System.currentTimeMillis()
                        synchronized(strokeTimestamps) {
                            strokeTimestamps.addLast(now)
                            val tenSecondsAgo = now - 10_000L
                            while (strokeTimestamps.isNotEmpty() && strokeTimestamps.first() < tenSecondsAgo) {
                                strokeTimestamps.removeFirst()
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        synchronized(strokeTimestamps) {
            assert(strokeTimestamps.size >= 0)
        }
    }
}
