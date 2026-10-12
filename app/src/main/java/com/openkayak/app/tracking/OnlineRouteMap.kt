package com.openkayak.app.tracking

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh

private data class RouteMapTile(val x: Int, val y: Int, val image: androidx.compose.ui.graphics.ImageBitmap)

private object RouteMapTileCache {
    private val values = object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>?): Boolean = size > 48
    }
    @Synchronized fun get(key: String) = values[key]
    @Synchronized fun put(key: String, image: androidx.compose.ui.graphics.ImageBitmap) { values[key] = image }
}

@Composable
fun OnlineRouteMap(
    points: List<GeoSample>,
    buoys: List<SavedBuoy>,
    latitude: Double?,
    longitude: Double?,
    ambient: Boolean,
    allowNetwork: Boolean = true,
    followPosition: Boolean = false
) {
    val context = LocalContext.current
    var routeColorHex by remember(context) { mutableStateOf(AppSettingsStore.routeColorHex(context)) }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences(AppSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppSettingsStore.ROUTE_COLOR_KEY) {
                routeColorHex = AppSettingsStore.routeColorHex(context)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val routeLineColor = Color(routeColorHex.toLongOrNull(16) ?: 0xFFFF8500L)
    val tileSizePx = with(LocalDensity.current) { 256.dp.toPx() }
    val firstPoint = points.firstOrNull()
    val routeKey = remember(firstPoint, points.size) {
        if (points.isEmpty()) 0 else 31 * points.size + points.first().hashCode()
    }
    val boundsCenter = remember(routeKey) {
        if (points.isEmpty()) null else GeoSample(
            (points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2.0,
            (points.minOf { it.longitude } + points.maxOf { it.longitude }) / 2.0
        )
    }
    var centerLat by remember(routeKey, followPosition) {
        mutableDoubleStateOf(if (!followPosition && boundsCenter != null) boundsCenter.latitude else latitude ?: boundsCenter?.latitude ?: 43.55)
    }
    var centerLon by remember(routeKey, followPosition) {
        mutableDoubleStateOf(if (!followPosition && boundsCenter != null) boundsCenter.longitude else longitude ?: boundsCenter?.longitude ?: -5.92)
    }
    var zoom by remember { mutableIntStateOf(AppSettingsStore.mapZoom(context)) }
    var userPanned by remember(routeKey) { mutableStateOf(false) }
    var tiles by remember { mutableStateOf<List<RouteMapTile>>(emptyList()) }
    var status by remember { mutableStateOf("Esperando posición GPS") }

    LaunchedEffect(latitude, longitude, followPosition, userPanned) {
        if (followPosition && !userPanned && latitude != null && longitude != null) {
            centerLat = latitude
            centerLon = longitude
        }
    }

    val centerX = tileX(centerLon, zoom)
    val centerY = tileY(centerLat, zoom)
    val centerTileX = floor(centerX).toInt()
    val centerTileY = floor(centerY).toInt()

    LaunchedEffect(centerTileX, centerTileY, zoom, allowNetwork, ambient) {
        status = "Cargando mosaicos…"
        val loaded = withContext(Dispatchers.IO) {
            val result = mutableListOf<RouteMapTile>()
            for (dy in -1..1) for (dx in -1..1) {
                val x = centerTileX + dx
                val y = centerTileY + dy
                if (y !in 0 until (1 shl zoom)) continue
                loadRouteTile(context, zoom, x, y, allowNetwork && !ambient)?.let { result.add(RouteMapTile(x, y, it)) }
                if (allowNetwork && !ambient) delay(80L)
            }
            result
        }
        tiles = loaded
        status = when {
            loaded.isNotEmpty() && (!allowNetwork || ambient) -> "Mapa guardado · sin red"
            loaded.isNotEmpty() -> "OpenStreetMap · Z$zoom · arrastra para mover"
            points.isEmpty() -> "Sin teselas: conecta a Internet o importa MBTiles"
            else -> "Sin teselas guardadas: conecta a Internet o importa MBTiles"
        }
    }

    val dragModifier = if (!ambient) Modifier.pointerInput(zoom) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            val worldX = tileX(centerLon, zoom) - dragAmount.x / tileSizePx.toDouble()
            val worldY = tileY(centerLat, zoom) - dragAmount.y / tileSizePx.toDouble()
            val moved = geoAtTile(worldX, worldY, zoom)
            centerLat = moved.latitude
            centerLon = moved.longitude
            userPanned = true
        }
    } else Modifier

    Box(Modifier.fillMaxSize().then(dragModifier).clip(RoundedCornerShape(22.dp))
        .background(if (ambient) Color.Black else Color(0xFF101820))) {
        Canvas(Modifier.fillMaxSize()) {
            // OSM tiles are 256 px; scaling them by display density distorts the map.
            val tileSize = tileSizePx
            tiles.forEach { tile ->
                val left = ((tile.x - centerX) * tileSize + size.width / 2f).toInt()
                val top = ((tile.y - centerY) * tileSize + size.height / 2f).toInt()
                drawImage(tile.image, dstOffset = IntOffset(left, top), dstSize = IntSize(tileSize.toInt(), tileSize.toInt()))
            }
            fun screen(point: GeoSample): Offset = Offset(
                ((tileX(point.longitude, zoom) - centerX) * tileSize + size.width / 2f).toFloat(),
                ((tileY(point.latitude, zoom) - centerY) * tileSize + size.height / 2f).toFloat()
            )
            val track = points.map(::screen)
            for (index in 1 until track.size) {
                val a = track[index - 1]
                val b = track[index]
                if ((a.x in -40f..(size.width + 40f) || b.x in -40f..(size.width + 40f)) &&
                    (a.y in -40f..(size.height + 40f) || b.y in -40f..(size.height + 40f))) {
                    drawLine(Color(0xE807111C), a, b, strokeWidth = 7f)
                    drawLine(routeLineColor, a, b, strokeWidth = 4f)
                }
            }
            buoys.filterNot { it.turnType.equals("CORRECTION", ignoreCase = true) }.forEach { buoy ->
                val color = when (buoy.turnType.uppercase()) {
                    "UTURN", "U_TURN", "U-TURN" -> Color(0xFFFFB54A)
                    "BUOY", "BOYA" -> Color(0xFFFF8FA3)
                    else -> if (buoy.isTurn) Color(0xFFFF8FA3) else Color(0xFF76F4D0)
                }
                val radius = if (buoy.turnType.equals("UTURN", true)) 6f else 4.5f
                drawCircle(color, radius, screen(GeoSample(buoy.latitude, buoy.longitude)))
            }
        }
        if (tiles.isEmpty()) Text(status, Modifier.align(Alignment.Center), color = Color.White,
            fontSize = 8.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
        Text(OfflineMapStore.attribution(context), Modifier.align(Alignment.BottomStart).padding(3.dp), color = Color.White, fontSize = 6.sp, maxLines = 1)
        if (!ambient) {
            Row(Modifier.align(Alignment.TopEnd).background(Color(0xDD101820), RoundedCornerShape(12.dp))) {
                Text("−", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { zoom = (zoom - 1).coerceIn(14, 17); userPanned = true }
                        .padding(horizontal = 10.dp, vertical = 3.dp))
                Text("+", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { zoom = (zoom + 1).coerceIn(14, 17); userPanned = true }
                        .padding(horizontal = 10.dp, vertical = 3.dp))
            }
        }
    }
}

private fun loadRouteTile(context: Context, zoom: Int, x: Int, y: Int, allowNetwork: Boolean): androidx.compose.ui.graphics.ImageBitmap? {
    val count = 1 shl zoom
    val wrappedX = ((x % count) + count) % count
    val key = "$zoom/$wrappedX/$y"
    // Imported provider-supplied MBTiles is authoritative for tiles it contains.
    val imported = OfflineMapStore.tileBytes(context, zoom, wrappedX, y)
    if (imported != null) {
        val image = runCatching { BitmapFactory.decodeByteArray(imported, 0, imported.size)?.asImageBitmap() }.getOrNull()
        if (image != null) { RouteMapTileCache.put(key, image); return image }
    }
    RouteMapTileCache.get(key)?.let { return it }

    // Persistent disk cache keeps only tiles requested by normal interactive viewing.
    val directory = OfflineMapStore.cacheDirectory(context)
    val file = File(directory, "$zoom-$wrappedX-$y.png")
    val legacyFile = File(context.cacheDir, "openkayak-map-tiles/$zoom-$wrappedX-$y.png")
    val existingFile = listOf(file, legacyFile).firstOrNull { it.isFile && it.length() > 0L }
    if (existingFile != null) {
        val cached = runCatching { BitmapFactory.decodeFile(existingFile.absolutePath)?.asImageBitmap() }.getOrNull()
        if (cached != null) { RouteMapTileCache.put(key, cached); return cached }
    }
    if (!allowNetwork) return null
    val bitmap = runCatching {
        val connection = URL("https://tile.openstreetmap.org/$zoom/$wrappedX/$y.png").openConnection()
        connection.connectTimeout = 2500
        connection.readTimeout = 2500
        connection.setRequestProperty("User-Agent", "OpenKayak-WearOS/2.1 (https://github.com/tovicito/OpenKayak-WearOS)")
        val bytes = connection.getInputStream().use { it.readBytes() }
        file.writeBytes(bytes)
        OfflineMapStore.trimViewedCache(context)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
    if (bitmap != null) RouteMapTileCache.put(key, bitmap)
    return bitmap
}

private fun tileX(longitude: Double, zoom: Int): Double = (longitude + 180.0) / 360.0 * (1 shl zoom)
private fun tileY(latitude: Double, zoom: Int): Double {
    val safe = latitude.coerceIn(-85.0511, 85.0511)
    val radians = Math.toRadians(safe)
    return (1.0 - ln(kotlin.math.tan(radians) + 1.0 / kotlin.math.cos(radians)) / Math.PI) / 2.0 * (1 shl zoom)
}
private fun geoAtTile(x: Double, y: Double, zoom: Int): GeoSample {
    val count = (1 shl zoom).toDouble()
    val normalizedY = Math.PI - 2.0 * Math.PI * y / count
    val latitude = Math.toDegrees(kotlin.math.atan(sinh(normalizedY))).coerceIn(-85.0511, 85.0511)
    val longitude = (x / count * 360.0 - 180.0).coerceIn(-180.0, 180.0)
    return GeoSample(latitude, longitude)
}
