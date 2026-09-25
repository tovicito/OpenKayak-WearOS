package com.openkayak.app.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView

data class DownloadState(
    val isDownloading: Boolean = false,
    val progressPercent: Int = 0,
    val downloadedTiles: Int = 0,
    val totalTiles: Int = 0,
    val currentPhaseText: String = "",
    val statusMessage: String = "Listo para descargar Asturias (Z10-Z14)"
)

class MapTileDownloader(private val context: Context) {
    companion object {
        private const val TAG = "OpenKayakMaps"
        private const val MIN_ZOOM = 10
        private const val MAX_ZOOM = 14
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _state = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _state.asStateFlow()
    private val asturias = BoundingBox(43.60, -4.50, 42.85, -7.20)

    fun downloadAsturiasOfflineMap() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = DownloadState(isDownloading = true, statusMessage = "Preparando mapa offline…")
            try {
                Configuration.getInstance().apply {
                    osmdroidBasePath = context.getDir("osmdroid", Context.MODE_PRIVATE)
                    osmdroidTileCache = java.io.File(osmdroidBasePath, "tiles").apply { mkdirs() }
                    userAgentValue = context.packageName
                }

                val mapView = MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setUseDataConnection(true)
                }

                val manager = CacheManager(mapView)
                    val total = manager.possibleTilesInArea(asturias, MIN_ZOOM, MAX_ZOOM)
                    _state.update {
                        it.copy(
                            totalTiles = total,
                            currentPhaseText = "Asturias Z" + MIN_ZOOM + "-Z" + MAX_ZOOM,
                            statusMessage = "Descargando " + total + " teselas…"
                        )
                    }

                manager.downloadAreaAsync(
                        context,
                        asturias,
                        MIN_ZOOM,
                        MAX_ZOOM,
                        object : CacheManager.CacheManagerCallback {
                            override fun downloadStarted() {
                                _state.update { it.copy(statusMessage = "Descarga iniciada…") }
                            }

                            override fun setPossibleTilesInArea(total: Int) {
                                _state.update { it.copy(totalTiles = total) }
                            }

                            override fun updateProgress(
                                progress: Int,
                                currentZoomLevel: Int,
                                zoomMin: Int,
                                zoomMax: Int
                            ) {
                                val totalNow = _state.value.totalTiles
                                val percent = if (totalNow > 0) (progress * 100 / totalNow).coerceIn(0, 100) else 0
                                _state.update {
                                    it.copy(
                                        downloadedTiles = progress,
                                        progressPercent = percent,
                                        currentPhaseText = "Z" + currentZoomLevel,
                                        statusMessage = "Teselas " + progress + "/" + totalNow + " (" + percent + "%)"
                                    )
                                }
                            }

                            override fun onTaskComplete() {
                                _state.value = DownloadState(
                                    progressPercent = 100,
                                    downloadedTiles = _state.value.totalTiles,
                                    totalTiles = _state.value.totalTiles,
                                    currentPhaseText = "Completado",
                                    statusMessage = "Mapa offline de Asturias listo"
                                )
                            }

                            override fun onTaskFailed(errors: Int) {
                                _state.update {
                                    it.copy(
                                        isDownloading = false,
                                        statusMessage = "Descarga terminada con " + errors + " errores"
                                    )
                                }
                            }
                        }
                )
            } catch (t: Throwable) {
                // Log exception details securely internally, do not leak stack/exception message to UI
                Log.e(TAG, "Offline map download failed", t)
                _state.value = DownloadState(
                    isDownloading = false,
                    statusMessage = "No se pudo descargar el mapa: error al descargar"
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(isDownloading = false, statusMessage = "Descarga cancelada") }
    }
}
