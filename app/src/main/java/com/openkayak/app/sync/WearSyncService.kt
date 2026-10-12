package com.openkayak.app.sync

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.openkayak.app.tracking.AppSettingsStore
import com.openkayak.app.tracking.AthleteProfileStore
import com.openkayak.app.tracking.BuoyStore
import com.openkayak.app.tracking.CircuitStore
import com.openkayak.app.tracking.SavedBuoy
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WearSyncService : WearableListenerService() {
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == "/openkayak/request-sync") {
            io.execute { WearSyncBridge.publishAll(applicationContext) }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val work = ArrayList<Pair<String, DataMap>>()
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path ?: return@forEach
                work += path to DataMapItem.fromDataItem(event.dataItem).dataMap
            }
        }
        work.forEach { (path, map) -> io.execute { applyIncoming(path, map) } }
    }

    private fun applyIncoming(path: String, data: DataMap) {
        when {
            path == "/openkayak/import/buoys" -> {
                val items = data.getString("items").orEmpty().split(';').mapNotNull { row ->
                    val p = row.split('|')
                    if (p.size < 7) return@mapNotNull null
                    val id = p[0].toLongOrNull() ?: return@mapNotNull null
                    val lat = p[1].toDoubleOrNull() ?: return@mapNotNull null
                    val lon = p[2].toDoubleOrNull() ?: return@mapNotNull null
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                    SavedBuoy(id, lat, lon, p[3] == "1", p[4], p[5].toDoubleOrNull() ?: 0.0, p[6].toIntOrNull() ?: 1)
                }
                BuoyStore.replaceAll(this, items)
                WearSyncBridge.publishBuoys(this)
            }
            path == "/openkayak/settings" -> {
                AppSettingsStore.save(
                    this,
                    data.getDouble("weightKg", AthleteProfileStore.weightKg(this)),
                    data.getInt("ageYears", AthleteProfileStore.ageYears(this)),
                    data.getInt("mapZoom", AppSettingsStore.mapZoom(this)),
                    data.getFloat("gpsAccuracyLimitM", AppSettingsStore.gpsAccuracyLimit(this))
                )
            }
            path.startsWith("/openkayak/import/circuit/") -> {
                val asset = data.getAsset("gpx") ?: return
                val source = readAsset(asset) ?: return
                CircuitStore.importFile(this, source, data.getString("label") ?: "Circuito importado")
                source.delete()
                WearSyncBridge.publishCircuit(this)
            }
        }
    }

    private fun readAsset(asset: Asset): File? = runCatching {
        val response = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset))
        val source = File(cacheDir, "openkayak-import-" + System.currentTimeMillis() + ".gpx")
        response.inputStream.use { input -> source.outputStream().use { output -> input.copyTo(output) } }
        source.takeIf { it.isFile && it.length() > 0L }
    }.getOrNull()
}
