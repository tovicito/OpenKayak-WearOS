package com.openkayak.app.ui

import android.content.Context
import android.location.Location
import com.openkayak.app.data.WorkoutEntity
import com.openkayak.app.service.GpsPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class LearnedCircuit(
    val id: Long,
    val name: String,
    val startLat: Double,
    val startLon: Double,
    val turnLat: Double,
    val turnLon: Double,
    val totalLaps: Int,
    val outerPolyline: List<GeoPoint> = emptyList(),
    val isDeleted: Boolean = false,
    val deletedAt: Long = 0L,
    val signature: String = "",
    val buoyPoints: List<GeoPoint> = emptyList(),
    val evidenceCount: Int = 0,
    val lastSeenAt: Long = 0L,
    val restoreDeclinedAt: Long = 0L,
    val restoreDeclinedEvidence: Int = 0
)

data class CircuitAnalysisResult(
    val activeCircuits: List<LearnedCircuit>,
    val restoreCandidates: List<LearnedCircuit>
)

private data class TurnObservation(
    val workoutIndex: Int,
    val pointIndex: Int,
    val point: GpsPoint
)

private data class BuoyCluster(
    val id: Int,
    val observations: MutableList<TurnObservation>
) {
    fun center(): GpsPoint {
        var lat = 0.0
        var lon = 0.0
        for (o in observations) {
            lat += o.point.latitude
            lon += o.point.longitude
        }
        return GpsPoint(lat / observations.size, lon / observations.size, 0.0, 0L)
    }
}

private data class SequenceObservation(
    val buoyId: Int,
    val pointIndex: Int
)

private data class CycleCandidate(
    val signature: String,
    val buoyIds: List<Int>,
    val occurrences: List<Pair<Int, List<SequenceObservation>>>,
    val support: Int
)

private const val PREFS = "learned_circuits_prefs"
private const val JSON_KEY = "circuits_json"
private const val SAMPLE_METERS = 15f
private const val BUOY_CLUSTER_METERS = 25f
private const val MIN_TURN_ANGLE = 25.0
private const val MIN_BUOY_OCCURRENCES = 5
private const val MIN_CIRCUIT_BUOYS = 3
private const val MAX_CIRCUIT_BUOYS = 10
private const val MAX_CIRCUIT_DIAMETER_METERS = 2500f
private const val SOFT_DELETE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

fun calculateTurnAngleDegrees(p1: GpsPoint, p2: GpsPoint, p3: GpsPoint): Double {
    val b1 = Math.toDegrees(atan2(p2.longitude - p1.longitude, p2.latitude - p1.latitude))
    val b2 = Math.toDegrees(atan2(p3.longitude - p2.longitude, p3.latitude - p2.latitude))
    var diff = abs(b2 - b1)
    if (diff > 180.0) diff = 360.0 - diff
    return diff
}

private val distanceBuffer = object : ThreadLocal<FloatArray>() {
    override fun initialValue(): FloatArray = FloatArray(1)
}

fun distanceBetweenMeters(p1: GpsPoint, p2: GpsPoint): Float {
    val result = distanceBuffer.get() ?: FloatArray(1)
    Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, result)
    return result[0]
}

private fun bearingDegrees(a: GpsPoint, b: GpsPoint): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private fun turnAngleFromWindow(points: List<GpsPoint>, index: Int): Double {
    val before = points[index - 2]
    val center = points[index]
    val after = points[index + 2]
    var diff = abs(bearingDegrees(before, center) - bearingDegrees(center, after))
    if (diff > 180.0) diff = 360.0 - diff
    return diff
}

private fun sampled(points: List<GpsPoint>): List<Pair<Int, GpsPoint>> {
    val result = mutableListOf<Pair<Int, GpsPoint>>()
    for (i in points.indices) {
        val p = points[i]
        if (result.isEmpty() || distanceBetweenMeters(result.last().second, p) >= SAMPLE_METERS) {
            result += i to p
        }
    }
    return result
}

