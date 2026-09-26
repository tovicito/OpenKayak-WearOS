package com.openkayak.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HealthConnectRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val manager = HealthConnectManager(context.applicationContext)
        if (intent.action == ACTION_ABORT) {
            manager.forgetWorkout(id)
            return
        }
        if (intent.action == ACTION_RETRY) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                manager.retryWorkout(id)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RETRY = "com.openkayak.app.healthconnect.RETRY"
        const val ACTION_ABORT = "com.openkayak.app.healthconnect.ABORT"
        const val EXTRA_ID = "workout_id"
    }
}
