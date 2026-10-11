package com.openkayak.app.tracking

import android.content.Context

data class SavedBuoy(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val isTurn: Boolean,
    val turnType: String = "GIRO",
    val angleDegrees: Double = 0.0,
    val repetitions: Int = 1
)

object BuoyStore {
    private const val PREFS = "openkayak_buoys"
    private const val KEY_ITEMS = "saved_buoys"

    fun all(context: Context): List<SavedBuoy> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "").orEmpty()
            .split(';')
            .filter { it.isNotBlank() }
            .mapNotNull(::decode)

    fun saveCurrent(context: Context, isTurn: Boolean): Boolean {
        val snapshot = TrackStore.snapshot(context)
        val lat = snapshot.latitude ?: return false
        val lon = snapshot.longitude ?: return false
        val items = all(context).toMutableList()
        items.add(SavedBuoy(System.currentTimeMillis(), lat, lon, isTurn))
        save(context, items)
        return true
    }

    fun saveAutomaticTurn(context: Context, latitude: Double, longitude: Double, turnType: String = "GIRO", angleDegrees: Double = 0.0, repetitions: Int = 1): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        val items = all(context).toMutableList()
        val idx = items.indexOfLast { item ->
            if (!item.isTurn) false else {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(latitude, longitude, item.latitude, item.longitude, results)
                results[0] < 10f
            }
        }
        if (idx >= 0) items[idx] = items[idx].copy(repetitions = maxOf(items[idx].repetitions, repetitions))
        else items.add(SavedBuoy(System.currentTimeMillis(), latitude, longitude, true, turnType, angleDegrees, repetitions))
        save(context, items)
        return true
    }

    fun saveDetectedTurns(context: Context, turns: List<SavedBuoy>) {
        val items = all(context).toMutableList()
        turns.forEach { turn ->
            val idx = items.indexOfLast { existing ->
                if (!existing.isTurn) false else {
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(turn.latitude, turn.longitude, existing.latitude, existing.longitude, results)
                    results[0] < 10f
                }
            }
            if (idx >= 0) items[idx] = items[idx].copy(repetitions = maxOf(items[idx].repetitions, turn.repetitions))
            else items.add(turn.copy(id = System.currentTimeMillis() + items.size))
        }
        save(context, items)
    }

    fun remove(context: Context, id: Long) {
        save(context, all(context).filterNot { it.id == id })
    }

    fun removeLast(context: Context): Boolean {
        val items = all(context).toMutableList()
        if (items.isEmpty()) return false
        items.removeAt(items.lastIndex)
        save(context, items)
        return true
    }

    private fun save(context: Context, items: List<SavedBuoy>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, items.joinToString(";") {
                "${it.id},${it.latitude},${it.longitude},${if (it.isTurn) 1 else 0},${it.turnType},${it.angleDegrees},${it.repetitions}"
            })
            .apply()
    }

    private fun decode(value: String): SavedBuoy? {
        val parts = value.split(',')
        if (parts.size < 4) return null
        val id = parts[0].toLongOrNull() ?: return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        val lon = parts[2].toDoubleOrNull() ?: return null
        val turn = parts[3] == "1"
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return SavedBuoy(id, lat, lon, turn, parts.getOrNull(4)?.takeIf { it.isNotBlank() } ?: "GIRO", parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0, parts.getOrNull(6)?.toIntOrNull()?.coerceAtLeast(1) ?: 1)
    }
}
