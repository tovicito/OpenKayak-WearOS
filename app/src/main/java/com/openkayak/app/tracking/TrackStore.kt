package com.openkayak.app.tracking

import android.content.Context
import android.location.Location
import java.io.File
import java.time.Instant
import java.util.Locale

data class GeoSample(val latitude: Double, val longitude: Double)
data class PulseSample(val timeMs: Long, val bpm: Long)

data class TrackSnapshot(
    val active: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0L,
    val distanceM: Double = 0.0,
    val speedKmh: Double = 0.0,
    val strokes: Int = 0,
    val cadenceSpm: Int = 0,
    val pulse: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Float = 0f,
    val gpsStatus: String = "GPS pendiente",
    val trackFilePath: String? = null,
    val startAt: Long = 0L,
    val endAt: Long = 0L
)

object TrackStore {
    private const val PREFS = "openkayak_session"
    private val lock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(context: Context): TrackSnapshot {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val strokeTimes = longList(p.getString("stroke_times", ""))
        return TrackSnapshot(
            active = p.getBoolean("active", false),
            paused = p.getBoolean("paused", false),
            elapsedMs = p.getLong("elapsed_ms", 0L),
            distanceM = p.getFloat("distance_m", 0f).toDouble(),
            speedKmh = p.getFloat("speed_mps", 0f).toDouble() * 3.6,
            strokes = p.getInt("strokes", 0),
            cadenceSpm = strokeTimes.count { now - it in 0..60_000L },
            pulse = p.getInt("pulse", 0),
            latitude = p.getString("lat", null)?.toDoubleOrNull(),
            longitude = p.getString("lon", null)?.toDoubleOrNull(),
            accuracyM = p.getFloat("accuracy_m", 0f),
            gpsStatus = p.getString("gps_status", "GPS pendiente") ?: "GPS pendiente",
            trackFilePath = p.getString("track_file", null),
            startAt = p.getLong("start_at", 0L),
            endAt = p.getLong("end_at", 0L)
        )
    }

