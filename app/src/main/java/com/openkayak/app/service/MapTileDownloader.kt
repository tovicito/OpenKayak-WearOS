package com.openkayak.app.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val statusMessage: String = "Listo para descargar Asturias (Z10-Z14)"
)

class MapTileDownloader(private val context: Context) {

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Asturias Bounding Box: North = 43.60, East = -4.50, South = 42.85, West = -7.20
    private val asturiasBoundingBox = BoundingBox(
        43.60,
        -4.50,
        42.85,
        -7.20
    )

    fun isNetworkAvailable(): Boolean {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)

            val hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val hasCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val hasBluetooth = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            @SuppressLint("MissingPermission")
            val isBtBonded = adapter != null && adapter.isEnabled && (adapter.bondedDevices?.isNotEmpty() == true)

            return hasWifi || hasCellular || hasBluetooth || hasInternet || isBtBonded
        } catch (e: Exception) {
            Log.w(TAG, "Network check exception: ${e.localizedMessage}")
            return true
        }
    }

    fun downloadAsturiasOfflineMap() {
        if (!isNetworkAvailable()) {
            _downloadState.update {
                it.copy(statusMessage = "Error: Conecta el reloj a Wi-Fi o Bluetooth para descargar.")
            }
            return
        }

        if (_downloadState.value.isDownloading) return

        _downloadState.update {
            DownloadState(
                isDownloading = true,
                statusMessage = "Calculando teselas de Asturias Z10-Z14..."
            )
        }

        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val osmdroidDir = java.io.File(context.filesDir, "osmdroid")
                    val tilesDir = java.io.File(osmdroidDir, "tiles")
                    if (!tilesDir.exists()) tilesDir.mkdirs()

                    Configuration.getInstance().osmdroidBasePath = osmdroidDir
                    Configuration.getInstance().osmdroidTileCache = tilesDir
                    Configuration.getInstance().userAgentValue = context.packageName

                    val mapView = withContext(Dispatchers.Main) {
                        MapView(context).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                        }
                    }

                    val cacheManager = CacheManager(mapView)
                    val minZoom = 10
                    val maxZoom = 14

                    val totalTiles = try {
                        cacheManager.possibleTilesInArea(asturiasBoundingBox, minZoom, maxZoom)
                    } catch (e: Exception) {
                        250
                    }

                    _downloadState.update {
                        it.copy(
                            totalTiles = totalTiles,
                            currentPhaseText = "Asturias (Z$minZoom-Z$maxZoom)",
                            statusMessage = "Iniciando descarga de $totalTiles teselas..."
                        )
                    }

                    cacheManager.downloadAreaAsync(
                        context,
                        asturiasBoundingBox,
                        minZoom,
                        maxZoom,
                        object : CacheManager.CacheManagerCallback {
                            override fun onTaskComplete() {
                                _downloadState.update {
                                    DownloadState(
                                        isDownloading = false,
                                        progressPercent = 100,
                                        downloadedTiles = totalTiles,
                                        totalTiles = totalTiles,
                                        currentPhaseText = "Completado",
                                        statusMessage = "¡Mapa de Asturias (Z10-Z14) descargado con éxito!"
                                    )
                                }
                            }

                            override fun onTaskFailed(errors: Int) {
                                _downloadState.update {
                                    DownloadState(
                                        isDownloading = false,
                                        statusMessage = "Descarga de Asturias finalizada con $errors avisos."
                                    )
                                }
                            }

                            override fun updateProgress(
                                progress: Int,
                                currentZoomLevel: Int,
                                zoomMin: Int,
                                zoomMax: Int
                            ) {
                                val percent = if (totalTiles > 0) {
                                    ((progress.toFloat() / totalTiles) * 100).toInt().coerceIn(0, 100)
                                } else 0

                                _downloadState.update {
                                    it.copy(
                                        downloadedTiles = progress,
                                        progressPercent = percent,
                                        statusMessage = "Descargando Z$currentZoomLevel: $progress/$totalTiles ($percent%)"
                                    )
                                }
                            }

                            override fun downloadStarted() {}
                            override fun setPossibleTilesInArea(total: Int) {}
                        }
                    )
                } catch (e: Exception) {
                    // Log internal error securely without exposing exception details to the user interface
                    Log.e(TAG, "Download Exception: ${e.localizedMessage}")
                    _downloadState.update {
                        DownloadState(
                            isDownloading = false,
                            statusMessage = "Error en la descarga del mapa."
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MapTileDownloader"
    }
}
