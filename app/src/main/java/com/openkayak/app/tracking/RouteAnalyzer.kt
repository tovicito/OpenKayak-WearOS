package com.openkayak.app.tracking

import android.location.Location
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil

data class RouteAnalysis(val points: List<GeoSample>, val turns: List<SavedBuoy>, val repeatedBuoyCount: Int, val isCircuit: Boolean)

object RouteAnalyzer {
    private data class TimedPoint(val point: GeoSample, val timeMs: Long)
    private data class Cluster(var latSum: Double, var lonSum: Double, var count: Int, var type: String, var angle: Double)
    private val pointRegex = Regex("""<trkpt lat="([^"]+)" lon="([^"]+)"><time>([^<]+)</time></trkpt>""")

    fun analyze(file: File?): RouteAnalysis {
        if (file == null || !file.isFile) return RouteAnalysis(emptyList(), emptyList(), 0, false)
        val timed = runCatching {
            pointRegex.findAll(file.readText()).mapNotNull { m ->
                val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                val time = runCatching { Instant.parse(m.groupValues[3]).toEpochMilli() }.getOrDefault(0L)
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) null else TimedPoint(GeoSample(lat, lon), time)
            }.toList()
        }.getOrDefault(emptyList())
        val cleaned = ArrayList<TimedPoint>()
        timed.forEach { next ->
            val last = cleaned.lastOrNull()
            if (last == null) cleaned.add(next) else {
                val dist = distance(last.point, next.point)
                val dt = ((next.timeMs - last.timeMs).coerceAtLeast(1L)) / 1000.0
                if (dt > 0 && dist / dt <= 18.0 && (dist >= 0.35 || next.timeMs - last.timeMs >= 4_000L)) cleaned.add(next)
            }
        }
        val turns = detectTurns(cleaned)
        val totalM = cleaned.zipWithNext().sumOf { distance(it.first.point, it.second.point) }
        val closingDistance = if (cleaned.size > 2) distance(cleaned.first().point, cleaned.last().point) else Double.MAX_VALUE
        val repeated = turns.count { it.repetitions >= 2 }
        return RouteAnalysis(downsample(cleaned.map { it.point }, 2200), turns, repeated,
            totalM > 250.0 && closingDistance < 60.0 && repeated >= 2)
    }

    private fun detectTurns(points: List<TimedPoint>): List<SavedBuoy> {
        if (points.size < 5) return emptyList()
        val events = ArrayList<SavedBuoy>()
        for (i in 4 until points.size) {
            val before = points[i - 4]; val pivot = points[i - 2]; val after = points[i]
            val seconds = (after.timeMs - before.timeMs) / 1000.0
            if (seconds !in 2.0..15.0) continue
            val d1 = distance(before.point, pivot.point); val d2 = distance(pivot.point, after.point)
            if (d1 < 2.0 || d2 < 2.0 || (d1 + d2) / seconds < 0.55) continue
            val angle = abs(((bearing(pivot.point, after.point) - bearing(before.point, pivot.point) + 540.0) % 360.0) - 180.0)
            if (angle < 10.0) continue
            val type = when { angle >= 160.0 -> "UTURN"; angle >= 25.0 -> "TURN"; else -> "CORRECTION" }
            val timestamp = pivot.timeMs
            val nearRecent = events.lastOrNull()?.let { old ->
                timestamp - old.id < 12_000L && distance(GeoSample(old.latitude, old.longitude), pivot.point) < 10.0
            } ?: false
            if (!nearRecent) events.add(SavedBuoy(timestamp, pivot.point.latitude, pivot.point.longitude, true, type, angle, 1))
        }
        val clusters = ArrayList<Cluster>()
        events.forEach { event ->
            val point = GeoSample(event.latitude, event.longitude)
            val cluster = clusters.firstOrNull { distance(GeoSample(it.latSum / it.count, it.lonSum / it.count), point) < 18.0 }
            if (cluster == null) clusters.add(Cluster(event.latitude, event.longitude, 1, event.turnType, event.angleDegrees))
            else {
                cluster.latSum += event.latitude; cluster.lonSum += event.longitude; cluster.count++
                if (event.angleDegrees > cluster.angle) { cluster.angle = event.angleDegrees; cluster.type = event.turnType }
            }
        }
        return clusters.mapIndexed { index, c ->
            SavedBuoy(index + 1L, c.latSum / c.count, c.lonSum / c.count, true, c.type, c.angle, c.count)
        }
    }
    private fun distance(a: GeoSample, b: GeoSample): Double {
        val out = FloatArray(1); Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out); return out[0].toDouble()
    }
    private fun bearing(a: GeoSample, b: GeoSample): Double {
        val out = FloatArray(2); Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out); return (out[1].toDouble() + 360f) % 360.0
    }
    private fun downsample(points: List<GeoSample>, max: Int): List<GeoSample> {
        if (points.size <= max) return points
        val step = ceil(points.size.toDouble() / max).toInt().coerceAtLeast(1)
        return points.filterIndexed { index, _ -> index % step == 0 || index == points.lastIndex }
    }
}
