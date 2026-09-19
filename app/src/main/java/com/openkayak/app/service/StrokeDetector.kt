package com.openkayak.app.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.sqrt

data class StrokeState(
    val strokeRateSpm: Int = 0,
    val totalStrokes: Int = 0
)

class StrokeDetector(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _strokeState = MutableStateFlow(StrokeState())
    val strokeState: StateFlow<StrokeState> = _strokeState.asStateFlow()

    private var isTracking = false

    // Filtering variables
    private var gravity = FloatArray(3)
    private var lowPassAcc = 0f
    private val alpha = 0.8f // Gravity filter constant

    // Peak detection parameters
    private val PEAK_THRESHOLD = 2.2f // Acceleration threshold (m/s²) for paddle stroke
    private val REFRACTORY_PERIOD_MS = 400L // Minimum time between paddle strokes (max 150 SPM)
    private var lastStrokeTimestamp = 0L

    // Rolling timestamps for SPM calculation (10 seconds window)
    private val strokeTimestamps = ArrayDeque<Long>()

    fun start() {
        if (isTracking || accelerometer == null) return
        isTracking = true
        _strokeState.update { StrokeState() }
        strokeTimestamps.clear()
        lastStrokeTimestamp = 0L
        sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!isTracking) return
        isTracking = false
        sensorManager?.unregisterListener(this)
        _strokeState.update { it.copy(strokeRateSpm = 0) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isTracking || event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()

        // Isolate gravity
        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

        // Linear acceleration (dynamic movement)
        val linearX = event.values[0] - gravity[0]
        val linearY = event.values[1] - gravity[1]
        val linearZ = event.values[2] - gravity[2]

        // Acceleration magnitude vector
        val magnitude = sqrt((linearX * linearX + linearY * linearY + linearZ * linearZ).toDouble()).toFloat()

        // Smooth acceleration signal
        lowPassAcc = lowPassAcc + 0.25f * (magnitude - lowPassAcc)

        // Peak detection logic
        if (lowPassAcc > PEAK_THRESHOLD && (now - lastStrokeTimestamp) > REFRACTORY_PERIOD_MS) {
            lastStrokeTimestamp = now
            strokeTimestamps.addLast(now)

            // Remove timestamps older than 10 seconds
            val tenSecondsAgo = now - 10_000L
            while (strokeTimestamps.isNotEmpty() && strokeTimestamps.first() < tenSecondsAgo) {
                strokeTimestamps.removeFirst()
            }

            // Calculate strokes per minute (SPM) based on rolling 10s window
            val strokesInWindow = strokeTimestamps.size
            val spm = (strokesInWindow * 6).coerceIn(0, 180)

            _strokeState.update { current ->
                current.copy(
                    strokeRateSpm = spm,
                    totalStrokes = current.totalStrokes + 1
                )
            }
        } else {
            // Decay SPM to 0 if no strokes detected recently
            val tenSecondsAgo = now - 10_000L
            while (strokeTimestamps.isNotEmpty() && strokeTimestamps.first() < tenSecondsAgo) {
                strokeTimestamps.removeFirst()
            }
            val spm = (strokeTimestamps.size * 6).coerceIn(0, 180)
            _strokeState.update { it.copy(strokeRateSpm = spm) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
