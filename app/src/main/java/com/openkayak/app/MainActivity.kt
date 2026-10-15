package com.openkayak.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Text
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.openkayak.app.ble.BleCandidate
import com.openkayak.app.ble.BleHeartRateManager
import com.openkayak.app.ble.BleHrState
import com.openkayak.app.ble.BlePhase
import com.openkayak.app.health.HealthConnectSync
import com.openkayak.app.health.SyncWorker
import com.openkayak.app.tracking.BuoyStore
import com.openkayak.app.tracking.GeoSample
import com.openkayak.app.tracking.OnlineRouteMap
import com.openkayak.app.tracking.SavedBuoy
import com.openkayak.app.tracking.TrackService
import com.openkayak.app.tracking.TrackSnapshot
import com.openkayak.app.tracking.TrackStore
import com.openkayak.app.tracking.SessionReviewStore
import com.openkayak.app.tracking.FinishedSession
import com.openkayak.app.tracking.CircuitStore
import com.openkayak.app.tracking.TurnMemoryStore
import com.openkayak.app.tracking.AthleteProfileStore
import com.openkayak.app.tracking.OfflineMapStore
import java.io.File
import java.util.Locale
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Night = Color(0xFF05090D)
private val Panel = Color(0xFF101820)
private val Panel2 = Color(0xFF17232D)
private val Mint = Color(0xFF76F4D0)
private val Ice = Color(0xFF9FD9FF)
private val Quiet = Color(0xFF8D9DA8)
private val Warning = Color(0xFFFFCA7A)
private val Pink = Color(0xFFFF8FA3)

private object BleManagerHolder {
    @Volatile private var value: BleHeartRateManager? = null
    fun get(context: Context): BleHeartRateManager = value ?: synchronized(this) {
        value ?: BleHeartRateManager(context.applicationContext).also { value = it }
    }
}

class MainActivity : ComponentActivity() {
    private var ambientMode by mutableStateOf(false)
    private var ambientTick by mutableIntStateOf(0)
    private lateinit var ambientObserver: AmbientLifecycleObserver
    private var temporaryWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientObserver = AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambientMode = true
            }
            override fun onUpdateAmbient() { ambientTick++ }
            override fun onExitAmbient() {
                ambientMode = false
            }
        })
        lifecycle.addObserver(ambientObserver)
        setContent {
            WearMaterialTheme {
                OpenKayakApp(isAmbient = ambientMode, ambientTick = ambientTick, onExitAmbient = { requestInteractiveMode() })
            }
        }
    }

    private fun requestInteractiveMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        runCatching {
            val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
            temporaryWakeLock = manager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "OpenKayak:ExitAmbient"
            ).also { it.acquire(1800L) }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }, 10_000L)
    }

    override fun onDestroy() {
        if (::ambientObserver.isInitialized) lifecycle.removeObserver(ambientObserver)
        runCatching { temporaryWakeLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }
}

