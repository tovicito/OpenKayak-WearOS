package com.openkayak.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.openkayak.app.ble.BleConnectionState
import com.openkayak.app.ble.HeartRateManager
import com.openkayak.app.data.HealthConnectManager
import com.openkayak.app.data.KayakDatabase
import com.openkayak.app.data.WorkoutEntity
import com.openkayak.app.service.GpsPoint
import com.openkayak.app.service.LocationService
import com.openkayak.app.service.MapTileDownloader
import com.openkayak.app.service.WorkoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : ComponentActivity() {

    private val locationServiceState = mutableStateOf<LocationService?>(null)
    private var isBound = false
    private lateinit var hrManager: HeartRateManager
    private lateinit var mapDownloader: MapTileDownloader
    private lateinit var healthConnectManager: HealthConnectManager

    private val isSystemAmbientMode = mutableStateOf(false)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isSystemAmbientMode.value = true
        }

        override fun onExitAmbient() {
            isSystemAmbientMode.value = false
        }

        override fun onUpdateAmbient() {}
    }

    private val ambientObserver = AmbientLifecycleObserver(this, ambientCallback)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocationService.LocalBinder
            locationServiceState.value = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            locationServiceState.value = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)

        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        hrManager = HeartRateManager(this)
        mapDownloader = MapTileDownloader(this)
        healthConnectManager = HealthConnectManager(this)

        val intent = Intent(this, LocationService::class.java)
        try {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e("MainActivity", "bindService failed: ${e.localizedMessage}")
        }

        setContent {
            val activeService = locationServiceState.value
            OpenKayakApp(
                locationService = activeService,
                hrManager = hrManager,
                mapDownloader = mapDownloader,
                healthConnectManager = healthConnectManager,
                isSystemAmbient = isSystemAmbientMode.value,
                onStartWorkout = {
                    val startIntent = Intent(this, LocationService::class.java).apply {
                        action = LocationService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(startIntent)
                    } else {
                        startService(startIntent)
                    }
                },
                onPauseWorkout = {
                    val pauseIntent = Intent(this, LocationService::class.java).apply {
                        action = LocationService.ACTION_PAUSE
                    }
                    startService(pauseIntent)
                },
                onResumeWorkout = {
                    val resumeIntent = Intent(this, LocationService::class.java).apply {
                        action = LocationService.ACTION_RESUME
                    }
                    startService(resumeIntent)
                },
                onStopWorkout = { workoutState, hrBpm ->
                    val points = activeService?.getTrackPoints() ?: emptyList()
                    val jsonRoute = pointsToJson(points)
                    val avgSpeed = if (workoutState.elapsedTimeSeconds > 0) {
                        (workoutState.distanceMeters / workoutState.elapsedTimeSeconds) * 3.6f
                    } else 0f
                    val calories = calculateCalories(workoutState.elapsedTimeSeconds, hrBpm)

                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - (workoutState.elapsedTimeSeconds * 1000L)

                    val entity = WorkoutEntity(
                        timestamp = endTime,
                        durationSeconds = workoutState.elapsedTimeSeconds,
                        distanceMeters = workoutState.distanceMeters,
                        maxSpeedKmh = workoutState.maxSpeedKmh,
                        avgSpeedKmh = avgSpeed,
                        totalStrokes = workoutState.totalStrokes,
                        avgStrokeRateSpm = workoutState.strokeRateSpm,
                        estimatedCalories = calories,
                        routeGpsJson = jsonRoute
                    )

                    val db = KayakDatabase.getInstance(applicationContext)
                    CoroutineScope(Dispatchers.IO).launch {
                        db.workoutDao().insertWorkout(entity)
                        healthConnectManager.writeKayakWorkout(
                            startTimeMillis = startTime,
                            endTimeMillis = endTime,
                            distanceMeters = workoutState.distanceMeters
                        )
                    }

                    val stopIntent = Intent(this, LocationService::class.java).apply {
                        action = LocationService.ACTION_STOP
                    }
                    startService(stopIntent)
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        hrManager.disconnect()
    }

    private fun pointsToJson(points: List<GpsPoint>): String {
        val sb = StringBuilder("[")
        for (i in points.indices) {
            val p = points[i]
            sb.append("{\"lat\":${p.latitude},\"lon\":${p.longitude}}")
            if (i < points.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun calculateCalories(durationSec: Long, avgBpm: Int): Int {
        val prefs = getSharedPreferences("user_profile", Context.MODE_PRIVATE)
        val age = prefs.getInt("age", 30)
        val weightKg = prefs.getFloat("weight", 75f)
        val isMale = prefs.getBoolean("is_male", true)

        val minutes = durationSec / 60f
        val bpm = if (avgBpm > 0) avgBpm else 125

        // Keytel formula for heart-rate based energy expenditure estimation
        val calories = if (isMale) {
            ((-55.0969 + (0.6309 * bpm) + (0.1988 * weightKg) + (0.2017 * age)) / 4.184) * minutes
        } else {
            ((-20.4022 + (0.4472 * bpm) - (0.1263 * weightKg) + (0.074 * age)) / 4.184) * minutes
        }
        return calories.coerceAtLeast(0.0).toInt()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun OpenKayakApp(
    locationService: LocationService?,
    hrManager: HeartRateManager,
    mapDownloader: MapTileDownloader,
    healthConnectManager: HealthConnectManager,
    isSystemAmbient: Boolean,
    onStartWorkout: () -> Unit,
    onPauseWorkout: () -> Unit,
    onResumeWorkout: () -> Unit,
    onStopWorkout: (WorkoutState, Int) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var permissionsGranted by remember { mutableStateOf(false) }

    val healthConnectLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        // Health connect permission result
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BODY_SENSORS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            permissionsGranted = true
        }
    }

    LaunchedEffect(permissionsGranted, locationService) {
        if (permissionsGranted) {
            locationService?.startLocationUpdates()
            hrManager.startMonitoring()
            if (healthConnectManager.healthConnectClient != null) {
                coroutineScope.launch {
                    try {
                        if (!healthConnectManager.hasAllPermissions()) {
                            healthConnectLauncher.launch(healthConnectManager.permissions)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Health Connect permission launch exception: ${e.localizedMessage}")
                    }
                }
            }
        }
    }

    val workoutState by (locationService?.workoutState ?: MutableStateFlow(WorkoutState())).collectAsState()
    val hrState by hrManager.hrState.collectAsState()
    val downloadState by mapDownloader.downloadState.collectAsState()

    var pendingRestoreCircuit by remember { mutableStateOf<LearnedCircuit?>(null) }

    var isWaterTouchLocked by remember { mutableStateOf(false) }
    var unlockTimeRemainingSeconds by remember { mutableStateOf(0) }

    var inactivitySeconds by remember { mutableStateOf(0) }
    val isThreeMinInactivityAmbient = inactivitySeconds >= 180

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            inactivitySeconds += 1
        }
    }

    LaunchedEffect(workoutState.isTracking) {
        if (workoutState.isTracking) {
            isWaterTouchLocked = true
            unlockTimeRemainingSeconds = 0
        } else {
            isWaterTouchLocked = false
            unlockTimeRemainingSeconds = 0
        }
    }

    LaunchedEffect(unlockTimeRemainingSeconds) {
        if (unlockTimeRemainingSeconds > 0) {
            delay(1000L)
            unlockTimeRemainingSeconds -= 1
            if (unlockTimeRemainingSeconds == 0 && workoutState.isTracking) {
                isWaterTouchLocked = true
            }
        }
    }

    val pagerState = rememberPagerState(initialPage = 0) { 5 }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val pageIndicatorState = remember(pagerState) {
        object : PageIndicatorState {
            override val pageCount: Int get() = 5
            override val pageOffset: Float get() = 0f
            override val selectedPage: Int get() = pagerState.currentPage
        }
    }

    val isEffectiveAmbient = isSystemAmbient || isThreeMinInactivityAmbient

    MaterialTheme {
        Scaffold(
            timeText = { if (!isEffectiveAmbient) TimeText() }
        ) {
            if (isEffectiveAmbient) {
                AmbientModeScreen(
                    workoutState = workoutState,
                    hrBpm = hrState.heartRateBpm,
                    locationService = locationService,
                    onExitAmbient = {
                        inactivitySeconds = 0
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .focusRequester(focusRequester)
                        .focusable()
                        .onRotaryScrollEvent { event ->
                            inactivitySeconds = 0
                            if (!isWaterTouchLocked) {
                                coroutineScope.launch {
                                    if (event.verticalScrollPixels > 0) {
                                        if (pagerState.currentPage < 3) {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    } else if (event.verticalScrollPixels < 0) {
                                        if (pagerState.currentPage > 0) {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    }
                                }
                            }
                            true
                        }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            android.view.View(ctx).apply {
                                setOnTouchListener { _, _ ->
                                    inactivitySeconds = 0
                                    false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !isWaterTouchLocked,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> DashboardScreen(
                                workoutState = workoutState,
                                hrState = hrState,
                                onStartWorkout = onStartWorkout,
                                onPauseWorkout = onPauseWorkout,
                                onResumeWorkout = onResumeWorkout,
                                onStopWorkout = { onStopWorkout(workoutState, hrState.heartRateBpm) }
                            )
                            1 -> MapScreen(
                                workoutState = workoutState,
                                locationService = locationService,
                                onNavigatePrev = {
                                    coroutineScope.launch { pagerState.animateScrollToPage(0) }
                                },
                                onNavigateNext = {
                                    coroutineScope.launch { pagerState.animateScrollToPage(2) }
                                }
                            )
                            2 -> HistoryScreen()
                            3 -> CircuitsScreen()
                            4 -> SettingsScreen(
                                hrManager = hrManager,
                                hrState = hrState,
                                mapDownloader = mapDownloader,
                                downloadState = downloadState,
                                healthConnectManager = healthConnectManager
                            )
                        }
                    }

                    HorizontalPageIndicator(
                        pageIndicatorState = pageIndicatorState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp),
                        selectedColor = Color(0xFFFFD700),
                        unselectedColor = Color.Gray
                    )

                    if (isWaterTouchLocked && workoutState.isTracking) {
                        WaterTouchLockOverlay(
                            onUnlock3SecComplete = {
                                inactivitySeconds = 0
                                isWaterTouchLocked = false
                                unlockTimeRemainingSeconds = 60
                            }
                        )
                    }

                    if (pendingRestoreCircuit != null) {
                        val circuit = pendingRestoreCircuit!!
                        RestoreCircuitDialog(
                            circuitName = circuit.name,
                            onRestore = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val prefs = context.getSharedPreferences("learned_circuits_prefs", Context.MODE_PRIVATE)
                                    val customJson = prefs.getString("circuits_json", null)
                                    if (!customJson.isNullOrEmpty()) {
                                        try {
                                            val arr = org.json.JSONArray(customJson)
                                            for (i in 0 until arr.length()) {
                                                val obj = arr.getJSONObject(i)
                                                if (obj.getLong("id") == circuit.id) {
                                                    obj.put("isDeleted", false)
                                                    break
                                                }
                                            }
                                            prefs.edit().putString("circuits_json", arr.toString()).apply()
                                        } catch (e: Exception) {}
                                    }
                                }
                                pendingRestoreCircuit = null
                            },
                            onKeepDeleted = {
                                pendingRestoreCircuit = null
                            },
                            onPermanentDelete = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val prefs = context.getSharedPreferences("learned_circuits_prefs", Context.MODE_PRIVATE)
                                    val customJson = prefs.getString("circuits_json", null)
                                    if (!customJson.isNullOrEmpty()) {
                                        try {
                                            val arr = org.json.JSONArray(customJson)
                                            val newArr = org.json.JSONArray()
                                            for (i in 0 until arr.length()) {
                                                val obj = arr.getJSONObject(i)
                                                if (obj.getLong("id") != circuit.id) {
                                                    newArr.put(obj)
                                                }
                                            }
                                            prefs.edit().putString("circuits_json", newArr.toString()).apply()
                                        } catch (e: Exception) {}
                                    }
                                }
                                pendingRestoreCircuit = null
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RestoreCircuitDialog(
    circuitName: String,
    onRestore: () -> Unit,
    onKeepDeleted: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE000000))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Circuito repetido:",
                fontSize = 11.sp,
                color = Color.LightGray
            )
            Text(
                text = circuitName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Yellow,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "¿Desea restaurarlo?",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onRestore,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00C853)),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(28.dp)
            ) {
                Text("Sí, Restaurar", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onKeepDeleted,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(28.dp)
            ) {
                Text("No (Mantener borrado)", fontSize = 10.sp, color = Color.White)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onPermanentDelete,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD50000)),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(28.dp)
            ) {
                Text("Borrar Permanentemente", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
fun WaterTouchLockOverlay(
    onUnlock3SecComplete: () -> Unit
) {
    var holdProgress by remember { mutableStateOf(0f) }
    var isHolding by remember { mutableStateOf(false) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val startTime = System.currentTimeMillis()
            while (isHolding) {
                val elapsed = System.currentTimeMillis() - startTime
                holdProgress = (elapsed / 3000f).coerceAtMost(1f)
                if (holdProgress >= 1f) {
                    onUnlock3SecComplete()
                    break
                }
                delay(30L)
            }
        } else {
            holdProgress = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xBB000000)),
        contentAlignment = Alignment.BottomCenter
    ) {
        AndroidView(
            factory = { context ->
                android.view.View(context).apply {
                    setOnTouchListener { _, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                isHolding = true
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isHolding = false
                                true
                            }
                            else -> true
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (holdProgress > 0f) {
                CircularProgressIndicator(
                    progress = holdProgress,
                    modifier = Modifier.size(36.dp),
                    indicatorColor = Color.Yellow,
                    trackColor = Color.DarkGray,
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                text = if (isHolding) "DESBLOQUEANDO (3s)..." else "Pulsa 3 segundos para desbloquear",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isHolding) Color.Yellow else Color.Cyan,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xEE111111))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun DashboardScreen(
    workoutState: WorkoutState,
    hrState: com.openkayak.app.ble.BleHeartRateState,
    onStartWorkout: () -> Unit,
    onPauseWorkout: () -> Unit,
    onResumeWorkout: () -> Unit,
    onStopWorkout: () -> Unit
) {
    val heartPulseScale by animateFloatAsState(
        targetValue = if (hrState.isPulseActive) 1.25f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(top = 20.dp, bottom = 18.dp, start = 10.dp, end = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF181818)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "VELOCIDAD",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray
                    )
                    Text(
                        text = String.format("%.1f", workoutState.speedKmh),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFFD700)
                    )
                    Text(
                        text = "km/h",
                        fontSize = 9.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF181818)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(
                            modifier = Modifier
                                .size(10.dp)
                                .scale(heartPulseScale)
                        ) {
                            drawCircle(color = Color.Red)
                        }
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "PULSO BLE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.LightGray
                        )
                    }
                    Text(
                        text = if (hrState.heartRateBpm > 0) "${hrState.heartRateBpm}" else "--",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = "BPM",
                        fontSize = 9.sp,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF181818)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "PALADAS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray
                    )
                    Text(
                        text = "${workoutState.strokeRateSpm}",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFF5722)
                    )
                    Text(
                        text = "SPM (${workoutState.totalStrokes} tot)",
                        fontSize = 8.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF181818)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = String.format("%.2f km", workoutState.distanceMeters / 1000f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.Cyan
                    )
                    Text(
                        text = formatTime(workoutState.elapsedTimeSeconds),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Green
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!workoutState.isTracking) {
                    Button(
                        onClick = onStartWorkout,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00C853)),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(36.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Iniciar entrenamiento")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("INICIAR", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(0.95f),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                if (workoutState.isPaused) onResumeWorkout() else onPauseWorkout()
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (workoutState.isPaused) Color(0xFF00C853) else Color(0xFFFF9100)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                        ) {
                            Icon(
                                if (workoutState.isPaused) Icons.Default.PlayArrow else Icons.Default.Refresh,
                                contentDescription = if (workoutState.isPaused) "Reanudar entrenamiento" else "Pausar entrenamiento"
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        TwoSecondLongPressButton(
                            onLongPressComplete = onStopWorkout,
                            modifier = Modifier
                                .weight(1.5f)
                                .height(36.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TwoSecondLongPressButton(
    onLongPressComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var progress by remember { mutableStateOf(0f) }
    var isPressing by remember { mutableStateOf(false) }

    LaunchedEffect(isPressing) {
        if (isPressing) {
            val startTime = System.currentTimeMillis()
            while (isPressing) {
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed / 2000f).coerceAtMost(1f)
                if (progress >= 1f) {
                    onLongPressComplete()
                    break
                }
                delay(30L)
            }
        } else {
            progress = 0f
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFD50000))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressing = true
                        try {
                            awaitRelease()
                        } finally {
                            isPressing = false
                        }
                    }
                )
            }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (progress > 0f) {
            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxSize(),
                indicatorColor = Color.Yellow,
                trackColor = Color.Transparent,
                strokeWidth = 3.dp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(12.dp)) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(2f, 2f),
                    size = Size(size.width - 4f, size.height - 4f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = if (isPressing) "MANTÉN 2s" else "GUARDAR",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * MapScreen featuring Osmdroid offline map view and Left/Right Arrow Navigation Buttons
 */
@Composable
fun MapScreen(
    workoutState: WorkoutState,
    locationService: LocationService?,
    onNavigatePrev: () -> Unit,
    onNavigateNext: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { KayakDatabase.getInstance(context) }
    val workoutList by db.workoutDao().getAllWorkouts().collectAsState(initial = emptyList())
    var learnedCircuits by remember { mutableStateOf<List<LearnedCircuit>>(emptyList()) }

    LaunchedEffect(workoutList) {
        learnedCircuits = getLearnedCircuitsAsync(context, workoutList)
    }

    val trackPoints = locationService?.getTrackPoints() ?: emptyList()
    val activePoint = workoutState.currentPoint ?: trackPoints.lastOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)

                    setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }

                    if (activePoint != null) {
                        controller.setCenter(GeoPoint(activePoint.latitude, activePoint.longitude))
                    } else {
                        controller.setCenter(GeoPoint(43.3614, -5.8593))
                    }
                }
            },
            update = { mapView ->
                mapView.overlays.clear()

                // Render learned green routes & pink turnaround markers
                for (circuit in learnedCircuits) {
                    val turnGeo = GeoPoint(circuit.turnLat, circuit.turnLon)
                    val pathPts = if (circuit.outerPolyline.isNotEmpty()) circuit.outerPolyline else listOf(GeoPoint(circuit.startLat, circuit.startLon), turnGeo)

                    val greenPolyline = Polyline().apply {
                        setPoints(pathPts)
                        outlinePaint.color = android.graphics.Color.GREEN
                        outlinePaint.strokeWidth = 8f
                    }
                    mapView.overlays.add(greenPolyline)

                    val pinkMarker = Marker(mapView).apply {
                        position = turnGeo
                        title = "Boya Giro: ${circuit.name}"
                    }
                    mapView.overlays.add(pinkMarker)
                }

                val points = trackPoints.map { GeoPoint(it.latitude, it.longitude) }
                if (points.isNotEmpty()) {
                    val polyline = Polyline().apply {
                        setPoints(points)
                        outlinePaint.color = android.graphics.Color.RED
                        outlinePaint.strokeWidth = 8f
                    }
                    mapView.overlays.add(polyline)

                    val startMarker = Marker(mapView).apply {
                        position = points.first()
                        title = "Inicio Kayak"
                    }
                    mapView.overlays.add(startMarker)
                }

                if (activePoint != null) {
                    val currentMarker = Marker(mapView).apply {
                        position = GeoPoint(activePoint.latitude, activePoint.longitude)
                        title = "Posición Actual"
                    }
                    mapView.overlays.add(currentMarker)
                    mapView.controller.animateTo(GeoPoint(activePoint.latitude, activePoint.longitude))
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay info box at top
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 22.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "GPS: ${trackPoints.size} pts | ${String.format("%.2f", workoutState.distanceMeters / 1000f)} km",
                fontSize = 11.sp,
                color = Color.Yellow,
                fontWeight = FontWeight.Bold
            )
        }

        // Left Navigation Arrow Button
        Button(
            onClick = onNavigatePrev,
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xCC222222)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
                .size(32.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Página anterior" }
        ) {
            Text("<", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.Yellow)
        }

        // Right Navigation Arrow Button
        Button(
            onClick = onNavigateNext,
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xCC222222)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .size(32.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Página siguiente" }
        ) {
            Text(">", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.Yellow)
        }
    }
}

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val db = remember { KayakDatabase.getInstance(context) }
    val workoutList by db.workoutDao().getAllWorkouts().collectAsState(initial = emptyList())
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    var selectedWorkoutForMap by remember { mutableStateOf<WorkoutEntity?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(top = 20.dp, bottom = 12.dp)
    ) {
        if (selectedWorkoutForMap != null) {
            val workout = selectedWorkoutForMap!!
            val points = remember(workout) { parseJsonRoute(workout.routeGpsJson) }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(15.0)
                        }
                    },
                    update = { mapView ->
                        mapView.overlays.clear()
                        if (points.isNotEmpty()) {
                            val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
                            val polyline = Polyline().apply {
                                setPoints(geoPoints)
                                outlinePaint.color = android.graphics.Color.CYAN
                                outlinePaint.strokeWidth = 6f
                            }
                            mapView.overlays.add(polyline)
                            mapView.controller.setCenter(geoPoints.first())
                        }
                        mapView.invalidate()
                    },
                    modifier = Modifier.fillMaxSize()
                )

                Button(
                    onClick = { selectedWorkoutForMap = null },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xCC000000)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "Cerrar vista previa" }
                ) {
                    Text("X", color = Color.Yellow, fontWeight = FontWeight.Bold)
                }
            }
        } else if (workoutList.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Sin Historial",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                Text(
                    text = "Guarda tu primer entreno",
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
            }
        } else {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Text(
                        text = "HISTORIAL KAYAK",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Yellow,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                items(workoutList) { item ->
                    Card(
                        onClick = { selectedWorkoutForMap = item },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = String.format("%.2f km", item.distanceMeters / 1000f),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Cyan
                                )
                                Text(
                                    text = formatTime(item.durationSeconds),
                                    fontSize = 13.sp,
                                    color = Color.Green
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Paladas: ${item.totalStrokes} | ${item.estimatedCalories} kcal",
                                    fontSize = 9.sp,
                                    color = Color.White
                                )

                                Button(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            db.workoutDao().deleteWorkout(item)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD50000)),
                                    modifier = Modifier
                                        .size(22.dp)
                                        .semantics { contentDescription = "Eliminar entrenamiento" }
                                ) {
                                    Text("X", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseJsonRoute(json: String): List<GpsPoint> {
    val points = mutableListOf<GpsPoint>()
    try {
        val array = org.json.JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            points.add(
                GpsPoint(
                    latitude = obj.getDouble("lat"),
                    longitude = obj.getDouble("lon"),
                    altitude = 0.0,
                    timestamp = 0L
                )
            )
        }
    } catch (e: Exception) {}
    return points
}

data class LearnedCircuit(
    val id: Long,
    val name: String,
    val startLat: Double,
    val startLon: Double,
    val turnLat: Double,
    val turnLon: Double,
    val totalLaps: Int,
    val outerPolyline: List<GeoPoint> = emptyList(),
    val isDeleted: Boolean = false
)

fun calculateTurnAngleDegrees(p1: GpsPoint, p2: GpsPoint, p3: GpsPoint): Double {
    val b1 = Math.toDegrees(Math.atan2(p2.longitude - p1.longitude, p2.latitude - p1.latitude))
    val b2 = Math.toDegrees(Math.atan2(p3.longitude - p2.longitude, p3.latitude - p2.latitude))
    var diff = Math.abs(b2 - b1)
    if (diff > 180.0) diff = 360.0 - diff
    return diff
}

fun distanceBetweenMeters(p1: GpsPoint, p2: GpsPoint): Float {
    val res = FloatArray(1)
    android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, res)
    return res[0]
}

suspend fun getLearnedCircuitsAsync(context: Context, dbWorkouts: List<WorkoutEntity>): List<LearnedCircuit> = kotlinx.coroutines.withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences("learned_circuits_prefs", Context.MODE_PRIVATE)
    val customJson = prefs.getString("circuits_json", null)
    val savedCircuits = mutableListOf<LearnedCircuit>()
    val deletedCircuitIds = mutableSetOf<Long>()

    if (!customJson.isNull_or_empty()) {
        try {
            val arr = org.json.JSONArray(customJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val isDeleted = obj.optBoolean("isDeleted", false)
                val id = obj.getLong("id")
                if (isDeleted) {
                    deletedCircuitIds.add(id)
                } else {
                    val polyArr = obj.optJSONArray("outerPolyline")
                    val polyList = mutableListOf<GeoPoint>()
                    if (polyArr != null) {
                        for (j in 0 until polyArr.length()) {
                            val pObj = polyArr.getJSONObject(j)
                            polyList.add(GeoPoint(pObj.getDouble("lat"), pObj.getDouble("lon")))
                        }
                    }
                    savedCircuits.add(
                        LearnedCircuit(
                            id = id,
                            name = obj.getString("name"),
                            startLat = obj.getDouble("startLat"),
                            startLon = obj.getDouble("startLon"),
                            turnLat = obj.getDouble("turnLat"),
                            turnLon = obj.getDouble("turnLon"),
                            totalLaps = obj.getInt("totalLaps"),
                            outerPolyline = polyList,
                            isDeleted = false
                        )
                    )
                }
            }
        } catch (e: Exception) {}
    }

    // Process raw workouts into independent circuits and real GPS trajectories
    val parsedWorkouts = dbWorkouts.map { parseJsonRoute(it.routeGpsJson) }.filter { it.size >= 3 }
    val rawTurns = mutableListOf<GpsPoint>()

    for (pts in parsedWorkouts) {
        val sampled = mutableListOf<GpsPoint>()
        for (pt in pts) {
            if (sampled.isEmpty() || distanceBetweenMeters(sampled.last(), pt) >= 15f) {
                sampled.add(pt)
            }
        }
        for (i in 1 until sampled.size - 1) {
            val angle = calculateTurnAngleDegrees(sampled[i - 1], sampled[i], sampled[i + 1])
            if (angle >= 20.0) {
                rawTurns.add(sampled[i])
            }
        }
    }

    // Group turn points into spatial buoy clusters (15m radius)
    val buoyClusters = mutableListOf<MutableList<GpsPoint>>()
    for (turn in rawTurns) {
        var added = false
        for (cluster in buoyClusters) {
            val avgLat = cluster.map { it.latitude }.average()
            val avgLon = cluster.map { it.longitude }.average()
            val center = GpsPoint(avgLat, avgLon, 0.0, 0L)
            if (distanceBetweenMeters(turn, center) <= 15f) {
                cluster.add(turn)
                added = true
                break
            }
        }
        if (!added) {
            buoyClusters.add(mutableListOf(turn))
        }
    }

    val validBuoys = buoyClusters.filter { it.size >= 5 }.map { cluster ->
        val avgLat = cluster.map { it.latitude }.average()
        val avgLon = cluster.map { it.longitude }.average()
        GeoPoint(avgLat, avgLon)
    }

    if (validBuoys.isEmpty()) return@withContext savedCircuits

    val generatedCircuits = mutableListOf<LearnedCircuit>()
    var circuitIdCounter = 100L

    val unassignedBuoys = validBuoys.toMutableList()
    while (unassignedBuoys.isNotEmpty()) {
        val currentGroup = mutableListOf<GeoPoint>()
        currentGroup.add(unassignedBuoys.removeAt(0))

        var addedMore = true
        while (addedMore) {
            addedMore = false
            val iterator = unassignedBuoys.iterator()
            while (iterator.hasNext()) {
                val buoy = iterator.next()
                val isConnected = currentGroup.any { g ->
                    val p1 = GpsPoint(g.latitude, g.longitude, 0.0, 0L)
                    val p2 = GpsPoint(buoy.latitude, buoy.longitude, 0.0, 0L)
                    distanceBetweenMeters(p1, p2) <= 500f
                }
                if (isConnected) {
                    currentGroup.add(buoy)
                    iterator.remove()
                    addedMore = true
                }
            }
        }

        if (currentGroup.size >= 2) {
            // Find actual representative GPS track between buoys across workouts
            var bestPolyline: List<GeoPoint> = emptyList()
            for (pts in parsedWorkouts) {
                val matchingIndices = mutableListOf<Int>()
                for (b in currentGroup) {
                    val idx = pts.indexOfFirst { pt ->
                        distanceBetweenMeters(pt, GpsPoint(b.latitude, b.longitude, 0.0, 0L)) <= 30f
                    }
                    if (idx != -1) matchingIndices.add(idx)
                }
                if (matchingIndices.size >= 2) {
                    matchingIndices.sort()
                    val subTrack = pts.subList(matchingIndices.first(), matchingIndices.last() + 1)
                        .map { GeoPoint(it.latitude, it.longitude) }
                    if (subTrack.size > bestPolyline.size) {
                        bestPolyline = subTrack
                    }
                }
            }

            if (bestPolyline.isEmpty()) {
                bestPolyline = currentGroup.toList() + currentGroup.first()
            }

            val cId = circuitIdCounter++
            if (!deletedCircuitIds.contains(cId)) {
                val learned = LearnedCircuit(
                    id = cId,
                    name = "Circuito Asimilado ${generatedCircuits.size + 1} (${currentGroup.size} Boyas)",
                    startLat = currentGroup.first().latitude,
                    startLon = currentGroup.first().longitude,
                    turnLat = currentGroup.last().latitude,
                    turnLon = currentGroup.last().longitude,
                    totalLaps = 5,
                    outerPolyline = bestPolyline,
                    isDeleted = false
                )
                generatedCircuits.add(learned)
            }
        }
    }

    val combined = (savedCircuits + generatedCircuits).distinctBy { it.id }
    return@withContext combined
}

