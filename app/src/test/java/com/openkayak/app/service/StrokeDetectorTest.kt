package com.openkayak.app.service

import android.hardware.SensorEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StrokeDetectorTest {

    private lateinit var strokeDetector: StrokeDetector

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        strokeDetector = StrokeDetector(context)
    }

    @Test
    fun testInitialState() {
        val state = strokeDetector.strokeState.value
        assertEquals(0, state.strokeRateSpm)
        assertEquals(0, state.totalStrokes)
    }

    @Test
    fun testStartAndStopTracking() {
        strokeDetector.start()
        strokeDetector.stop()
        val state = strokeDetector.strokeState.value
        assertEquals(0, state.strokeRateSpm)
    }

    @Test
    fun testMinSpeedRequirement() {
        strokeDetector.start()
        strokeDetector.currentSpeedKmh = 0.2f // Below MIN_SPEED_KMH (0.5 km/h)

        // Simulate sensor event (Dummy event or null)
        strokeDetector.onSensorChanged(null)

        val state = strokeDetector.strokeState.value
        assertEquals(0, state.strokeRateSpm)
        strokeDetector.stop()
    }
}
