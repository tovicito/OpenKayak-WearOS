package com.openkayak.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import com.openkayak.app.tracking.TrackStore
import java.time.Instant
import java.time.ZoneId

object HealthConnectSync {
    // Ask only for data this app actually writes: workout, distance and strap HR.
    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class)
    )

    fun sdkStatus(context: Context): Int = runCatching {
        HealthConnectClient.getSdkStatus(context, "com.google.android.apps.healthdata")
    }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)

    fun isAvailable(context: Context): Boolean = sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun unavailableMessage(context: Context): String = when (sdkStatus(context)) {
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            "Health Connect necesita instalarse o actualizarse en el reloj"
        HealthConnectClient.SDK_UNAVAILABLE ->
            "Health Connect no está disponible en este reloj"
        else -> "Health Connect no está listo; revisa la app Health Connect del reloj"
    }

    fun client(context: Context): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun grantedPermissions(context: Context): Set<String> {
        if (!isAvailable(context)) return emptySet()
        return client(context).permissionController.getGrantedPermissions()
    }

    suspend fun syncLatestSession(context: Context) {
        val p = context.getSharedPreferences("openkayak_session", Context.MODE_PRIVATE)
        val startMs = p.getLong("start_at", 0L)
        val endMs = p.getLong("end_at", 0L)
        require(startMs > 0L && endMs > startMs) { "No hay una sesión terminada para sincronizar" }
        check(isAvailable(context)) { "Health Connect no está disponible en este reloj" }

        val hc = client(context)
        val granted = hc.permissionController.getGrantedPermissions()
        val startPermission = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        check(startPermission in granted) { "Falta el permiso de ejercicio en Health Connect" }

        val start = Instant.ofEpochMilli(startMs)
        val end = Instant.ofEpochMilli(endMs)
        val rules = ZoneId.systemDefault().rules
        val startOffset = rules.getOffset(start)
        val endOffset = rules.getOffset(end)
        val distanceM = p.getFloat("distance_m", 0f).toDouble()
        val strokes = p.getInt("strokes", 0)
        val session = ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = startOffset,
            endTime = end,
            endZoneOffset = endOffset,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_PADDLING,
            title = "Piragüismo en OpenKayak-WearOS",
            notes = "Distancia: %.1f m. Paladas detectadas: %d. La ruta completa está guardada en GPX.".format(distanceM, strokes),
            metadata = Metadata.manualEntry(clientRecordId = "openkayak-session-$startMs")
        )
        hc.insertRecords(listOf(session))

        if (HealthPermission.getWritePermission(DistanceRecord::class) in granted && distanceM > 0.0) {
            hc.insertRecords(listOf(
                DistanceRecord(
                    startTime = start,
                    startZoneOffset = startOffset,
                    endTime = end,
                    endZoneOffset = endOffset,
                    distance = Length.meters(distanceM),
                    metadata = Metadata.manualEntry(clientRecordId = "openkayak-distance-$startMs")
                )
            ))
        }

        if (HealthPermission.getWritePermission(HeartRateRecord::class) in granted) {
            val samples = TrackStore.heartRateSamples(context)
                .filter { it.timeMs in startMs..endMs && it.bpm in 25L..240L }
                .map { HeartRateRecord.Sample(time = Instant.ofEpochMilli(it.timeMs), beatsPerMinute = it.bpm) }
            if (samples.isNotEmpty()) {
                hc.insertRecords(listOf(
                    HeartRateRecord(
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        samples = samples,
                        metadata = Metadata.manualEntry(clientRecordId = "openkayak-heart-rate-$startMs")
                    )
                ))
            }
        }
    }
}
