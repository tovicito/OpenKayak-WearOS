package com.openkayak.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.openkayak.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GpsPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: Long
)

data class WorkoutState(
    val isTracking: Boolean = false,
    val isPaused: Boolean = false,
    val currentPoint: GpsPoint? = null,
    val speedKmh: Float = 0.0f,
    val maxSpeedKmh: Float = 0.0f,
    val distanceMeters: Float = 0.0f,
    val elapsedTimeSeconds: Long = 0L,
    val strokeRateSpm: Int = 0,
    val totalStrokes: Int = 0,
    val lapCount: Int = 0
)

class LocationService : Service() {

    private val binder = LocalBinder()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var strokeDetector: StrokeDetector? = null

    private var serviceJob = Job()
    private var serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var timerJob: Job? = null
    private var strokeCollectorJob: Job? = null

    private val locationHistory = mutableListOf<GpsPoint>()

    private val speedWindow = ArrayDeque<Float>(5)
    private var lastLocation: Location? = null

    private var lapOriginPoint: Location? = null
    private var lastLapTimestamp: Long = 0L
    private var isLapArmed: Boolean = false

    private val realtimeTrackPoints = mutableListOf<Location>()
    private var lastSampledLocation: Location? = null

    private fun processRealtimeTurnDetection(location: Location) {
        if (lastSampledLocation == null || lastSampledLocation!!.distanceTo(location) >= 15f) {
            lastSampledLocation = location
            realtimeTrackPoints.add(location)

            val size = realtimeTrackPoints.size
            if (size >= 3) {
                val p1 = realtimeTrackPoints[size - 3]
                val p2 = realtimeTrackPoints[size - 2]
                val p3 = realtimeTrackPoints[size - 1]

                val b1 = p1.bearingTo(p2)
                val b2 = p2.bearingTo(p3)
                var diff = Math.abs(b2 - b1)
                if (diff > 180f) diff = 360f - diff

                if (diff >= 20f) {
                    saveRealtimeTurnPoint(p2, realtimeTrackPoints)
                }
            }
        }
    }

    private fun saveRealtimeTurnPoint(turnLocation: Location, track: List<Location>) {
        try {
            val prefs = getSharedPreferences("learned_circuits_prefs", Context.MODE_PRIVATE)
            val customJson = prefs.getString("circuits_json", null)
            val jsonArray = if (!customJson.isNullOrEmpty()) org.json.JSONArray(customJson) else org.json.JSONArray()

            val turnLat = turnLocation.latitude
            val turnLon = turnLocation.longitude

            var matchedIndex = -1
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val tLat = obj.getDouble("turnLat")
                val tLon = obj.getDouble("turnLon")
                val results = FloatArray(1)
                Location.distanceBetween(turnLat, turnLon, tLat, tLon, results)
                if (results[0] <= 15f) {
                    matchedIndex = i
                    break
                }
            }

            if (matchedIndex != -1) {
                val obj = jsonArray.getJSONObject(matchedIndex)
                val laps = obj.getInt("totalLaps") + 1
                obj.put("totalLaps", laps)
                jsonArray.put(matchedIndex, obj)
            } else {
                val newObj = org.json.JSONObject()
                newObj.put("id", System.currentTimeMillis())
                newObj.put("name", "Boya Giro (${String.format("%.4f", turnLat)}, ${String.format("%.4f", turnLon)})")
                newObj.put("startLat", track.firstOrNull()?.latitude ?: turnLat)
                newObj.put("startLon", track.firstOrNull()?.longitude ?: turnLon)
                newObj.put("turnLat", turnLat)
                newObj.put("turnLon", turnLon)
                newObj.put("totalLaps", 1)

                val polyArr = org.json.JSONArray()
                for (loc in track.takeLast(20)) {
                    val pObj = org.json.JSONObject()
                    pObj.put("lat", loc.latitude)
                    pObj.put("lon", loc.longitude)
                    polyArr.put(pObj)
                }
                newObj.put("outerPolyline", polyArr)
                jsonArray.put(newObj)
            }

            prefs.edit().putString("circuits_json", jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveRealtimeTurnPoint error: ${e.localizedMessage}")
        }
    }

