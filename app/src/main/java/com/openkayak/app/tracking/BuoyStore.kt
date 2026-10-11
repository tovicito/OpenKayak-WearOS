package com.openkayak.app.tracking

import android.content.Context

data class SavedBuoy(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val isTurn: Boolean
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

    fun saveAutomaticTurn(context: Context, latitude: Double, longitude: Double): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
        val items = all(context).toMutableList()
        val nearby = items.any { item ->
            if (!item.isTurn) false else {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(latitude, longitude, item.latitude, item.longitude, results)
                results[0] < 28f
            }
        }
        if (nearby) return false
        items.add(SavedBuoy(System.currentTimeMillis(), latitude, longitude, true))
        save(context, items)
        return true
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
                "${it.id},${it.latitude},${it.longitude},${if (it.isTurn) 1 else 0}"
            })
            .apply()
    }

    private fun decode(value: String): SavedBuoy? {
        val parts = value.split(',')
        if (parts.size != 4) return null
        val id = parts[0].toLongOrNull() ?: return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        val lon = parts[2].toDoubleOrNull() ?: return null
        val turn = parts[3] == "1"
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return SavedBuoy(id, lat, lon, turn)
    }
}
