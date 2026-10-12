package com.openkayak.app.tracking

import android.location.Location
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot

data class RouteAnalysis(
    val points: List<GeoSample>,
    val turns: List<SavedBuoy>,
    val repeatedBuoyCount: Int,
    val isCircuit: Boolean
)

/**
 * Conservative post-session geometry analysis.
 * Small GPS heading corrections are filtered out; sustained turns and U-turns are retained.
 */
object RouteAnalyzer {
    private data class TimedPoint(val point: GeoSample, val timeMs: Long)
    private data class Candidate(val point: GeoSample, val timeMs: Long, val angle: Double, val type: String)
    private data class Cluster(
        var latSum: Double,
        var lonSum: Double,
        var count: Int,
        var type: String,
        var angle: Double,
        var lastTimeMs: Long
    ) {
        val latitude get() = latSum / count
        val longitude get() = lonSum / count
    }

    private const val MAX_MAP_POINTS = 14_000
    private const val TURN_BASELINE_METERS = 12.0
    private const val TURN_THRESHOLD_DEGREES = 62.0
    private const val UTURN_THRESHOLD_DEGREES = 150.0
    private const val EVENT_COOLDOWN_MS = 22_000L
    private const val EVENT_COOLDOWN_METERS = 22.0
    private const val CLUSTER_RADIUS_METERS = 20.0
    private const val MIN_REPEAT_INTERVAL_MS = 40_000L

