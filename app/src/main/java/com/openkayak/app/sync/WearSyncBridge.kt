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
import com.openkayak.app.tracking.TrackStore
import java.io.File
import java.util.concurrent.Executors

object WearSyncBridge {
    private const val TAG = "OpenKayakSync"
    private val io = Executors.newSingleThreadExecutor()

    fun publishWorkout(context: Context, startAt: Long) {
        val app = context.applicationContext
        io.execute {
            val session = SessionHistoryStore.all(app).firstOrNull { it.startAt == startAt } ?: return@execute
            publishWorkoutInternal(
                app, session.startAt, session.endAt, session.elapsedMs, session.distanceM, session.strokes,
                session.trackPath, session.averageBpm, session.maxBpm, session.minBpm, session.calories
            )
        }
    }

    fun publishAll(context: Context) {
        val app = context.applicationContext
        io.execute {
            SessionHistoryStore.all(app).forEach { session ->
                publishWorkoutInternal(
                    app, session.startAt, session.endAt, session.elapsedMs, session.distanceM, session.strokes,
                    session.trackPath, session.averageBpm, session.maxBpm, session.minBpm, session.calories
                )
            }
            publishBuoysNow(app)
            publishCircuitNow(app)
        }
    }

    fun publishBuoys(context: Context) {
        val app = context.applicationContext
        io.execute { publishBuoysNow(app) }
    }

    fun publishCircuit(context: Context) {
        val app = context.applicationContext
        io.execute { publishCircuitNow(app) }
    }

    /** Send a tombstone only for an explicit user deletion, not when the watch starts empty. */
    fun publishCircuitDeleted(context: Context) {
        val app = context.applicationContext
        io.execute {
            val request = PutDataMapRequest.create("/openkayak/circuit/current")
            request.dataMap.putBoolean("deleted", true)
            request.dataMap.putLong("updatedAt", System.currentTimeMillis())
            putDataItem(app, request, "circuit deletion")
        }
    }

    private fun publishWorkoutInternal(
        context: Context, startAt: Long, endAt: Long, elapsedMs: Long, distanceM: Double,
        strokes: Int, trackPath: String, averageBpm: Int, maxBpm: Int, minBpm: Int, calories: Int
    ) {
        try {
            val request = PutDataMapRequest.create("/openkayak/workouts/$startAt")
            val map = request.dataMap
            map.putLong("startAt", startAt)
            map.putLong("endAt", endAt)
            map.putLong("elapsedMs", elapsedMs)
            map.putDouble("distanceM", distanceM)
            map.putInt("strokes", strokes)
            map.putInt("averageBpm", averageBpm)
            map.putInt("maxBpm", maxBpm)
            map.putInt("minBpm", minBpm)
            map.putInt("calories", calories)

            // Keep compact 10-second samples for the phone charts without inflating the Data Layer payload.
            val hrSamples = TrackStore.heartRateSamples(context)
                .filter { it.timeMs in startAt..endAt && it.bpm in 25L..240L }
                .sortedBy { it.timeMs }
                .groupBy { (it.timeMs - startAt).coerceAtLeast(0L) / 10_000L }
                .values
                .mapNotNull { samples -> samples.lastOrNull() }
                .joinToString(";") { "${it.timeMs},${it.bpm}" }
            if (hrSamples.isNotEmpty()) map.putString("heartRateSamples", hrSamples)

            val file = File(trackPath)
            if (file.isFile && file.length() > 0L) {
                runCatching { map.putAsset("gpx", Asset.createFromBytes(file.readBytes())) }
                    .onFailure { Log.w(TAG, "No se pudo adjuntar el GPX del entreno $startAt", it) }
            }
            putDataItem(context, request, "workout $startAt")
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo preparar el entreno $startAt", error)
        }
    }

    private fun publishBuoysNow(context: Context) {
        val encoded = BuoyStore.all(context).joinToString(";") {
            listOf(it.id, it.latitude, it.longitude, if (it.isTurn) 1 else 0, it.turnType, it.angleDegrees, it.repetitions)
                .joinToString("|")
        }
        val request = PutDataMapRequest.create("/openkayak/buoys/current")
        request.dataMap.putString("items", encoded)
        putDataItem(context, request, "buoys")
    }

    private fun publishCircuitNow(context: Context) {
        val file = CircuitStore.file(context) ?: return
        if (!file.isFile || file.length() <= 0L) return
        val request = PutDataMapRequest.create("/openkayak/circuit/current")
        request.dataMap.putBoolean("deleted", false)
        request.dataMap.putLong("updatedAt", System.currentTimeMillis())
        request.dataMap.putString(
            "label",
            context.getSharedPreferences("openkayak_saved_circuit", Context.MODE_PRIVATE)
                .getString("label", "Circuito guardado") ?: "Circuito guardado"
        )
        try {
            request.dataMap.putAsset("gpx", Asset.createFromBytes(file.readBytes()))
            putDataItem(context, request, "circuit")
        } catch (error: Exception) {
            Log.e(TAG, "No se pudo preparar la sincronización del circuito", error)
        }
    }

    private fun putDataItem(context: Context, request: PutDataMapRequest, label: String) {
        runCatching {
            Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()))
            Log.d(TAG, "Data Layer actualizado: $label")
        }.onFailure { Log.e(TAG, "Falló la sincronización de $label", it) }
    }
}
