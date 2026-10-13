package com.openkayak.app.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.openkayak.app.tracking.BuoyStore
import com.openkayak.app.tracking.CircuitStore
import com.openkayak.app.tracking.SessionHistoryStore
import java.io.File
import java.util.concurrent.Executors

object WearSyncBridge {
    private const val TAG = "OpenKayakSync"
    private val io = Executors.newSingleThreadExecutor()
    fun publishWorkout(context: Context, startAt: Long) {
        val app = context.applicationContext
        io.execute {
            val s = SessionHistoryStore.all(app).firstOrNull { it.startAt == startAt } ?: return@execute
            publishWorkoutInternal(app, s.startAt, s.endAt, s.elapsedMs, s.distanceM, s.strokes, s.trackPath, s.averageBpm, s.maxBpm, s.minBpm, s.calories)
        }
    }
    fun publishAll(context: Context) {
        val app = context.applicationContext
        io.execute {
            SessionHistoryStore.all(app).forEach { s ->
                publishWorkoutInternal(app, s.startAt, s.endAt, s.elapsedMs, s.distanceM, s.strokes, s.trackPath, s.averageBpm, s.maxBpm, s.minBpm, s.calories)
            }
            publishBuoysNow(app)
            publishCircuitNow(app)
        }
    }
    fun publishBuoys(context: Context) { val app = context.applicationContext; io.execute { publishBuoysNow(app) } }
    fun publishCircuit(context: Context) { val app = context.applicationContext; io.execute { publishCircuitNow(app) } }
    fun publishCircuitDeleted(context: Context) {
        val app = context.applicationContext
        io.execute {
            val request = PutDataMapRequest.create("/openkayak/circuit/current")
            request.dataMap.putBoolean("deleted", true)
            request.dataMap.putLong("updatedAt", System.currentTimeMillis())
            putDataItem(app, request, "circuit deletion")
        }
    }
    private fun publishWorkoutInternal(context: Context, startAt: Long, endAt: Long, elapsedMs: Long, distanceM: Double,
        strokes: Int, trackPath: String, averageBpm: Int, maxBpm: Int, minBpm: Int, calories: Int) {
        try {
            val request = PutDataMapRequest.create("/openkayak/workouts/$startAt")
            val map = request.dataMap
            map.putLong("startAt", startAt); map.putLong("endAt", endAt); map.putLong("elapsedMs", elapsedMs)
            map.putDouble("distanceM", distanceM); map.putInt("strokes", strokes)
            map.putInt("averageBpm", averageBpm); map.putInt("maxBpm", maxBpm); map.putInt("minBpm", minBpm)
            map.putInt("calories", calories)
            map.putString("hrSamples", s.hrSamples)
            // Force a fresh Data Layer event when the user manually retries unchanged workouts.
            map.putLong("syncRevision", System.currentTimeMillis())
            val file = File(trackPath)
            if (file.isFile && file.length() > 0L) map.putAsset("gpx", Asset.createFromBytes(file.readBytes()))
            putDataItem(context, request, "workout $startAt")
        } catch (error: Exception) { Log.e(TAG, "No se pudo preparar el entreno $startAt", error) }
    }
    private fun publishBuoysNow(context: Context) {
        val encoded = BuoyStore.all(context).joinToString(";") {
            listOf(it.id, it.latitude, it.longitude, if (it.isTurn) 1 else 0, it.turnType, it.angleDegrees, it.repetitions).joinToString("|")
        }
        val request = PutDataMapRequest.create("/openkayak/buoys/current")
        request.dataMap.putString("items", encoded)
        request.dataMap.putLong("syncRevision", System.currentTimeMillis())
        putDataItem(context, request, "buoys")
    }
    private fun publishCircuitNow(context: Context) {
        val file = CircuitStore.file(context) ?: return
        if (!file.isFile || file.length() <= 0L) return
        val request = PutDataMapRequest.create("/openkayak/circuit/current")
        request.dataMap.putBoolean("deleted", false)
        request.dataMap.putLong("updatedAt", System.currentTimeMillis())
        request.dataMap.putString("label", context.getSharedPreferences("openkayak_saved_circuit", Context.MODE_PRIVATE)
            .getString("label", "Circuito guardado") ?: "Circuito guardado")
        try {
            request.dataMap.putAsset("gpx", Asset.createFromBytes(file.readBytes()))
            putDataItem(context, request, "circuit")
        } catch (error: Exception) { Log.e(TAG, "No se pudo preparar el circuito para sincronización", error) }
    }
    private fun putDataItem(context: Context, request: PutDataMapRequest, label: String) {
        runCatching { Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())) }
            .onSuccess { Log.d(TAG, "Data Layer confirmado: $label") }
            .onFailure { Log.e(TAG, "Falló la sincronización de $label", it) }
    }
}
