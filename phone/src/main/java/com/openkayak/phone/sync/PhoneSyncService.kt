package com.openkayak.phone.sync

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
                if (startAt <= 0L) return
                var gpxPath = ""
                data.getAsset("gpx")?.let { asset ->
                    gpxPath = copyAsset(asset, "piraguismo-$startAt.gpx")?.absolutePath.orEmpty()
                }
                PhoneDataStore.recordWorkout(this, PhoneWorkout(
                    startAt, data.getLong("endAt", startAt), data.getLong("elapsedMs", 0L),
                    data.getDouble("distanceM", 0.0), data.getInt("strokes", 0),
                    data.getInt("averageBpm", 0), data.getInt("maxBpm", 0), data.getInt("minBpm", 0),
                    data.getInt("calories", 0), gpxPath
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
        response.inputStream.use { input -> output.outputStream().use { out -> input.copyTo(out) } }
        output.takeIf { it.isFile && it.length() > 0L }
    }.getOrNull()

    companion object {
        private val io: ExecutorService = Executors.newSingleThreadExecutor()
        fun requestSync(context: android.content.Context) {
            val app = context.applicationContext
            io.execute {
                runCatching {
                    val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes)
                    nodes.forEach { node ->
                        Tasks.await(Wearable.getMessageClient(app).sendMessage(node.id, "/openkayak/request-sync", byteArrayOf()))
                    }
                }
            }
        }
        fun publishBuoys(context: android.content.Context) {
            val encoded = PhoneDataStore.allBuoys(context).joinToString(";") { b ->
                listOf(b.id, b.latitude, b.longitude, if (b.type == "ROUTE") 0 else 1, b.type, 0, 1).joinToString("|")
            }
            val request = PutDataMapRequest.create("/openkayak/import/buoys")
            request.dataMap.putString("items", encoded)
            io.execute { runCatching { Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())) } }
        }
        fun publishSettings(
            context: android.content.Context, weightKg: Double, ageYears: Int,
            mapZoom: Int, gpsAccuracy: Float, routeColorHex: String
        ) {
            val request = PutDataMapRequest.create("/openkayak/settings")
            request.dataMap.putDouble("weightKg", weightKg)
            request.dataMap.putInt("ageYears", ageYears)
            request.dataMap.putInt("mapZoom", mapZoom)
            request.dataMap.putFloat("gpsAccuracyLimitM", gpsAccuracy)
            request.dataMap.putString("routeColorHex", routeColorHex)
            io.execute { runCatching { Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())) } }
        }
        fun importCircuit(context: android.content.Context, file: File, label: String) {
            io.execute {
                runCatching {
                    val request = PutDataMapRequest.create("/openkayak/import/circuit/" + System.currentTimeMillis())
                    request.dataMap.putString("label", label)
                    request.dataMap.putAsset("gpx", Asset.createFromBytes(file.readBytes()))
                    Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()))
                }
            }
        }
        fun deleteCircuitFromWatch(context: android.content.Context) {
            val request = PutDataMapRequest.create("/openkayak/import/circuit/delete")
            request.dataMap.putBoolean("delete", true)
            io.execute { runCatching { Tasks.await(Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())) } }
        }
    }
}
