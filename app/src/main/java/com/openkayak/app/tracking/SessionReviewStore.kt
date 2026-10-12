package com.openkayak.app.tracking

import android.content.Context
import java.io.File
import kotlin.math.roundToInt

data class FinishedSession(
    val startAt: Long, val endAt: Long, val elapsedMs: Long, val distanceM: Double,
    val strokes: Int, val averageBpm: Int, val maxBpm: Int, val minBpm: Int, val calories: Int,
    val trackPath: String, val points: List<GeoSample>, val turns: List<SavedBuoy>,
    val repeatedBuoyCount: Int, val isCircuit: Boolean
)
object AthleteProfileStore {
    private const val PREFS = "openkayak_athlete_profile"
    fun weightKg(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("weight_kg", 70f).toDouble()
    fun ageYears(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("age_years", 30)
    fun save(context: Context, weightKg: Double, ageYears: Int): Boolean {
        if (!weightKg.isFinite() || weightKg !in 25.0..250.0 || ageYears !in 8..100) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("weight_kg", weightKg.toFloat()).putInt("age_years", ageYears).apply()
        return true
    }
}
object SessionReviewStore {
    private const val PREFS = "openkayak_finish_review"
    @Volatile private var cache: FinishedSession? = null
    fun prepare(context: Context, startAt: Long, endAt: Long, elapsedMs: Long, distanceM: Double, strokes: Int, trackPath: String?) {
        if (startAt <= 0L || endAt <= startAt) return
        val analysis = RouteAnalyzer.analyze(trackPath?.let(::File))
        val hr = TrackStore.heartRateSamples(context).filter { it.timeMs in startAt..endAt && it.bpm in 25L..240L }.map { it.bpm.toInt() }
        val average = if (hr.isEmpty()) 0 else hr.average().roundToInt()
        val minimum = hr.minOrNull() ?: 0; val maximum = hr.maxOrNull() ?: 0
        // MET estimate; indicative only and based on user-configured body weight.
        val calories = (6.0 * 3.5 * AthleteProfileStore.weightKg(context) / 200.0 * (elapsedMs.coerceAtLeast(0L) / 60_000.0)).roundToInt().coerceAtLeast(0)
        val review = FinishedSession(startAt, endAt, elapsedMs, distanceM, strokes, average, maximum, minimum, calories,
            trackPath.orEmpty(), analysis.points, analysis.turns, analysis.repeatedBuoyCount, analysis.isCircuit)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("pending", true).putLong("start", startAt).putLong("end", endAt).putLong("elapsed", elapsedMs)
            .putFloat("distance", distanceM.toFloat()).putInt("strokes", strokes).putInt("average_hr", average)
            .putInt("max_hr", maximum).putInt("min_hr", minimum).putInt("calories", calories)
            .putString("track_path", trackPath.orEmpty()).putString("turns", encodeTurns(analysis.turns))
            .putInt("repeated_buoys", analysis.repeatedBuoyCount).putBoolean("is_circuit", analysis.isCircuit).apply()
        if (trackPath != null) runCatching {
            File(context.filesDir, "analysis-" + startAt + ".tsv").writeText(buildString {
                appendLine("latitude\tlongitude\tturnType\tangleDegrees\trepetitions")
                analysis.points.forEach { appendLine(it.latitude.toString() + "\t" + it.longitude + "\t\t\t") }
                analysis.turns.forEach { appendLine(it.latitude.toString() + "\t" + it.longitude + "\t" + it.turnType + "\t" + it.angleDegrees + "\t" + it.repetitions) }
            })
        }
        cache = review
    }
    fun pending(context: Context): FinishedSession? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("pending", false)) return null
        val end = p.getLong("end", 0L)
        cache?.takeIf { it.endAt == end }?.let { return it }
        val path = p.getString("track_path", "").orEmpty()
        val analysis = RouteAnalyzer.analyze(path.takeIf { it.isNotBlank() }?.let(::File))
        return FinishedSession(p.getLong("start", 0L), end, p.getLong("elapsed", 0L), p.getFloat("distance", 0f).toDouble(),
            p.getInt("strokes", 0), p.getInt("average_hr", 0), p.getInt("max_hr", 0), p.getInt("min_hr", 0),
            p.getInt("calories", 0), path, analysis.points, decodeTurns(p.getString("turns", "").orEmpty()),
            p.getInt("repeated_buoys", 0), p.getBoolean("is_circuit", false)).also { cache = it }
    }
    fun saveWorkout(context: Context, r: FinishedSession) {
        SessionHistoryStore.record(context, r.startAt, r.endAt, r.elapsedMs, r.distanceM, r.strokes, r.trackPath,
            r.averageBpm, r.maxBpm, r.minBpm, r.calories)
        com.openkayak.app.sync.WearSyncBridge.publishWorkout(context, r.startAt)
    }
    fun saveCircuit(context: Context, r: FinishedSession) {
        CircuitStore.save(context, r); BuoyStore.saveDetectedTurns(context, r.turns)
        com.openkayak.app.sync.WearSyncBridge.publishCircuit(context)
        com.openkayak.app.sync.WearSyncBridge.publishBuoys(context)
    }
    fun complete(context: Context, r: FinishedSession, keepWorkout: Boolean) {
        if (!keepWorkout && r.trackPath.isNotBlank()) runCatching { File(r.trackPath).delete() }
        runCatching { File(context.filesDir, "analysis-" + r.startAt + ".tsv").delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("pending", false).apply()
        cache = null
    }
    private fun encodeTurns(turns: List<SavedBuoy>) = turns.joinToString(";") {
        listOf(it.id, it.latitude, it.longitude, it.turnType, it.angleDegrees, it.repetitions).joinToString(",")
    }
    private fun decodeTurns(text: String) = text.split(';').filter { it.isNotBlank() }.mapNotNull { raw ->
        val p = raw.split(',')
        if (p.size != 6) null else {
            val id = p[0].toLongOrNull() ?: return@mapNotNull null
            val lat = p[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = p[2].toDoubleOrNull() ?: return@mapNotNull null
            SavedBuoy(id, lat, lon, true, p[3], p[4].toDoubleOrNull() ?: 0.0, p[5].toIntOrNull() ?: 1)
        }
    }
}
object CircuitStore {
    private const val PREFS = "openkayak_saved_circuit"
    @Volatile private var cachedPath: String? = null
    @Volatile private var cachedModified = -1L
    @Volatile private var cachedPoints: List<GeoSample> = emptyList()
    fun save(context: Context, r: FinishedSession) {
        if (r.trackPath.isBlank()) return
        val source = File(r.trackPath); if (!source.exists()) return
        val target = File(context.filesDir, "circuit-" + r.startAt + ".gpx")
        source.copyTo(target, overwrite = true)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("track_path", target.absolutePath)
            .putLong("saved_at", r.endAt).putString("label", if (r.isCircuit) "Circuito repetido" else "Último recorrido").apply()
        cachedPath = null
    }
    fun file(context: Context): File? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("track_path", null)?.let(::File)?.takeIf { it.isFile }

    fun importFile(context: Context, source: File, label: String = "Circuito importado"): Boolean {
        if (!source.isFile || source.length() <= 0L) return false
        val target = File(context.filesDir, "circuito-importado-" + System.currentTimeMillis() + ".gpx")
        return runCatching {
            source.copyTo(target, overwrite = true)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("track_path", target.absolutePath).putLong("saved_at", System.currentTimeMillis())
                .putString("label", label.take(80)).apply()
            cachedPath = null
            true
        }.getOrDefault(false)
    }

    fun clear(context: Context) {
        file(context)?.let { runCatching { it.delete() } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("track_path").remove("saved_at").remove("label").apply()
        cachedPath = null
        cachedModified = -1L
        cachedPoints = emptyList()
    }

    fun points(context: Context): List<GeoSample> {
        val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("track_path", null) ?: return emptyList()
        val file = File(path); if (!file.exists()) return emptyList()
        if (cachedPath == path && cachedModified == file.lastModified()) return cachedPoints
        return RouteAnalyzer.analyze(file).points.also { cachedPath = path; cachedModified = file.lastModified(); cachedPoints = it }
    }
}
