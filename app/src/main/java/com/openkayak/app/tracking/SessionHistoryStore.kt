package com.openkayak.app.tracking

import android.content.Context

data class SessionSummary(
    val startAt: Long,
    val endAt: Long,
    val elapsedMs: Long,
    val distanceM: Double,
    val strokes: Int,
    val trackPath: String
)

object SessionHistoryStore {
    private const val PREFS = "openkayak_history"
    private const val KEY = "sessions"
    private const val LIMIT = 30

    fun all(context: Context): List<SessionSummary> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
            .split(';')
            .filter(String::isNotBlank)
            .mapNotNull(::decode)
            .sortedByDescending { it.endAt }

    fun record(
        context: Context,
        startAt: Long,
        endAt: Long,
        elapsedMs: Long,
        distanceM: Double,
        strokes: Int,
        trackPath: String?
    ) {
        if (startAt <= 0L || endAt <= startAt) return
        val existing = all(context).filterNot { it.startAt == startAt }.toMutableList()
        existing.add(SessionSummary(startAt, endAt, elapsedMs.coerceAtLeast(0L), distanceM.coerceAtLeast(0.0), strokes.coerceAtLeast(0), trackPath.orEmpty()))
        val encoded = existing.sortedByDescending { it.endAt }.take(LIMIT).joinToString(";") {
            listOf(it.startAt, it.endAt, it.elapsedMs, it.distanceM, it.strokes, it.trackPath).joinToString(",")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encoded).apply()
    }

    private fun decode(raw: String): SessionSummary? {
        val p = raw.split(',', limit = 6)
        if (p.size != 6) return null
        val start = p[0].toLongOrNull() ?: return null
        val end = p[1].toLongOrNull() ?: return null
        val elapsed = p[2].toLongOrNull() ?: return null
        val distance = p[3].toDoubleOrNull() ?: return null
        val strokes = p[4].toIntOrNull() ?: return null
        return SessionSummary(start, end, elapsed, distance, strokes, p[5])
    }
}