package com.openkayak.app

import com.openkayak.app.ble.BleConnectionState
import com.openkayak.app.ble.BleHeartRateState
import com.openkayak.app.data.WorkoutEntity
import com.openkayak.app.service.DownloadState
import com.openkayak.app.service.GpsPoint
import com.openkayak.app.service.WorkoutState
import com.openkayak.app.ui.calculateTurnAngleDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PureLogicUnitTest {

    @Test
    fun testCalculateTurnAngleDegrees_straightLine() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 0L)
        val p3 = GpsPoint(43.3634, -5.8593, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(0.0, angle, 0.01)
    }

    @Test
    fun testCalculateTurnAngleDegrees_rightAngleTurn() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 0L)
        val p3 = GpsPoint(43.3624, -5.8493, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(90.0, angle, 1.0)
    }

    @Test
    fun testCalculateTurnAngleDegrees_uTurn() {
        val p1 = GpsPoint(43.3614, -5.8593, 0.0, 0L)
        val p2 = GpsPoint(43.3624, -5.8593, 0.0, 0L)
        val p3 = GpsPoint(43.3614, -5.8593, 0.0, 0L)

        val angle = calculateTurnAngleDegrees(p1, p2, p3)
        assertEquals(180.0, angle, 1.0)
    }

    @Test
    fun testWorkoutStateModel() {
        val state = WorkoutState(
            isTracking = true,
            isPaused = false,
            speedKmh = 12.5f,
            distanceMeters = 1500f,
            elapsedTimeSeconds = 300L,
            strokeRateSpm = 55,
            totalStrokes = 275,
            lapCount = 2
        )
        assertTrue(state.isTracking)
        assertFalse(state.isPaused)
        assertEquals(12.5f, state.speedKmh, 0.01f)
        assertEquals(1500f, state.distanceMeters, 0.01f)
        assertEquals(300L, state.elapsedTimeSeconds)
        assertEquals(55, state.strokeRateSpm)
        assertEquals(275, state.totalStrokes)
        assertEquals(2, state.lapCount)
    }

    @Test
    fun testBleHeartRateStateModel() {
        val state = BleHeartRateState(
            connectionState = BleConnectionState.CONNECTED,
            heartRateBpm = 145,
            deviceName = "Polar H10",
            deviceAddress = "00:11:22:33:44:55"
        )
        assertEquals(BleConnectionState.CONNECTED, state.connectionState)
        assertEquals(145, state.heartRateBpm)
        assertEquals("Polar H10", state.deviceName)
        assertEquals("00:11:22:33:44:55", state.deviceAddress)
    }

    @Test
    fun testDownloadStateModel() {
        val downloadState = DownloadState(
            isDownloading = true,
            progressPercent = 45,
            downloadedTiles = 112,
            totalTiles = 250,
            statusMessage = "Downloading"
        )
        assertTrue(downloadState.isDownloading)
        assertEquals(45, downloadState.progressPercent)
        assertEquals(112, downloadState.downloadedTiles)
        assertEquals(250, downloadState.totalTiles)
    }

    @Test
    fun testWorkoutEntityModel() {
        val workout = WorkoutEntity(
            id = 1L,
            timestamp = 1600000000000L,
            durationSeconds = 600L,
            distanceMeters = 2000f,
            maxSpeedKmh = 15.0f,
            avgSpeedKmh = 12.0f,
            totalStrokes = 500,
            avgStrokeRateSpm = 50,
            estimatedCalories = 120,
            routeGpsJson = "[]"
        )
        assertEquals(1L, workout.id)
        assertEquals(2000f, workout.distanceMeters, 0.01f)
        assertEquals(120, workout.estimatedCalories)
    }
}
