package com.openkayak.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LatLon(val latitude: Double, val longitude: Double)
data class PhoneWorkout(
    val startAt: Long, val endAt: Long, val elapsedMs: Long, val distanceM: Double,
    val strokes: Int, val averageBpm: Int, val maxBpm: Int, val minBpm: Int,
    val calories: Int, val gpxPath: String, val hrSamples: String = ""
)
data class WorkoutTrackPoint(val timeMs: Long, val latitude: Double, val longitude: Double)
data class WorkoutTelemetry(val seconds: Int, val bpm: Int, val speedKmh: Double, val latitude: Double?, val longitude: Double?)
data class PhoneBuoy(val id: Long, val latitude: Double, val longitude: Double, val type: String)

object PhoneDataStore {
    const val ROUTE_COLOR_ORANGE = "FFFF8500"
    const val ROUTE_COLOR_CYAN = "FF42D9FF"
    const val ROUTE_COLOR_PINK = "FFFF4FA3"
    private val routeColorPalette = setOf(ROUTE_COLOR_ORANGE, ROUTE_COLOR_CYAN, ROUTE_COLOR_PINK)

    private const val PREFS = "openkayak_phone"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun allWorkouts(context: Context): List<PhoneWorkout> = runCatching {
        val data = JSONArray(prefs(context).getString("workouts", "[]"))
        (0 until data.length()).mapNotNull { index ->
            val o = data.optJSONObject(index) ?: return@mapNotNull null
            PhoneWorkout(o.optLong("startAt"), o.optLong("endAt"), o.optLong("elapsedMs"),
                o.optDouble("distanceM"), o.optInt("strokes"), o.optInt("averageBpm"), o.optInt("maxBpm"),
                o.optInt("minBpm"), o.optInt("calories"), o.optString("gpxPath"), o.optString("hrSamples"))
        }.sortedByDescending { it.endAt }
    }.getOrDefault(emptyList())

    fun recordWorkout(context: Context, workout: PhoneWorkout) {
        val json = JSONArray()
        (allWorkouts(context).filterNot { it.startAt == workout.startAt } + workout)
            .sortedByDescending { it.endAt }.forEach { s ->
                json.put(JSONObject().put("startAt", s.startAt).put("endAt", s.endAt)
                    .put("elapsedMs", s.elapsedMs).put("distanceM", s.distanceM).put("strokes", s.strokes)
                    .put("averageBpm", s.averageBpm).put("maxBpm", s.maxBpm).put("minBpm", s.minBpm)
                    .put("calories", s.calories).put("gpxPath", s.gpxPath))
            }
        prefs(context).edit().putString("workouts", json.toString()).apply()
    }

    fun deleteWorkout(context: Context, startAt: Long) {
        val all = allWorkouts(context)
        val target = all.firstOrNull { it.startAt == startAt } ?: return
        val json = JSONArray()
        all.filterNot { it.startAt == startAt }.forEach { w ->
            json.put(JSONObject().put("startAt", w.startAt).put("endAt", w.endAt).put("elapsedMs", w.elapsedMs)
                .put("distanceM", w.distanceM).put("strokes", w.strokes).put("averageBpm", w.averageBpm)
                .put("maxBpm", w.maxBpm).put("minBpm", w.minBpm).put("calories", w.calories)
                .put("gpxPath", w.gpxPath).put("hrSamples", w.hrSamples))
        }
        prefs(context).edit().putString("workouts", json.toString()).apply()
        target.gpxPath.takeIf { it.isNotBlank() }?.let { runCatching { File(it).delete() } }
    }

    fun telemetry(workout: PhoneWorkout): List<WorkoutTelemetry> {
        val points = routeTrackPoints(workout.gpxPath)
        val heart = workout.hrSamples.split(';').mapNotNull { row ->
            val p = row.split(',')
            if (p.size != 2) null else {
                val t = p[0].toLongOrNull(); val bpm = p[1].toIntOrNull()
                if (t == null || bpm == null || bpm !in 25..240) null else t to bpm
            }
        }.sortedBy { it.first }
        val duration = (workout.elapsedMs.coerceAtLeast(0L) / 1000L).toInt()
        if (duration <= 0) return emptyList()
        val speeds = points.mapIndexedNotNull { i, pt ->
            if (i == 0) null else {
                val prev = points[i - 1]; val dt = (pt.timeMs - prev.timeMs) / 1000.0
                if (dt <= 0.0) null else Triple(pt.timeMs, (distanceMeters(prev.latitude, prev.longitude, pt.latitude, pt.longitude) / dt * 3.6).coerceIn(0.0, 60.0), pt)
            }
        }
        return (0..duration step 5).map { sec ->
            val time = workout.startAt + sec * 1000L
            val hr = heart.minByOrNull { kotlin.math.abs(it.first - time) }?.takeIf { kotlin.math.abs(it.first - time) <= 15000L }?.second ?: 0
            val sp = speeds.minByOrNull { kotlin.math.abs(it.first - time) }?.takeIf { kotlin.math.abs(it.first - time) <= 10000L }
            val pt = points.minByOrNull { kotlin.math.abs(it.timeMs - time) }?.takeIf { kotlin.math.abs(it.timeMs - time) <= 15000L }
            WorkoutTelemetry(sec, hr, sp?.second ?: 0.0, pt?.latitude, pt?.longitude)
        }
    }

