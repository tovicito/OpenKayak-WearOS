package com.openkayak.app.tracking

import android.content.Context
import android.location.Location
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

enum class TurnType(val label: String) { CORRECTION("CORRECCIÓN"), TURN("GIRO"), UTURN("VUELTA") }
data class TurnPrediction(
    val latitude: Double, val longitude: Double, val timestampMs: Long,
    val angleDegrees: Double, val type: TurnType, val confidence: Double
)

/** Tiny on-device 3-class softmax model trained at startup on deterministic synthetic GPS turns. */
class TurnAiModel {
    private data class Sample(val point: GeoSample, val timeMs: Long)
    private val window = ArrayList<Sample>()
    private val classifier = SyntheticTurnClassifier.train()
    private var lastInferenceElapsed = Long.MIN_VALUE
    private var lastEventElapsed = Long.MIN_VALUE
    private var lastInputTime = Long.MIN_VALUE

    fun reset() {
        window.clear()
        lastInferenceElapsed = Long.MIN_VALUE
        lastEventElapsed = Long.MIN_VALUE
        lastInputTime = Long.MIN_VALUE
    }

    fun onGpsPoint(
        origin: GeoSample?, point: GeoSample, accuracyM: Float, speedKmh: Double,
        timestampMs: Long, elapsedRealtimeMs: Long
    ): TurnPrediction? {
        if (origin == null || timestampMs == lastInputTime) return null
        if (lastInferenceElapsed != Long.MIN_VALUE && elapsedRealtimeMs - lastInferenceElapsed < 1000L) return null
        lastInputTime = timestampMs
        lastInferenceElapsed = elapsedRealtimeMs
        window.add(Sample(point, timestampMs))
        while (window.size > 9) window.removeAt(0)
        while (window.size > 5 && timestampMs - window.first().timeMs > 9_000L) window.removeAt(0)
        if (accuracyM !in 0f..15f || speedKmh < 1.0 || window.size < 5) return null
        val before = window[window.size - 5]
        val pivot = window[window.size - 3]
        val after = window.last()
        if (distance(origin, pivot.point) < 8.0) return null
        val d1 = distance(before.point, pivot.point)
        val d2 = distance(pivot.point, after.point)
        if (d1 < 2.5 || d2 < 2.5) return null
        val seconds = (after.timeMs - before.timeMs) / 1000.0
        if (seconds !in 2.0..10.0 || (d1 + d2) / seconds * 3.6 < 2.0) return null
        val angle = abs(((bearing(pivot.point, after.point) - bearing(before.point, pivot.point) + 540.0) % 360.0) - 180.0)
        if (angle < 8.0) return null
        val probabilities = classifier.predict(angle, speedKmh, accuracyM.toDouble())
        val kind = when { angle >= 160.0 -> 2; angle >= 25.0 -> 1; else -> 0 }
        if (probabilities[kind] < 0.34) return null
        val cooldown = if (angle >= 160.0) 7_000L else 4_000L
        if (lastEventElapsed != Long.MIN_VALUE && elapsedRealtimeMs - lastEventElapsed < cooldown) return null
        lastEventElapsed = elapsedRealtimeMs
        return TurnPrediction(pivot.point.latitude, pivot.point.longitude, pivot.timeMs, angle, TurnType.values()[kind], probabilities[kind])
    }

    private fun distance(a: GeoSample, b: GeoSample): Double {
        val out = FloatArray(1); Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out); return out[0].toDouble()
    }
    private fun bearing(a: GeoSample, b: GeoSample): Double {
        val out = FloatArray(2); Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out); return (out[1].toDouble() + 360.0) % 360.0
    }
}

