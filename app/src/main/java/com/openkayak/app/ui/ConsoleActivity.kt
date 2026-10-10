package com.openkayak.app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.openkayak.app.ble.BleConnectionState
import com.openkayak.app.ble.BleDeviceOption
import com.openkayak.app.ble.BleHeartRateState
import com.openkayak.app.ble.HeartRateManager
import com.openkayak.app.data.HealthConnectManager
import com.openkayak.app.data.KayakDatabase
import com.openkayak.app.data.WorkoutEntity
import com.openkayak.app.service.GpsPoint
import com.openkayak.app.service.LocationService
import com.openkayak.app.service.WorkoutState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val Ink = Color(0xFF05080C)
private val Deep = Color(0xFF111821)
private val Raised = Color(0xFF19232D)
private val Aqua = Color(0xFF74F7E3)
private val Lime = Color(0xFFD8FF78)
private val Paper = Color(0xFFF2F7F8)
private val Muted = Color(0xFF8B9AA8)
private val Coral = Color(0xFFFF7D86)

private object HeartRateManagerHolder {
    @Volatile private var manager: HeartRateManager? = null
    fun get(context: Context): HeartRateManager = manager ?: synchronized(this) {
        manager ?: HeartRateManager(context.applicationContext).also { manager = it }
    }
}