private fun extractTurnObservations(workouts: List<List<GpsPoint>>): List<TurnObservation> {
    val turns = mutableListOf<TurnObservation>()
    for ((workoutIndex, points) in workouts.withIndex()) {
        val samples = sampled(points)
        if (samples.size < 7) continue

        // Optimization: Map sample points once per workout instead of inside the sample point loop
        val samplePoints = samples.map { it.second }
        var lastAccepted: GpsPoint? = null
        for (i in 2 until samples.lastIndex - 1) {
            val angle = turnAngleFromWindow(samplePoints, i)
            val point = samplePoints[i]
            if (angle < MIN_TURN_ANGLE) continue
            if (lastAccepted != null && distanceBetweenMeters(lastAccepted, point) < 45f) continue
            turns += TurnObservation(workoutIndex, samples[i].first, point)
            lastAccepted = point
        }
    }
    return turns
}

private fun clusterTurns(turns: List<TurnObservation>): List<BuoyCluster> {
    val clusters = mutableListOf<BuoyCluster>()
    for (turn in turns) {
        var best: BuoyCluster? = null
        var bestDistance = Float.MAX_VALUE
        for (cluster in clusters) {
            val d = distanceBetweenMeters(turn.point, cluster.center())
            if (d <= BUOY_CLUSTER_METERS && d < bestDistance) {
                best = cluster
                bestDistance = d
            }
        }
        if (best != null) {
            best.observations += turn
        } else {
            clusters += BuoyCluster(clusters.size, mutableListOf(turn))
        }
    }
    return clusters
}

private fun buildWorkoutSequences(
    clusters: List<BuoyCluster>,
    workoutCount: Int
): List<List<SequenceObservation>> {
    val byWorkout = clusters.flatMap { cluster ->
        cluster.observations.map { cluster.id to it }
    }.groupBy { it.second.workoutIndex }

    return (0 until workoutCount).map { workoutIndex ->
        val observations = byWorkout[workoutIndex].orEmpty().sortedBy { it.second.pointIndex }
        val sequence = mutableListOf<SequenceObservation>()
        for ((clusterId, observation) in observations) {
            if (sequence.lastOrNull()?.buoyId == clusterId) continue
            sequence += SequenceObservation(clusterId, observation.pointIndex)
        }
        sequence
    }
}

// Optimization: Find lexicographically smallest rotation directly without string conversions or sublist allocations
private fun canonicalCycle(ids: List<Int>): Pair<String, List<Int>> {
    val n = ids.size
    if (n == 0) return "" to emptyList()
    var bestIndex = 0
    for (start in 1 until n) {
        for (i in 0 until n) {
            val a = ids[(start + i) % n]
            val b = ids[(bestIndex + i) % n]
            if (a != b) {
                if (a < b) bestIndex = start
                break
            }
        }
    }
    val best = List(n) { i -> ids[(bestIndex + i) % n] }
    return best.joinToString("-") to best
}

private fun collectCycleCandidates(
    sequences: List<List<SequenceObservation>>
): List<CycleCandidate> {
    val grouped = mutableMapOf<String, MutableList<Pair<Int, List<SequenceObservation>>>>()

    for ((workoutIndex, sequence) in sequences.withIndex()) {
        if (sequence.size < MIN_CIRCUIT_BUOYS + 1) continue
        val seenKeys = mutableSetOf<String>()

        for (start in sequence.indices) {
            for (end in (start + MIN_CIRCUIT_BUOYS)..minOf(sequence.lastIndex, start + MAX_CIRCUIT_BUOYS)) {
                if (sequence[end].buoyId != sequence[start].buoyId) continue
                val body = sequence.subList(start, end)
                if (body.size !in MIN_CIRCUIT_BUOYS..MAX_CIRCUIT_BUOYS) continue

                // Optimization: Reuse mapped buoy ID list to avoid multiple mappings
                val buoyIds = body.map { it.buoyId }
                if (buoyIds.toSet().size < MIN_CIRCUIT_BUOYS) continue

                val (signature, canonicalIds) = canonicalCycle(buoyIds)
                if (seenKeys.add(signature)) {
                    grouped.getOrPut(signature) { mutableListOf() } += workoutIndex to (body + sequence[end])
                }
                if (canonicalIds.isEmpty()) break
            }
        }
    }

    return grouped.mapNotNull { (signature, occurrences) ->
        if (occurrences.size < MIN_BUOY_OCCURRENCES) return@mapNotNull null
        CycleCandidate(signature, signature.split("-").map { it.toInt() }, occurrences, occurrences.size)
    }
}

