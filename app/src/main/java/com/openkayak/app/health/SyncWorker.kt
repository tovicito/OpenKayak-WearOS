package com.openkayak.app.health

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.openkayak.app.MainActivity
import com.openkayak.app.R

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val UNIQUE_NAME = "openkayak-health-connect-sync"
        const val CHANNEL = "openkayak_health_sync"
        const val NOTIFICATION_ID = 502
    }

    override suspend fun doWork(): Result {
        return try {
            HealthConnectSync.syncLatestSession(applicationContext)
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
            Result.success()
        } catch (e: Exception) {
            postFailureNotification(e.message ?: "Error desconocido")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun postFailureNotification(reason: String) {
        val context = applicationContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sincronización de entrenamientos", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val retry = PendingIntent.getBroadcast(
            context, 11,
            Intent(context, SyncActionReceiver::class.java).setAction(SyncActionReceiver.ACTION_RETRY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val abort = PendingIntent.getBroadcast(
            context, 12,
            Intent(context, SyncActionReceiver::class.java).setAction(SyncActionReceiver.ACTION_ABORT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            context, 13, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Sincronización Health Connect Fallida")
            .setContentText(reason.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Reintentar", retry)
            .addAction(0, "Abortar", abort)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
