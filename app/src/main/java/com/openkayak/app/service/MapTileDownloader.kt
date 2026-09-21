package com.openkayak.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val statusMessage: String = "Listo para descargar el mapa de Asturias"
)

class MapTileDownloader(private val context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val asturiasBoundingBox = BoundingBox(
        43.60,
        -4.50,
        42.85,
        -7.20
    )

    @Volatile
    private var activeDownload: Job? = null

    @Volatile
    private var activeTask: CacheManager.CacheManagerTask? = null

    private var downloadMapView: MapView? = null

    /*
     * CacheManager is tied to MapView and its tile provider.
     * MapView is a UI object, so it must be created and touched on Main.
     * The previous implementation created it on Main and then moved the same
     * object into Dispatchers.IO, causing the thread/main crash.
     */
    fun downloadAsturiasOfflineMap() {
        if (activeDownload?.isActive == true || _downloadState.value.isDownloading) return

        if (!isValidatedNetworkAvailable()) {
            _downloadState.value = DownloadState(
                statusMessage = "Sin Internet válido. Conecta el reloj a Wi-Fi."
            )
            return
        }

        activeDownload = scope.launch {
            _downloadState.value = DownloadState(
                isDownloading = true,
                currentPhaseText = "Preparando",
                statusMessage = "Preparando mapa offline de Asturias..."
            )

            try {
                configureOsmdroidOnMainThread()

                val mapView = MapView(appContext).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(false)
                }
                downloadMapView = mapView

                val cacheManager = CacheManager(mapView)
                val minZoom = 10
                val maxZoom = 14

                val totalTiles = runCatching {
                    cacheManager.possibleTilesInArea(
                        asturiasBoundingBox,
                        minZoom,
                        maxZoom
                    )
                }.getOrElse { error ->
                    Log.w(TAG, "Could not calculate tile count", error)
                    0
                }

                _downloadState.value = DownloadState(
                    isDownloading = true,
                    totalTiles = totalTiles,
                    currentPhaseText = "Asturias Z" + minZoom + "-Z" + maxZoom,
                    statusMessage = if (totalTiles > 0) {
                        "Preparando " + totalTiles + " teselas..."
                    } else {
                        "Preparando descarga..."
                    }
                )

                // IMPORTANT: use the no-UI variant. The normal method installs
                // osmdroid's ProgressDialog callback, which is inappropriate for
                // a standalone Wear OS activity and can crash when its window
                // lifecycle is not valid. CacheManager itself performs the work
                // asynchronously on its own worker thread.
                activeTask = cacheManager.downloadAreaAsyncNoUI(
                    appContext,
                    asturiasBoundingBox,
                    minZoom,
                    maxZoom,
                    object : CacheManager.CacheManagerCallback {
                        override fun downloadStarted() {
                            postState {
                                it.copy(
                                    isDownloading = true,
                                    currentPhaseText = "Descargando"
                                )
                            }
                        }

                        override fun setPossibleTilesInArea(total: Int) {
                            postState {
                                it.copy(
                                    totalTiles = total,
                                    currentPhaseText = "Asturias Z" + minZoom + "-Z" + maxZoom
                                )
                            }
                        }

                        override fun updateProgress(
                            progress: Int,
                            currentZoomLevel: Int,
                            zoomMin: Int,
                            zoomMax: Int
                        ) {
                            val knownTotal = _downloadState.value.totalTiles
                            val percent = if (knownTotal > 0) {
                                (progress * 100L / knownTotal).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }

                            postState {
                                it.copy(
                                    isDownloading = true,
                                    downloadedTiles = progress,
                                    progressPercent = percent,
                                    currentPhaseText = "Zoom " + currentZoomLevel + "/" + zoomMax,
                                    statusMessage = "Descargando Z" + currentZoomLevel +
                                        ": " + progress + "/" + knownTotal + " (" + percent + "%)"
                                )
                            }
                        }

                        override fun onTaskComplete() {
                            postState {
                                it.copy(
                                    isDownloading = false,
                                    progressPercent = 100,
                                    downloadedTiles = it.totalTiles,
                                    currentPhaseText = "Completado",
                                    statusMessage = "Mapa de Asturias descargado"
                                )
                            }
                            activeTask = null
                            releaseMapView()
                        }

                        override fun onTaskFailed(errors: Int) {
                            postState {
                                it.copy(
                                    isDownloading = false,
                                    currentPhaseText = "Error",
                                    statusMessage = "La descarga terminó con " + errors + " errores"
                                )
                            }
                            activeTask = null
                            releaseMapView()
                        }
                    }
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                _downloadState.value = DownloadState(statusMessage = "Descarga cancelada")
                releaseMapView()
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Offline map download failed", error)
                _downloadState.value = DownloadState(
                    statusMessage = "Error del mapa: " + (error.localizedMessage ?: "desconocido")
                )
                releaseMapView()
            }
        }
    }

    private suspend fun configureOsmdroidOnMainThread() {
        withContext(Dispatchers.Main.immediate) {
            val config = Configuration.getInstance()
            val base = java.io.File(appContext.filesDir, "osmdroid")
            val tiles = java.io.File(base, "tiles")
            if (!tiles.exists() && !tiles.mkdirs()) {
                Log.w(TAG, "Could not create tile cache directory: " + tiles.absolutePath)
            }
            config.osmdroidBasePath = base
            config.osmdroidTileCache = tiles
            config.userAgentValue = appContext.packageName
        }
    }

    private fun postState(transform: (DownloadState) -> DownloadState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _downloadState.update(transform)
        } else {
            scope.launch {
                _downloadState.update(transform)
            }
        }
    }

    private fun releaseMapView() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            scope.launch { releaseMapView() }
            return
        }
        try {
            downloadMapView?.onDetach()
        } catch (error: Exception) {
            Log.w(TAG, "MapView detach warning", error)
        } finally {
            downloadMapView = null
            activeDownload = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun isValidatedNetworkAvailable(): Boolean {
        return try {
            val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false

            val active = connectivity.activeNetwork ?: return false
            val capabilities = connectivity.getNetworkCapabilities(active) ?: return false

            val hasInternet = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
            val isValidated = capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
            val supportedTransport =
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)

            hasInternet && isValidated && supportedTransport
        } catch (error: Exception) {
            Log.w(TAG, "Network validation failed", error)
            false
        }
    }

    fun clearState() {
        scope.launch {
            if (activeDownload?.isActive == true) return@launch
            _downloadState.value = DownloadState()
        }
    }

    fun close() {
        activeDownload?.cancel()
        activeDownload = null
        try {
            activeTask?.cancel(true)
        } catch (error: Exception) {
            Log.w(TAG, "Could not cancel map download task", error)
        } finally {
            activeTask = null
        }
        releaseMapView()
        scope.cancel()
    }

    companion object {
        private const val TAG = "MapTileDownloader"
    }
}
