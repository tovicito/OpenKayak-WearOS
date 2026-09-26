package com.openkayak.mobile

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

class WearWorkoutListenerService : WearableListenerService(), DataClient.OnDataChangedListener {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (!path.startsWith("/openkayak/hc/workout/")) continue
            val payload = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("payload") ?: continue
            val id = path.substringAfterLast('/')
            val dir = File(filesDir, "health_connect_pending").apply { mkdirs() }
            File(dir, "$id.json").writeText(payload)
            val work = OneTimeWorkRequestBuilder<HealthConnectWorker>()
                .setInputData(workDataOf("workout_id" to id))
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                "health-connect-$id",
                ExistingWorkPolicy.REPLACE,
                work
            )
        }
    }
}