class ConsoleActivity : ComponentActivity() {
    private val serviceState = mutableStateOf<LocationService?>(null)
    private val ambientState = mutableStateOf(false)
    private val syncMessageState = mutableStateOf("Health Connect: pendiente")
    private var isBound = false
    private lateinit var hrManager: HeartRateManager
    private lateinit var healthConnectManager: HealthConnectManager
    private var deckModePreference = true

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) Log.i("OpenKayak", "GPS autorizado")
    }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        syncMessageState.value = if (granted.containsAll(healthConnectManager.permissions)) {
            "Health Connect listo"
        } else {
            "Faltan permisos de Health Connect"
        }
    }

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambientState.value = true
            }
            override fun onExitAmbient() { ambientState.value = false }
            override fun onUpdateAmbient() = Unit
        }
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? LocationService.LocalBinder ?: return
            serviceState.value = local.getService()
            serviceState.value?.setDeckMountedMode(deckModePreference)
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceState.value = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        deckModePreference = getSharedPreferences("console_settings", MODE_PRIVATE).getBoolean("deck_mode", true)
        hrManager = HeartRateManagerHolder.get(applicationContext)
        hrManager.setExternalOnly(deckModePreference)
        healthConnectManager = HealthConnectManager(applicationContext)
        createSyncNotificationChannel()
        try {
            bindService(Intent(this, LocationService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e("OpenKayak", "No se pudo enlazar el servicio de entrenamiento", e)
        }

        setContent {
            ConsoleRoot(
                service = serviceState.value,
                hrManager = hrManager,
                ambient = ambientState.value,
                initialDeckMode = deckModePreference,
                syncMessage = syncMessageState.value,
                onDeckModeChange = { enabled ->
                    deckModePreference = enabled
                    getSharedPreferences("console_settings", MODE_PRIVATE).edit().putBoolean("deck_mode", enabled).apply()
                    hrManager.setExternalOnly(enabled)
                    serviceState.value?.setDeckMountedMode(enabled)
                },
                onStart = { startWorkout() },
                onPause = { sendServiceAction(LocationService.ACTION_PAUSE) },
                onResume = { sendServiceAction(LocationService.ACTION_RESUME) },
                onFinish = { finishWorkout() },
                onScanBle = { scanForHeartRate() },
                onConnectBleDevice = { connectBleDevice(it) },
                onDisconnectBle = { hrManager.disconnect() },
                onHealthSync = { retryLatestHealthSync() }
            )
        }

        requestCorePermissions()
        requestHealthConnectPermissionsIfNeeded()
        when (intent?.action) {
            ACTION_RETRY_SYNC -> retryLatestHealthSync()
            ACTION_ABORT_SYNC -> syncMessageState.value = "Reintento cancelado"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            ACTION_RETRY_SYNC -> retryLatestHealthSync()
            ACTION_ABORT_SYNC -> syncMessageState.value = "Reintento cancelado"
        }
    }

    private fun requestCorePermissions() {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BODY_SENSORS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required += Manifest.permission.BLUETOOTH_SCAN
            required += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            required += Manifest.permission.BLUETOOTH
            required += Manifest.permission.BLUETOOTH_ADMIN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) required += Manifest.permission.POST_NOTIFICATIONS
        val missing = required.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) runtimePermissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestHealthConnectPermissionsIfNeeded() {
        lifecycleScope.launch {
            val client = healthConnectManager.healthConnectClient
            if (client == null) {
                syncMessageState.value = "Health Connect no disponible en este reloj"
                return@launch
            }
            try {
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(healthConnectManager.permissions)) {
                    healthPermissionLauncher.launch(healthConnectManager.permissions)
                } else syncMessageState.value = "Health Connect listo"
            } catch (e: Exception) {
                Log.w("OpenKayak", "No se pudieron comprobar los permisos de Health Connect", e)
                syncMessageState.value = "No se pudo comprobar Health Connect"
            }
        }
    }

    private fun startWorkout() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestCorePermissions()
            syncMessageState.value = "Concede el permiso GPS y pulsa INICIAR"
            return
        }
        serviceState.value?.setDeckMountedMode(deckModePreference)
        sendServiceAction(LocationService.ACTION_START, foreground = true)
    }

    private fun sendServiceAction(action: String, foreground: Boolean = false) {
        val intent = Intent(this, LocationService::class.java).setAction(action)
        try {
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            Log.e("OpenKayak", "No se pudo iniciar el servicio", e)
            syncMessageState.value = "No se pudo iniciar. Revisa los permisos."
        }
    }

    private fun scanForHeartRate() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            runtimePermissionLauncher.launch(missing.toTypedArray())
            return
        }
        hrManager.setExternalOnly(deckModePreference)
        hrManager.startScanAndConnect()
    }

    private fun connectBleDevice(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            runtimePermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        hrManager.setExternalOnly(deckModePreference)
        hrManager.connectToAddress(address)
    }

    private fun finishWorkout() {
        val service = serviceState.value
        val state = service?.workoutState?.value
        if (service != null && state != null && state.isTracking && (state.elapsedTimeSeconds > 0L || state.distanceMeters > 0f)) {
            val endTime = System.currentTimeMillis()
            val routeJson = service.getTrackPoints().joinToString(prefix = "[", postfix = "]") { point ->
                String.format(Locale.US, "{\"lat\":%.7f,\"lon\":%.7f,\"t\":%d}", point.latitude, point.longitude, point.timestamp)
            }
            val average = if (state.elapsedTimeSeconds > 0L) state.distanceMeters / state.elapsedTimeSeconds * 3.6f else 0f
            val entity = WorkoutEntity(
                timestamp = endTime,
                durationSeconds = state.elapsedTimeSeconds,
                distanceMeters = state.distanceMeters,
                maxSpeedKmh = state.maxSpeedKmh,
                avgSpeedKmh = average,
                totalStrokes = state.totalStrokes,
                avgStrokeRateSpm = state.strokeRateSpm,
                estimatedCalories = max(0, (state.elapsedTimeSeconds / 60.0 * 5.0).roundToInt()),
                routeGpsJson = routeJson
            )
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        KayakDatabase.getInstance(applicationContext).workoutDao().insertWorkout(entity)
                    }
                    val synced = healthConnectManager.writeKayakWorkout(
                        startTimeMillis = endTime - state.elapsedTimeSeconds * 1000L,
                        endTimeMillis = endTime,
                        distanceMeters = state.distanceMeters,
                        clientRecordId = "openkayak-session-" + endTime
                    )
                    syncMessageState.value = if (synced) "Sesión guardada y sincronizada" else "Sesión guardada; falta sincronizar"
                    if (!synced) showSyncFailureNotification()
                } catch (e: Exception) {
                    Log.e("OpenKayak", "Fallo al guardar la sesión", e)
                    syncMessageState.value = "Error al guardar la sesión local"
                }
            }
        } else syncMessageState.value = "Sesión demasiado corta para guardar"
        sendServiceAction(LocationService.ACTION_STOP)
    }

    private fun retryLatestHealthSync() {
        lifecycleScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) {
                    KayakDatabase.getInstance(applicationContext).workoutDao().getAllWorkouts().first().firstOrNull()
                }
                if (latest == null) {
                    syncMessageState.value = "No hay sesiones para sincronizar"
                    return@launch
                }
                val ok = healthConnectManager.writeKayakWorkout(
                    startTimeMillis = latest.timestamp - latest.durationSeconds * 1000L,
                    endTimeMillis = latest.timestamp,
                    distanceMeters = latest.distanceMeters,
                    clientRecordId = "openkayak-session-" + latest.timestamp
                )
                syncMessageState.value = if (ok) "Última sesión sincronizada" else "Sincronización Health Connect fallida"
                if (!ok) showSyncFailureNotification()
            } catch (e: Exception) {
                Log.e("OpenKayak", "Falló el reintento de Health Connect", e)
                syncMessageState.value = "Sincronización Health Connect fallida"
                showSyncFailureNotification()
            }
        }
    }

    private fun createSyncNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(SYNC_CHANNEL, "Sincronización de OpenKayak", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "Estado de la sincronización de entrenamientos"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun showSyncFailureNotification() {
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val retry = PendingIntent.getActivity(this, 71, Intent(this, ConsoleActivity::class.java).setAction(ACTION_RETRY_SYNC), flags)
            val abort = PendingIntent.getActivity(this, 72, Intent(this, ConsoleActivity::class.java).setAction(ACTION_ABORT_SYNC), flags)
            val notification = NotificationCompat.Builder(this, SYNC_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Sincronización Health Connect Fallida")
                .setContentText("La sesión está guardada localmente.")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(0, "Reintentar", retry)
                .addAction(0, "Abortar", abort)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(SYNC_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w("OpenKayak", "No se pudo mostrar el aviso de sincronización", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
        // HeartRateManagerHolder intentionally keeps the BLE GATT connection across UI recreation.
    }

    companion object {
        private const val SYNC_CHANNEL = "openkayak_health_connect_sync"
        private const val SYNC_NOTIFICATION_ID = 2045
        private const val ACTION_RETRY_SYNC = "com.openkayak.app.ACTION_RETRY_HEALTH_SYNC"
        private const val ACTION_ABORT_SYNC = "com.openkayak.app.ACTION_ABORT_HEALTH_SYNC"
    }
}

@Composable
private fun ConsoleRoot(
    service: LocationService?,
    hrManager: HeartRateManager,
    ambient: Boolean,
    initialDeckMode: Boolean,
    syncMessage: String,
    onDeckModeChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onScanBle: () -> Unit,
    onConnectBleDevice: (String) -> Unit,
    onDisconnectBle: () -> Unit,
    onHealthSync: () -> Unit
) {
    val emptyFlow = remember { MutableStateFlow(WorkoutState()) }
    val workout by (service?.workoutState ?: emptyFlow).collectAsState()
    val hrState by hrManager.hrState.collectAsState()
    val points = remember { mutableStateListOf<GpsPoint>() }
    var page by rememberSaveable { mutableStateOf(0) }
    var deckMode by rememberSaveable { mutableStateOf(initialDeckMode) }
    var manualAod by rememberSaveable { mutableStateOf(false) }
    val pageCount = 4
    val lowPower = ambient || manualAod

    LaunchedEffect(service, ambient) {
        while (true) {
            if (!ambient) {
                points.clear()
                points.addAll(service?.getTrackPoints().orEmpty().takeLast(900))
                kotlinx.coroutines.delay(1500L)
            } else kotlinx.coroutines.delay(60_000L)
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            if (lowPower) AmbientConsole(workout, hrState, manualAod, onExit = { manualAod = false })
            else when (page) {
                0 -> TrainingPage(workout, hrState, deckMode, onStart, onPause, onResume, onFinish)
                1 -> RoutePage(workout, points)
                2 -> HeartRatePage(hrState, deckMode, onScanBle, onConnectBleDevice, onDisconnectBle)
                else -> MorePage(deckMode, syncMessage, onDeckModeChange = {
                    deckMode = it
                    onDeckModeChange(it)
                }, onEnterAod = { manualAod = true }, onHealthSync = onHealthSync)
            }

            SideArrow("Página anterior", "‹", { page = (page - 1 + pageCount) % pageCount }, Modifier.align(Alignment.CenterStart).padding(start = 3.dp), ambient)
            SideArrow("Página siguiente", "›", { page = (page + 1) % pageCount }, Modifier.align(Alignment.CenterEnd).padding(end = 3.dp), ambient)

            if (!lowPower) {
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(pageCount) { index ->
                        Box(
                            Modifier.size(if (page == index) 15.dp else 5.dp, 5.dp)
                                .clip(CircleShape)
                                .background(if (page == index) Aqua else Muted.copy(alpha = 0.45f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrainingPage(
    workout: WorkoutState,
    hr: BleHeartRateState,
    deckMode: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (workout.isTracking && !workout.isPaused) Lime else Muted))
            Spacer(Modifier.width(5.dp))
            Text(if (!workout.isTracking) "OPENKAYAK" else if (workout.isPaused) "EN PAUSA" else "PIRAGÜISMO", color = Paper, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        }
        Text(formatDuration(workout.elapsedTimeSeconds), color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(String.format(Locale("es", "ES"), "%.2f", workout.distanceMeters / 1000f), color = Paper, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Text(" km", color = Aqua, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text("DISTANCIA", color = Muted, fontSize = 8.sp, letterSpacing = 1.7.sp)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricTile("VELOCIDAD", String.format(Locale.US, "%.1f", workout.speedKmh), "km/h", Modifier.weight(1f))
            MetricTile(if (deckMode) "PALADAS*" else "PALADAS", workout.strokeRateSpm.toString(), "por min", Modifier.weight(1f), Lime)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricTile("PULSO", if (hr.heartRateBpm in 30..240) hr.heartRateBpm.toString() else "--", "lpm", Modifier.weight(1f), Coral)
            MetricTile("MEDIA", String.format(Locale.US, "%.1f", if (workout.elapsedTimeSeconds > 0L) workout.distanceMeters / workout.elapsedTimeSeconds * 3.6f else 0f), "km/h", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        if (!workout.isTracking) AccentAction("INICIAR SESIÓN", Aqua, Ink, onStart)
        else Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AccentAction(if (workout.isPaused) "REANUDAR" else "PAUSAR", if (workout.isPaused) Lime else Raised, if (workout.isPaused) Ink else Paper, if (workout.isPaused) onResume else onPause, Modifier.weight(1f))
            AccentAction("FINALIZAR", Color(0xFF452128), Coral, onFinish, Modifier.weight(1f))
        }
        if (deckMode) Text("*Paladas estimadas desde la cubierta", color = Muted, fontSize = 7.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RoutePage(workout: WorkoutState, points: List<GpsPoint>) {
    Column(
        Modifier.fillMaxWidth().padding(start = 31.dp, end = 31.dp, top = 12.dp, bottom = 17.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("RECORRIDO", color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(25.dp)).background(Deep).border(1.dp, Raised, RoundedCornerShape(25.dp))) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val valid = points.filter { it.latitude.isFinite() && it.longitude.isFinite() }
                if (valid.size >= 2) {
                    val minLat = valid.minOf { it.latitude }
                    val maxLat = valid.maxOf { it.latitude }
                    val minLon = valid.minOf { it.longitude }
                    val maxLon = valid.maxOf { it.longitude }
                    val latSpan = (maxLat - minLat).coerceAtLeast(0.00001)
                    val lonSpan = (maxLon - minLon).coerceAtLeast(0.00001)
                    val inset = 7f
                    val path = Path()
                    valid.forEachIndexed { index, point ->
                        val x = inset + ((point.longitude - minLon) / lonSpan).toFloat() * (size.width - inset * 2)
                        val y = size.height - inset - ((point.latitude - minLat) / latSpan).toFloat() * (size.height - inset * 2)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, Aqua, style = Stroke(width = 4f, cap = StrokeCap.Round))
                    val last = valid.last()
                    val x = inset + ((last.longitude - minLon) / lonSpan).toFloat() * (size.width - inset * 2)
                    val y = size.height - inset - ((last.latitude - minLat) / latSpan).toFloat() * (size.height - inset * 2)
                    drawCircle(Lime, radius = 6f, center = androidx.compose.ui.geometry.Offset(x, y))
                } else {
                    for (i in 1..3) {
                        val x = size.width * i / 4f
                        val y = size.height * i / 4f
                        drawLine(Raised, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                        drawLine(Raised, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                    }
                }
            }
            if (points.size < 2) Text("Esperando ruta GPS", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricTile("PUNTOS GPS", points.size.toString(), "", Modifier.weight(1f))
            MetricTile("DISTANCIA", String.format(Locale.US, "%.2f", workout.distanceMeters / 1000f), "km", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (workout.currentPoint != null) String.format(Locale.US, "%.5f, %.5f", workout.currentPoint.latitude, workout.currentPoint.longitude) else "GPS sin posición",
            color = Muted, fontSize = 8.sp, textAlign = TextAlign.Center
        )
        Text("Traza local; no es un mapa topográfico", color = Muted, fontSize = 7.sp)
    }
}

@Composable
private fun HeartRatePage(
    hr: BleHeartRateState,
    deckMode: Boolean,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 31.dp, end = 31.dp, top = 12.dp, bottom = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SENSOR DE PULSO", color = Paper, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text(if (hr.heartRateBpm in 30..240) hr.heartRateBpm.toString() else "--", color = Coral, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("LPM  ·  " + when (hr.connectionState) {
            BleConnectionState.CONNECTED -> if (hr.hrNotificationsEnabled) "BANDA CONECTADA" else "ACTIVANDO NOTIFICACIONES"
            BleConnectionState.CONNECTING -> "CONECTANDO"
            BleConnectionState.SCANNING -> "BUSCANDO BANDAS"
            BleConnectionState.DISCONNECTED -> if (hr.isUsingInternalSensor && !deckMode) "SENSOR DEL RELOJ" else "SIN SEÑAL"
        }, color = if (hr.connectionState == BleConnectionState.CONNECTED && hr.hrNotificationsEnabled) Lime else Muted, fontSize = 8.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(7.dp))
        AccentAction(if (hr.connectionState == BleConnectionState.SCANNING) "BUSCANDO…" else "BUSCAR BANDA BLE", Aqua, Ink, onScan)
        Spacer(Modifier.height(4.dp))
        if (hr.connectionState == BleConnectionState.CONNECTED) {
            Text(hr.deviceName ?: "Banda BLE", color = Paper, fontSize = 10.sp, textAlign = TextAlign.Center)
            AccentAction("DESCONECTAR", Raised, Paper, onDisconnect)
        } else if (hr.availableDevices.isNotEmpty()) {
            Text("DISPOSITIVOS DETECTADOS", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            hr.availableDevices.take(3).forEach { BleDeviceRow(it, onClick = { onConnect(it.address) }) }
            Text("Los dispositivos sin anuncio de pulso se seleccionan manualmente.", color = Muted, fontSize = 7.sp, textAlign = TextAlign.Center)
        } else {
            Text(if (deckMode) "Modo cubierta: solo banda externa; sensor óptico desactivado." else "El sensor óptico puede actuar como respaldo.", color = Muted, fontSize = 8.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MorePage(
    deckMode: Boolean,
    syncMessage: String,
    onDeckModeChange: (Boolean) -> Unit,
    onEnterAod: () -> Unit,
    onHealthSync: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("MÁS CONTROLES", color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(8.dp))
        AccentAction(if (deckMode) "✓ MODO CUBIERTA" else "MODO MUÑECA", if (deckMode) Lime else Raised, if (deckMode) Ink else Paper, { onDeckModeChange(!deckMode) })
        Spacer(Modifier.height(4.dp))
        Text(if (deckMode) "Pulso óptico desactivado · paladas estimadas" else "Pulso óptico disponible como respaldo", color = Muted, fontSize = 7.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        AccentAction("ACTIVAR VISTA AOD", Aqua, Ink, onEnterAod)
        Spacer(Modifier.height(6.dp))
        AccentAction("SINCRONIZAR ÚLTIMA SESIÓN", Raised, Paper, onHealthSync)
        Spacer(Modifier.height(5.dp))
        Text(syncMessage, color = Muted, fontSize = 8.sp, textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(7.dp))
        Text("OpenKayak · Piragüismo", color = Paper, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        Text("GPS · BLE · traza · Health Connect", color = Muted, fontSize = 7.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AmbientConsole(workout: WorkoutState, hr: BleHeartRateState, manual: Boolean, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 35.dp, vertical = 23.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("OPENKAYAK  /  AOD", color = Muted, fontSize = 8.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(8.dp))
        Text(formatDuration(workout.elapsedTimeSeconds), color = Paper, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        Text(String.format(Locale.US, "%.2f", workout.distanceMeters / 1000f) + " km", color = Paper, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(String.format(Locale.US, "%.1f km/h", workout.speedKmh), color = Aqua, fontSize = 10.sp)
            Text(if (hr.heartRateBpm > 0) hr.heartRateBpm.toString() + " lpm" else "-- lpm", color = Coral, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))
        if (manual) AccentAction("SALIR DEL AOD", Raised, Paper, onExit)
        else Text("Toca la pantalla para activarla", color = Muted, fontSize = 8.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MetricTile(label: String, value: String, unit: String, modifier: Modifier = Modifier, accent: Color = Aqua) {
    Column(
        modifier.clip(RoundedCornerShape(17.dp)).background(Deep).padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, color = Muted, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, textAlign = TextAlign.Center)
        Text(value, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (unit.isNotBlank()) Text(unit, color = Muted, fontSize = 7.sp)
    }
}

@Composable
private fun AccentAction(label: String, background: Color, foreground: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(33.dp).clip(CircleShape).background(background)
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BleDeviceRow(device: BleDeviceOption, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(13.dp))
            .background(Raised).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name, color = Paper, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(device.address.takeLast(8), color = Muted, fontSize = 7.sp)
        }
        Text("CONECTAR", color = Aqua, fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SideArrow(label: String, text: String, onClick: () -> Unit, modifier: Modifier, dimmed: Boolean) {
    Box(
        modifier.size(29.dp).alpha(if (dimmed) 0.38f else 0.92f).clip(CircleShape)
            .background(Raised).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Aqua, fontSize = 24.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
    }
}

private fun formatDuration(seconds: Long): String {
    val safe = max(0L, seconds)
    val hours = safe / 3600L
    val minutes = (safe % 3600L) / 60L
    val secs = safe % 60L
    return if (hours > 0L) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    else String.format(Locale.US, "%02d:%02d", minutes, secs)
}
