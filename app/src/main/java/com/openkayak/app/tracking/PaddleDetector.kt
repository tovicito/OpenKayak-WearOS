package com.openkayak.app.tracking

import kotlin.math.sqrt

/**
 * Experimental peak detector for a watch fixed to the kayak deck.
 * It estimates strokes from acceleration peaks; waves and impacts can generate
 * false positives. The threshold must be calibrated on the actual kayak.
 */
class PaddleDetector(
    private val threshold: Float = 2.15f,
    private val refractoryMs: Long = 900L
) {
    private var baseline = 0f
    private var previousResidual = 0f
    private var lastStrokeMs = Long.MIN_VALUE
    private var initialized = false
    private var peakCount = 0
    private var lastPeakMs = Long.MIN_VALUE

    fun reset() {
        baseline = 0f
        previousResidual = 0f
        lastStrokeMs = Long.MIN_VALUE
        initialized = false
        peakCount = 0
        lastPeakMs = Long.MIN_VALUE
    }

    fun onSample(x: Float, y: Float, z: Float, elapsedMs: Long): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (!magnitude.isFinite()) return false
        if (!initialized) {
            baseline = magnitude
            initialized = true
            previousResidual = 0f
            return false
        }
        val residual = magnitude - baseline
        baseline += (magnitude - baseline) * 0.015f
        val risingPeak = residual >= threshold && previousResidual < threshold * 0.55f
        previousResidual = residual
        if (!risingPeak) return false
        if (lastPeakMs != Long.MIN_VALUE) {
            val interval = elapsedMs - lastPeakMs
            if (interval !in 350L..2_200L) {
                peakCount = 0
                lastPeakMs = elapsedMs
                return false
            }
            peakCount++
        } else {
            peakCount = 1
        }
        lastPeakMs = elapsedMs
        if (peakCount < 2) return false
        if (lastStrokeMs != Long.MIN_VALUE && elapsedMs - lastStrokeMs < refractoryMs) return false
        lastStrokeMs = elapsedMs
        return true
    }
}
