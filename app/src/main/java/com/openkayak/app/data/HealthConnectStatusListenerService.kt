package com.openkayak.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class HealthConnectStatusListenerService : WearableListenerService(), DataClient.OnDataChangedListener {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (!path.startsWith("/openkayak/hc/status/")) continue
            val id = path.substringAfterLast('/')
            val status = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("status") ?: continue
            when (status) {
                "success" -> {
                    HealthConnectManager(this).forgetWorkout(id)
                    getSystemService(NotificationManager::class.java)?.cancel(id.hashCode())
                }
                "failed" -> showFailureNotification(id)
            }
        }
    }

    private fun showFailureNotification(id: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Health Connect", NotificationManager.IMPORTANCE_HIGH)
        )

        val retry = PendingIntent.getBroadcast(
            this, id.hashCode(),
            Intent(this, HealthConnectRetryReceiver::class.java)
                .setAction(HealthConnectRetryReceiver.ACTION_RETRY)
                .putExtra(HealthConnectRetryReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val abort = PendingIntent.getBroadcast(
            this, id.hashCode() + 1,
            Intent(this, HealthConnectRetryReceiver::class.java)
                .setAction(HealthConnectRetryReceiver.ACTION_ABORT)
                .putExtra(HealthConnectRetryReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        manager?.notify(
            id.hashCode(),
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Health Connect")
                .setContentText("Sincronización Health Connect Fallida")
                .addAction(android.R.drawable.ic_popup_sync, "Reintentar", retry)
                .addAction(android.R.drawable.ic_delete, "Abortar", abort)
                .build()
        )
    }

    companion object {
        private const val CHANNEL = "health_connect_sync"
    }
}
