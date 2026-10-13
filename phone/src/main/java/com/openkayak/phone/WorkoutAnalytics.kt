package com.openkayak.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.*

private val AnalyticsNavy = Color(0xFF07111C)
private val AnalyticsPanel = Color(0xFF142333)
private val AnalyticsMint = Color(0xFF70E5C5)
private val AnalyticsBlue = Color(0xFF70C5F0)
private val AnalyticsPink = Color(0xFFFF829B)
private val AnalyticsMuted = Color(0xFFA5B7C5)

private data class TimedMetric(val seconds: Double, val value: Double)
private data class TenSecondRow(val seconds: Int, val heartRate: Double?, val speedKmh: Double?)

@Composable
fun WorkoutDetailDialog(
    workout: PhoneWorkout,
    weightKg: Double,
    heightCm: Int,
    ageYears: Int,
    biologicalSex: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val durationSeconds = (workout.elapsedMs / 1000L).toInt().coerceAtLeast(0)
    val durationMinutes = workout.elapsedMs.coerceAtLeast(0L) / 60_000.0
    val routePoints = remember(workout.gpxPath) { PhoneDataStore.trackPoints(workout.gpxPath) }
    val speedSeries = remember(routePoints, workout.startAt) {
        val out = mutableListOf<TimedMetric>()
        routePoints.zipWithNext().forEach { (a, b) ->
            val deltaSeconds = (b.timeMs - a.timeMs) / 1000.0
            if (deltaSeconds > 0.0 && deltaSeconds <= 120.0) {
                val metres = haversineMetres(a.latitude, a.longitude, b.latitude, b.longitude)
                val kmh = metres / deltaSeconds * 3.6
                if (kmh.isFinite() && kmh in 0.0..30.0) {
                    val rel = ((b.timeMs - workout.startAt).coerceAtLeast(0L)) / 1000.0
                    out += TimedMetric(rel, kmh)
                }
            }
        }
        out
    }
    val heartSeries = remember(workout.heartRateSamples, workout.startAt) {
        workout.heartRateSamples.mapNotNull { sample ->
            val seconds = (sample.timeMs - workout.startAt) / 1000.0
            if (seconds >= 0.0 && sample.value in 25.0..240.0) TimedMetric(seconds, sample.value) else null
        }.sortedBy { it.seconds }
    }
    val speedTenSecond = remember(speedSeries) { averageByTenSeconds(speedSeries) }
    val heartTenSecond = remember(heartSeries) { averageByTenSeconds(heartSeries) }
    val rows = remember(durationSeconds, speedTenSecond, heartTenSecond) {
        (0..(durationSeconds / 10)).map { index ->
            TenSecondRow(index * 10, heartTenSecond[index], speedTenSecond[index])
        }
    }
    val averageSpeed = if (durationMinutes > 0) workout.distanceM / 1000.0 / (durationMinutes / 60.0) else 0.0
    val peakSpeed = speedSeries.maxOfOrNull { it.value } ?: 0.0
    val cadence = if (durationMinutes > 0.0) workout.strokes / durationMinutes else 0.0
    val met = when {
        averageSpeed < 3.0 -> 3.5
        averageSpeed < 5.5 -> 5.5
        else -> 7.0
    }
    val mobileKcalEstimate = (met * 3.5 * weightKg.coerceIn(25.0, 250.0) / 200.0 * durationMinutes)
        .roundToInt()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp)
                .background(AnalyticsNavy, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ANÁLISIS DEL ENTRENO", color = AnalyticsMint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text(java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
                    ).format(java.util.Date(workout.startAt)), color = AnalyticsMuted, fontSize = 11.sp)
                }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SummaryTile("DISTANCIA", "%.2f km".format(Locale.US, workout.distanceM / 1000.0), AnalyticsMint, Modifier.weight(1f))
                SummaryTile("TIEMPO", analyticsDuration(workout.elapsedMs), AnalyticsBlue, Modifier.weight(1f))
                SummaryTile("RITMO", "%.1f km/h".format(Locale.US, averageSpeed), AnalyticsPink, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("Resumen", "FC", "Velocidad", "Tabla 10 s").forEachIndexed { index, label ->
                    FilterChip(
                        selected = tab == index,
                        onClick = { tab = index },
                        label = { Text(label, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            when (tab) {
                0 -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AnalyticsSectionTitle("ESFUERZO")
                    AnalyticsMetric("Paladas", workout.strokes.toString())
                    AnalyticsMetric("Cadencia media calculada", "%.1f paladas/min".format(Locale.US, cadence))
                    AnalyticsMetric("Velocidad máxima registrada", if (peakSpeed > 0.0) "%.1f km/h".format(Locale.US, peakSpeed) else "Sin datos GPX suficientes")
                    AnalyticsSectionTitle("FRECUENCIA CARDÍACA")
                    AnalyticsMetric("Media", if (workout.averageBpm > 0) "${workout.averageBpm} lpm" else "Sin datos")
                    AnalyticsMetric("Máxima / mínima", if (workout.maxBpm > 0 && workout.minBpm > 0) "${workout.maxBpm} / ${workout.minBpm} lpm" else "Sin datos")
                    if (heartSeries.isEmpty()) {
                        Text("Este entrenamiento solo contiene el resumen de FC. No se han sincronizado muestras crudas para dibujar una curva real cada 10 segundos.", color = AnalyticsMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    AnalyticsSectionTitle("CALORÍAS")
                    AnalyticsMetric("Calorías guardadas por el reloj", if (workout.calories > 0) "${workout.calories} kcal" else "No disponibles")
                    AnalyticsMetric("Estimación alternativa móvil", "${mobileKcalEstimate} kcal")
                    Text("La estimación alternativa es aproximada: usa peso, duración y un nivel de esfuerzo inferido del ritmo medio. No sustituye a las kcal calculadas por el reloj. Altura (${heightCm} cm), edad (${ageYears}) y perfil (${sexLabel(biologicalSex)}) se guardan en el perfil, pero esta fórmula MET sencilla no los usa para fingir una precisión que no tiene.", color = AnalyticsMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                    if (routePoints.isEmpty()) {
                        Text("No se recibió una ruta GPX con marcas de tiempo. Los gráficos de velocidad necesitan esos puntos.", color = AnalyticsMuted, fontSize = 12.sp)
                    }
                }
                1 -> Column(Modifier.weight(1f).fillMaxWidth()) {
                    AnalyticsSectionTitle("FRECUENCIA CARDÍACA · LPM")
                    Text("Muestras reales recibidas: ${heartSeries.size}", color = AnalyticsMuted, fontSize = 11.sp)
                    if (heartSeries.isEmpty()) {
                        EmptyAnalytics("No hay serie temporal de FC en los datos sincronizados. La media, máxima y mínima sí aparecen en el resumen. La app no inventa puntos interpolados.")
                    } else {
                        MetricChart(heartSeries, AnalyticsPink, "lpm")
                        Text("La tabla agrupa las muestras recibidas en intervalos de 10 segundos.", color = AnalyticsMuted, fontSize = 11.sp)
                    }
                }
                2 -> Column(Modifier.weight(1f).fillMaxWidth()) {
                    AnalyticsSectionTitle("VELOCIDAD · KM/H")
                    Text("Puntos GPS temporizados: ${routePoints.size}", color = AnalyticsMuted, fontSize = 11.sp)
                    if (speedSeries.isEmpty()) {
                        EmptyAnalytics("El GPX no contiene suficientes puntos GPS con hora para calcular la velocidad por intervalos.")
                    } else {
                        MetricChart(speedSeries, AnalyticsBlue, "km/h")
                    }
                }
                else -> {
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        AnalyticsSectionTitle("MUESTRAS AGRUPADAS CADA 10 SEGUNDOS")
                        Row(Modifier.fillMaxWidth().background(AnalyticsPanel, RoundedCornerShape(8.dp)).padding(8.dp)) {
                            Text("Tiempo", Modifier.weight(1f), color = AnalyticsMint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("FC (lpm)", Modifier.weight(1f), color = AnalyticsPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Vel. (km/h)", Modifier.weight(1f), color = AnalyticsBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(rows) { row ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                                    Text(analyticsDuration(row.seconds * 1000L), Modifier.weight(1f), color = Color.White, fontSize = 12.sp)
                                    Text(row.heartRate?.let { "%.0f".format(Locale.US, it) } ?: "—", Modifier.weight(1f), color = AnalyticsPink, fontSize = 12.sp)
                                    Text(row.speedKmh?.let { "%.2f".format(Locale.US, it) } ?: "—", Modifier.weight(1f), color = AnalyticsBlue, fontSize = 12.sp)
                                }
                                HorizontalDivider(color = Color(0xFF253849), thickness = 0.5.dp)
                            }
                        }
                        if (heartSeries.isEmpty()) {
                            Text("FC: el reloj no ha enviado muestras temporales. Los guiones significan datos no disponibles, no pulso cero.", color = AnalyticsMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = AnalyticsPink)) {
                    Text("Borrar entrenamiento")
                }
                Spacer(Modifier.weight(1f))
                Text("Solo afecta al historial del móvil", color = AnalyticsMuted, fontSize = 10.sp)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar este entrenamiento?") },
            text = { Text("Se quitará del historial del móvil y se eliminará su GPX local. No se envía ninguna orden de borrado al reloj.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Borrar", color = AnalyticsPink) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            },
            containerColor = AnalyticsPanel,
            titleContentColor = Color.White,
            textContentColor = AnalyticsMuted
        )
    }
}

@Composable
private fun SummaryTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(AnalyticsPanel, RoundedCornerShape(12.dp)).padding(9.dp)) {
        Text(label, color = AnalyticsMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun AnalyticsSectionTitle(text: String) {
    Text(text, color = AnalyticsMint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))
}

@Composable
private fun AnalyticsMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AnalyticsMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = Color(0xFF253849), thickness = 0.5.dp)
}

@Composable
private fun EmptyAnalytics(message: String) {
    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Sin serie temporal", color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = AnalyticsMuted, fontSize = 12.sp)
    }
}

@Composable
private fun MetricChart(points: List<TimedMetric>, color: Color, unit: String) {
    if (points.isEmpty()) {
        EmptyAnalytics("No hay muestras para representar.")
        return
    }
    val minimum = points.minOf { it.value }
    val maximum = points.maxOf { it.value }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Mín. %.1f".format(Locale.US, minimum), color = AnalyticsMuted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("Máx. %.1f %s".format(Locale.US, maximum, unit), color = AnalyticsMuted, fontSize = 10.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(190.dp).padding(top = 8.dp, bottom = 4.dp)) {
            val left = 8f
            val right = size.width - 8f
            val top = 8f
            val bottom = size.height - 8f
            repeat(4) { index ->
                val y = top + (bottom - top) * index / 3f
                drawLine(Color(0xFF253849), Offset(left, y), Offset(right, y), strokeWidth = 1f)
            }
            val maxTime = (points.maxOf { it.seconds }).coerceAtLeast(10.0)
            val span = (maximum - minimum).takeIf { it > 0.001 } ?: 1.0
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = left + ((point.seconds / maxTime).coerceIn(0.0, 1.0) * (right - left)).toFloat()
                val y = bottom - (((point.value - minimum) / span).coerceIn(0.0, 1.0) * (bottom - top)).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 3f))
            if (points.size == 1) {
                val point = points.first()
                val x = left + ((point.seconds / maxTime).coerceIn(0.0, 1.0) * (right - left)).toFloat()
                drawCircle(color, radius = 5f, center = Offset(x, (top + bottom) / 2f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Inicio", color = AnalyticsMuted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(analyticsDuration((points.maxOf { it.seconds } * 1000.0).toLong()), color = AnalyticsMuted, fontSize = 10.sp)
        }
    }
}

private fun averageByTenSeconds(points: List<TimedMetric>): Map<Int, Double> =
    points.groupBy { floor(it.seconds / 10.0).toInt() }
        .mapValues { (_, values) -> values.map { it.value }.average() }

private fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    return 2.0 * r * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
}

private fun analyticsDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    return if (seconds >= 3600L) "%d:%02d:%02d".format(seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L)
    else "%02d:%02d".format(seconds / 60L, seconds % 60L)
}

private fun sexLabel(sex: String): String = when (sex) {
    "FEMALE" -> "mujer"
    "MALE" -> "hombre"
    else -> "sin especificar"
}