private class SyntheticTurnClassifier private constructor(private val weights: Array<DoubleArray>) {
    fun predict(angle: Double, speed: Double, accuracy: Double): DoubleArray {
        val x = features(angle, speed, accuracy)
        val logits = DoubleArray(3) { c -> weights[c].indices.sumOf { j -> weights[c][j] * x[j] } }
        val maxLogit = logits.maxOrNull() ?: 0.0
        val exps = DoubleArray(3) { exp((logits[it] - maxLogit).coerceIn(-30.0, 30.0)) }
        val total = exps.sum().coerceAtLeast(1e-12)
        return DoubleArray(3) { exps[it] / total }
    }
    companion object {
        private fun features(angle: Double, speed: Double, accuracy: Double): DoubleArray {
            val x = (angle / 180.0).coerceIn(0.0, 1.0)
            return doubleArrayOf(1.0, x, x * x, x * x * x, (speed / 10.0).coerceIn(0.0, 1.5), (accuracy / 20.0).coerceIn(0.0, 2.0))
        }
        fun train(): SyntheticTurnClassifier {
            data class Example(val x: DoubleArray, val target: Int)
            val examples = ArrayList<Example>()
            for (angle in 0..180 step 2) for (speed in listOf(2.0, 4.0, 7.0, 10.0)) for (accuracy in listOf(2.5, 6.0, 12.0)) {
                val target = when { angle >= 160 -> 2; angle >= 25 -> 1; else -> 0 }
                examples.add(Example(features(angle.toDouble(), speed, accuracy), target))
            }
            val w = Array(3) { DoubleArray(6) }
            repeat(220) {
                for (sample in examples) {
                    val logits = DoubleArray(3) { c -> w[c].indices.sumOf { j -> w[c][j] * sample.x[j] } }
                    val maxLogit = logits.maxOrNull() ?: 0.0
                    val es = DoubleArray(3) { exp((logits[it] - maxLogit).coerceIn(-30.0, 30.0)) }
                    val total = es.sum().coerceAtLeast(1e-12)
                    for (c in 0..2) {
                        val error = es[c] / total - if (sample.target == c) 1.0 else 0.0
                        for (j in 0..5) w[c][j] -= 0.035 * (error * sample.x[j] + if (j == 0) 0.0 else 0.0002 * w[c][j])
                    }
                }
            }
            return SyntheticTurnClassifier(w)
        }
    }
}

object TurnMemoryStore {
    private const val FILE = "openkayak-turn-memory.tsv"
    private const val GPS_FILE = "openkayak-gps-memory.tsv"
    @Synchronized fun rememberPoint(context: Context, sessionStart: Long, point: GeoSample, timestampMs: Long, accuracyM: Float, speedKmh: Double) {
        if (sessionStart <= 0L || !point.latitude.isFinite() || !point.longitude.isFinite()) return
        val line = listOf(sessionStart.toString(), timestampMs.toString(), String.format(Locale.US, "%.7f", point.latitude),
            String.format(Locale.US, "%.7f", point.longitude), String.format(Locale.US, "%.2f", accuracyM),
            String.format(Locale.US, "%.2f", speedKmh)).joinToString("\t") + "\n"
        runCatching { File(context.filesDir, GPS_FILE).appendText(line) }
    }

    @Synchronized fun remember(context: Context, sessionStart: Long, event: TurnPrediction) {
        if (sessionStart <= 0L) return
        val line = listOf(sessionStart.toString(), event.timestampMs.toString(),
            String.format(Locale.US, "%.7f", event.latitude), String.format(Locale.US, "%.7f", event.longitude),
            event.type.name, String.format(Locale.US, "%.2f", event.angleDegrees),
            String.format(Locale.US, "%.3f", event.confidence)).joinToString("\t") + "\n"
        runCatching { File(context.filesDir, FILE).appendText(line) }
    }
    @Synchronized fun eventsForSession(context: Context, sessionStart: Long): List<SavedBuoy> {
        if (sessionStart <= 0L) return emptyList()
        return runCatching {
            File(context.filesDir, FILE).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 7 || p[0].toLongOrNull() != sessionStart) return@mapNotNull null
                val timestamp = p[1].toLongOrNull() ?: return@mapNotNull null
                val lat = p[2].toDoubleOrNull() ?: return@mapNotNull null
                val lon = p[3].toDoubleOrNull() ?: return@mapNotNull null
                SavedBuoy(timestamp, lat, lon, true, p[4], p[5].toDoubleOrNull() ?: 0.0, 1)
            }.takeLast(120)
        }.getOrDefault(emptyList())
    }
}
