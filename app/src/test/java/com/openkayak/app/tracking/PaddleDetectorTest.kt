package com.openkayak.app.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleDetectorTest {
    @Test fun detectsPeakAndEnforcesRefractoryWindow() {
        val detector = PaddleDetector(threshold = 1.0f, refractoryMs = 350L)
        assertFalse(detector.onSample(0f, 0f, 0f, 1000L))
        assertTrue(detector.onSample(2.5f, 0f, 0f, 1400L))
        assertFalse(detector.onSample(0f, 0f, 0f, 1450L))
        assertFalse(detector.onSample(2.5f, 0f, 0f, 1500L))
        assertTrue(detector.onSample(2.5f, 0f, 0f, 1800L))
    }
}