// Optimization: Precompute edge frequencies once across all sequences to avoid O(Candidates * TotalEdges) scanning
private fun buildEdgeCounts(sequences: List<List<SequenceObservation>>): Map<Pair<Int, Int>, Int> {
    val counts = HashMap<Pair<Int, Int>, Int>()
    for (sequence in sequences) {
        for (i in 0 until sequence.lastIndex) {
            val edge = sequence[i].buoyId to sequence[i + 1].buoyId
            counts[edge] = (counts[edge] ?: 0) + 1
        }
    }
    return counts
}

private fun cycleEdgeSupport(candidate: CycleCandidate, edgeCounts: Map<Pair<Int, Int>, Int>): Boolean {
    val buoyIds = candidate.buoyIds
    val size = buoyIds.size
    for (i in 0 until size) {
        val edge = buoyIds[i] to buoyIds[(i + 1) % size]
        if ((edgeCounts[edge] ?: 0) < MIN_BUOY_OCCURRENCES) return false
    }
    return true
}

private fun meanPolyline(polylines: List<List<GeoPoint>>, targetSize: Int = 80): List<GeoPoint> {
    if (polylines.isEmpty()) return emptyList()
    val normalized = polylines.map { poly ->
        if (poly.size <= targetSize) poly
        else List(targetSize) { i ->
            val index = (i.toDouble() * (poly.lastIndex.toDouble() / (targetSize - 1))).toInt()
            poly[index]
        }
    }
    val size = normalized.minOf { it.size }
    return List(size) { i ->
        GeoPoint(
            normalized.sumOf { it[i].latitude } / normalized.size,
            normalized.sumOf { it[i].longitude } / normalized.size
        )
    }
}

private fun circuitPolylineForOccurrence(
    rawWorkout: List<GpsPoint>,
    occurrence: List<SequenceObservation>
): List<GeoPoint> {
    if (occurrence.size < 2) return emptyList()
    val start = occurrence.first().pointIndex.coerceIn(0, rawWorkout.lastIndex)
    val end = occurrence.last().pointIndex.coerceIn(start, rawWorkout.lastIndex)
    if (end <= start) return emptyList()
    return rawWorkout.subList(start, end + 1).map { GeoPoint(it.latitude, it.longitude) }
}

private fun circuitDistance(points: List<GeoPoint>): Float {
    var total = 0f
    for (i in 1 until points.size) {
        total += distanceBetweenMeters(
            GpsPoint(points[i - 1].latitude, points[i - 1].longitude, 0.0, 0L),
            GpsPoint(points[i].latitude, points[i].longitude, 0.0, 0L)
        )
    }
    return total
}

private fun loadSavedCircuits(context: Context): MutableList<LearnedCircuit> {
    val result = mutableListOf<LearnedCircuit>()
    val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(JSON_KEY, null) ?: return result
    try {
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val buoys = mutableListOf<GeoPoint>()
            obj.optJSONArray("buoys")?.let { a ->
                for (j in 0 until a.length()) {
                    val p = a.getJSONObject(j)
                    buoys += GeoPoint(p.getDouble("lat"), p.getDouble("lon"))
                }
            }
            if (buoys.isEmpty() && obj.has("turnLat")) {
                buoys += GeoPoint(obj.getDouble("turnLat"), obj.getDouble("turnLon"))
            }

            val polyline = mutableListOf<GeoPoint>()
            obj.optJSONArray("outerPolyline")?.let { a ->
                for (j in 0 until a.length()) {
                    val p = a.getJSONObject(j)
                    polyline += GeoPoint(p.getDouble("lat"), p.getDouble("lon"))
                }
            }

            result += LearnedCircuit(
                id = obj.getLong("id"),
                name = obj.optString("name", "Circuito aprendido"),
                startLat = obj.optDouble("startLat", buoys.firstOrNull()?.latitude ?: 0.0),
                startLon = obj.optDouble("startLon", buoys.firstOrNull()?.longitude ?: 0.0),
                turnLat = obj.optDouble("turnLat", buoys.firstOrNull()?.latitude ?: 0.0),
                turnLon = obj.optDouble("turnLon", buoys.firstOrNull()?.longitude ?: 0.0),
                totalLaps = obj.optInt("totalLaps", obj.optInt("evidenceCount", 0)),
                outerPolyline = polyline,
                isDeleted = obj.optBoolean("isDeleted", false),
                deletedAt = obj.optLong("deletedAt", 0L),
                signature = obj.optString("signature", ""),
                buoyPoints = buoys,
                evidenceCount = obj.optInt("evidenceCount", obj.optInt("totalLaps", 0)),
                lastSeenAt = obj.optLong("lastSeenAt", 0L),
                restoreDeclinedAt = obj.optLong("restoreDeclinedAt", 0L),
                restoreDeclinedEvidence = obj.optInt("restoreDeclinedEvidence", 0)
            )
        }
    } catch (_: Exception) {
    }
    return result
}

