package com.openkayak.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openkayak.phone.sync.PhoneSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF07111C)
private val Panel = Color(0xFF142333)
private val Mint = Color(0xFF70E5C5)
private val Pink = Color(0xFFFF829B)
private val Orange = Color(0xFFFFB54A)
private val Muted = Color(0xFFA5B7C5)
private val Trasona = LatLon(43.558, -5.91)

private data class RouteColorOption(val label: String, val hex: String)
private data class CoachChatMessage(val isUser: Boolean, val text: String)
private val RouteColorOptions = listOf(
    RouteColorOption("Naranja", PhoneDataStore.ROUTE_COLOR_ORANGE),
    RouteColorOption("Cian", PhoneDataStore.ROUTE_COLOR_CYAN),
    RouteColorOption("Rosa", PhoneDataStore.ROUTE_COLOR_PINK)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Mint, secondary = Color(0xFF70C5F0), background = Navy, surface = Panel,
                onBackground = Color.White, onSurface = Color.White
            )) { OpenKayakPhone() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenKayakPhone() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var workouts by remember { mutableStateOf(PhoneDataStore.allWorkouts(context)) }
    var buoys by remember { mutableStateOf(PhoneDataStore.allBuoys(context)) }
    var circuitPath by remember { mutableStateOf(PhoneDataStore.currentCircuitPath(context)) }
    var status by remember { mutableStateOf("Abre OpenKayak en el reloj y solicita la sincronización.") }
    var selectedStart by remember { mutableStateOf<Long?>(null) }
    var detailWorkout by remember { mutableStateOf<PhoneWorkout?>(null) }
    var deleteCandidate by remember { mutableStateOf<PhoneWorkout?>(null) }
    var picked by remember { mutableStateOf(Trasona) }
    var buoyType by remember { mutableStateOf("BUOY") }
    var weightText by remember { mutableStateOf(PhoneDataStore.weightKg(context).toString()) }
    var ageText by remember { mutableStateOf(PhoneDataStore.ageYears(context).toString()) }
    var heightText by remember { mutableStateOf(PhoneDataStore.heightCm(context).toString()) }
    var zoom by remember { mutableIntStateOf(PhoneDataStore.mapZoom(context)) }
    var accuracy by remember { mutableFloatStateOf(PhoneDataStore.gpsAccuracy(context)) }
    var routeColorHex by remember { mutableStateOf(PhoneDataStore.routeColorHex(context)) }
    var apiKeyText by remember { mutableStateOf(ApiKeyStore.get(context)) }
    var chatInput by remember { mutableStateOf("") }
    var chatBusy by remember { mutableStateOf(false) }
    var chatMessages by remember {
        mutableStateOf(listOf(CoachChatMessage(false, "Soy tu coach de piragüismo. Puedo analizar los entrenamientos que se hayan sincronizado en este móvil.")))
    }

    fun sendCoachMessage() {
        val question = chatInput.trim()
        if (question.isEmpty() || chatBusy) return
        val key = ApiKeyStore.get(context)
        if (key.isBlank()) {
            chatMessages = chatMessages + CoachChatMessage(false, "Primero añade tu clave de Gemini en Ajustes. La clave se guarda cifrada en este móvil y no se envía al reloj.")
            chatInput = ""
            page = 2
            return
        }
        val history = chatMessages.map { GeminiChatTurn(it.isUser, it.text) }
        chatMessages = chatMessages + CoachChatMessage(true, question)
        chatInput = ""
        chatBusy = true
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    GeminiCoach.reply(key, question, PhoneDataStore.allWorkouts(context), history)
                }
                chatMessages = chatMessages + CoachChatMessage(false, answer)
            } catch (error: Exception) {
                val message = error.message?.takeIf { it.isNotBlank() } ?: "No se pudo contactar con Gemini."
                chatMessages = chatMessages + CoachChatMessage(false, "Error del coach: $message")
            } finally {
                chatBusy = false
            }
        }
    }

    val circuitPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            status = "Importando GPX…"
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = File(context.filesDir, "circuits").apply { mkdirs() }
                    val output = File(directory, "circuit-" + System.currentTimeMillis() + ".gpx")
                    val input = context.contentResolver.openInputStream(uri) ?: error("No se pudo leer el archivo")
                    input.use { source -> output.outputStream().use { target -> source.copyTo(target) } }
                    output
                }
            }
            imported.onSuccess { file ->
                PhoneDataStore.saveCircuit(context, file, file.nameWithoutExtension)
                PhoneSyncService.importCircuit(context, file, file.nameWithoutExtension)
                circuitPath = file.absolutePath
                status = "Circuito enviado al reloj. Si está desconectado, se enviará al volver a conectarse."
            }.onFailure { status = "Error importando GPX: " + (it.message ?: "desconocido") }
        }
    }

    LaunchedEffect(Unit) {
        PhoneSyncService.publishSettings(
            context, PhoneDataStore.weightKg(context), PhoneDataStore.ageYears(context), PhoneDataStore.heightCm(context),
            PhoneDataStore.mapZoom(context), PhoneDataStore.gpsAccuracy(context), PhoneDataStore.routeColorHex(context)
        )
        PhoneSyncService.requestSync(context)
        status = "Buscando el reloj y solicitando todos los entrenamientos guardados…"
        while (true) {
            workouts = PhoneDataStore.allWorkouts(context)
            buoys = PhoneDataStore.allBuoys(context)
            circuitPath = PhoneDataStore.currentCircuitPath(context)
            delay(1500L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OPENKAYAK", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                        Text("V2.6 · CENTRO DE CONTROL", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        PhoneSyncService.requestSync(context)
                        status = "Solicitud enviada al reloj."
                    }) { Text("SINCRONIZAR", fontSize = 10.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0C1926)) {
                NavigationBarItem(selected = page == 0, onClick = { page = 0 }, icon = { Text("↻") }, label = { Text("Entrenos") })
                NavigationBarItem(selected = page == 1, onClick = { page = 1 }, icon = { Text("⌖") }, label = { Text("Mapa y boyas") })
                NavigationBarItem(selected = page == 2, onClick = { page = 2 }, icon = { Text("⚙") }, label = { Text("Ajustes") })
                NavigationBarItem(selected = page == 3, onClick = { page = 3 }, icon = { Text("✦") }, label = { Text("Coach IA") })
            }
        },
        containerColor = Navy
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(status, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.height(10.dp))
            when (page) {
                0 -> WorkoutsPage(
                    workouts, selectedStart,
                    onSelect = { start -> selectedStart = start; detailWorkout = workouts.firstOrNull { it.startAt == start } },
                    onSync = { PhoneSyncService.requestSync(context); status = "Solicitando el historial completo del reloj…" }
                )
                1 -> MapAndBuoysPage(
                    workouts = workouts, selectedStart = selectedStart, buoys = buoys,
                    picked = picked, buoyType = buoyType, circuitPath = circuitPath, routeColorHex = routeColorHex,
                    onPick = { picked = it }, onType = { buoyType = it },
                    onSaveBuoy = {
                        PhoneDataStore.addBuoy(context, picked.latitude, picked.longitude, buoyType)
                        PhoneSyncService.publishBuoys(context)
                        buoys = PhoneDataStore.allBuoys(context)
                        status = "Punto guardado y enviado: %.6f, %.6f".format(Locale.US, picked.latitude, picked.longitude)
                    },
                    onDeleteBuoy = { id ->
                        PhoneDataStore.removeBuoy(context, id)
                        PhoneSyncService.publishBuoys(context)
                        buoys = PhoneDataStore.allBuoys(context)
                        status = "Punto eliminado y lista sincronizada."
                    },
                    onImportCircuit = { circuitPicker.launch(arrayOf("application/gpx+xml", "application/gpx", "application/xml", "text/xml", "application/octet-stream")) },
                    onDeleteCircuit = {
                        PhoneDataStore.clearCircuit(context)
                        PhoneSyncService.deleteCircuitFromWatch(context)
                        circuitPath = null
                        status = "Circuito eliminado del móvil y se envió la orden al reloj."
                    },
                    onOpenMaps = {
                        val url = "https://www.google.com/maps/search/?api=1&query=" + picked.latitude + "," + picked.longitude
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { Toast.makeText(context, "No se pudo abrir Google Maps", Toast.LENGTH_SHORT).show() }
                    }
                )
                2 -> SettingsPage(
                    weightText, ageText, heightText, zoom, accuracy, routeColorHex, apiKeyText,
                    onWeight = { weightText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) },
                    onAge = { ageText = it.filter(Char::isDigit).take(3) },
                    onHeight = { heightText = it.filter(Char::isDigit).take(3) },
                    onZoom = { zoom = it },
                    onAccuracy = { accuracy = it },
                    onRouteColor = { routeColorHex = it },
                    onApiKey = { apiKeyText = it },
                    onSave = {
                        val weight = weightText.replace(',', '.').toDoubleOrNull()
                        val age = ageText.toIntOrNull()
                        val height = heightText.toIntOrNull()
                        if (weight == null || age == null || height == null || weight !in 25.0..250.0 || age !in 8..100 || height !in 100..230) {
                            status = "Comprueba peso (25–250 kg), edad (8–100) y altura (100–230 cm)."
                        } else {
                            val keySaved = runCatching { ApiKeyStore.save(context, apiKeyText) }.getOrDefault(false)
                            if (!keySaved) {
                                status = "No se pudo guardar la clave de Gemini de forma segura. Inténtalo de nuevo."
                            } else {
                                apiKeyText = ApiKeyStore.get(context)
                                PhoneDataStore.saveSettings(context, weight, age, height, zoom, accuracy, routeColorHex)
                                PhoneSyncService.publishSettings(context, weight, age, height, zoom, accuracy, routeColorHex)
                                status = "Ajustes guardados y enviados al reloj. La clave de Gemini queda cifrada solo en este móvil."
                            }
                        }
                    }
                )
                else -> CoachPage(
                    messages = chatMessages,
                    draft = chatInput,
                    busy = chatBusy,
                    onDraft = { chatInput = it },
                    onSend = { sendCoachMessage() }
                )
            }
        }
    }
    detailWorkout?.let { workout -> WorkoutDetailDialog(workout, { detailWorkout = null }, { deleteCandidate = workout; detailWorkout = null }) }
    deleteCandidate?.let { workout ->
        AlertDialog(onDismissRequest = { deleteCandidate = null }, title = { Text("Borrar entrenamiento") },
            text = { Text("Se borrará del móvil y se enviará la orden al reloj cuando esté conectado.") },
            confirmButton = { TextButton(onClick = {
                PhoneDataStore.deleteWorkout(context, workout.startAt)
                PhoneSyncService.deleteWorkout(context, workout.startAt)
                workouts = PhoneDataStore.allWorkouts(context); selectedStart = null; deleteCandidate = null
                status = "Entrenamiento borrado del móvil; orden enviada al reloj."
            }) { Text("Borrar", color = Pink) } },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun WorkoutDetailDialog(workout: PhoneWorkout, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val samples = remember(workout.startAt, workout.hrSamples, workout.gpxPath) { PhoneDataStore.telemetry(workout) }
    val route = remember(workout.gpxPath) { PhoneDataStore.routePoints(workout.gpxPath) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f), shape = RoundedCornerShape(24.dp), color = Navy) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RESUMEN AMPLIADO", Modifier.weight(1f), color = Mint, fontWeight = FontWeight.ExtraBold)
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
                Text("Distancia %.2f km · Tiempo %s · kcal %d · paladas %d".format(Locale.US, workout.distanceM / 1000.0, duration(workout.elapsedMs), workout.calories, workout.strokes), color = Color.White, fontSize = 11.sp)
                Text("FC media %s · máxima %s · mínima %s lpm".format(if (workout.averageBpm > 0) workout.averageBpm else "--", if (workout.maxBpm > 0) workout.maxBpm else "--", if (workout.minBpm > 0) workout.minBpm else "--"), color = Pink, fontSize = 11.sp)
                Text("Velocidad media %.2f km/h".format(Locale.US, if (workout.elapsedMs > 0) workout.distanceM / (workout.elapsedMs / 3600000.0) else 0.0), color = Color.White, fontSize = 11.sp)
                Text("MAPA COMPLETO", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (route.isNotEmpty()) PhoneMapPicker(Modifier.fillMaxWidth().height(145.dp), route, emptyList(), route[route.size / 2], route.last(), PhoneDataStore.routeColorHex(context), {})
                else Text("Sin ruta GPS recibida", color = Muted, fontSize = 10.sp)
                Text("FC cada 5 s", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DetailChart(samples.filter { it.bpm > 0 }.map { it.seconds to it.bpm.toDouble() }, Pink)
                Text("Velocidad cada 5 s", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                DetailChart(samples.map { it.seconds to it.speedKmh }, Color(0xFF70C5F0))
                Text("TABLA DE MUESTRAS · cada 5 segundos", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(7.dp)).padding(5.dp)) {
                    Text("Tiempo", Modifier.weight(1f), color = Muted, fontSize = 9.sp)
                    Text("FC", Modifier.weight(0.7f), color = Muted, fontSize = 9.sp)
                    Text("km/h", Modifier.weight(0.8f), color = Muted, fontSize = 9.sp)
                    Text("GPS", Modifier.weight(1.7f), color = Muted, fontSize = 9.sp)
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(samples, key = { it.seconds }) { sample ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(duration(sample.seconds * 1000L), Modifier.weight(1f), color = Color.White, fontSize = 9.sp)
                            Text(if (sample.bpm > 0) sample.bpm.toString() else "--", Modifier.weight(0.7f), color = Pink, fontSize = 9.sp)
                            Text("%.2f".format(Locale.US, sample.speedKmh), Modifier.weight(0.8f), color = Color(0xFF70C5F0), fontSize = 9.sp)
                            Text(if (sample.latitude != null && sample.longitude != null) "%.4f, %.4f".format(Locale.US, sample.latitude, sample.longitude) else "--", Modifier.weight(1.7f), color = Muted, fontSize = 8.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Pink)) { Text("Borrar entreno") }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Listo") }
                }
            }
        }
    }
}

