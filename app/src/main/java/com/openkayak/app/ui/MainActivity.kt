package com.openkayak.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.openkayak.app.data.KayakDatabase
import com.openkayak.app.data.WorkoutEntity
import com.openkayak.app.service.GpsPoint
import com.openkayak.app.service.LocationService
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

    // Reactive Compose state for async service binding
    private val locationServiceState = mutableStateOf<LocationService?>(null)
    private var isBound = false
    private lateinit var hrManager: HeartRateManager

    private val isAmbientMode = mutableStateOf(false)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbientMode.value = true
        }

        override fun onExitAmbient() {
            isAmbientMode.value = false
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

        hrManager = HeartRateManager(this)

        val intent = Intent(this, LocationService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            val activeService = locationServiceState.value
            OpenKayakApp(
                locationService = activeService,
                hrManager = hrManager,
                isAmbient = isAmbientMode.value,
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

                    val entity = WorkoutEntity(
                        timestamp = System.currentTimeMillis(),
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
        val minutes = durationSec / 60f
        val bpm = if (avgBpm > 0) avgBpm else 120
        val caloriesPerMin = (bpm * 0.07f) + 3.0f
        return (minutes * caloriesPerMin).toInt()
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun OpenKayakApp(
    locationService: LocationService?,
    hrManager: HeartRateManager,
    isAmbient: Boolean,
    onStartWorkout: () -> Unit,
    onPauseWorkout: () -> Unit,
    onResumeWorkout: () -> Unit,
    onStopWorkout: (WorkoutState, Int) -> Unit
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
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

    val workoutState by (locationService?.workoutState ?: MutableStateFlow(WorkoutState())).collectAsState()
    val hrState by hrManager.hrState.collectAsState()

    var isWaterTouchLocked by remember { mutableStateOf(false) }
    var unlockTimeRemainingSeconds by remember { mutableStateOf(0) }

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

    // Standard PagerState initializer with pageCount lambda
    val pagerState = rememberPagerState(initialPage = 0) { 4 }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val pageIndicatorState = remember(pagerState) {
        object : PageIndicatorState {
            override val pageCount: Int get() = 4
            override val pageOffset: Float get() = 0f
            override val selectedPage: Int get() = pagerState.currentPage
        }
    }

    MaterialTheme {
        Scaffold(
            timeText = { if (!isAmbient) TimeText() }
        ) {
            if (isAmbient) {
                AmbientModeScreen(workoutState = workoutState, hrBpm = hrState.heartRateBpm)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .focusRequester(focusRequester)
                        .focusable()
                        .onRotaryScrollEvent { event ->
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
                            1 -> MapScreen(workoutState = workoutState, locationService = locationService)
                            2 -> HistoryScreen()
                            3 -> SettingsScreen(hrManager = hrManager, hrState = hrState)
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
                                isWaterTouchLocked = false
                                unlockTimeRemainingSeconds = 60
                            }
                        )
                    }
                }
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
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start")
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
                                contentDescription = "PauseResume"
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

        AndroidView(
            factory = { context ->
                android.view.View(context).apply {
                    setOnTouchListener { _, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                isPressing = true
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isPressing = false
                                true
                            }
                            else -> false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

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

@Composable
fun MapScreen(
    workoutState: WorkoutState,
    locationService: LocationService?
) {
    val trackPoints = locationService?.getTrackPoints() ?: emptyList()

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

                    // Allow horizontal page swiping when touch is near edges
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }

                    if (trackPoints.isNotEmpty()) {
                        val last = trackPoints.last()
                        controller.setCenter(GeoPoint(last.latitude, last.longitude))
                    } else {
                        controller.setCenter(GeoPoint(40.416775, -3.703790))
                    }
                }
            },
            update = { mapView ->
                mapView.overlays.clear()
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

                    val currentMarker = Marker(mapView).apply {
                        position = points.last()
                        title = "Posición Actual"
                    }
                    mapView.overlays.add(currentMarker)

                    mapView.controller.animateTo(points.last())
                }
                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

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
    }
}

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val db = remember { KayakDatabase.getInstance(context) }
    val workoutList by db.workoutDao().getAllWorkouts().collectAsState(initial = emptyList())
    val listState = rememberScalingLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(top = 20.dp, bottom = 12.dp)
    ) {
        if (workoutList.isEmpty()) {
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
                        onClick = {},
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
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Paladas: ${item.totalStrokes}",
                                    fontSize = 10.sp,
                                    color = Color(0xFFFF5722)
                                )
                                Text(
                                    text = "${item.estimatedCalories} kcal",
                                    fontSize = 10.sp,
                                    color = Color.Magenta
                                )
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
    hrState: com.openkayak.app.ble.BleHeartRateState
) {
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
                                onClick = { hrManager.startScanAndConnect() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF29B6F6)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                            ) {
                                Text("Escanear y Conectar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = { hrManager.disconnect() },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE53935)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                            ) {
                                Text("Desconectar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
    hrBpm: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = formatTime(workoutState.elapsedTimeSeconds),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "KM/H", fontSize = 9.sp, color = Color.Gray)
                Text(
                    text = String.format("%.1f", workoutState.speedKmh),
                    fontSize = 18.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "PALADAS", fontSize = 9.sp, color = Color.Gray)
                Text(
                    text = "${workoutState.strokeRateSpm}",
                    fontSize = 18.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "BPM", fontSize = 9.sp, color = Color.Gray)
                Text(
                    text = if (hrBpm > 0) "$hrBpm" else "--",
                    fontSize = 18.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
