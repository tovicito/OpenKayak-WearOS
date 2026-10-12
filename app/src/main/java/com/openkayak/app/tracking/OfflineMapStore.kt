package com.openkayak.app.tracking

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * Offline raster MBTiles plus a persistent cache of tiles the user has already viewed.
 *
 * The public OSM tile server is intentionally not used for bulk/offline prefetching.
 * A provider-supplied MBTiles package can be imported instead.
 */
object OfflineMapStore {
    private const val PACK_FILE = "openkayak-offline-map.mbtiles"
    private const val STAGING_FILE = "openkayak-offline-map.pending.mbtiles"
    private const val BACKUP_FILE = "openkayak-offline-map.previous.mbtiles"
    private const val MAX_IMPORT_BYTES = 512L * 1024L * 1024L
    private const val MAX_CACHED_TILES = 512

    private var openDatabase: SQLiteDatabase? = null
    private var openPath: String? = null
    private var openModified: Long = -1L

    fun cacheDirectory(context: Context): File =
        File(context.filesDir, "openkayak-map-tiles").apply { mkdirs() }

    fun packFile(context: Context): File = File(context.filesDir, PACK_FILE)

    fun status(context: Context): String {
        val pack = packFile(context)
        val cachedTiles = cacheDirectory(context).listFiles()?.count { it.isFile } ?: 0
        return if (pack.isFile && pack.length() > 0L) {
            "Paquete offline instalado · ${formatBytes(pack.length())} · caché vista: $cachedTiles teselas"
        } else {
            "Sin paquete MBTiles · se guardan las teselas vistas ($cachedTiles); importa un paquete raster para ampliar la cobertura offline."
        }
    }

    fun attribution(context: Context): String {
        val providerAttribution = database(context)?.let { readMetadata(it, "attribution") }
            ?.takeIf { it.isNotBlank() }
        return when {
            providerAttribution == null -> "© OpenStreetMap contributors"
            providerAttribution.contains("openstreetmap", ignoreCase = true) -> providerAttribution
            else -> "$providerAttribution · © OpenStreetMap contributors"
        }
    }

    /**
     * Copies and validates an MBTiles raster archive selected by the user.
     * A bounded size prevents accidentally filling the watch with a regional multi-GB pack.
     */
    fun importMbtiles(context: Context, uri: Uri): String {
        val staging = File(context.filesDir, STAGING_FILE)
        val target = packFile(context)
        val backup = File(context.filesDir, BACKUP_FILE)
        runCatching { staging.delete() }

        val declaredSize = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
        if (declaredSize > MAX_IMPORT_BYTES) {
            throw IOException("El paquete supera el límite de 512 MiB para proteger el almacenamiento del reloj.")
        }

        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo seleccionado.")
            input.use { source ->
                staging.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_IMPORT_BYTES) {
                            throw IOException("El paquete supera el límite de 512 MiB para proteger el almacenamiento del reloj.")
                        }
                        sink.write(buffer, 0, read)
                    }
                }
            }

            validateRasterMbtiles(staging)
            synchronized(this) {
                closeCachedDatabase()
                runCatching { backup.delete() }
                if (target.exists() && !target.renameTo(backup)) {
                    throw IOException("No se pudo apartar el mapa offline anterior.")
                }
                if (!staging.renameTo(target)) {
                    if (backup.exists()) backup.renameTo(target)
                    throw IOException("No se pudo instalar el paquete MBTiles.")
                }
                runCatching { backup.delete() }
                closeCachedDatabase()
            }
            return "Mapa offline importado · ${formatBytes(target.length())}"
        } catch (error: Throwable) {
            runCatching { staging.delete() }
            throw error
        }
    }

    /**
     * Returns PNG/JPEG bytes for an imported raster tile, or null when the pack has no tile
     * at this zoom/coordinate. MBTiles normally stores rows in TMS order.
     */
    @Synchronized
    fun tileBytes(context: Context, zoom: Int, x: Int, y: Int): ByteArray? {
        val db = database(context) ?: return null
        return runCatching {
            val scheme = readMetadata(db, "scheme")?.lowercase()
            val primaryRow = if (scheme == "xyz") y else (1 shl zoom) - 1 - y
            queryTile(db, zoom, x, primaryRow) ?: queryTile(db, zoom, x, if (primaryRow == y) (1 shl zoom) - 1 - y else y)
        }.getOrNull()
    }

    @Synchronized
    fun trimViewedCache(context: Context) {
        val files = cacheDirectory(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_CACHED_TILES).forEach { runCatching { it.delete() } }
    }

    private fun validateRasterMbtiles(file: File) {
        if (!file.isFile || file.length() < 4096L) throw IOException("El archivo no parece una base MBTiles válida.")
        val db = try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (error: Throwable) {
            throw IOException("No se pudo abrir la base de datos MBTiles.", error)
        }
        db.use { database ->
            database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='tiles' LIMIT 1", null).use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("El MBTiles no contiene la tabla raster 'tiles'.")
            }
            val format = readMetadata(database, "format")?.lowercase()
            if (format != null && format !in setOf("png", "jpg", "jpeg", "webp")) {
                throw IOException("Este paquete usa formato '$format'; se necesita MBTiles raster PNG/JPEG/WEBP.")
            }
            val bytes = database.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null).use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("El paquete MBTiles no contiene teselas.")
                cursor.getBlob(0)
            }
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) {
                throw IOException("El paquete contiene teselas vectoriales o un formato de imagen no compatible.")
            }
            if (readMetadata(database, "attribution").isNullOrBlank()) {
                throw IOException("El paquete no declara atribución en metadata. Importa un MBTiles que incluya la atribución del proveedor.")
            }
        }
    }

    private fun database(context: Context): SQLiteDatabase? {
        val file = packFile(context)
        if (!file.isFile || file.length() == 0L) {
            closeCachedDatabase()
            return null
        }
        if (openDatabase?.isOpen == true && openPath == file.absolutePath && openModified == file.lastModified()) {
            return openDatabase
        }
        closeCachedDatabase()
        return runCatching {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).also {
                openDatabase = it
                openPath = file.absolutePath
                openModified = file.lastModified()
            }
        }.getOrNull()
    }

    private fun queryTile(db: SQLiteDatabase, zoom: Int, x: Int, row: Int): ByteArray? {
        return db.query(
            "tiles",
            arrayOf("tile_data"),
            "zoom_level = ? AND tile_column = ? AND tile_row = ?",
            arrayOf(zoom.toString(), x.toString(), row.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
    }

    private fun readMetadata(db: SQLiteDatabase, name: String): String? = runCatching {
        db.query("metadata", arrayOf("value"), "name = ?", arrayOf(name), null, null, null, "1")
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun closeCachedDatabase() {
        runCatching { openDatabase?.close() }
        openDatabase = null
        openPath = null
        openModified = -1L
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
