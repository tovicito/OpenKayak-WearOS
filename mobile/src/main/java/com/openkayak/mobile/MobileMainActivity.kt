package com.openkayak.mobile

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import kotlinx.coroutines.launch

class MobileMainActivity : ComponentActivity() {
    private var statusView: TextView? = null

    private val requiredPermissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(SpeedRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(ElevationGainedRecord::class)
    )

    private val permissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        statusView?.text = if (granted.containsAll(requiredPermissions)) {
            "Health Connect listo"
        } else {
            "Faltan permisos de Health Connect"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            text = "Comprobando Health Connect..."
            textSize = 18f
            setPadding(32, 48, 32, 48)
        }
        setContentView(statusView)

        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            statusView?.text = "Health Connect no está disponible en este móvil."
            return
        }

        val client = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(requiredPermissions)) {
                permissionsLauncher.launch(requiredPermissions)
            } else {
                statusView?.text = "Health Connect listo"
            }
        }
    }
}
