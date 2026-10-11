package com.openkayak.app.tracking

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawImage
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh

private data class MapTile(val x: Int, val y: Int, val image: androidx.compose.ui.graphics.ImageBitmap)

@Composable
fun OnlineRouteMap(
    points: List<GeoSample>,
    buoys: List<SavedBuoy>,
    latitude: Double?,
    longitude: Double?,
    ambient: Boolean
) {
    val center = points.lastOrNull()
    val lat = latitude ?: center?.latitude
    val lon = longitude ?: center?.longitude
    val zoom = 16
    val centerX = if (lat != null && lon != null) floor(tileX(lon, zoom)).toInt() else -1
    val centerY = if (lat != null && lon != null) floor(tileY(lat, zoom)).toInt() else -1
    var tiles by remember { mutableStateOf<List<MapTile>>(emptyList()) }
    var mapStatus by remember { mutableStateOf("Esperando posición GPS") }

    LaunchedEffect(centerX, centerY) {
        if (centerX < 0 || centerY < 0) {
            tiles = emptyList()
            mapStatus = "Inicia una sesión para cargar el mapa"
        } else {
            mapStatus = "Cargando mapa online…"
            tiles = withContext(Dispatchers.IO) {
                coroutineScope {
                    (-1..1).flatMap { dx ->
                        (-1..1).map { dy ->
                            async {
                                val tx = centerX + dx
                                val ty = centerY + dy
                                val bitmap = runCatching {
                                    val connection = URL("https://tile.openstreetmap.org/$zoom/$tx/$ty.png").openConnection()
                                    connection.connectTimeout = 2500
                                    connection.readTimeout = 2500
                                    connection.setRequestProperty("User-Agent", "OpenKayak-WearOS/2.1 (Wear OS kayaking app)")
                                    connection.getInputStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                                }.getOrNull()
                                if (bitmap == null) null else MapTile(tx, ty, bitmap)
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }
            mapStatus = if (tiles.isEmpty()) "Sin conexión: mapa no disponible" else "Mapa OpenStreetMap · zoom $zoom"
        }
    }

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(if (ambient) Color.Black else Color(0xFF101820))) {
        Canvas(Modifier.fillMaxSize()) {
            val lat0 = lat
            val lon0 = lon
            if (lat0 != null && lon0 != null) {
                val worldX = tileX(lon0, zoom)
                val worldY = tileY(lat0, zoom)
                val scale = density
                val tileSize = 256f * scale
                tiles.forEach { tile ->
                    val left = (tile.x - worldX) * tileSize + size.width / 2f
                    val top = (tile.y - worldY) * tileSize + size.height / 2f
                    drawImage(tile.image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(tileSize.toInt(), tileSize.toInt()))
                }
                fun screen(latP: Double, lonP: Double): Offset {
                    val x = (tileX(lonP, zoom) - worldX) * tileSize + size.width / 2f
                    val y = (tileY(latP, zoom) - worldY) * tileSize + size.height / 2f
                    return Offset(x, y)
                }
                val track = points.map { screen(it.latitude, it.longitude) }
                for (i in 1 until track.size) {
                    drawLine(Color(0xFF42D9FF), track[i - 1], track[i], strokeWidth = 3.5f)
                }
                buoys.forEach { buoy ->
                    drawCircle(if (buoy.isTurn) Color(0xFFFF8FA3) else Color(0xFF76F4D0), radius = 5f, center = screen(buoy.latitude, buoy.longitude))
                }
                drawCircle(Color.White, radius = 4f, center = Offset(size.width / 2f, size.height / 2f))
            }
        }
        if (tiles.isEmpty()) {
            Text(mapStatus, modifier = Modifier.align(Alignment.Center), color = Color.White, fontSize = 8.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
        }
    }
}

private fun tileX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * (1 shl zoom)
private fun tileY(lat: Double, zoom: Int): Double {
    val safe = lat.coerceIn(-85.0511, 85.0511)
    val rad = Math.toRadians(safe)
    return (1.0 - ln(sinh(rad) + 1.0 / kotlin.math.cosh(rad)) / Math.PI) / 2.0 * (1 shl zoom)
}