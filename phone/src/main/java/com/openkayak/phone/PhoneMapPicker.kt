package com.openkayak.phone

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh

private data class PhoneTile(val x: Int, val y: Int, val image: androidx.compose.ui.graphics.ImageBitmap)

@Composable
fun PhoneMapPicker(
    modifier: Modifier = Modifier,
    route: List<LatLon>,
    buoys: List<PhoneBuoy>,
    center: LatLon,
    selected: LatLon,
    onPick: (LatLon) -> Unit
) {
    val context = LocalContext.current
    val tileSizePx = with(LocalDensity.current) { 256.dp.toPx() }
    var centerLat by remember(center) { mutableDoubleStateOf(center.latitude) }
    var centerLon by remember(center) { mutableDoubleStateOf(center.longitude) }
    var zoom by remember { mutableIntStateOf(PhoneDataStore.mapZoom(context)) }
    var tiles by remember { mutableStateOf<List<PhoneTile>>(emptyList()) }
    var status by remember { mutableStateOf("Cargando mapa…") }
    val worldX = tileX(centerLon, zoom)
    val worldY = tileY(centerLat, zoom)
    val baseX = floor(worldX).toInt()
    val baseY = floor(worldY).toInt()

    LaunchedEffect(baseX, baseY, zoom) {
        status = "Cargando mosaicos…"
        val loaded = withContext(Dispatchers.IO) {
            val result = mutableListOf<PhoneTile>()
            for (dy in -1..1) for (dx in -1..1) {
                val x = baseX + dx
                val y = baseY + dy
                if (y !in 0 until (1 shl zoom)) continue
                loadPhoneTile(context, zoom, x, y)?.let { result.add(PhoneTile(x, y, it)) }
                delay(70L)
            }
            result
        }
        tiles = loaded
        status = if (loaded.isEmpty()) "Mapa no disponible. Comprueba Internet." else "OpenStreetMap · Z" + zoom + " · toca para elegir"
    }

    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF182A38))
        .pointerInput(zoom) {
            detectDragGestures { change, delta ->
                change.consume()
                val moved = tileToGeo(tileX(centerLon, zoom) - delta.x / tileSizePx.toDouble(),
                    tileY(centerLat, zoom) - delta.y / tileSizePx.toDouble(), zoom)
                centerLat = moved.latitude
                centerLon = moved.longitude
            }
        }
        .pointerInput(zoom) {
            detectTapGestures { tap ->
                val target = tileToGeo(tileX(centerLon, zoom) + (tap.x - size.width / 2.0) / tileSizePx.toDouble(),
                    tileY(centerLat, zoom) + (tap.y - size.height / 2.0) / tileSizePx.toDouble(), zoom)
                onPick(target)
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val tileSize = tileSizePx
            tiles.forEach { tile ->
                val left = ((tile.x - worldX) * tileSize + size.width / 2.0).toInt()
                val top = ((tile.y - worldY) * tileSize + size.height / 2.0).toInt()
                drawImage(tile.image, dstOffset = IntOffset(left, top), dstSize = IntSize(tileSize.toInt(), tileSize.toInt()))
            }
            fun screen(point: LatLon) = Offset(
                ((tileX(point.longitude, zoom) - worldX) * tileSize + size.width / 2.0).toFloat(),
                ((tileY(point.latitude, zoom) - worldY) * tileSize + size.height / 2.0).toFloat()
            )
            for (index in 1 until route.size) drawLine(Color(0xFF42D9FF), screen(route[index - 1]), screen(route[index]), 4f)
            buoys.forEach { buoy ->
                drawCircle(if (buoy.type == "UTURN") Color(0xFFFFB54A) else Color(0xFFFF829B),
                    7f, screen(LatLon(buoy.latitude, buoy.longitude)))
            }
            drawCircle(Color.White, 7f, screen(selected))
            drawCircle(Color(0xFF07111C), 3f, screen(selected))
        }
        Text(status, color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart)
            .background(Color(0xC807111C), RoundedCornerShape(topEnd = 8.dp)).padding(7.dp))
        Text("© OpenStreetMap", color = Color.White, fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomEnd)
            .background(Color(0xC807111C), RoundedCornerShape(topStart = 8.dp)).padding(5.dp))
        Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Text("−", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(Color(0xD807111C)).clickable { zoom = (zoom - 1).coerceIn(13, 18) }.padding(horizontal = 12.dp, vertical = 5.dp))
            Text("+", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(Color(0xD807111C)).clickable { zoom = (zoom + 1).coerceIn(13, 18) }.padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

private fun loadPhoneTile(context: Context, zoom: Int, x: Int, y: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val count = 1 shl zoom
    val wrappedX = ((x % count) + count) % count
    val directory = File(context.cacheDir, "openkayak-phone-tiles").apply { mkdirs() }
    val file = File(directory, zoom.toString() + "-" + wrappedX + "-" + y + ".png")
    if (file.isFile && file.length() > 0) {
        val cached = runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
        if (cached != null) return cached
    }
    return runCatching {
        val connection = URL("https://tile.openstreetmap.org/" + zoom + "/" + wrappedX + "/" + y + ".png").openConnection()
        connection.connectTimeout = 2500
        connection.readTimeout = 2500
        connection.setRequestProperty("User-Agent", "OpenKayak-Android/2.1 (https://github.com/tovicito/OpenKayak-WearOS)")
        val bytes = connection.getInputStream().use { it.readBytes() }
        file.writeBytes(bytes)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
private fun tileX(lon: Double, zoom: Int) = (lon + 180.0) / 360.0 * (1 shl zoom)
private fun tileY(lat: Double, zoom: Int): Double {
    val safe = lat.coerceIn(-85.0511, 85.0511)
    val rad = Math.toRadians(safe)
    return (1.0 - ln(kotlin.math.tan(rad) + 1.0 / kotlin.math.cos(rad)) / Math.PI) / 2.0 * (1 shl zoom)
}
private fun tileToGeo(x: Double, y: Double, zoom: Int): LatLon {
    val n = Math.PI - 2.0 * Math.PI * y / (1 shl zoom)
    return LatLon(Math.toDegrees(kotlin.math.atan(sinh(n))).coerceIn(-85.0511,85.0511),
        (x / (1 shl zoom) * 360.0 - 180.0).coerceIn(-180.0,180.0))
}
