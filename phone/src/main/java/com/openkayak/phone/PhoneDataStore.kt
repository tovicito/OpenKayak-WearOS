package com.openkayak.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

data class LatLon(val latitude: Double, val longitude: Double)
data class PhoneTrackPoint(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val elevationM: Double? = null,
    val heartRateBpm: Int? = null
)
data class PhoneMetricSample(val timeMs: Long, val value: Double)
data class PhoneWorkout(
    val startAt: Long, val endAt: Long, val elapsedMs: Long, val distanceM: Double,
    val strokes: Int, val averageBpm: Int, val maxBpm: Int, val minBpm: Int,
    val calories: Int, val gpxPath: String,
    val heartRateSamples: List<PhoneMetricSample> = emptyList()
)
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
        val deleted = deletedWorkoutStarts(context)
        (0 until data.length()).mapNotNull { index ->
            val o = data.optJSONObject(index) ?: return@mapNotNull null
            if (o.optLong("startAt") in deleted) return@mapNotNull null
            val samplesJson = o.optJSONArray("heartRateSamples") ?: JSONArray()
            val samples = (0 until samplesJson.length()).mapNotNull { sampleIndex ->
                val sample = samplesJson.optJSONObject(sampleIndex) ?: return@mapNotNull null
                val time = sample.optLong("timeMs", 0L)
                val value = sample.optDouble("value", Double.NaN)
                if (time > 0L && value.isFinite() && value in 25.0..240.0) PhoneMetricSample(time, value) else null
            }
            PhoneWorkout(o.optLong("startAt"), o.optLong("endAt"), o.optLong("elapsedMs"),
                o.optDouble("distanceM"), o.optInt("strokes"), o.optInt("averageBpm"), o.optInt("maxBpm"),
                o.optInt("minBpm"), o.optInt("calories"), o.optString("gpxPath"), samples)
        }.sortedByDescending { it.endAt }
    }.getOrDefault(emptyList())

    fun recordWorkout(context: Context, workout: PhoneWorkout) {
        if (isWorkoutDeleted(context, workout.startAt)) return
        saveWorkouts(context, allWorkouts(context).filterNot { it.startAt == workout.startAt } + workout)
    }

    fun isWorkoutDeleted(context: Context, startAt: Long): Boolean =
        startAt > 0L && startAt in deletedWorkoutStarts(context)

    private fun deletedWorkoutStarts(context: Context): Set<Long> = runCatching {
        val data = JSONArray(prefs(context).getString("deleted_workout_starts", "[]"))
        (0 until data.length()).mapNotNull { index -> data.optLong(index).takeIf { it > 0L } }.toSet()
    }.getOrDefault(emptySet())

    fun deleteWorkout(context: Context, startAt: Long) {
        if (startAt <= 0L) return
        val deleted = deletedWorkoutStarts(context).toMutableSet().apply { add(startAt) }
        val keep = deleted.sortedDescending().take(5000).toMutableSet().apply { add(startAt) }
        val tombstones = JSONArray()
        keep.forEach { tombstones.put(it) }
        prefs(context).edit().putString("deleted_workout_starts", tombstones.toString()).apply()
        val old = allWorkouts(context).firstOrNull { it.startAt == startAt }
        old?.gpxPath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching {
                val file = File(path)
                val received = File(context.filesDir, "received").canonicalPath + File.separator
                if (file.isFile && file.canonicalPath.startsWith(received)) file.delete()
            }
        }
        saveWorkouts(context, allWorkouts(context).filterNot { it.startAt == startAt })
    }

    private fun saveWorkouts(context: Context, workouts: List<PhoneWorkout>) {
        val json = JSONArray()
        workouts.distinctBy { it.startAt }.sortedByDescending { it.endAt }.forEach { s ->
            val samples = JSONArray()
            s.heartRateSamples.forEach { sample ->
                samples.put(JSONObject().put("timeMs", sample.timeMs).put("value", sample.value))
            }
            json.put(JSONObject().put("startAt", s.startAt).put("endAt", s.endAt)
                .put("elapsedMs", s.elapsedMs).put("distanceM", s.distanceM).put("strokes", s.strokes)
                .put("averageBpm", s.averageBpm).put("maxBpm", s.maxBpm).put("minBpm", s.minBpm)
                .put("calories", s.calories).put("gpxPath", s.gpxPath).put("heartRateSamples", samples))
        }
        prefs(context).edit().putString("workouts", json.toString()).apply()
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

    fun trackPoints(path: String?): List<PhoneTrackPoint> {
        if (path.isNullOrBlank()) return emptyList()
        val file = File(path)
        if (!file.isFile) return emptyList()
        val pointRegex = Regex("""<trkpt\b([^>]*)>(.*?)</trkpt>""", setOf(RegexOption.DOT_MATCHES_ALL))
        val latRegex = Regex("""\blat="([^"]+)""")
        val lonRegex = Regex("""\blon="([^"]+)""")
        val timeRegex = Regex("""<time>\s*([^<]+)\s*</time>""")
        return runCatching {
            pointRegex.findAll(file.readText()).mapNotNull { match ->
                val attrs = match.groupValues[1]
                val body = match.groupValues[2]
                val lat = latRegex.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = lonRegex.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
                val timeText = timeRegex.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
                val time = runCatching { Instant.parse(timeText).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
                val elevation = Regex("""<ele>\s*([^<]+)\s*</ele>""", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -500.0..9000.0 }
                val hr = Regex("""<(?:[A-Za-z0-9_.-]+:)?(?:hr|heartrate|heart_rate)\b[^>]*>\s*([^<]+)\s*</(?:[A-Za-z0-9_.-]+:)?(?:hr|heartrate|heart_rate)\s*>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.takeIf { it in 25..240 }
                if (lat in -90.0..90.0 && lon in -180.0..180.0)
                    PhoneTrackPoint(time, lat, lon, elevation, hr) else null
            }.sortedBy { it.timeMs }.toList()
        }.getOrDefault(emptyList())
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

    fun saveSettings(
        context: Context, weightKg: Double, ageYears: Int, heightCm: Int, biologicalSex: String,
        mapZoom: Int, gpsAccuracy: Float, routeColorHex: String
    ) {
        val safeRouteColor = routeColorHex.takeIf { it in routeColorPalette } ?: ROUTE_COLOR_ORANGE
        val safeSex = biologicalSex.takeIf { it in setOf("FEMALE", "MALE", "UNSPECIFIED") } ?: "UNSPECIFIED"
        prefs(context).edit().putFloat("weight_kg", weightKg.toFloat()).putInt("age_years", ageYears)
            .putInt("height_cm", heightCm.coerceIn(80, 230)).putString("biological_sex", safeSex)
            .putInt("map_zoom", mapZoom.coerceIn(14, 17)).putFloat("gps_accuracy", gpsAccuracy.coerceIn(10f, 65f))
            .putString("route_color_hex", safeRouteColor).apply()
    }
    fun routeColorHex(context: Context): String =
        prefs(context).getString("route_color_hex", ROUTE_COLOR_ORANGE)
            ?.takeIf { it in routeColorPalette } ?: ROUTE_COLOR_ORANGE

    fun weightKg(context: Context) = prefs(context).getFloat("weight_kg", 70f).toDouble()
    fun ageYears(context: Context) = prefs(context).getInt("age_years", 30)
    fun heightCm(context: Context) = prefs(context).getInt("height_cm", 170).coerceIn(80, 230)
    fun biologicalSex(context: Context) = prefs(context).getString("biological_sex", "UNSPECIFIED") ?: "UNSPECIFIED"
    fun mapZoom(context: Context) = prefs(context).getInt("map_zoom", 16).coerceIn(14, 17)
    fun gpsAccuracy(context: Context) = prefs(context).getFloat("gps_accuracy", 35f).coerceIn(10f, 65f)
}