    fun start(context: Context) = synchronized(lock) {
        val p = prefs(context)
        val start = System.currentTimeMillis()
        val file = File(context.filesDir, "piraguismo-$start.gpx")
        file.writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<gpx version=\"1.1\" creator=\"OpenKayak-WearOS\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>" +
                "Piragüismo en OpenKayak-WearOS</name><trkseg>\n"
        )
        p.edit().clear()
            .putBoolean("active", true)
            .putBoolean("paused", false)
            .putLong("elapsed_ms", 0L)
            .putFloat("distance_m", 0f)
            .putFloat("speed_mps", 0f)
            .putInt("strokes", 0)
            .putInt("pulse", 0)
            .putString("gps_status", "Buscando satélites")
            .putLong("start_at", start)
            .putLong("end_at", 0L)
            .putString("track_file", file.absolutePath)
            .putString("recent_points", "")
            .putString("stroke_times", "")
            .putString("hr_samples", "")
            .putLong("last_gps_time", 0L)
            .putBoolean("gpx_closed", false)
            .apply()
    }

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean("paused", paused).apply()
    }

    fun setElapsed(context: Context, elapsedMs: Long) {
        prefs(context).edit().putLong("elapsed_ms", elapsedMs.coerceAtLeast(0L)).apply()
    }

    fun setGpsStatus(context: Context, status: String) {
        prefs(context).edit().putString("gps_status", status).apply()
    }

    fun recordLocation(context: Context, location: Location): Boolean = synchronized(lock) {
        val p = prefs(context)
        if (!p.getBoolean("active", false) || p.getBoolean("paused", false)) return false
        if (location.hasAccuracy() && location.accuracy > 65f) {
            p.edit().putString("gps_status", "Precisión baja").apply()
            return false
        }

        val now = if (location.time > 0L) location.time else System.currentTimeMillis()
        val oldLat = p.getString("lat", null)?.toDoubleOrNull()
        val oldLon = p.getString("lon", null)?.toDoubleOrNull()
        val oldTime = p.getLong("last_gps_time", 0L)
        var segmentM = 0.0
        var speedMps = 0.0

        if (oldLat != null && oldLon != null && oldTime > 0L) {
            val dt = (now - oldTime) / 1000.0
            if (dt <= 0.0) {
                p.edit().putString("gps_status", "Muestra GPS antigua descartada").apply()
                return false
            }
            val results = FloatArray(1)
            Location.distanceBetween(oldLat, oldLon, location.latitude, location.longitude, results)
            val deltaM = results[0].toDouble()
            val instantaneous = deltaM / dt
            if (instantaneous > 14.0 || (dt < 0.4 && deltaM > 4.0)) {
                p.edit().putString("gps_status", "Salto GPS descartado").apply()
                return false
            }
            if (deltaM >= 0.45) segmentM = deltaM
            speedMps = if (location.hasSpeed() && location.speed in 0f..14f) {
                location.speed.toDouble()
            } else instantaneous
            val oldSpeed = p.getFloat("speed_mps", 0f).toDouble()
            if (oldSpeed > 0.0) speedMps = oldSpeed * 0.55 + speedMps * 0.45
        }

        val total = p.getFloat("distance_m", 0f).toDouble() + segmentM
        val accuracy = if (location.hasAccuracy()) location.accuracy else 0f
        p.edit()
            .putString("lat", location.latitude.toString())
            .putString("lon", location.longitude.toString())
            .putLong("last_gps_time", now)
            .putFloat("distance_m", total.toFloat())
            .putFloat("speed_mps", speedMps.coerceIn(0.0, 14.0).toFloat())
            .putFloat("accuracy_m", accuracy)
            .putString("gps_status", if (accuracy in 0f..25f) "GPS preciso" else "GPS activo")
            .apply()

        val recent = stringList(p.getString("recent_points", "")).toMutableList()
        recent.add("${location.latitude},${location.longitude}")
        while (recent.size > 100) recent.removeAt(0)
        p.edit().putString("recent_points", recent.joinToString(";")).apply()

        val path = p.getString("track_file", null)
        if (path != null) {
            runCatching {
                val line = String.format(
                    Locale.US,
                    "<trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time></trkpt>\n",
                    location.latitude, location.longitude, Instant.ofEpochMilli(now).toString()
                )
                File(path).appendText(line)
            }.onFailure { p.edit().putString("gps_status", "Error guardando ruta").apply() }
        }
        true
    }

    fun recordStroke(context: Context, timeMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val p = prefs(context)
        if (!p.getBoolean("active", false) || p.getBoolean("paused", false)) return
        val times = longList(p.getString("stroke_times", "")).filter { timeMs - it in 0..60_000L }.toMutableList()
        times.add(timeMs)
        p.edit()
            .putInt("strokes", p.getInt("strokes", 0) + 1)
            .putString("stroke_times", times.joinToString(";"))
            .apply()
    }

    fun recordHeartRate(context: Context, bpm: Int, timeMs: Long = System.currentTimeMillis()) {
        if (bpm !in 25..240) return
        synchronized(lock) {
            val p = prefs(context)
            p.edit().putInt("pulse", bpm).apply()
            val samples = pulseList(p.getString("hr_samples", "")).toMutableList()
            val last = samples.lastOrNull()?.first ?: 0L
            if (timeMs - last >= 5_000L) samples.add(timeMs to bpm.toLong())
            while (samples.size > 600) samples.removeAt(0)
            p.edit().putString("hr_samples", samples.joinToString(";") { "${it.first},${it.second}" }).apply()
        }
    }

    fun heartRateSamples(context: Context): List<PulseSample> =
        pulseList(prefs(context).getString("hr_samples", "")).map { PulseSample(it.first, it.second) }

    fun recentTrack(context: Context): List<GeoSample> =
        stringList(prefs(context).getString("recent_points", "")).mapNotNull { item ->
            val p = item.split(',')
            if (p.size != 2) null else {
                val lat = p[0].toDoubleOrNull()
                val lon = p[1].toDoubleOrNull()
                if (lat == null || lon == null) null else GeoSample(lat, lon)
            }
        }

    fun finish(context: Context, elapsedMs: Long, endAt: Long = System.currentTimeMillis()) = synchronized(lock) {
        val p = prefs(context)
        SessionHistoryStore.record(
            context = context,
            startAt = p.getLong("start_at", 0L),
            endAt = endAt,
            elapsedMs = elapsedMs,
            distanceM = p.getFloat("distance_m", 0f).toDouble(),
            strokes = p.getInt("strokes", 0),
            trackPath = p.getString("track_file", null)
        )
        p.edit().putBoolean("active", false).putBoolean("paused", false)
            .putLong("elapsed_ms", elapsedMs.coerceAtLeast(0L)).putLong("end_at", endAt).apply()
        val path = p.getString("track_file", null)
        if (path != null && !p.getBoolean("gpx_closed", false)) {
            runCatching { File(path).appendText("</trkseg></trk></gpx>\n") }
            p.edit().putBoolean("gpx_closed", true).apply()
        }
    }

    fun currentTrackFile(context: Context): File? =
        prefs(context).getString("track_file", null)?.let(::File)?.takeIf { it.exists() }

    private fun stringList(text: String?): List<String> =
        text.orEmpty().split(';').filter { it.isNotBlank() }

    private fun longList(text: String?): List<Long> =
        stringList(text).mapNotNull { it.toLongOrNull() }

    private fun pulseList(text: String?): List<Pair<Long, Long>> =
        stringList(text).mapNotNull { item ->
            val p = item.split(',')
            if (p.size != 2) null else {
                val t = p[0].toLongOrNull()
                val bpm = p[1].toLongOrNull()
                if (t == null || bpm == null) null else t to bpm
            }
        }
}
