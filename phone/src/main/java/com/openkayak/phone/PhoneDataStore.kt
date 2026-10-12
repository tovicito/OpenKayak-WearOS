package com.openkayak.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LatLon(val latitude: Double, val longitude: Double)
data class PhoneWorkout(
    val startAt: Long, val endAt: Long, val elapsedMs: Long, val distanceM: Double,
    val strokes: Int, val averageBpm: Int, val maxBpm: Int, val minBpm: Int,
    val calories: Int, val gpxPath: String
)
data class PhoneBuoy(val id: Long, val latitude: Double, val longitude: Double, val type: String)

object PhoneDataStore {
    private const val PREFS = "openkayak_phone"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun allWorkouts(context: Context): List<PhoneWorkout> = runCatching {
        val data = JSONArray(prefs(context).getString("workouts", "[]"))
        (0 until data.length()).mapNotNull { index ->
            val o = data.optJSONObject(index) ?: return@mapNotNull null
            PhoneWorkout(o.optLong("startAt"), o.optLong("endAt"), o.optLong("elapsedMs"),
                o.optDouble("distanceM"), o.optInt("strokes"), o.optInt("averageBpm"), o.optInt("maxBpm"),
                o.optInt("minBpm"), o.optInt("calories"), o.optString("gpxPath"))
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

    fun saveSettings(context: Context, weightKg: Double, ageYears: Int, mapZoom: Int, gpsAccuracy: Float) {
        prefs(context).edit().putFloat("weight_kg", weightKg.toFloat()).putInt("age_years", ageYears)
            .putInt("map_zoom", mapZoom.coerceIn(14, 17)).putFloat("gps_accuracy", gpsAccuracy.coerceIn(10f, 65f)).apply()
    }
    fun weightKg(context: Context) = prefs(context).getFloat("weight_kg", 70f).toDouble()
    fun ageYears(context: Context) = prefs(context).getInt("age_years", 30)
    fun mapZoom(context: Context) = prefs(context).getInt("map_zoom", 16).coerceIn(14, 17)
    fun gpsAccuracy(context: Context) = prefs(context).getFloat("gps_accuracy", 35f).coerceIn(10f, 65f)
}
