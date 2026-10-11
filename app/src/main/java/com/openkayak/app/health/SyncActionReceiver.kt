package com.openkayak.app.health

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class SyncActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RETRY = "com.openkayak.app.health.RETRY_SYNC"
        const val ACTION_ABORT = "com.openkayak.app.health.ABORT_SYNC"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val manager = WorkManager.getInstance(context)
        when (intent?.action) {
            ACTION_RETRY -> {
                val request = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                manager.enqueueUniqueWork(SyncWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
            }
            ACTION_ABORT -> manager.cancelUniqueWork(SyncWorker.UNIQUE_NAME)
            else -> return
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(SyncWorker.NOTIFICATION_ID)
    }
}