@Composable
private fun OpenKayakApp(isAmbient: Boolean, ambientTick: Int, onExitAmbient: () -> Unit) {
    val context = LocalContext.current
    val permissionScope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(TrackStore.snapshot(context)) }
    val hrManager = remember { BleManagerHolder.get(context) }
    val ble by hrManager.state.collectAsState()
    var page by remember { mutableIntStateOf(0) }
    var showFinishConfirm by remember { mutableStateOf(false) }
    var healthStatus by remember { mutableStateOf("Health Connect: comprobando…") }
    var mapPackStatus by remember { mutableStateOf(OfflineMapStore.status(context)) }
    var savedBuoys by remember { mutableStateOf(BuoyStore.all(context)) }
    var pendingReview by remember { mutableStateOf(SessionReviewStore.pending(context)) }
    var voiceStatus by remember { mutableStateOf("") }
    var activeRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val speechEngine = remember(context) {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { status -> if (status == TextToSpeech.SUCCESS) engine.language = Locale("es", "ES") }
        engine
    }
    DisposableEffect(speechEngine) {
        onDispose {
            runCatching { activeRecognizer?.cancel(); activeRecognizer?.destroy() }
            speechEngine.stop()
            speechEngine.shutdown()
        }
    }
    fun speak(text: String) {
        voiceStatus = text
        speechEngine.language = Locale("es", "ES")
        speechEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "openkayak-voice")
    }
    fun handleVoiceCommand(raw: String) {
        val command = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val current = TrackStore.snapshot(context)
        when {
            "distancia" in command -> speak("Llevas %.2f kilómetros.".format(Locale("es", "ES"), current.distanceM / 1000.0))
            "velocidad" in command || "a cuanto voy" in command || "voy a cuanto" in command -> {
                val value = AppSettingsStore.speedValue(context, current.speedKmh)
                speak("Tu velocidad actual es %.1f %s.".format(Locale("es", "ES"), value, AppSettingsStore.speedUnitLabel(context)))
            }
            "tiempo" in command || "cuanto llevo" in command || "duracion" in command -> speak("Llevas ${formatDuration(current.elapsedMs)}.")
            "marca una vuelta" in command || "marca vuelta" in command || "marca un giro" in command -> {
                if (!current.active || current.paused) speak("Necesitas una sesión activa y en marcha para marcar una vuelta.")
                else if (BuoyStore.saveCurrent(context, true)) { savedBuoys = BuoyStore.all(context); speak("Vuelta marcada con la posición GPS actual.") }
                else speak("Todavía no tengo una posición GPS para marcar la vuelta.")
            }
            "termina el entrenamiento" in command || "finaliza el entrenamiento" in command || "terminar entrenamiento" in command || "termina la sesion" in command -> {
                if (current.active) { showFinishConfirm = true; speak("¿Quieres terminar el entrenamiento? Confírmalo en la pantalla del reloj.") }
                else speak("No hay ningún entrenamiento activo.")
            }
            else -> speak("No he entendido el comando. Puedes preguntar por distancia, velocidad o tiempo, marcar una vuelta o pedir terminar el entrenamiento.")
        }
    }
    fun startVoiceCapture() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { speak("El reconocimiento de voz no está disponible en este reloj."); return }
        runCatching {
            val recognizer = if (android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            activeRecognizer = recognizer
            voiceStatus = "Escuchando…"
            recognizer.setRecognitionListener(object : RecognitionListener {
                private fun release() { if (activeRecognizer === recognizer) activeRecognizer = null; runCatching { recognizer.destroy() } }
                override fun onReadyForSpeech(params: Bundle?) { voiceStatus = "Te escucho…" }
                override fun onBeginningOfSpeech() { voiceStatus = "Reconociendo voz…" }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { voiceStatus = "Procesando…" }
                override fun onError(error: Int) {
                    release()
                    speak(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "No he reconocido el comando. Pulsa el botón e inténtalo de nuevo." else "No se ha podido reconocer la voz. Comprueba el permiso de micrófono y el idioma español.")
                }
                override fun onResults(results: Bundle?) {
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    release()
                    if (phrase.isNullOrBlank()) speak("No he entendido nada. Inténtalo de nuevo.") else handleVoiceCommand(phrase)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        }.onFailure { activeRecognizer = null; speak("No se ha podido iniciar el micrófono.") }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceCapture() else speak("OpenKayak necesita permiso de micrófono para los comandos de voz.")
    }
    fun activateVoiceAssistant() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceCapture()
        else microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        healthStatus = if (granted.containsAll(HealthConnectSync.permissions))
            "Health Connect: listo" else "Health Connect: faltan permisos"
    }

    val offlineMapPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) permissionScope.launch {
            mapPackStatus = "Importando mapa offline…"
            val result = withContext(Dispatchers.IO) {
                runCatching { OfflineMapStore.importMbtiles(context, uri) }
            }
            result.onSuccess {
                mapPackStatus = OfflineMapStore.status(context)
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            }.onFailure {
                mapPackStatus = it.message ?: "No se pudo importar el paquete offline."
                Toast.makeText(context, mapPackStatus, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun checkHealthPermissions() {
        permissionScope.launch {
            if (!HealthConnectSync.isAvailable(context)) {
                val reason = HealthConnectSync.unavailableMessage(context)
                healthStatus = reason
                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                return@launch
            }
            val granted = runCatching { HealthConnectSync.grantedPermissions(context) }.getOrDefault(emptySet())
            healthStatus = if (granted.containsAll(HealthConnectSync.permissions))
                "Health Connect: listo" else "Health Connect: faltan permisos"
            if (!granted.containsAll(HealthConnectSync.permissions)) {
                healthLauncher.launch(HealthConnectSync.permissions)
            }
        }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
            Toast.makeText(context, "Para medir la ruta con precisión, concede ubicación precisa.", Toast.LENGTH_LONG).show()
        }
        if (BuildCompat.bluetoothPermissionsGranted(context)) {
            hrManager.connectSavedIfPresent()
        }
        permissionScope.launch {
            delay(700L)
            checkHealthPermissions()
        }
    }

    LaunchedEffect(Unit) {
        val required = buildList {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (required.isNotEmpty()) runtimeLauncher.launch(required.toTypedArray())
        else {
            if (BuildCompat.bluetoothPermissionsGranted(context)) hrManager.connectSavedIfPresent()
            checkHealthPermissions()
        }
    }

    LaunchedEffect(ambientTick) { snapshot = TrackStore.snapshot(context) }

    LaunchedEffect(isAmbient, snapshot.active) {
        while (true) {
            snapshot = TrackStore.snapshot(context)
            savedBuoys = BuoyStore.all(context)
            val review = SessionReviewStore.pending(context)
            if (review != null && review.endAt != pendingReview?.endAt) pendingReview = review
            if (review == null) pendingReview = null
            delay(if (isAmbient) 1000L else 400L)
        }
    }

    Box(Modifier.fillMaxSize().background(if (isAmbient) Color.Black else Night)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 31.dp, end = 31.dp, top = 10.dp, bottom = 23.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!isAmbient) {
                Text("OPENKAYAK", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf("EN EL AGUA", "TRAZADO GPS", "BANDA DE PULSO", "PALADAS", "BOYAS Y RUTAS", "AJUSTES", "HISTORIAL").getOrElse(page) { "OPENKAYAK" },
                    color = Quiet, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
                Spacer(Modifier.height(5.dp))
            }

            if (isAmbient) {
                val mapPoints = if (snapshot.active) TrackStore.recentTrack(context) else CircuitStore.points(context).ifEmpty { TrackStore.recentTrack(context) }
                val mapBuoys = if (snapshot.active) TurnMemoryStore.eventsForSession(context, snapshot.startAt) else savedBuoys
                AmbientDashboard(snapshot, ble, mapPoints, mapBuoys, ambientTick)
            } else when (page) {
                0 -> DashboardPage(snapshot, ble, isAmbient,
                    onStart = { startSession(context) },
                    onPauseResume = {
                        sendServiceAction(context, if (snapshot.paused) TrackService.ACTION_RESUME else TrackService.ACTION_PAUSE)
                    },
                    onFinish = { showFinishConfirm = true },
                    onVoice = { activateVoiceAssistant() },
                    voiceStatus = voiceStatus
                )
                1 -> RoutePage(snapshot, if (snapshot.active) TrackStore.recentTrack(context) else CircuitStore.points(context).ifEmpty { TrackStore.recentTrack(context) }, savedBuoys, isAmbient)
                2 -> PulsePage(ble, isAmbient,
                    onScan = { hrManager.scan() },
                    onConnect = { hrManager.connect(it) },
                    onDisconnect = { hrManager.disconnect() })
                3 -> StrokePage(snapshot, isAmbient)
                4 -> BuoysPage(
                    snapshot = snapshot,
                    buoys = savedBuoys,
                    ambient = isAmbient,
                    onSaveTurn = {
                        if (BuoyStore.saveCurrent(context, true)) savedBuoys = BuoyStore.all(context)
                        else Toast.makeText(context, "Espera a tener una posición GPS.", Toast.LENGTH_SHORT).show()
                    },
                    onSaveRoute = {
                        if (BuoyStore.saveCurrent(context, false)) savedBuoys = BuoyStore.all(context)
                        else Toast.makeText(context, "Espera a tener una posición GPS.", Toast.LENGTH_SHORT).show()
                    },
                    onDelete = { id -> BuoyStore.remove(context, id); savedBuoys = BuoyStore.all(context) },
                    onUndo = {
                        BuoyStore.removeLast(context)
                        savedBuoys = BuoyStore.all(context)
                    }
                )
                5 -> SettingsPage(
                    context = context,
                    healthStatus = healthStatus,
                    isAmbient = isAmbient,
                    onHealthPermissions = { checkHealthPermissions() },
                    onSync = { enqueueSync(context) },
                    onExport = { exportGpx(context) },
                    mapPackStatus = mapPackStatus,
                    onImportOfflineMap = { offlineMapPicker.launch(arrayOf("*/*")) }
                )
                else -> HistoryPage(context, isAmbient)
            }
        }

        if (!isAmbient) {
            ArrowControl("‹", Modifier.align(Alignment.CenterStart).padding(start = 2.dp), false) { page = (page + 6) % 7 }
            ArrowControl("›", Modifier.align(Alignment.CenterEnd).padding(end = 2.dp), false) { page = (page + 1) % 7 }
        }

        if (showFinishConfirm && !isAmbient) {
            Box(Modifier.fillMaxSize().background(Color(0xB8000000)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 30.dp)
                        .clip(RoundedCornerShape(24.dp)).background(Panel2).padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("¿Terminar sesión?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("Se analizará el GPX para detectar giros y posibles circuitos. Después podrás guardar o borrar el resultado.", color = Quiet, fontSize = 10.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionPill("SEGUIR", Panel, Color.White, { showFinishConfirm = false }, Modifier.weight(1f))
                        ActionPill("FINALIZAR", Mint, Night, {
                            sendServiceAction(context, TrackService.ACTION_STOP)
                            showFinishConfirm = false
                        }, Modifier.weight(1f))
                    }
                }
            }
        }

        if (isAmbient) {
            ActionPill(
                "SALIR AOD",
                Color(0xFF151515),
                Color.White,
                onExitAmbient,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
            )
        } else {
            Text(
                listOf("● SESIÓN", "● GPS", "● BLE", "● PALADAS", "● BOYAS", "● AJUSTES", "● HISTORIAL").getOrElse(page) { "OPENKAYAK" },
                color = Quiet, fontSize = 8.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
            )
        }
        if (pendingReview != null && !isAmbient) {
            FinishReviewDialog(
                review = pendingReview!!,
                onSaveBoth = {
                    val value = pendingReview
                    if (value != null) {
                        SessionReviewStore.saveWorkout(context, value)
                        SessionReviewStore.saveCircuit(context, value)
                        SessionReviewStore.complete(context, value, keepWorkout = true)
                        pendingReview = null
                        enqueueSync(context)
                    }
                },
                onSaveWorkout = {
                    val value = pendingReview
                    if (value != null) {
                        SessionReviewStore.saveWorkout(context, value)
                        SessionReviewStore.complete(context, value, keepWorkout = true)
                        pendingReview = null
                        enqueueSync(context)
                    }
                },
                onSaveCircuit = {
                    val value = pendingReview
                    if (value != null) {
                        SessionReviewStore.saveCircuit(context, value)
                        SessionReviewStore.complete(context, value, keepWorkout = false)
                        pendingReview = null
                    }
                },
                onDelete = {
                    val value = pendingReview
                    if (value != null) {
                        SessionReviewStore.complete(context, value, keepWorkout = false)
                        pendingReview = null
                    }
                }
            )
        }
    }
}

private object BuildCompat {
    fun bluetoothPermissionsGranted(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}

private fun startSession(context: Context) {
    if (SessionReviewStore.pending(context) != null) {
        Toast.makeText(context, "Guarda o borra el resumen anterior antes de iniciar otro entrenamiento.", Toast.LENGTH_LONG).show()
        return
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        Toast.makeText(context, "Concede ubicación precisa y vuelve a iniciar.", Toast.LENGTH_LONG).show()
        return
    }
    sendServiceAction(context, TrackService.ACTION_START)
}

private fun sendServiceAction(context: Context, action: String) {
    val intent = Intent(context, TrackService::class.java).setAction(action)
    try {
        if (action == TrackService.ACTION_START) androidx.core.content.ContextCompat.startForegroundService(context, intent)
        else context.startService(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No se pudo controlar el entrenamiento. Revisa los permisos.", Toast.LENGTH_LONG).show()
    }
}

private fun enqueueSync(context: Context) {
    val request = OneTimeWorkRequestBuilder<SyncWorker>()
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(SyncWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    Toast.makeText(context, "Sincronización puesta en cola", Toast.LENGTH_SHORT).show()
}

private fun exportGpx(context: Context) {
    val file: File = TrackStore.currentTrackFile(context) ?: run {
        Toast.makeText(context, "Todavía no hay una ruta GPX guardada.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Compartir ruta GPX"))
    } catch (_: Exception) {
        Toast.makeText(context, "No se pudo compartir la ruta GPX.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun DashboardPage(
    s: TrackSnapshot,
    ble: BleHrState,
    ambient: Boolean,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onFinish: () -> Unit,
    onVoice: () -> Unit,
    voiceStatus: String
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!ambient) {
            Text(
                if (s.active) if (s.paused) "EN PAUSA" else "EN SESIÓN" else "LISTO PARA REMAR",
                color = if (s.paused) Warning else Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
        }
        Text("DISTANCIA", color = if (ambient) Quiet else Ice, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(String.format(Locale.getDefault(), "%.2f", s.distanceM / 1000.0),
                color = Color.White, fontSize = if (ambient) 30.sp else 32.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(3.dp))
            Text("km", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
        }
        Text(formatDuration(s.elapsedMs), color = if (ambient) Color.LightGray else Color.White,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            val context = LocalContext.current
            MetricTile("VELOCIDAD", String.format(Locale.getDefault(), "%.1f", AppSettingsStore.speedValue(context, s.speedKmh)), AppSettingsStore.speedUnitLabel(context), Mint, Modifier.weight(1f), ambient)
            MetricTile("PULSO", if (ble.isFresh) ble.bpm.toString() else "--", "lpm", Pink, Modifier.weight(1f), ambient)
            MetricTile("RITMO/KM", pacePerKm(s.elapsedMs, s.distanceM), "min/km", Ice, Modifier.weight(1f), ambient)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            StatusDot(s.gpsStatus.contains("preciso", true) || s.gpsStatus.contains("activo", true), ambient)
            Spacer(Modifier.width(4.dp))
            Text(s.gpsStatus.take(16), color = Quiet, fontSize = 7.sp, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            StatusDot(ble.isFresh, ambient)
            Spacer(Modifier.width(4.dp))
            Text(if (ble.isFresh) "BANDA BLE" else "SIN FC", color = Quiet, fontSize = 7.sp)
        }
        if (!ambient) {
            Spacer(Modifier.height(8.dp))
            if (!s.active) {
                ActionPill("INICIAR PIRAGÜISMO", Mint, Night, onStart, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionPill(if (s.paused) "REANUDAR" else "PAUSA", Panel2, Color.White, onPauseResume, Modifier.weight(1f))
                    ActionPill("FINALIZAR", Color(0xFF4B2428), Pink, onFinish, Modifier.weight(1f))
                }
                Spacer(Modifier.height(5.dp))
                ActionPill("🎙 HABLAR CON OPENKAYAK", Panel2, Ice, onVoice, Modifier.fillMaxWidth())
                if (voiceStatus.isNotBlank()) { Spacer(Modifier.height(3.dp)); Text(voiceStatus, color = Quiet, fontSize = 8.sp, textAlign = TextAlign.Center, maxLines = 2) }
            }
        }
    }
}

@Composable
private fun RoutePage(s: TrackSnapshot, points: List<GeoSample>, buoys: List<SavedBuoy>, ambient: Boolean) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("RUTA GPS", color = if (ambient) Color.LightGray else Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(22.dp))
                .background(if (ambient) Color.Black else Panel)
                .border(1.dp, if (ambient) Color(0xFF333333) else Color(0xFF273A46), RoundedCornerShape(22.dp))
        ) {
            OnlineRouteMap(
                points = points,
                buoys = buoys,
                latitude = s.latitude,
                longitude = s.longitude,
                ambient = ambient,
                followPosition = s.latitude != null && s.longitude != null
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(s.gpsStatus, color = if (ambient) Color.LightGray else Ice, fontSize = 10.sp, textAlign = TextAlign.Center)
        Text(if (s.accuracyM > 0f) String.format(Locale.getDefault(), "Precisión GPS ±%.0f m", s.accuracyM) else "Precisión GPS pendiente",
            color = Quiet, fontSize = 9.sp)
        if (s.latitude != null && s.longitude != null) {
            Spacer(Modifier.height(4.dp))
            Text(String.format(Locale.US, "%.5f, %.5f", s.latitude, s.longitude),
                color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(4.dp))
        Text("Mapa online · cian: recorrido · rosa: boyas · naranja: U-turns", color = Quiet, fontSize = 7.sp, textAlign = TextAlign.Center)
        Text("El trazado se guarda localmente; importa MBTiles o visualiza el área online para guardarla.", color = Quiet, fontSize = 7.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PulsePage(ble: BleHrState, ambient: Boolean, onScan: () -> Unit, onConnect: (String) -> Unit, onDisconnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BANDA BLUETOOTH", color = if (ambient) Color.LightGray else Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(if (ble.isFresh) ble.bpm.toString() else "--", color = if (ble.isFresh) Pink else Quiet,
            fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        Text("PULSACIONES/MIN", color = Quiet, fontSize = 8.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text(ble.detail, color = if (ble.phase == BlePhase.ERROR) Warning else Quiet, fontSize = 8.sp, textAlign = TextAlign.Center)
        if (!ambient) {
            Spacer(Modifier.height(7.dp))
            ActionPill(if (ble.phase == BlePhase.SCANNING) "BUSCANDO…" else "BUSCAR BANDA", Panel2, Color.White, onScan, Modifier.fillMaxWidth())
            if (ble.phase == BlePhase.CONNECTED || ble.phase == BlePhase.CONNECTING) {
                Spacer(Modifier.height(4.dp))
                ActionPill("DESCONECTAR", Color(0xFF45242A), Pink, onDisconnect, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(5.dp))
            ble.candidates.take(3).forEach { candidate ->
                CandidateRow(candidate) { onConnect(candidate.address) }
                Spacer(Modifier.height(3.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("La app oculta muestras antiguas en vez de presentar un pulso congelado como actual.", color = Quiet, fontSize = 8.sp, textAlign = TextAlign.Center)
        Text("El sensor óptico no sirve montado en la cubierta; utiliza una banda pectoral BLE.", color = Quiet, fontSize = 8.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StrokePage(s: TrackSnapshot, ambient: Boolean) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SENSOR DE CUBIERTA", color = if (ambient) Color.LightGray else Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(s.strokes.toString(), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
        Text("PALADAS DETECTADAS", color = Quiet, fontSize = 8.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(7.dp))
        MetricTile("RITMO ACTUAL", s.cadenceSpm.toString(), "paladas/min", Ice, Modifier.fillMaxWidth(), ambient)
        Spacer(Modifier.height(8.dp))
        Text("El algoritmo analiza picos de aceleración con periodo refractario para reducir dobles conteos.", color = Color.White,
            fontSize = 9.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(5.dp))
        Text("Oleaje, golpes y vibración pueden crear falsos positivos. Hay que calibrarlo en tu piragua; no se ha validado en agua real.",
            color = Quiet, fontSize = 8.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BuoysPage(
    snapshot: TrackSnapshot,
    buoys: List<SavedBuoy>,
    ambient: Boolean,
    onSaveTurn: () -> Unit,
    onSaveRoute: () -> Unit,
    onDelete: (Long) -> Unit,
    onUndo: () -> Unit
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BOYAS Y RUTAS", color = if (ambient) Color.LightGray else Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("${buoys.size} puntos guardados", color = Quiet, fontSize = 8.sp)
        if (!ambient) {
            Spacer(Modifier.height(5.dp))
            Text("Las boyas se infieren automáticamente del recorrido GPS al finalizar. Se guardan como circuito solo cuando lo eliges en el resumen.", color = Quiet, fontSize = 8.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(5.dp))
            buoys.takeLast(3).reversed().forEach { buoy ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(true, false)
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(when { buoy.turnType.equals("UTURN", true) -> "GIRO EN U"; buoy.turnType.equals("BUOY", true) -> "BOYA NORMAL"; buoy.isTurn -> "GIRO GPS"; else -> "PUNTO DE RUTA" }, color = when { buoy.turnType.equals("UTURN", true) -> Color(0xFFFFB54A); buoy.isTurn -> Pink; else -> Mint }, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text(String.format(Locale.US, "%.4f, %.4f", buoy.latitude, buoy.longitude), color = Quiet, fontSize = 7.sp)
                    }
                    Text("×", color = Pink, fontSize = 18.sp, modifier = Modifier.clickable { onDelete(buoy.id) }.padding(horizontal = 4.dp))
                }
                Spacer(Modifier.height(3.dp))
            }
        }
        if (snapshot.latitude == null) {
            Text("Espera a recibir una posición GPS para analizar el trazado.", color = Quiet, fontSize = 8.sp, textAlign = TextAlign.Center)
        }
        Text("Las boyas se infieren del recorrido GPS, no por visión artificial. Al guardar un circuito se agrupan sus repeticiones.", color = Quiet, fontSize = 8.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AmbientDashboard(s: TrackSnapshot, ble: BleHrState, points: List<GeoSample>, buoys: List<SavedBuoy>, ambientTick: Int) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(formatDuration(s.elapsedMs), color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        Text(if (s.active && !s.paused) "PIRAGÜISMO EN CURSO" else "CIRCUITO GUARDADO", color = Color(0xFF8D9DA8), fontSize = 7.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.size(132.dp).clip(CircleShape).background(Color.Black)) {
            OnlineRouteMap(points = points, buoys = buoys, latitude = s.latitude ?: points.lastOrNull()?.latitude,
                longitude = s.longitude ?: points.lastOrNull()?.longitude, ambient = true, allowNetwork = false, followPosition = s.active && !s.paused)
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(brush = Brush.radialGradient(
                    0.0f to Color.Transparent,
                    0.62f to Color.Transparent,
                    0.88f to Color(0xCC000000),
                    1.0f to Color.Black,
                    center = center,
                    radius = size.minDimension / 2f
                ), radius = size.minDimension / 2f)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MetricTile("FC", if (ble.isFresh) ble.bpm.toString() else "--", "lpm", Pink, Modifier.weight(1f), true)
            MetricTile("DISTANCIA", String.format(Locale.US, "%.2f", s.distanceM / 1000.0), "km", Ice, Modifier.weight(1f), true)
            MetricTile("VELOCIDAD", String.format(Locale.US, "%.1f", AppSettingsStore.speedValue(context, s.speedKmh)), AppSettingsStore.speedUnitLabel(context), Mint, Modifier.weight(1f), true)
        }
        Text("AOD · actualización solicitada cada segundo · " + s.gpsStatus, color = Color.DarkGray, fontSize = 6.sp)
    }
}

@Composable
private fun FinishReviewDialog(review: FinishedSession, onSaveBoth: () -> Unit, onSaveWorkout: () -> Unit, onSaveCircuit: () -> Unit, onDelete: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 27.dp, vertical = 10.dp).clip(RoundedCornerShape(22.dp))
            .background(Panel2).padding(10.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("RESUMEN DEL ENTRENAMIENTO", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Text(String.format(Locale.getDefault(), "%.2f km · %s", review.distanceM / 1000.0, formatDuration(review.elapsedMs)),
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MetricTile("FC MED.", if (review.averageBpm > 0) review.averageBpm.toString() else "--", "lpm", Pink, Modifier.weight(1f))
                MetricTile("FC MÁX.", if (review.maxBpm > 0) review.maxBpm.toString() else "--", "lpm", Pink, Modifier.weight(1f))
                MetricTile("FC MÍN.", if (review.minBpm > 0) review.minBpm.toString() else "--", "lpm", Pink, Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Text(review.calories.toString() + " kcal estimadas · " + review.strokes + " paladas · " + review.turns.size + " giros", color = Ice, fontSize = 8.sp, textAlign = TextAlign.Center)
            Text(if (review.turns.any { it.repetitions >= 2 }) review.turns.count { it.repetitions >= 2 }.toString() + " posibles boyas repetidas" else "Boyas inferidas del recorrido GPS", color = Quiet, fontSize = 8.sp, textAlign = TextAlign.Center)
            if (review.isCircuit) Text("Posible circuito cerrado detectado", color = Mint, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(15.dp)).background(Night)) {
                OnlineRouteMap(points = review.points, buoys = review.turns, latitude = review.points.lastOrNull()?.latitude,
                    longitude = review.points.lastOrNull()?.longitude, ambient = false, allowNetwork = true)
            }
            Spacer(Modifier.height(5.dp))
            ActionPill("GUARDAR ENTRENO Y CIRCUITO", Mint, Night, onSaveBoth, Modifier.fillMaxWidth())
            Spacer(Modifier.height(3.dp))
            ActionPill("GUARDAR ENTRENO", Panel, Color.White, onSaveWorkout, Modifier.fillMaxWidth())
            Spacer(Modifier.height(3.dp))
            ActionPill("GUARDAR CIRCUITO", Panel, Ice, onSaveCircuit, Modifier.fillMaxWidth())
            Spacer(Modifier.height(3.dp))
            ActionPill("BORRAR ENTRENO Y CIRCUITO", Color(0xFF4B2428), Pink, onDelete, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SettingsPage(
    context: Context,
    healthStatus: String,
    isAmbient: Boolean,
    onHealthPermissions: () -> Unit,
    onSync: () -> Unit,
    onExport: () -> Unit,
    mapPackStatus: String,
    onImportOfflineMap: () -> Unit
) {
    var weightText by remember { mutableStateOf(String.format(Locale.US, "%.1f", AthleteProfileStore.weightKg(context))) }
    var ageText by remember { mutableStateOf(AthleteProfileStore.ageYears(context).toString()) }
    var heightText by remember { mutableStateOf(AthleteProfileStore.heightCm(context).toString()) }
    var speedUnit by remember { mutableStateOf(AppSettingsStore.speedUnit(context)) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("AJUSTES Y DATOS", color = if (isAmbient) Color.LightGray else Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text(healthStatus, color = Quiet, fontSize = 9.sp, textAlign = TextAlign.Center)
        if (!isAmbient) {
            Spacer(Modifier.height(6.dp))
            Text("PERFIL DEPORTISTA", color = Ice, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Peso (kg)", color = Quiet, fontSize = 7.sp)
                    BasicTextField(value = weightText, onValueChange = { weightText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(5) }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp), modifier = Modifier.fillMaxWidth().background(Panel2, RoundedCornerShape(8.dp)).padding(7.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Edad", color = Quiet, fontSize = 7.sp)
                    BasicTextField(value = ageText, onValueChange = { ageText = it.filter(Char::isDigit).take(3) }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp), modifier = Modifier.fillMaxWidth().background(Panel2, RoundedCornerShape(8.dp)).padding(7.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Altura cm", color = Quiet, fontSize = 7.sp)
                    BasicTextField(value = heightText, onValueChange = { heightText = it.filter(Char::isDigit).take(3) }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp), modifier = Modifier.fillMaxWidth().background(Panel2, RoundedCornerShape(8.dp)).padding(7.dp))
                }
            }
            Text("UNIDADES DE VELOCIDAD", color = Ice, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("km/h", "mph", "m/s").forEach { unit ->
                    ActionPill(unit, if (speedUnit == unit) Mint else Panel2, if (speedUnit == unit) Night else Color.White, {
                        AppSettingsStore.setSpeedUnit(context, unit); speedUnit = unit
                    }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(5.dp))
            ActionPill("GUARDAR PERFIL", Panel2, Ice, {
                val weight = weightText.replace(',', '.').toDoubleOrNull()
                val age = ageText.toIntOrNull()
                val height = heightText.toIntOrNull()
                if (weight != null && age != null && height != null && AthleteProfileStore.save(context, weight, age, height)) Toast.makeText(context, "Perfil guardado", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "Comprueba peso, edad y altura (100–230 cm)", Toast.LENGTH_LONG).show()
            }, Modifier.fillMaxWidth())
        }
        if (!isAmbient) {
            Spacer(Modifier.height(6.dp))
            ActionPill("PERMISOS HEALTH CONNECT", Panel2, Color.White, onHealthPermissions, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            ActionPill("SINCRONIZAR SESIÓN", Panel2, Ice, onSync, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            ActionPill("COMPARTIR RUTA GPX", Panel2, Mint, onExport, Modifier.fillMaxWidth())
            Spacer(Modifier.height(9.dp))
            Text("MAPA SIN CONEXIÓN", color = Ice, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(mapPackStatus, color = Quiet, fontSize = 7.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Importa un paquete MBTiles raster pequeño que permita uso offline y declare su atribución. Las teselas vistas con Internet también se conservan en el reloj.",
                color = Quiet, fontSize = 7.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            ActionPill("IMPORTAR MAPA MBTILES", Panel2, Mint, onImportOfflineMap, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text("AOD: negro puro, cifras de alto contraste y refresco reducido. «SALIR AOD» solicita despertar la pantalla; el sistema puede exigir un toque para reactivarla.", color = Quiet, fontSize = 8.sp, textAlign = TextAlign.Center)
        Text("Versión 2.5 · asistente de voz · unidades de velocidad", color = if (isAmbient) Color.DarkGray else Quiet,
            fontSize = 7.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MetricTile(title: String, value: String, unit: String, tint: Color, modifier: Modifier = Modifier, ambient: Boolean = false) {
    Column(
        modifier.clip(RoundedCornerShape(17.dp))
            .background(if (ambient) Color.Black else Panel)
            .border(1.dp, if (ambient) Color(0xFF303030) else Color(0xFF24313C), RoundedCornerShape(17.dp))
            .padding(horizontal = 4.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = Quiet, fontSize = 6.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(value, color = if (ambient) Color.White else tint, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Text(unit, color = Quiet, fontSize = 6.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ActionPill(label: String, background: Color, foreground: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 34.dp).clip(CircleShape).background(background)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun CandidateRow(candidate: BleCandidate, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Panel2)
            .clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(candidate.name.take(18), color = Color.White, fontSize = 9.sp, maxLines = 1)
            Text(candidate.address.takeLast(8), color = Quiet, fontSize = 7.sp)
        }
        Text("CONECTAR ›", color = Mint, fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusDot(active: Boolean, ambient: Boolean) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(
        if (ambient) Color.LightGray else if (active) Mint else Color(0xFF53616A)
    ))
}

@Composable
private fun ArrowControl(label: String, modifier: Modifier, ambient: Boolean, onClick: () -> Unit) {
    Box(
        modifier.size(30.dp).clip(CircleShape)
            .background(if (ambient) Color.Black else Color(0xFF131E26))
            .border(1.dp, if (ambient) Color(0xFF606060) else Color(0xFF314550), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (ambient) Color.White else Mint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val remainder = seconds % 60L
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remainder)
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, remainder)
}

private fun pacePerKm(elapsedMs: Long, distanceM: Double): String {
    if (distanceM < 100.0) return "--:--"
    val totalSeconds = (elapsedMs.coerceAtLeast(0L) / 1000.0 / (distanceM / 1000.0)).toLong()
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
