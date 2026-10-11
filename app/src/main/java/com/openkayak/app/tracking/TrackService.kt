package com.openkayak.app.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.openkayak.app.MainActivity
import com.openkayak.app.health.SyncWorker
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TrackService : Service(), LocationListener, SensorEventListener {
    companion object {
        const val ACTION_START = "com.openkayak.app.START"
        const val ACTION_PAUSE = "com.openkayak.app.PAUSE"
        const val ACTION_RESUME = "com.openkayak.app.RESUME"
        const val ACTION_STOP = "com.openkayak.app.STOP"
        private const val CHANNEL = "openkayak_tracking"
        private const val NOTIFICATION_ID = 201
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var gpsRegistered = false
    private var sensorRegistered = false
    private var sensor: Sensor? = null
    private var usesLinearAcceleration = false
    private val paddleDetector = PaddleDetector()
    private var elapsedBase = 0L
    private var segmentStartedElapsed = 0L
    private var ticker: Job? = null
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var lastTurnLocation: Location? = null
    private var previousTurnBearing: Float? = null
    private var lastAutoTurnElapsed = 0L
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
            if (used == 0) TrackStore.setGpsStatus(this@TrackService, "Buscando satélites · ${status.satelliteCount} visibles")
            else TrackStore.setGpsStatus(this@TrackService, "Satélites en uso: $used")
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        usesLinearAcceleration = sensor != null
        if (sensor == null) sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureForeground()
                val current = TrackStore.snapshot(this)
                if (!current.active) {
                    TrackStore.start(this)
                    elapsedBase = 0L
                    paddleDetector.reset()
                    lastTurnLocation = null
                    previousTurnBearing = null
                    lastAutoTurnElapsed = 0L
                    gravityX = 0f; gravityY = 0f; gravityZ = 0f
                    segmentStartedElapsed = SystemClock.elapsedRealtime()
                } else if (current.paused) {
                    resumeSession()
                } else {
                    elapsedBase = current.elapsedMs
                    segmentStartedElapsed = SystemClock.elapsedRealtime()
                }
                registerGps()
                registerSensor()
                startTicker()
            }
            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> finishSession()
            else -> {
                val current = TrackStore.snapshot(this)
                if (current.active) {
                    ensureForeground()
                    elapsedBase = current.elapsedMs
                    segmentStartedElapsed = SystemClock.elapsedRealtime()
                    if (!current.paused) {
                        registerGps()
                        registerSensor()
                    }
                    startTicker()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerGps() {
        if (gpsRegistered) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            TrackStore.setGpsStatus(this, "Permiso de ubicación denegado")
            return
        }
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                TrackStore.setGpsStatus(this, "Activa la ubicación del reloj")
            } else {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
                runCatching { locationManager.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper())) }
                gpsRegistered = true
                TrackStore.setGpsStatus(this, "Buscando satélites; sal al exterior para fijar señal")
            }
        } catch (_: SecurityException) {
            TrackStore.setGpsStatus(this, "Permiso de ubicación denegado")
        } catch (_: IllegalArgumentException) {
            TrackStore.setGpsStatus(this, "GPS no disponible")
        }
    }

    private fun unregisterGps() {
        if (!gpsRegistered) return
        runCatching { locationManager.removeUpdates(this) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        gpsRegistered = false
    }

    private fun registerSensor() {
        if (sensorRegistered) return
        val selected = sensor ?: return
        sensorRegistered = sensorManager.registerListener(this, selected, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregisterSensor() {
        if (!sensorRegistered) return
        sensorManager.unregisterListener(this)
        sensorRegistered = false
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = serviceScope.launch {
            while (isActive) {
                val paused = TrackStore.snapshot(this@TrackService).paused
                val elapsed = if (paused) elapsedBase else
                    elapsedBase + (SystemClock.elapsedRealtime() - segmentStartedElapsed).coerceAtLeast(0L)
                TrackStore.setElapsed(this@TrackService, elapsed)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification())
                delay(1000L)
            }
        }
    }

    private fun pauseSession() {
        val current = TrackStore.snapshot(this)
        if (!current.active || current.paused) return
        elapsedBase += (SystemClock.elapsedRealtime() - segmentStartedElapsed).coerceAtLeast(0L)
        TrackStore.setElapsed(this, elapsedBase)
        TrackStore.setPaused(this, true)
        unregisterGps()
        unregisterSensor()
        startTicker()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun resumeSession() {
        val current = TrackStore.snapshot(this)
        if (!current.active || !current.paused) return
        TrackStore.setPaused(this, false)
        segmentStartedElapsed = SystemClock.elapsedRealtime()
        registerGps()
        registerSensor()
        startTicker()
    }

    private fun finishSession() {
        val current = TrackStore.snapshot(this)
        if (current.active) {
            val elapsed = if (current.paused) elapsedBase else
                elapsedBase + (SystemClock.elapsedRealtime() - segmentStartedElapsed).coerceAtLeast(0L)
            TrackStore.finish(this, elapsed)
            unregisterGps()
            unregisterSensor()
            ticker?.cancel()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(SyncWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Entrenamiento OpenKayak", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Seguimiento GPS de piragüismo durante la sesión" }
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snap = TrackStore.snapshot(this)
        val line = if (snap.paused) "Entrenamiento en pausa" else
            String.format(Locale.getDefault(), "%.2f km · %.1f km/h", snap.distanceM / 1000.0, snap.speedKmh)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("OpenKayak · Piragüismo")
            .setContentText(line)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(snap.active)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onLocationChanged(location: Location) {
        if (TrackStore.recordLocation(this, location)) {
            detectAutomaticTurn(location)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun detectAutomaticTurn(location: Location) {
        val snapshot = TrackStore.snapshot(this)
        if ((location.hasAccuracy() && location.accuracy > 20f) || snapshot.speedKmh < 2.5) {
            lastTurnLocation = location
            previousTurnBearing = null
            return
        }
        val previous = lastTurnLocation
        if (previous != null) {
            val distance = previous.distanceTo(location)
            if (distance >= 8f) {
                val bearing = previous.bearingTo(location)
                val oldBearing = previousTurnBearing
                if (oldBearing != null) {
                    val delta = kotlin.math.abs(((bearing - oldBearing + 540f) % 360f) - 180f)
                    val now = SystemClock.elapsedRealtime()
                    if (delta >= 55f && now - lastAutoTurnElapsed >= 45_000L) {
                        if (BuoyStore.saveAutomaticTurn(this, previous.latitude, previous.longitude)) {
                            lastAutoTurnElapsed = now
                        }
                    }
                }
                previousTurnBearing = bearing
                lastTurnLocation = location
            }
        } else {
            lastTurnLocation = location
        }
    }

    override fun onProviderEnabled(provider: String) {
        TrackStore.setGpsStatus(this, "Buscando satélites")
        registerGps()
    }

    override fun onProviderDisabled(provider: String) {
        TrackStore.setGpsStatus(this, "GPS desactivado")
        unregisterGps()
    }

    @Deprecated("Kept for LocationListener compatibility")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != sensor?.type) return
        val state = TrackStore.snapshot(this)
        if (!state.active || state.paused) return
        // A deck-mounted watch vibrates even when the kayak is still. Never count strokes
        // unless GPS confirms sustained movement; reset the detector while stationary.
        if (state.speedKmh < 2.5) {
            paddleDetector.reset()
            return
        }
        val v = event.values
        val x: Float
        val y: Float
        val z: Float
        if (usesLinearAcceleration) {
            x = v[0]; y = v[1]; z = v[2]
        } else {
            val alpha = 0.82f
            gravityX = alpha * gravityX + (1f - alpha) * v[0]
            gravityY = alpha * gravityY + (1f - alpha) * v[1]
            gravityZ = alpha * gravityZ + (1f - alpha) * v[2]
            x = v[0] - gravityX; y = v[1] - gravityY; z = v[2] - gravityZ
        }
        if (paddleDetector.onSample(x, y, z, SystemClock.elapsedRealtime())) TrackStore.recordStroke(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ticker?.cancel()
        serviceScope.cancel()
        unregisterGps()
        unregisterSensor()
        super.onDestroy()
    }
}
