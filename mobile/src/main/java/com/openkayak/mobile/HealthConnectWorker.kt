package com.openkayak.mobile

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.kilocalories
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.metersPerSecond
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString("workout_id") ?: return Result.failure()
        val file = File(applicationContext.filesDir, "health_connect_pending/$id.json")
        return try {
            val client = HealthConnectClient.getOrCreate(applicationContext)
            val required = setOf(
                HealthPermission.getWritePermission(ExerciseSessionRecord::class),
                HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
                HealthPermission.getWritePermission(HeartRateRecord::class),
                HealthPermission.getWritePermission(DistanceRecord::class),
                HealthPermission.getWritePermission(SpeedRecord::class),
                HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
                HealthPermission.getWritePermission(androidx.health.connect.client.records.ElevationGainedRecord::class)
            )
            if (!client.permissionController.getGrantedPermissions().containsAll(required)) {
                publishStatus(id, "failed", "permissions")
                return Result.failure()
            }

            val json = JSONObject(file.readText())
            val start = Instant.ofEpochMilli(json.getLong("startTime"))
            val end = Instant.ofEpochMilli(json.getLong("endTime"))
            val startOffset = ZoneOffset.systemDefault().rules.getOffset(start)
            val endOffset = ZoneOffset.systemDefault().rules.getOffset(end)
            val baseMetadata = Metadata.autoRecorded(
                Device(type = Device.TYPE_WATCH),
                "openkayak-$id",
                0
            )

            val routeArray = JSONArray(json.optString("routeJson", "[]"))
            val locations = buildList {
                for (i in 0 until routeArray.length()) {
                    val p = routeArray.getJSONObject(i)
                    add(
                        ExerciseRoute.Location(
                        time = Instant.ofEpochMilli(p.getLong("t")),
                        latitude = p.getDouble("lat"),
                        longitude = p.getDouble("lon"),
                        altitude = Length.meters(p.optDouble("alt", 0.0))
                    )
                    )
                }
            }

            val session = ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                metadata = baseMetadata,
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_PADDLING,
                title = "Piragüismo en OpenKayak-WearOS",
                exerciseRoute = locations.takeIf { it.isNotEmpty() }?.let { ExerciseRoute(it) },
                notes = "Modo Kayak. Paladas: " + json.optInt("totalStrokes", 0) +
                    ". Ritmo medio: " + json.optDouble("avgSpeedKmh", 0.0) + " km/h."
            )

            val records = mutableListOf<Record>(
                session,
                DistanceRecord(
                    startTime = start, startZoneOffset = startOffset,
                    endTime = end, endZoneOffset = endOffset,
                    distance = Length.meters(json.getDouble("distanceMeters"))
                ),
                ActiveCaloriesBurnedRecord(
                    startTime = start, startZoneOffset = startOffset,
                    endTime = end, endZoneOffset = endOffset,
                    energy = json.optDouble("calories", 0.0).kilocalories
                )
            )

            val hr = JSONArray(json.optString("heartRateJson", "[]"))
            if (hr.length() > 0) {
                val samples = buildList {
                    for (i in 0 until hr.length()) {
                        val s = hr.getJSONObject(i)
                        add(HeartRateRecord.Sample(
                            time = Instant.ofEpochMilli(s.getLong("t")),
                            beatsPerMinute = s.getLong("bpm")
                        ))
                    }
                }
                records.add(HeartRateRecord(
                    startTime = start, startZoneOffset = startOffset,
                    endTime = end, endZoneOffset = endOffset,
                    metadata = Metadata.autoRecorded(Device(type = Device.TYPE_CHEST_STRAP), "openkayak-$id-hr", 0),
                    samples = samples
                ))
            }

            val speedSamples = buildList {
                for (i in 0 until routeArray.length()) {
                    val p = routeArray.getJSONObject(i)
                    val kmh = p.optDouble("speed", -1.0)
                    if (kmh >= 0.0) {
                        add(SpeedRecord.Sample(
                            time = Instant.ofEpochMilli(p.getLong("t")),
                            speed = (kmh / 3.6).metersPerSecond
                        ))
                    }
                }
            }
            if (speedSamples.isNotEmpty()) {
                records.add(SpeedRecord(
                    startTime = start, startZoneOffset = startOffset,
                    endTime = end, endZoneOffset = endOffset,
                    metadata = Metadata.autoRecorded(Device(type = Device.TYPE_WATCH), "openkayak-$id-speed", 0),
                    samples = speedSamples
                ))
            }

            client.insertRecords(records)
            file.delete()
            publishStatus(id, "success", "")
            Result.success()
        } catch (e: SecurityException) {
            Log.e("OpenKayakHC", "Health Connect permission failure", e)
            publishStatus(id, "failed", "permission")
            Result.failure()
        } catch (e: Exception) {
            Log.e("OpenKayakHC", "Health Connect sync failed", e)
            publishStatus(id, "failed", e.localizedMessage ?: "unknown")
            Result.failure()
        }
    }

    private suspend fun publishStatus(id: String, status: String, detail: String) {
        val request = PutDataMapRequest.create("/openkayak/hc/status/$id").apply {
            dataMap.putString("status", status)
            dataMap.putString("detail", detail)
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(applicationContext).putDataItem(request).await() }
    }
}