fun getSavedLearnedCircuits(context: Context): List<LearnedCircuit> =
    loadSavedCircuits(context).filterNot { it.isDeleted }.sortedBy { it.name }

fun saveLearnedCircuits(context: Context, circuits: List<LearnedCircuit>) {
    val array = JSONArray()
    for (c in circuits) {
        val obj = JSONObject()
        obj.put("id", c.id)
        obj.put("name", c.name)
        obj.put("startLat", c.startLat)
        obj.put("startLon", c.startLon)
        obj.put("turnLat", c.turnLat)
        obj.put("turnLon", c.turnLon)
        obj.put("totalLaps", c.totalLaps)
        obj.put("isDeleted", c.isDeleted)
        obj.put("deletedAt", c.deletedAt)
        obj.put("signature", c.signature)
        obj.put("evidenceCount", c.evidenceCount)
        obj.put("lastSeenAt", c.lastSeenAt)
        obj.put("restoreDeclinedAt", c.restoreDeclinedAt)
        obj.put("restoreDeclinedEvidence", c.restoreDeclinedEvidence)

        val buoys = JSONArray()
        for (p in c.buoyPoints) buoys.put(JSONObject().put("lat", p.latitude).put("lon", p.longitude))
        obj.put("buoys", buoys)

        val polyline = JSONArray()
        for (p in c.outerPolyline) polyline.put(JSONObject().put("lat", p.latitude).put("lon", p.longitude))
        obj.put("outerPolyline", polyline)
        array.put(obj)
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(JSON_KEY, array.toString()).apply()
}

fun softDeleteLearnedCircuit(context: Context, circuitId: Long) {
    val circuits = loadSavedCircuits(context)
    val now = System.currentTimeMillis()
    saveLearnedCircuits(context, circuits.map { if (it.id == circuitId) it.copy(isDeleted = true, deletedAt = now, restoreDeclinedAt = 0L, restoreDeclinedEvidence = 0) else it })
}

fun restoreLearnedCircuit(context: Context, circuitId: Long) {
    val circuits = loadSavedCircuits(context)
    saveLearnedCircuits(context, circuits.map { if (it.id == circuitId) it.copy(isDeleted = false, deletedAt = 0L, restoreDeclinedAt = 0L, restoreDeclinedEvidence = 0) else it })
}

fun permanentlyDeleteLearnedCircuit(context: Context, circuitId: Long) {
    saveLearnedCircuits(context, loadSavedCircuits(context).filterNot { it.id == circuitId })
}

fun declineCircuitRestore(context: Context, circuitId: Long, evidenceCount: Int) {
    val circuits = loadSavedCircuits(context)
    saveLearnedCircuits(context, circuits.map {
        if (it.id == circuitId) it.copy(restoreDeclinedAt = System.currentTimeMillis(), restoreDeclinedEvidence = evidenceCount)
        else it
    })
}

private fun cleanupExpiredDeleted(circuits: MutableList<LearnedCircuit>, now: Long): MutableList<LearnedCircuit> {
    circuits.removeAll { it.isDeleted && it.deletedAt > 0L && now - it.deletedAt > SOFT_DELETE_RETENTION_MS }
    return circuits
}

suspend fun analyzeLearnedCircuits(context: Context, dbWorkouts: List<WorkoutEntity>): CircuitAnalysisResult =
    withContext(Dispatchers.IO) {
        val saved = cleanupExpiredDeleted(loadSavedCircuits(context), System.currentTimeMillis())
        val workouts: List<List<GpsPoint>> = dbWorkouts.mapNotNull { workout ->
            parseJsonRoute(workout.routeGpsJson).takeIf { it.size >= 7 }
        }
        if (workouts.isEmpty()) {
            saveLearnedCircuits(context, saved)
            return@withContext CircuitAnalysisResult(saved.filterNot { it.isDeleted }, emptyList())
        }

        val turns = extractTurnObservations(workouts)
        val clusters = clusterTurns(turns)
        val validClusters = clusters.filter { it.observations.size >= MIN_BUOY_OCCURRENCES }
        if (validClusters.size < MIN_CIRCUIT_BUOYS) {
            saveLearnedCircuits(context, saved)
            return@withContext CircuitAnalysisResult(saved.filterNot { it.isDeleted }, emptyList())
        }

        val sequences = buildWorkoutSequences(validClusters, workouts.size)
        val edgeCounts = buildEdgeCounts(sequences)
        val candidates = collectCycleCandidates(sequences).filter { cycleEdgeSupport(it, edgeCounts) }
        val now = System.currentTimeMillis()
        val generated = mutableListOf<LearnedCircuit>()

        for (candidate in candidates) {
            val buoys = candidate.buoyIds.map { cluster ->
                val p = validClusters[cluster].center()
                GeoPoint(p.latitude, p.longitude)
            }
            val diameter = buoys.maxOfOrNull { a ->
                buoys.maxOfOrNull { b ->
                    distanceBetweenMeters(
                        GpsPoint(a.latitude, a.longitude, 0.0, 0L),
                        GpsPoint(b.latitude, b.longitude, 0.0, 0L)
                    )
                } ?: 0f
            } ?: 0f
            if (diameter > MAX_CIRCUIT_DIAMETER_METERS) continue

            val occurrencePolylines = candidate.occurrences.mapNotNull { (workoutIndex, occurrence) ->
                circuitPolylineForOccurrence(workouts[workoutIndex], occurrence).takeIf { it.size >= 3 }
            }
            val representative = meanPolyline(occurrencePolylines)
            if (representative.size < 3 || circuitDistance(representative) < 30f) continue

            val previous = saved.firstOrNull { it.signature == candidate.signature }
            generated += LearnedCircuit(
                id = previous?.id ?: stableCircuitId(candidate.signature),
                name = previous?.name ?: "Circuito \${generated.size + 1} (\${buoys.size} boyas)",
                startLat = buoys.first().latitude,
                startLon = buoys.first().longitude,
                turnLat = buoys.first().latitude,
                turnLon = buoys.first().longitude,
                totalLaps = candidate.support,
                outerPolyline = representative,
                isDeleted = previous?.isDeleted ?: false,
                deletedAt = previous?.deletedAt ?: 0L,
                signature = candidate.signature,
                buoyPoints = buoys,
                evidenceCount = candidate.support,
                lastSeenAt = now,
                restoreDeclinedAt = previous?.restoreDeclinedAt ?: 0L,
                restoreDeclinedEvidence = previous?.restoreDeclinedEvidence ?: 0
            )
        }

        val merged = saved.toMutableList()
        val restoreCandidates = mutableListOf<LearnedCircuit>()

        for (generatedCircuit in generated) {
            val index = merged.indexOfFirst { it.signature == generatedCircuit.signature }
            if (index < 0) {
                merged += generatedCircuit
                continue
            }

            val old = merged[index]
            if (old.isDeleted) {
                val canPrompt =
                    now - old.deletedAt <= SOFT_DELETE_RETENTION_MS &&
                    generatedCircuit.evidenceCount > old.restoreDeclinedEvidence &&
                    old.restoreDeclinedAt == 0L
                if (canPrompt) restoreCandidates += generatedCircuit.copy(id = old.id, name = old.name)

                merged[index] = generatedCircuit.copy(
                    id = old.id,
                    name = old.name,
                    isDeleted = true,
                    deletedAt = old.deletedAt,
                    restoreDeclinedAt = old.restoreDeclinedAt,
                    restoreDeclinedEvidence = old.restoreDeclinedEvidence
                )
            } else {
                merged[index] = generatedCircuit.copy(id = old.id, name = old.name)
            }
        }

        saveLearnedCircuits(context, merged)
        CircuitAnalysisResult(
            activeCircuits = merged.filterNot { it.isDeleted }.sortedBy { it.name },
            restoreCandidates = restoreCandidates.distinctBy { it.id }
        )
    }

private fun stableCircuitId(signature: String): Long {
    return 1_000_000L + (signature.hashCode().toLong() and 0x7FFFFFFFL)
}