@Composable
private fun DetailChart(values: List<Pair<Int, Double>>, tint: Color) {
    if (values.size < 2) { Text("Datos insuficientes para dibujar la gráfica.", color = Muted, fontSize = 9.sp); return }
    Canvas(Modifier.fillMaxWidth().height(43.dp).background(Panel, RoundedCornerShape(8.dp)).padding(5.dp)) {
        val lo = values.minOf { it.second }; val hi = values.maxOf { it.second }.let { if (it <= lo) lo + 1.0 else it }
        val end = values.last().first.coerceAtLeast(1); val path = Path()
        values.forEachIndexed { i, v ->
            val x = v.first.toFloat() / end * size.width
            val y = size.height - ((v.second - lo) / (hi - lo)).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawLine(Color(0xFF34434F), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun WorkoutsPage(
    workouts: List<PhoneWorkout>, selectedStart: Long?,
    onSelect: (Long) -> Unit, onSync: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("HISTORIAL", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Mint)
            Text(workouts.size.toString() + " entrenamientos recibidos", color = Muted, fontSize = 11.sp)
        }
        OutlinedButton(onClick = onSync) { Text("Traer todo") }
    }
    Spacer(Modifier.height(8.dp))
    if (workouts.isEmpty()) {
        Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Aún no hay entrenamientos")
                Spacer(Modifier.height(8.dp))
                Text("Abre OpenKayak en el reloj y pulsa Sincronizar. Deben estar emparejados en Wear OS y con Data Layer disponible.", color = Muted, fontSize = 12.sp)
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(workouts, key = { it.startAt }) { workout ->
                Surface(color = if (workout.startAt == selectedStart) Color(0xFF193B46) else Panel,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable { onSelect(workout.startAt) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                            .format(Date(workout.startAt)), color = Muted, fontSize = 11.sp)
                        Text("%.2f km".format(Locale.US, workout.distanceM / 1000.0), color = Mint, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        Text(duration(workout.elapsedMs) + " · " + workout.strokes + " paladas · " +
                            if (workout.averageBpm > 0) workout.averageBpm.toString() + " lpm" else "sin FC",
                            color = Color.White, fontSize = 12.sp)
                        Text(if (workout.gpxPath.isNotBlank()) "GPX recibido · toca para ver el mapa" else "Sin archivo GPX recibido",
                            color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MapAndBuoysPage(
    workouts: List<PhoneWorkout>, selectedStart: Long?, buoys: List<PhoneBuoy>,
    picked: LatLon, buoyType: String, circuitPath: String?, routeColorHex: String,
    onPick: (LatLon) -> Unit, onType: (String) -> Unit, onSaveBuoy: () -> Unit,
    onDeleteBuoy: (Long) -> Unit, onImportCircuit: () -> Unit, onDeleteCircuit: () -> Unit,
    onOpenMaps: () -> Unit
) {
    val selectedWorkout = workouts.firstOrNull { it.startAt == selectedStart }
    val selectedPath = selectedWorkout?.gpxPath?.takeIf { it.isNotBlank() } ?: circuitPath
    var route by remember(selectedPath) { mutableStateOf(PhoneDataStore.routePoints(selectedPath)) }
    LaunchedEffect(selectedPath) { route = withContext(Dispatchers.IO) { PhoneDataStore.routePoints(selectedPath) } }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MAPA INTERACTIVO", color = Mint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Toca el mapa para colocar una boya.", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = onImportCircuit) { Text("Importar GPX") }
        }
        Spacer(Modifier.height(8.dp))
        PhoneMapPicker(
            modifier = Modifier.fillMaxWidth().height(260.dp),
            route = route,
            buoys = buoys,
            center = route.getOrNull(route.size / 2) ?: picked,
            selected = picked,
            routeColorHex = routeColorHex,
            onPick = onPick
        )
        Spacer(Modifier.height(8.dp))
        Text("COORDENADAS SELECCIONADAS", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("%.6f, %.6f".format(Locale.US, picked.latitude, picked.longitude), color = Color.White, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = buoyType == "BUOY", onClick = { onType("BUOY") }, label = { Text("Boya normal") })
            FilterChip(selected = buoyType == "UTURN", onClick = { onType("UTURN") }, label = { Text("U-turn") })
            TextButton(onClick = onOpenMaps) { Text("Google Maps ↗") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSaveBuoy, modifier = Modifier.weight(1f)) { Text("Guardar y enviar") }
            OutlinedButton(onClick = onDeleteCircuit, enabled = circuitPath != null) { Text("Borrar circuito") }
        }
        Text(buoys.size.toString() + " puntos guardados", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
            items(buoys, key = { it.id }) { buoy ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(50), color = if (buoy.type == "UTURN") Orange else Pink, modifier = Modifier.size(9.dp)) {}
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (buoy.type == "UTURN") "Giro en U" else if (buoy.type == "ROUTE") "Punto de ruta" else "Boya normal",
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("%.5f, %.5f".format(Locale.US, buoy.latitude, buoy.longitude), color = Muted, fontSize = 10.sp)
                    }
                    TextButton(onClick = { onDeleteBuoy(buoy.id) }) { Text("Borrar", color = Pink) }
                }
            }
        }
    }
}

@Composable
private fun CoachPage(
    messages: List<CoachChatMessage>,
    draft: String,
    busy: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
        Text("COACH DE PIRAGÜISMO", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Consulta entrenamientos ya sincronizados. Las preguntas y el resumen enviado se procesan mediante la API de Gemini.", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (message.isUser) Color(0xFF17483F) else Panel,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Text(
                            message.text,
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
            if (busy) {
                item { Text("El coach está preparando la respuesta…", color = Muted, fontSize = 11.sp) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                label = { Text("Pregunta al coach") },
                enabled = !busy,
                maxLines = 3
            )
            Button(onClick = onSend, enabled = !busy && draft.isNotBlank()) { Text("Enviar") }
        }
    }
}

@Composable
private fun SettingsPage(
    weightText: String, ageText: String, heightText: String, zoom: Int, accuracy: Float, routeColorHex: String,
    apiKeyText: String,
    onWeight: (String) -> Unit, onAge: (String) -> Unit, onHeight: (String) -> Unit, onZoom: (Int) -> Unit,
    onAccuracy: (Float) -> Unit, onRouteColor: (String) -> Unit, onApiKey: (String) -> Unit, onSave: () -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
        Text("AJUSTES DEL RELOJ", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("Se aplican por Wear OS Data Layer cuando el reloj recibe la actualización.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = weightText, onValueChange = onWeight, label = { Text("Peso del perfil (kg)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = ageText, onValueChange = onAge, label = { Text("Edad del perfil") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = heightText, onValueChange = onHeight, label = { Text("Altura (cm)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Text("Color del circuito", color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            RouteColorOptions.forEach { option ->
                FilterChip(
                    selected = routeColorHex == option.hex,
                    onClick = { onRouteColor(option.hex) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color(option.hex.toLong(16).toInt()), shape = RoundedCornerShape(50), modifier = Modifier.size(10.dp)) {}
                            Spacer(Modifier.width(5.dp))
                            Text(option.label)
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("COACH CON GEMINI", color = Mint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
            "Pega una clave de Gemini AI Studio. Se cifra con Android Keystore y permanece en este móvil, sin sincronizarse con el reloj. Al enviar preguntas, el texto y el resumen de entrenamientos se transmiten a Google para generar la respuesta.",
            color = Muted, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKeyText,
            onValueChange = onApiKey,
            label = { Text("Clave de API de Gemini") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showApiKey = !showApiKey }) { Text(if (showApiKey) "Ocultar" else "Mostrar") }
            }
        )
        Spacer(Modifier.height(14.dp))
        Text("Zoom inicial del mapa: Z" + zoom, color = Color.White)
        Slider(value = zoom.toFloat(), onValueChange = { onZoom(it.toInt()) }, valueRange = 14f..17f, steps = 2)
        Spacer(Modifier.height(8.dp))
        Text("Umbral de precisión GPS: ±" + accuracy.toInt() + " m", color = Color.White)
        Slider(value = accuracy, onValueChange = onAccuracy, valueRange = 10f..65f)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Guardar ajustes en móvil y reloj") }
        Spacer(Modifier.height(10.dp))
        Text("Los APK son versiones de prueba. Verifica GPS, distancia, paladas, banda BLE y sincronización en recorridos reales antes de fiarte de las métricas.",
            color = Muted, fontSize = 12.sp)
    }
}

private fun duration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}
