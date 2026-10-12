package com.openkayak.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.openkayak.app.tracking.SessionHistoryStore
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryPage(context: Context, ambient: Boolean) {
    var sessions by remember { mutableStateOf(SessionHistoryStore.all(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            sessions = SessionHistoryStore.all(context)
            delay(2_000L)
        }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("HISTORIAL", color = if (ambient) Color.LightGray else Color(0xFF76F4D0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("${sessions.size} entrenamientos guardados", color = Color(0xFF8D9DA8), fontSize = 8.sp)
        Spacer(Modifier.height(6.dp))
        if (sessions.isEmpty()) {
            Text("Todavía no hay entrenamientos terminados. Cuando finalices uno, aparecerá aquí.", color = Color(0xFF8D9DA8), fontSize = 9.sp, textAlign = TextAlign.Center)
        } else {
            sessions.take(8).forEach { s ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 5.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (ambient) Color.Black else Color(0xFF101820)).padding(8.dp)
                ) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault()).format(Date(s.startAt)), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text(String.format(Locale.getDefault(), "%.2f km · %s", s.distanceM / 1000.0, duration(s.elapsedMs)), color = Color(0xFF76F4D0), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${s.strokes} paladas · ${if (s.trackPath.isNotBlank()) "GPX guardado" else "sin GPX"}", color = Color(0xFF8D9DA8), fontSize = 8.sp)
                    Text("FC med " + (s.averageBpm.takeIf { it > 0 }?.toString() ?: "--") + " · máx " + (s.maxBpm.takeIf { it > 0 }?.toString() ?: "--") + " · mín " + (s.minBpm.takeIf { it > 0 }?.toString() ?: "--"), color = Color(0xFF9FD9FF), fontSize = 7.sp)
                    Text(s.calories.toString() + " kcal estimadas", color = Color(0xFF8D9DA8), fontSize = 7.sp)
                }
            }
        }
    }
}

private fun duration(ms: Long): String {
    val sec = ms.coerceAtLeast(0L) / 1000
    return if (sec >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    else String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60)
}