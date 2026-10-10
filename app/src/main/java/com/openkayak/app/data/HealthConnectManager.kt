package com.openkayak.app.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) {

    val healthConnectClient by lazy {
        try {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Health Connect Client initialization error: ${e.localizedMessage}")
            null
        }
    }

    val permissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        return try {
            val client = healthConnectClient ?: return false
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(permissions)
        } catch (e: Exception) {
            Log.e(TAG, "hasAllPermissions exception: ${e.localizedMessage}")
            false
        }
    }

    suspend fun writeKayakWorkout(
        startTimeMillis: Long,
        endTimeMillis: Long,
        distanceMeters: Float,
        clientRecordId: String = "openkayak-session-" + endTimeMillis
    ): Boolean {
        val client = healthConnectClient ?: return false
        if (endTimeMillis <= startTimeMillis || !distanceMeters.isFinite() || distanceMeters < 0f) return false

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
                title = "Piragüismo en OpenKayak-WearOS",
                metadata = Metadata.activelyRecorded(
                    clientRecordId = clientRecordId,
                    device = Device(type = Device.TYPE_WATCH)
                )
            )

            val distanceRecord = DistanceRecord(
                startTime = startInstant,
                startZoneOffset = ZoneOffset.UTC,
                endTime = endInstant,
                endZoneOffset = ZoneOffset.UTC,
                distance = Length.meters(distanceMeters.toDouble()),
                metadata = Metadata.activelyRecorded(
                    clientRecordId = clientRecordId + "-distance",
                    device = Device(type = Device.TYPE_WATCH)
                )
            )

            client.insertRecords(listOf(exerciseRecord, distanceRecord))
            Log.d(TAG, "Successfully written Kayak session to Health Connect")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write Health Connect records: ${e.localizedMessage}", e)
            return false
        }
    }

    companion object {
        private const val TAG = "HealthConnectManager"
    }
}
