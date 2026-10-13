package com.openkayak.phone.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.openkayak.phone.PhoneBuoy
import com.openkayak.phone.PhoneDataStore
import com.openkayak.phone.PhoneMetricSample
import com.openkayak.phone.PhoneWorkout
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PhoneSyncService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val items = ArrayList<Pair<String, DataMap>>()
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path ?: return@forEach
                items += path to DataMapItem.fromDataItem(event.dataItem).dataMap
            }
        }
        items.forEach { (path, data) -> io.execute { accept(path, data) } }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) = Unit

    private fun accept(path: String, data: DataMap) {
        when {
            path.startsWith("/openkayak/workouts/") -> {
                val startAt = data.getLong("startAt", 0L)
                if (startAt <= 0L || PhoneDataStore.isWorkoutDeleted(this, startAt)) return
                val previous = PhoneDataStore.allWorkouts(this).firstOrNull { it.startAt == startAt }
                val incomingGpx = data.getAsset("gpx")?.let { asset ->
                    copyAsset(asset, "piraguismo-$startAt.gpx")?.absolutePath
                }.orEmpty()
                val gpxPath = incomingGpx.takeIf { it.isNotBlank() } ?: previous?.gpxPath.orEmpty()
                val rawHeartRateSamples = data.getString("heartRateSamples")
                    ?: data.getString("hrSamples").orEmpty()
                val incomingSamples = rawHeartRateSamples.split(';').mapNotNull { row ->
                    val fields = row.split(',')
                    if (fields.size != 2) return@mapNotNull null
                    val timeMs = fields[0].toLongOrNull() ?: return@mapNotNull null
                    val bpm = fields[1].toDoubleOrNull() ?: return@mapNotNull null
                    if (timeMs <= 0L || bpm !in 25.0..240.0) null else PhoneMetricSample(timeMs, bpm)
                }
                PhoneDataStore.recordWorkout(this, PhoneWorkout(
                    startAt, data.getLong("endAt", previous?.endAt ?: startAt),
                    data.getLong("elapsedMs", previous?.elapsedMs ?: 0L),
                    data.getDouble("distanceM", previous?.distanceM ?: 0.0),
                    data.getInt("strokes", previous?.strokes ?: 0),
                    data.getInt("averageBpm", previous?.averageBpm ?: 0),
                    data.getInt("maxBpm", previous?.maxBpm ?: 0),
                    data.getInt("minBpm", previous?.minBpm ?: 0),
                    data.getInt("calories", previous?.calories ?: 0), gpxPath,
                    incomingSamples.ifEmpty { previous?.heartRateSamples.orEmpty() }
                ))
            }
            path == "/openkayak/buoys/current" -> {
                val buoys = data.getString("items").orEmpty().split(';').mapNotNull { row ->
                    val fields = row.split('|')
                    if (fields.size < 7) return@mapNotNull null
                    val id = fields[0].toLongOrNull() ?: return@mapNotNull null
                    val lat = fields[1].toDoubleOrNull() ?: return@mapNotNull null
                    val lon = fields[2].toDoubleOrNull() ?: return@mapNotNull null
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                    PhoneBuoy(id, lat, lon, if (fields[3] == "1") fields[4] else "ROUTE")
                }
                PhoneDataStore.replaceBuoys(this, buoys)
            }
            path == "/openkayak/circuit/current" -> {
                if (data.getBoolean("deleted", false)) {
                    PhoneDataStore.clearCircuit(this)
                    return
                }
                val asset = data.getAsset("gpx") ?: return
                val file = copyAsset(asset, "circuito-reloj-" + System.currentTimeMillis() + ".gpx") ?: return
                PhoneDataStore.saveCircuit(this, file, data.getString("label") ?: "Circuito del reloj")
            }
        }
    }

    private fun copyAsset(asset: Asset, filename: String): File? = runCatching {
        val response = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset))
        val folder = File(filesDir, "received").apply { mkdirs() }
        val output = File(folder, filename)
        response.inputStream.use { input -> input.copyTo(output.outputStream()) }
        output.takeIf { it.isFile && it.length() > 0L }
    }.onFailure { Log.e("OpenKayakSync", "No se pudo copiar un asset recibido", it) }.getOrNull()

    companion object {
        private val io: ExecutorService = Executors.newSingleThreadExecutor()

        fun requestSync(context: Context) {
            val app = context.applicationContext
            io.execute {
                runCatching {
                    // Send persisted settings before asking for history, repairing stale watch colors on every manual sync.
                    publishSettingsNow(
                        app, PhoneDataStore.weightKg(app), PhoneDataStore.ageYears(app),
                        PhoneDataStore.mapZoom(app), PhoneDataStore.gpsAccuracy(app), PhoneDataStore.routeColorHex(app)
                    )
                    val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes)
                    nodes.forEach { node ->
                        Tasks.await(Wearable.getMessageClient(app).sendMessage(
                            node.id, "/openkayak/request-sync", byteArrayOf()
                        ))
                    }
                    Log.d("OpenKayakSync", "Solicitud de historial enviada a ${nodes.size} dispositivo(s)")
                }.onFailure { Log.e("OpenKayakSync", "No se pudo solicitar la sincronización", it) }
            }
        }

        fun publishBuoys(context: Context) {
            val app = context.applicationContext
            io.execute {
                runCatching {
                    val encoded = PhoneDataStore.allBuoys(app).joinToString(";") { buoy ->
                        listOf(buoy.id, buoy.latitude, buoy.longitude,
                            if (buoy.type == "ROUTE") 0 else 1, buoy.type, 0, 1).joinToString("|")
                    }
                    val request = PutDataMapRequest.create("/openkayak/import/buoys")
                    request.dataMap.putString("items", encoded)
                    Tasks.await(Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()))
                }.onFailure { Log.e("OpenKayakSync", "No se pudieron enviar las boyas al reloj", it) }
            }
        }

        fun publishSettings(
            context: Context, weightKg: Double, ageYears: Int,
            mapZoom: Int, gpsAccuracy: Float, routeColorHex: String
        ) {
            val app = context.applicationContext
            io.execute { publishSettingsNow(app, weightKg, ageYears, mapZoom, gpsAccuracy, routeColorHex) }
        }

        private fun publishSettingsNow(
            context: Context, weightKg: Double, ageYears: Int,
            mapZoom: Int, gpsAccuracy: Float, routeColorHex: String
        ) {
            val request = PutDataMapRequest.create("/openkayak/settings")
            request.dataMap.putDouble("weightKg", weightKg)
            request.dataMap.putInt("ageYears", ageYears)
            request.dataMap.putInt("mapZoom", mapZoom)
            request.dataMap.putFloat("gpsAccuracyLimitM", gpsAccuracy)
            request.dataMap.putString("routeColorHex", PhoneDataStore.saveRouteColor(context, routeColorHex))
            request.dataMap.putLong("updatedAt", System.currentTimeMillis())
            runCatching {
                Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()))
            }.onFailure { Log.e("OpenKayakSync", "No se pudieron enviar los ajustes al reloj", it) }
        }

        fun importCircuit(context: Context, file: File, label: String) {
            val app = context.applicationContext
            io.execute {
                runCatching {
                    require(file.isFile && file.length() > 0L) { "El GPX está vacío o no existe" }
                    val request = PutDataMapRequest.create("/openkayak/import/circuit/" + System.currentTimeMillis())
                    request.dataMap.putString("label", label)
                    request.dataMap.putAsset("gpx", Asset.createFromBytes(file.readBytes()))
                    request.dataMap.putLong("updatedAt", System.currentTimeMillis())
                    Tasks.await(Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()))
                }.onFailure { Log.e("OpenKayakSync", "No se pudo enviar el circuito al reloj", it) }
            }
        }

        fun deleteCircuitFromWatch(context: Context) {
            val app = context.applicationContext
            io.execute {
                runCatching {
                    val request = PutDataMapRequest.create("/openkayak/import/circuit/delete")
                    request.dataMap.putBoolean("delete", true)
                    request.dataMap.putLong("updatedAt", System.currentTimeMillis())
                    Tasks.await(Wearable.getDataClient(app).putDataItem(request.asPutDataRequest().setUrgent()))
                }.onFailure { Log.e("OpenKayakSync", "No se pudo borrar el circuito del reloj", it) }
            }
        }
    }
}