    private val pointRegex = Regex(
        """<trkpt\b[^>]*\blat="([^"]+)"[^>]*\blon="([^"]+)"[^>]*>.*?<time>([^<]+)</time>.*?</trkpt>""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun analyze(file: File?): RouteAnalysis {
        if (file == null || !file.isFile) return RouteAnalysis(emptyList(), emptyList(), 0, false)
        val timed = runCatching {
            pointRegex.findAll(file.readText()).mapNotNull { match ->
                val lat = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                val timestamp = runCatching { Instant.parse(match.groupValues[3]).toEpochMilli() }.getOrDefault(0L)
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || timestamp <= 0L) null
                else TimedPoint(GeoSample(lat, lon), timestamp)
            }.toList()
        }.getOrDefault(emptyList())

        val cleaned = ArrayList<TimedPoint>(timed.size)
        timed.forEach { next ->
            val previous = cleaned.lastOrNull()
            if (previous == null) cleaned.add(next) else {
                val elapsedMs = next.timeMs - previous.timeMs
                if (elapsedMs <= 0L) return@forEach
                val dist = distance(previous.point, next.point)
                val speed = dist / (elapsedMs / 1000.0)
                // A kayak track should not contain GNSS teleport spikes.
                if (speed <= 8.0 && (dist >= 0.65 || elapsedMs >= 5_000L)) cleaned.add(next)
            }
        }

        val stable = removeIsolatedGpsSpikes(cleaned)
        val turns = clusterRepeatVisits(detectTurnCandidates(stable))
        val totalMeters = stable.zipWithNext().sumOf { distance(it.first.point, it.second.point) }
        val closingMeters = if (stable.size >= 3) distance(stable.first().point, stable.last().point) else Double.MAX_VALUE
        val repeated = turns.count { it.repetitions >= 2 }
        val circuit = totalMeters > 250.0 && closingMeters < maxOf(25.0, totalMeters * 0.04) && turns.size >= 3
        return RouteAnalysis(downsample(stable.map { it.point }, MAX_MAP_POINTS), turns, repeated, circuit)
    }

    /**
     * Remove only an isolated, large sideways excursion whose neighbours are nearly
     * on the same track. This rejects common single-fix GNSS spikes without rounding
     * genuine square corners or smoothing the whole route into an oval.
     */
    private fun removeIsolatedGpsSpikes(points: List<TimedPoint>): List<TimedPoint> {
        if (points.size < 3) return points
        val result = ArrayList<TimedPoint>(points.size)
        result.add(points.first())
        for (index in 1 until points.lastIndex) {
            val previous = points[index - 1]
            val current = points[index]
            val next = points[index + 1]
            val dtBefore = current.timeMs - previous.timeMs
            val dtAfter = next.timeMs - current.timeMs
            if (dtBefore in 1L..4_000L && dtAfter in 1L..4_000L) {
                val direct = distance(previous.point, next.point)
                val detour = distance(previous.point, current.point) + distance(current.point, next.point) - direct
                val sideError = perpendicularDistanceMeters(current.point, previous.point, next.point)
                if (detour > 18.0 && sideError > 12.0) continue
            }
            result.add(current)
        }
        result.add(points.last())
        return result
    }

    private fun perpendicularDistanceMeters(point: GeoSample, start: GeoSample, end: GeoSample): Double {
        val referenceLat = Math.toRadians((start.latitude + end.latitude) / 2.0)
        val metersPerLon = 111_320.0 * cos(referenceLat)
        val dx = (end.longitude - start.longitude) * metersPerLon
        val dy = (end.latitude - start.latitude) * 111_320.0
        val px = (point.longitude - start.longitude) * metersPerLon
        val py = (point.latitude - start.latitude) * 111_320.0
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < 0.01) return hypot(px, py)
        val projection = ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(px - projection * dx, py - projection * dy)
    }

    private fun detectTurnCandidates(points: List<TimedPoint>): List<Candidate> {
        if (points.size < 6) return emptyList()
        val candidates = ArrayList<Candidate>()
        var lastKept: Candidate? = null
        for (pivotIndex in 2 until points.lastIndex - 2) {
            val beforeIndex = indexAtDistance(points, pivotIndex, -1, TURN_BASELINE_METERS) ?: continue
            val afterIndex = indexAtDistance(points, pivotIndex, 1, TURN_BASELINE_METERS) ?: continue
            val before = points[beforeIndex]
            val pivot = points[pivotIndex]
            val after = points[afterIndex]
            val beforeSeconds = (pivot.timeMs - before.timeMs) / 1000.0
            val afterSeconds = (after.timeMs - pivot.timeMs) / 1000.0
            if (beforeSeconds !in 2.0..35.0 || afterSeconds !in 2.0..35.0) continue
            val inbound = bearing(before.point, pivot.point)
            val outbound = bearing(pivot.point, after.point)
            val angle = abs(((outbound - inbound + 540.0) % 360.0) - 180.0)
            // Corrections and ordinary GPS course noise never become stored buoy candidates.
            if (angle < TURN_THRESHOLD_DEGREES) continue
            val candidate = Candidate(pivot.point, pivot.timeMs, angle,
                if (angle >= UTURN_THRESHOLD_DEGREES) "UTURN" else "BUOY")
            val previous = lastKept
            if (previous != null && pivot.timeMs - previous.timeMs < EVENT_COOLDOWN_MS &&
                distance(previous.point, pivot.point) < EVENT_COOLDOWN_METERS) continue
            candidates.add(candidate)
            lastKept = candidate
        }
        return candidates
    }

    private fun indexAtDistance(points: List<TimedPoint>, pivotIndex: Int, direction: Int, targetMeters: Double): Int? {
        var walked = 0.0
        var index = pivotIndex
        var steps = 0
        while (true) {
            val next = index + direction
            if (next !in points.indices || steps++ > 90) return null
            walked += distance(points[index].point, points[next].point)
            index = next
            if (walked >= targetMeters) return index
        }
    }

    private fun clusterRepeatVisits(candidates: List<Candidate>): List<SavedBuoy> {
        val clusters = ArrayList<Cluster>()
        candidates.forEach { candidate ->
            val nearby = clusters.firstOrNull {
                distance(GeoSample(it.latitude, it.longitude), candidate.point) <= CLUSTER_RADIUS_METERS
            }
            if (nearby == null) {
                clusters.add(Cluster(candidate.point.latitude, candidate.point.longitude, 1,
                    candidate.type, candidate.angle, candidate.timeMs))
            } else if (candidate.timeMs - nearby.lastTimeMs >= MIN_REPEAT_INTERVAL_MS) {
                // Only a later, distinct pass adds a repetition, not every GPS fix inside one turn.
                nearby.latSum += candidate.point.latitude
                nearby.lonSum += candidate.point.longitude
                nearby.count++
                nearby.lastTimeMs = candidate.timeMs
                if (candidate.type == "UTURN" || candidate.angle > nearby.angle) {
                    nearby.type = candidate.type
                    nearby.angle = candidate.angle
                }
            }
        }
        return clusters.mapIndexed { index, c ->
            SavedBuoy(index + 1L, c.latitude, c.longitude, true, c.type, c.angle, c.count)
        }
    }

    private fun distance(a: GeoSample, b: GeoSample): Double {
        val out = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
        return out[0].toDouble()
    }

    private fun bearing(a: GeoSample, b: GeoSample): Double {
        val out = FloatArray(2)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
        return (out[1].toDouble() + 360.0) % 360.0
    }

    private fun downsample(points: List<GeoSample>, maximum: Int): List<GeoSample> {
        if (points.size <= maximum) return points
        val step = ceil((points.size - 1).toDouble() / (maximum - 1)).toInt().coerceAtLeast(1)
        val sampled = points.filterIndexed { index, _ -> index % step == 0 }.toMutableList()
        if (sampled.lastOrNull() != points.last()) sampled.add(points.last())
        return sampled.take(maximum - 1) + points.last()
    }
}
