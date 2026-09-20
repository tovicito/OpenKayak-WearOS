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

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var decayTimerJob: Job? = null

    private var previousAcceleration = 0f
    private var isPeakArmed = true

    @Volatile
    var currentSpeedKmh: Float = 0f

    private val MIN_SPEED_KMH = 1.2f
    private val UPPER_THRESHOLD = 2.0f
    private val LOWER_THRESHOLD = 0.5f
    private val REFRACTORY_PERIOD_MS = 350L

    private var lastStrokeTimestamp = 0L
    private val strokeTimestamps = ArrayDeque<Long>()

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

        val now = System.currentTimeMillis()

        val ay = event.values[1]
        val az = event.values[2]

        // Performance Optimization: Use Float overload for sqrt directly to avoid
        // converting Float -> Double -> Float on every high-frequency sensor event (20-50Hz).
        val currentAcc = sqrt(ay * ay + az * az)

        val filteredAcc = previousAcceleration + 0.3f * (currentAcc - previousAcceleration)

        val isLocalPeak = previousAcceleration > UPPER_THRESHOLD && filteredAcc < previousAcceleration

        if (isPeakArmed && isLocalPeak && (now - lastStrokeTimestamp) > REFRACTORY_PERIOD_MS) {
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
