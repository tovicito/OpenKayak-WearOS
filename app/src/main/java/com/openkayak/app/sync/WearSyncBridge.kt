package com.openkayak.app.sync

import android.content.Context
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.openkayak.app.tracking.BuoyStore
import com.openkayak.app.tracking.CircuitStore
import com.openkayak.app.tracking.SessionHistoryStore
import java.io.File
import java.util.concurrent.Executors

object WearSyncBridge {
    private val io = Executors.newSingleThreadExecutor()

    fun publishWorkout(context: Context, startAt: Long) {
        val app = context.applicationContext
        io.execute {
            val s = SessionHistoryStore.all(app).firstOrNull { it.startAt == startAt } ?: return@execute
            publishWorkoutInternal(app, s.startAt, s.endAt, s.elapsedMs, s.distanceM, s.strokes, s.trackPath,
                s.averageBpm, s.maxBpm, s.minBpm, s.calories)
        }
    }

    fun publishAll(context: Context) {
        val app = context.applicationContext
        io.execute {
            SessionHistoryStore.all(app).forEach { s ->
                publishWorkoutInternal(app, s.startAt, s.endAt, s.elapsedMs, s.distanceM, s.strokes, s.trackPath,
                    s.averageBpm, s.maxBpm, s.minBpm, s.calories)
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

    private fun publishWorkoutInternal(
        context: Context, startAt: Long, endAt: Long, elapsedMs: Long, distanceM: Double,
        strokes: Int, trackPath: String, averageBpm: Int, maxBpm: Int, minBpm: Int, calories: Int
    ) {
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
        val file = File(trackPath)
        if (file.isFile && file.length() > 0L) runCatching { map.putAsset("gpx", Asset.createFromBytes(file.readBytes())) }
        runCatching { Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()) }
    }

    private fun publishBuoysNow(context: Context) {
        val encoded = BuoyStore.all(context).joinToString(";") {
            listOf(it.id, it.latitude, it.longitude, if (it.isTurn) 1 else 0, it.turnType, it.angleDegrees, it.repetitions).joinToString("|")
        }
        val request = PutDataMapRequest.create("/openkayak/buoys/current")
        request.dataMap.putString("items", encoded)
        runCatching { Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()) }
    }

    private fun publishCircuitNow(context: Context) {
        val file = CircuitStore.file(context) ?: return
        val request = PutDataMapRequest.create("/openkayak/circuit/current")
        request.dataMap.putString("label", context.getSharedPreferences("openkayak_saved_circuit", Context.MODE_PRIVATE)
            .getString("label", "Circuito guardado") ?: "Circuito guardado")
        runCatching { request.dataMap.putAsset("gpx", Asset.createFromBytes(file.readBytes())) }
        runCatching { Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()) }
    }
}
