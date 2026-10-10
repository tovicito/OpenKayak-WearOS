package com.openkayak.app.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class StrokeState(
    val strokeRateSpm: Int = 0,
    val totalStrokes: Int = 0
)

class StrokeDetector(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val linearAccSensor =
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _strokeState = MutableStateFlow(StrokeState())
    val strokeState: StateFlow<StrokeState> = _strokeState.asStateFlow()

    private var isTracking = false
    @Volatile private var deckMountedMode = false

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var decayTimerJob: Job? = null

    private var previousAcceleration = 0f
    private var isPeakArmed = true

    @Volatile
    var currentSpeedKmh: Float = 0f

    private val MIN_SPEED_KMH = 0.5f
    private val UPPER_THRESHOLD = 1.8f
    private val LOWER_THRESHOLD = 0.4f
    private val REFRACTORY_PERIOD_MS = 400L

    private var lastStrokeTimestamp = 0L
    private val strokeTimestamps = ArrayDeque<Long>()

    fun setDeckMountedMode(enabled: Boolean) {
        deckMountedMode = enabled
        previousAcceleration = 0f
        isPeakArmed = true
    }

    fun start() {
        if (isTracking || linearAccSensor == null) return
        isTracking = true
        _strokeState.update { StrokeState() }
        strokeTimestamps.clear()
        lastStrokeTimestamp = 0L
        previousAcceleration = 0f
        isPeakArmed = true

        sensorManager?.registerListener(this, linearAccSensor, SensorManager.SENSOR_DELAY_GAME)
        startActiveDecayLoop()
    }

    fun stop() {
        if (!isTracking) return
        isTracking = false
        decayTimerJob?.cancel()
        sensorManager?.unregisterListener(this)
        _strokeState.update { it.copy(strokeRateSpm = 0) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null) return

        // Minimum speed requirement: must be moving at >= 0.5 km/h to count strokes
        if (currentSpeedKmh < MIN_SPEED_KMH) {
            _strokeState.update { it.copy(strokeRateSpm = 0) }
            return
        }

        val now = System.currentTimeMillis()

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        val absAx = Math.abs(ax)
        val absAy = Math.abs(ay)
        val absAz = Math.abs(az)

        // Reject vertical stepping motion (walking/running):
        // Walking produces strong vertical Z accelerations relative to forward/horizontal thrust (Y/X).
        // If vertical upward motion dominates horizontal forward motion, ignore step impact.
        if (!deckMountedMode && absAz > (absAy + absAx) * 1.5f) {
            return
        }

        // Horizontal forward paddle acceleration magnitude
        // In deck mode the sensor follows the hull, so use the full linear-acceleration
        // magnitude and a lower provisional threshold. This is an estimate, not a calibrated paddle sensor.
        val forwardAcc = if (deckMountedMode) {
            sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
        } else {
            sqrt((ax * ax + ay * ay).toDouble()).toFloat()
        }
        val smoothing = if (deckMountedMode) 0.22f else 0.3f
        val filteredAcc = previousAcceleration + smoothing * (forwardAcc - previousAcceleration)
        val threshold = if (deckMountedMode) 0.85f else UPPER_THRESHOLD
        val refractory = if (deckMountedMode) 450L else REFRACTORY_PERIOD_MS
        val isLocalPeak = previousAcceleration > threshold && filteredAcc < previousAcceleration

        if (isPeakArmed && isLocalPeak && (now - lastStrokeTimestamp) > refractory) {
            isPeakArmed = false
            lastStrokeTimestamp = now
            strokeTimestamps.addLast(now)

            val spm = calculateSpm(now)
            _strokeState.update { current ->
                current.copy(
                    strokeRateSpm = spm,
                    totalStrokes = current.totalStrokes + 1
                )
            }
        } else if (!isPeakArmed && filteredAcc < LOWER_THRESHOLD) {
            isPeakArmed = true
        }

        previousAcceleration = filteredAcc
    }

    private fun calculateSpm(now: Long): Int {
        val tenSecondsAgo = now - 10_000L
        while (strokeTimestamps.isNotEmpty() && strokeTimestamps.first() < tenSecondsAgo) {
            strokeTimestamps.removeFirst()
        }
        val strokesInWindow = strokeTimestamps.size
        return (strokesInWindow * 6).coerceIn(0, 180)
    }

    private fun startActiveDecayLoop() {
        decayTimerJob?.cancel()
        decayTimerJob = scope.launch {
            while (isTracking) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val spm = calculateSpm(now)
                _strokeState.update { it.copy(strokeRateSpm = spm) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
