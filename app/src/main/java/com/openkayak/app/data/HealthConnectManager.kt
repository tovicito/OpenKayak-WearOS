package com.openkayak.app.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class HealthConnectManager(private val context: Context) {

    suspend fun syncWorkout(workout: WorkoutEntity): Boolean {
        return runCatching {
            val id = "workout-" + workout.id + "-" + workout.timestamp
            val payload = JSONObject().apply {
                put("id", id)
                put("startTime", workout.timestamp - workout.durationSeconds * 1000L)
                put("endTime", workout.timestamp)
                put("distanceMeters", workout.distanceMeters.toDouble())
                put("avgSpeedKmh", workout.avgSpeedKmh.toDouble())
                put("maxSpeedKmh", workout.maxSpeedKmh.toDouble())
                put("totalStrokes", workout.totalStrokes)
                put("avgStrokeRateSpm", workout.avgStrokeRateSpm)
                put("calories", workout.estimatedCalories)
                put("routeJson", workout.routeGpsJson)
                put("heartRateJson", workout.heartRateJson)
            }.toString()

            // Health Connect lives on Android phones, not on Wear OS. The Data Layer
            // persists this item and delivers it when the paired phone is available.
            val request = PutDataMapRequest.create("/openkayak/hc/workout/$id").apply {
                dataMap.putString("payload", payload)
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(context).putDataItem(request).await()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(id, payload).apply()
            true
        }.onFailure {
            Log.e(TAG, "Health Connect handoff failed", it)
        }.getOrDefault(false)
    }

    suspend fun retryWorkout(id: String): Boolean {
        val payload = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(id, null) ?: return false
        return runCatching {
            val request = PutDataMapRequest.create("/openkayak/hc/workout/$id").apply {
                dataMap.putString("payload", payload)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
            true
        }.getOrDefault(false)
    }

    fun forgetWorkout(id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
    }

    companion object {
        private const val TAG = "OpenKayakHealthConnect"
        private const val PREFS = "health_connect_pending"
    }
}