    fun routeTrackPoints(path: String?): List<WorkoutTrackPoint> {
        if (path.isNullOrBlank()) return emptyList()
        val file = File(path); if (!file.isFile) return emptyList()
        val regex = Regex("""<trkpt\\b[^>]*\\blat="([^"]+)"[^>]*\\blon="([^"]+)"[^>]*>\\s*<time>([^<]+)</time>""")
        return runCatching {
            regex.findAll(file.readText()).mapNotNull { m ->
                val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                val time = runCatching { java.time.Instant.parse(m.groupValues[3]).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
                if (lat in -90.0..90.0 && lon in -180.0..180.0) WorkoutTrackPoint(time, lat, lon) else null
            }.toList()
        }.getOrDefault(emptyList())
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371000.0; val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val h = kotlin.math.sin(dLat / 2).let { it * it } + kotlin.math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun allBuoys(context: Context): List<PhoneBuoy> = runCatching {
        val data = JSONArray(prefs(context).getString("buoys", "[]"))
        (0 until data.length()).mapNotNull { index ->
            val o = data.optJSONObject(index) ?: return@mapNotNull null
            val lat = o.optDouble("lat", Double.NaN); val lon = o.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) null
            else PhoneBuoy(o.optLong("id"), lat, lon, o.optString("type", "BUOY"))
        }
    }.getOrDefault(emptyList())

    fun replaceBuoys(context: Context, items: List<PhoneBuoy>) {
        val json = JSONArray()
        items.distinctBy { it.id }.forEach { b ->
            json.put(JSONObject().put("id", b.id).put("lat", b.latitude).put("lon", b.longitude).put("type", b.type))
        }
        prefs(context).edit().putString("buoys", json.toString()).apply()
    }
    fun addBuoy(context: Context, lat: Double, lon: Double, type: String) {
        if (lat in -90.0..90.0 && lon in -180.0..180.0)
            replaceBuoys(context, allBuoys(context) + PhoneBuoy(System.currentTimeMillis(), lat, lon, type))
    }
    fun removeBuoy(context: Context, id: Long) = replaceBuoys(context, allBuoys(context).filterNot { it.id == id })

    fun saveCircuit(context: Context, file: File, label: String) {
        prefs(context).edit().putString("circuit_path", file.absolutePath).putString("circuit_label", label.take(80)).apply()
    }
    fun currentCircuitPath(context: Context): String? = prefs(context).getString("circuit_path", null)?.takeIf { File(it).isFile }
    fun clearCircuit(context: Context) {
        currentCircuitPath(context)?.let { runCatching { File(it).delete() } }
        prefs(context).edit().remove("circuit_path").remove("circuit_label").apply()
    }

    fun routePoints(path: String?): List<LatLon> {
        if (path.isNullOrBlank()) return emptyList()
        val file = File(path)
        if (!file.isFile) return emptyList()
        val regex = Regex("""<trkpt\b[^>]*\blat="([^"]+)"[^>]*\blon="([^"]+)"[^>]*>""")
        return runCatching {
            regex.findAll(file.readText()).mapNotNull { match ->
                val lat = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
            }.toList()
        }.getOrDefault(emptyList())
    }

    fun saveSettings(context: Context, weightKg: Double, ageYears: Int, heightCm: Int, mapZoom: Int, gpsAccuracy: Float, routeColorHex: String) {
        val safeRouteColor = routeColorHex.takeIf { it in routeColorPalette } ?: ROUTE_COLOR_ORANGE
        prefs(context).edit().putFloat("weight_kg", weightKg.toFloat()).putInt("age_years", ageYears).putInt("height_cm", heightCm.coerceIn(100, 230))
            .putInt("map_zoom", mapZoom.coerceIn(14, 17)).putFloat("gps_accuracy", gpsAccuracy.coerceIn(10f, 65f))
            .putString("route_color_hex", safeRouteColor).apply()
    }
    fun routeColorHex(context: Context): String =
        prefs(context).getString("route_color_hex", ROUTE_COLOR_ORANGE)
            ?.takeIf { it in routeColorPalette } ?: ROUTE_COLOR_ORANGE

    fun weightKg(context: Context) = prefs(context).getFloat("weight_kg", 70f).toDouble()
    fun ageYears(context: Context) = prefs(context).getInt("age_years", 30)
    fun heightCm(context: Context) = prefs(context).getInt("height_cm", 170).coerceIn(100, 230)
    fun mapZoom(context: Context) = prefs(context).getInt("map_zoom", 16).coerceIn(14, 17)
    fun gpsAccuracy(context: Context) = prefs(context).getFloat("gps_accuracy", 35f).coerceIn(10f, 65f)
}
