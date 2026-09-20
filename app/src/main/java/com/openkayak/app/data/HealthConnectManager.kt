package com.openkayak.app.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) {

    val healthConnectClient by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else null
    }

    val permissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(permissions)
    }

    suspend fun writeKayakWorkout(
        startTimeMillis: Long,
        endTimeMillis: Long,
        distanceMeters: Float
    ) {
        val client = healthConnectClient ?: return

        try {
            val startInstant = Instant.ofEpochMilli(startTimeMillis)
            val endInstant = Instant.ofEpochMilli(endTimeMillis)

            // Kayaking Paddling Exercise Record
            val exerciseRecord = ExerciseSessionRecord(
                startTime = startInstant,
                startZoneOffset = ZoneOffset.UTC,
                endTime = endInstant,
                endZoneOffset = ZoneOffset.UTC,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_PADDLING,
                title = "Kayak OpenKayak"
            )

            val distanceRecord = DistanceRecord(
                startTime = startInstant,
                startZoneOffset = ZoneOffset.UTC,
                endTime = endInstant,
                endZoneOffset = ZoneOffset.UTC,
                distance = Length.meters(distanceMeters.toDouble())
            )

            client.insertRecords(listOf(exerciseRecord, distanceRecord))
            Log.d(TAG, "Successfully written Kayak session to Health Connect!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write Health Connect records: ${e.localizedMessage}")
        }
    }

    companion object {
        private const val TAG = "HealthConnectManager"
    }
}