    private var toneGenerator: ToneGenerator? = null

    private val _workoutState = MutableStateFlow(WorkoutState())
    val workoutState: StateFlow<WorkoutState> = _workoutState.asStateFlow()

    fun getTrackPoints(): List<GpsPoint> = synchronized(locationHistory) { locationHistory.toList() }

    inner class LocalBinder : Binder() {
        fun getService(): LocationService = this@LocationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        try {
            strokeDetector = StrokeDetector(this)
        } catch (e: Exception) {
            Log.e(TAG, "StrokeDetector init warning: ${e.localizedMessage}")
            strokeDetector = null
        }

        createNotificationChannel()

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: Exception) {
            toneGenerator = null
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    processNewLocation(location)
                }
            }
        }

        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                playBeepStart()
                startWorkout()
            }
            ACTION_PAUSE -> {
                playBeepPause()
                pauseWorkout()
            }
            ACTION_RESUME -> {
                playBeepPause()
                resumeWorkout()
            }
            ACTION_STOP -> {
                playBeepStop()
                stopWorkout()
            }
        }
        return START_STICKY
    }

    private fun playBeepStart() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {}
    }

    private fun playBeepPause() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
        } catch (e: Exception) {}
    }

    private fun playBeepStop() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 300)
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenKayak Training",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Muestra el estado del entrenamiento de kayak en tiempo real."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val currentState = _workoutState.value
        val speedStr = String.format("%.1f km/h", currentState.speedKmh)
        val distStr = String.format("%.2f km", currentState.distanceMeters / 1000f)
        val spmStr = "${currentState.strokeRateSpm} SPM"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kayak Activo - OpenKayak")
            .setContentText("Vel: $speedStr | Dist: $distStr | SPM: $spmStr")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun startWorkout() {
        if (_workoutState.value.isTracking) return

        if (serviceJob.isCancelled) {
            serviceJob = Job()
            serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
        }

        synchronized(locationHistory) { locationHistory.clear() }
        speedWindow.clear()
        lastLocation = null
        lapOriginPoint = null
        lastLapTimestamp = 0L
        isLapArmed = false

        _workoutState.update { current ->
            WorkoutState(
                isTracking = true,
                isPaused = false,
                currentPoint = current.currentPoint,
                speedKmh = 0.0f,
                maxSpeedKmh = 0.0f,
                distanceMeters = 0.0f,
                elapsedTimeSeconds = 0L,
                strokeRateSpm = 0,
                totalStrokes = 0,
                lapCount = 0
            )
        }

        startForegroundServiceInternal()
        startLocationUpdates()
        startTimer()

        try {
            strokeDetector?.start()
            observeStrokes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting stroke detector: ${e.localizedMessage}")
        }
    }

    private fun observeStrokes() {
        val detector = strokeDetector ?: return
        strokeCollectorJob?.cancel()
        strokeCollectorJob = serviceScope.launch {
            detector.strokeState.collectLatest { strokeState ->
                _workoutState.update { current ->
                    current.copy(
                        strokeRateSpm = strokeState.strokeRateSpm,
                        totalStrokes = strokeState.totalStrokes
                    )
                }
            }
        }
    }

    private fun startForegroundServiceInternal() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                Log.w(TAG, "Foreground HEALTH type fallback: ${e.localizedMessage}")
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1000L
            ).apply {
                setMinUpdateIntervalMillis(1000L)
                setWaitForAccurateLocation(false)
            }.build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed requesting location updates: ${e.localizedMessage}")
        }
    }

    private fun processNewLocation(location: Location) {
        val gpsPoint = GpsPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            timestamp = location.time
        )

        _workoutState.update { it.copy(currentPoint = gpsPoint) }

        if (!_workoutState.value.isTracking || _workoutState.value.isPaused) return

        if (location.hasAccuracy() && location.accuracy > 30f) return

        var rawSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        var addedDistance = 0f

        lastLocation?.let { prev ->
            val dist = prev.distanceTo(location)
            if (dist >= 0.7f && dist < 100f) {
                addedDistance = dist
                val timeDiffSec = (location.time - prev.time) / 1000f
                if (timeDiffSec > 0f && !location.hasSpeed()) {
                    rawSpeedKmh = (dist / timeDiffSec) * 3.6f
                }
            } else if (dist >= 100f) {
                Log.w(TAG, "Ignoring unrealistic GPS jump of $dist meters for distance accumulation")
            }
        }
        lastLocation = location

        if (speedWindow.size >= 5) {
            speedWindow.removeFirst()
        }
        speedWindow.addLast(rawSpeedKmh)
        val smoothedSpeedKmh = speedWindow.average().toFloat()

        synchronized(locationHistory) {
            locationHistory.add(gpsPoint)
        }

        strokeDetector?.currentSpeedKmh = smoothedSpeedKmh

        // Real-Time Background Turn & Circuit Learning Engine
        processRealtimeTurnDetection(location)

        // Automatic Lap Detection Logic
        if (lapOriginPoint == null) {
            lapOriginPoint = location
        } else {
            val distToOrigin = location.distanceTo(lapOriginPoint!!)
            val now = System.currentTimeMillis()

            if (!isLapArmed && distToOrigin > 60f) {
                isLapArmed = true
            } else if (isLapArmed && distToOrigin <= 25f && (now - lastLapTimestamp) > 40000L) {
                lastLapTimestamp = now
                isLapArmed = false
                playBeepStart()
                _workoutState.update { it.copy(lapCount = it.lapCount + 1) }
            }
        }

        _workoutState.update { current ->
            val newDist = current.distanceMeters + addedDistance
            val newMax = maxOf(current.maxSpeedKmh, smoothedSpeedKmh)
            current.copy(
                speedKmh = smoothedSpeedKmh,
                maxSpeedKmh = newMax,
                distanceMeters = newDist
            )
        }
    }

    private fun pauseWorkout() {
        if (!_workoutState.value.isTracking || _workoutState.value.isPaused) return
        _workoutState.update { it.copy(isPaused = true) }
        try {
            strokeDetector?.stop()
        } catch (e: Exception) {}
        timerJob?.cancel()
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun resumeWorkout() {
        if (!_workoutState.value.isTracking || !_workoutState.value.isPaused) return
        _workoutState.update { it.copy(isPaused = false) }
        try {
            strokeDetector?.start()
        } catch (e: Exception) {}
        startLocationUpdates()
        startTimer()
    }

    private fun stopWorkout() {
        try {
            strokeDetector?.stop()
        } catch (e: Exception) {}
        timerJob?.cancel()
        strokeCollectorJob?.cancel()
        _workoutState.update { it.copy(isTracking = false, isPaused = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (_workoutState.value.isTracking && !_workoutState.value.isPaused) {
                delay(1000L)
                _workoutState.update { current ->
                    current.copy(elapsedTimeSeconds = current.elapsedTimeSeconds + 1)
                }
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            strokeDetector?.stop()
        } catch (e: Exception) {}
        serviceScope.cancel()
        toneGenerator?.release()
        toneGenerator = null
    }

    companion object {
        private const val TAG = "LocationService"
        const val CHANNEL_ID = "openkayak_location_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.openkayak.app.ACTION_START"
        const val ACTION_PAUSE = "com.openkayak.app.ACTION_PAUSE"
        const val ACTION_RESUME = "com.openkayak.app.ACTION_RESUME"
        const val ACTION_STOP = "com.openkayak.app.ACTION_STOP"
    }
}
