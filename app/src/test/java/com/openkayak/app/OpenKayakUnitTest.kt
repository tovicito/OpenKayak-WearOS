package com.openkayak.app

import com.openkayak.app.ble.BleConnectionState
import com.openkayak.app.ble.BleHeartRateState
import com.openkayak.app.service.GpsPoint
import com.openkayak.app.service.StrokeState
import com.openkayak.app.service.WorkoutState
import com.openkayak.app.ui.calculateTurnAngleDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenKayakUnitTest {

    @Test
    fun testWorkoutStateDefaults() {
        val state = WorkoutState()
        assertFalse(state.isTracking)
        assertFalse(state.isPaused)
        assertEquals(0.0f, state.speedKmh, 0.001f)
        assertEquals(0.0f, state.distanceMeters, 0.001f)
        assertEquals(0L, state.elapsedTimeSeconds)
        assertEquals(0, state.strokeRateSpm)
        assertEquals(0, state.totalStrokes)
        assertEquals(0, state.lapCount)
    }

    @Test
    fun testBleHeartRateStateDefaults() {
        val hrState = BleHeartRateState()
        assertEquals(BleConnectionState.DISCONNECTED, hrState.connectionState)
        assertEquals(0, hrState.heartRateBpm)
        assertFalse(hrState.isPulseActive)
        assertFalse(hrState.isUsingInternalSensor)
    }

    @Test
    fun testStrokeStateDefaults() {
        val strokeState = StrokeState()
        assertEquals(0, strokeState.strokeRateSpm)
        assertEquals(0, strokeState.totalStrokes)
    }

    @Test
    fun testTurnAngleCalculation() {
        // Straight line going North
        val p1 = GpsPoint(43.00, -5.00, 0.0, 0L)
        val p2 = GpsPoint(43.01, -5.00, 0.0, 0L)
        val p3 = GpsPoint(43.02, -5.00, 0.0, 0L)

        val straightAngle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, straightAngle, 0.1)

        // 90-degree turn (North then East)
        val pEast = GpsPoint(43.01, -4.99, 0.0, 0L)
        val rightTurnAngle = calculateTurnAngleDegrees(p1, p2, pEast)
        assertTrue(rightTurnAngle > 80.0 && rightTurnAngle < 100.0)
    }

    @Test
    fun testRouteJsonSerialization() {
        val points = listOf(
            GpsPoint(43.5123, -5.8765, 0.0, 1000L),
            GpsPoint(43.5124, -5.8766, 0.0, 2000L)
        )

        val sb = StringBuilder("[")
        for (i in points.indices) {
            val p = points[i]
            sb.append("{\"lat\":${p.latitude},\"lon\":${p.longitude}}")
            if (i < points.size - 1) sb.append(",")
        }
        sb.append("]")
        val jsonStr = sb.toString()

        val expected = "[{\"lat\":43.5123,\"lon\":-5.8765},{\"lat\":43.5124,\"lon\":-5.8766}]"
        assertEquals(expected, jsonStr)
    }
}