fun saveLearnedCircuits(context: Context, circuits: List<LearnedCircuit>) {
    val arr = org.json.JSONArray()
    for (c in circuits) {
        val obj = org.json.JSONObject()
        obj.put("id", c.id)
        obj.put("name", c.name)
        obj.put("startLat", c.startLat)
        obj.put("startLon", c.startLon)
        obj.put("turnLat", c.turnLat)
        obj.put("turnLon", c.turnLon)
        obj.put("totalLaps", c.totalLaps)
        obj.put("isDeleted", c.isDeleted)

        val polyArr = org.json.JSONArray()
        for (pt in c.outerPolyline) {
            val pObj = org.json.JSONObject()
            pObj.put("lat", pt.latitude)
            pObj.put("lon", pt.longitude)
            polyArr.put(pObj)
        }
        obj.put("outerPolyline", polyArr)

        arr.put(obj)
    }
    context.getSharedPreferences("learned_circuits_prefs", Context.MODE_PRIVATE)
        .edit().putString("circuits_json", arr.toString()).apply()
}

private fun String?.isNull_or_empty(): Boolean = this == null || this.isEmpty()

@Composable
fun CircuitsScreen() {
    val context = LocalContext.current
    val db = remember { KayakDatabase.getInstance(context) }
    val workoutList by db.workoutDao().getAllWorkouts().collectAsState(initial = emptyList())
    var circuits by remember { mutableStateOf<List<LearnedCircuit>>(emptyList()) }

    LaunchedEffect(workoutList) {
        circuits = getLearnedCircuitsAsync(context, workoutList)
    }

    val listState = rememberScalingLazyListState()
    var previewCircuit by remember { mutableStateOf<LearnedCircuit?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(top = 20.dp, bottom = 12.dp)
    ) {
        if (previewCircuit != null) {
            val c = previewCircuit!!
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(15.5)
                        }
                    },
                    update = { mapView ->
                        mapView.overlays.clear()
                        val startGeo = GeoPoint(c.startLat, c.startLon)
                        val turnGeo = GeoPoint(c.turnLat, c.turnLon)

                        val greenPolyline = Polyline().apply {
                            setPoints(listOf(startGeo, turnGeo))
                            outlinePaint.color = android.graphics.Color.GREEN
                            outlinePaint.strokeWidth = 10f
                        }
                        mapView.overlays.add(greenPolyline)

                        val pinkMarker = Marker(mapView).apply {
                            position = turnGeo
                            title = "Giro Habitual"
                            icon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_compass)
                        }
                        mapView.overlays.add(pinkMarker)

                        mapView.controller.setCenter(startGeo)
                        mapView.invalidate()
                    },
                    modifier = Modifier.fillMaxSize()
                )

                Button(
                    onClick = { previewCircuit = null },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xCC000000)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = "Cerrar vista previa" }
                ) {
                    Text("X", color = Color.Yellow, fontWeight = FontWeight.Bold)
                }
            }
        } else if (circuits.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Sin Recorridos",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                Text(
                    text = "Completa 5 vueltas para asimilar",
                    fontSize = 10.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Text(
                        text = "RECORRIDOS ASIMILADOS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Yellow,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                items(circuits) { circuit ->
                    Card(
                        onClick = { previewCircuit = circuit },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text(
                                text = circuit.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Green
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Vueltas aprendidas: ${circuit.totalLaps}",
                                    fontSize = 10.sp,
                                    color = Color.Magenta
                                )

                                Button(
                                    onClick = {
                                        val updated = circuits.filter { it.id != circuit.id }
                                        circuits = updated
                                        saveLearnedCircuits(context, updated)
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFD50000)),
                                    modifier = Modifier
                                        .size(22.dp)
                                        .semantics { contentDescription = "Eliminar recorrido" }
                                ) {
                                    Text("X", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    hrManager: HeartRateManager,
    hrState: com.openkayak.app.ble.BleHeartRateState,
    mapDownloader: MapTileDownloader,
    downloadState: com.openkayak.app.service.DownloadState,
    healthConnectManager: HealthConnectManager
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("user_profile", Context.MODE_PRIVATE) }

    var age by remember { mutableStateOf(prefs.getInt("age", 30)) }
    var weightKg by remember { mutableStateOf(prefs.getFloat("weight", 75f)) }
    var heightCm by remember { mutableStateOf(prefs.getInt("height", 175)) }
    var isMale by remember { mutableStateOf(prefs.getBoolean("is_male", true)) }

    fun saveProfile() {
        prefs.edit()
            .putInt("age", age)
            .putFloat("weight", weightKg)
            .putInt("height", heightCm)
            .putBoolean("is_male", isMale)
            .apply()
    }

    val listState = rememberScalingLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(top = 20.dp, bottom = 12.dp)
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Text(
                    text = "AJUSTES DE SENSORES",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Yellow,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            item {
                Card(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "PERFIL DE PALADOR",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Cyan
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Edad: $age añ.", fontSize = 10.sp)
                            Row {
                                Button(
                                    onClick = { if (age > 10) { age--; saveProfile() } },
                                    modifier = Modifier.size(24.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                                ) { Text("-", fontSize = 10.sp) }
                                Spacer(modifier = Modifier.width(4.dp))
                                Button(
                                    onClick = { if (age < 99) { age++; saveProfile() } },
                                    modifier = Modifier.size(24.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                                ) { Text("+", fontSize = 10.sp) }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Peso: ${weightKg.toInt()} kg", fontSize = 10.sp)
                            Row {
                                Button(
                                    onClick = { if (weightKg > 30) { weightKg -= 1f; saveProfile() } },
                                    modifier = Modifier.size(24.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                                ) { Text("-", fontSize = 10.sp) }
                                Spacer(modifier = Modifier.width(4.dp))
                                Button(
                                    onClick = { if (weightKg < 200) { weightKg += 1f; saveProfile() } },
                                    modifier = Modifier.size(24.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                                ) { Text("+", fontSize = 10.sp) }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Altura: $heightCm cm", fontSize = 10.sp)
                            Row {
                                Button(
                                    onClick = { if (heightCm > 120) { heightCm--; saveProfile() } },
                                    modifier = Modifier.size(24.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                                ) { Text("-", fontSize = 10.sp) }
                                Spacer(modifier = Modifier.width(4.dp))
                                Button(
                                    onClick = { if (heightCm < 230) { heightCm++; saveProfile() } },
                                    modifier = Modifier.size(24.dp),
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray)
                                ) { Text("+", fontSize = 10.sp) }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = { isMale = !isMale; saveProfile() },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                        ) {
                            Text("Sexo: ${if (isMale) "Hombre" else "Mujer"}", fontSize = 10.sp)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
            }

            item {
                Card(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Banda Torácica BLE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Polar H7 / Estándar 0x180D",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        if (hrState.preferredDeviceAddress != null) {
                            Text(
                                text = "Preferido: ${hrState.preferredDeviceAddress}",
                                fontSize = 9.sp,
                                color = Color.Cyan
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = when (hrState.connectionState) {
                                BleConnectionState.DISCONNECTED -> "Estado: Desconectado"
                                BleConnectionState.SCANNING -> "Buscando banda BLE..."
                                BleConnectionState.CONNECTING -> "Conectando..."
                                BleConnectionState.CONNECTED -> "Conectado: ${hrState.deviceName ?: "Polar"}"
                            },
                            fontSize = 11.sp,
                            color = if (hrState.connectionState == BleConnectionState.CONNECTED) Color.Green else Color.White
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        if (hrState.connectionState == BleConnectionState.DISCONNECTED) {
                            Button(
                                onClick = { hrManager.connectLastDevice() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00C853)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(30.dp)
                            ) {
                                Text("Conectar Última Banda", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = { hrManager.startScanAndConnect() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF29B6F6)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(30.dp)
                            ) {
                                Text("Buscar Nueva Banda", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { hrManager.disconnect() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE53935)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(30.dp)
                            ) {
                                Text("Desconectar BLE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (hrState.preferredDeviceAddress != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Button(
                                onClick = { hrManager.clearPreferredDevice() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                            ) {
                                Text("Olvidar Sensor Preferido", fontSize = 9.sp)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Mapa Offline Asturias",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Yellow
                        )
                        Text(
                            text = "Descarga solo con Bluetooth",
                            fontSize = 9.sp,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = downloadState.statusMessage,
                            fontSize = 10.sp,
                            color = if (downloadState.isDownloading) Color.Cyan else Color.White,
                            textAlign = TextAlign.Center
                        )

                        if (downloadState.isDownloading) {
                            Spacer(modifier = Modifier.height(4.dp))
                            CircularProgressIndicator(
                                progress = downloadState.progressPercent / 100f,
                                modifier = Modifier.size(24.dp),
                                indicatorColor = Color.Yellow,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = { mapDownloader.downloadAsturiasOfflineMap() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00E676)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                            ) {
                                Text("Descargar Asturias (BT)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "OpenKayak Wear OS 5 v1.0",
                    fontSize = 10.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun AmbientModeScreen(
    workoutState: WorkoutState,
    hrBpm: Int,
    locationService: LocationService?,
    onExitAmbient: () -> Unit
) {
    val trackPoints = locationService?.getTrackPoints() ?: emptyList()
    val activePoint = workoutState.currentPoint ?: trackPoints.lastOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, bottom = 4.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = formatTime(workoutState.elapsedTimeSeconds),
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            if (workoutState.lapCount > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "V:${workoutState.lapCount}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Yellow,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF333300))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "KM/H", fontSize = 8.sp, color = Color.Gray)
                Text(
                    text = String.format("%.1f", workoutState.speedKmh),
                    fontSize = 15.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "PALADAS", fontSize = 8.sp, color = Color.Gray)
                Text(
                    text = "${workoutState.strokeRateSpm}",
                    fontSize = 15.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "FC", fontSize = 8.sp, color = Color.Gray)
                Text(
                    text = if (hrBpm > 0) "$hrBpm" else "--",
                    fontSize = 15.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        val context = LocalContext.current
        val db = remember { KayakDatabase.getInstance(context) }
        val workoutList by db.workoutDao().getAllWorkouts().collectAsState(initial = emptyList())
        var learnedCircuits by remember { mutableStateOf<List<LearnedCircuit>>(emptyList()) }

        LaunchedEffect(workoutList) {
            learnedCircuits = getLearnedCircuitsAsync(context, workoutList)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF111111))
        ) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(false)
                        controller.setZoom(16.5)
                        if (activePoint != null) {
                            controller.setCenter(GeoPoint(activePoint.latitude, activePoint.longitude))
                        } else {
                            controller.setCenter(GeoPoint(43.3614, -5.8593))
                        }
                    }
                },
                update = { mapView ->
                    mapView.overlays.clear()

                    for (circuit in learnedCircuits) {
                        val turnGeo = GeoPoint(circuit.turnLat, circuit.turnLon)
                        val pathPts = if (circuit.outerPolyline.isNotEmpty()) circuit.outerPolyline else listOf(GeoPoint(circuit.startLat, circuit.startLon), turnGeo)

                        val greenPolyline = Polyline().apply {
                            setPoints(pathPts)
                            outlinePaint.color = android.graphics.Color.GREEN
                            outlinePaint.strokeWidth = 6f
                        }
                        mapView.overlays.add(greenPolyline)

                        val pinkMarker = Marker(mapView).apply {
                            position = turnGeo
                            title = "Boya Giro"
                        }
                        mapView.overlays.add(pinkMarker)
                    }

                    val points = trackPoints.map { GeoPoint(it.latitude, it.longitude) }
                    if (points.isNotEmpty()) {
                        val polyline = Polyline().apply {
                            setPoints(points)
                            outlinePaint.color = android.graphics.Color.RED
                            outlinePaint.strokeWidth = 6f
                        }
                        mapView.overlays.add(polyline)
                    }

                    if (activePoint != null) {
                        val currentMarker = Marker(mapView).apply {
                            position = GeoPoint(activePoint.latitude, activePoint.longitude)
                            title = "Posición"
                        }
                        mapView.overlays.add(currentMarker)
                        mapView.controller.setCenter(GeoPoint(activePoint.latitude, activePoint.longitude))
                    }

                    mapView.invalidate()
                },
                modifier = Modifier.fillMaxSize()
            )

            // Faded edge vignette overlay for smooth blending with ambient screen background
            Canvas(modifier = Modifier.fillMaxSize()) {
                val edgeWidth = 18.dp.toPx()
                // Top edge fade
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = 0f,
                        endY = edgeWidth
                    )
                )
                // Bottom edge fade
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = size.height - edgeWidth,
                        endY = size.height
                    )
                )
                // Left edge fade
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startX = 0f,
                        endX = edgeWidth
                    )
                )
                // Right edge fade
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startX = size.width - edgeWidth,
                        endX = size.width
                    )
                )
            }
        }
        }

        // Green 'X' Exit AOD Button in top-right corner
        Button(
            onClick = onExitAmbient,
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF00E676)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .size(32.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Salir del modo ambiente" }
        ) {
            Text(
                text = "X",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.Black
            )
        }
    }
}

private fun formatTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
