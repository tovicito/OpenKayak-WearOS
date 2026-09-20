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
    val statusMessage: String = "Listo para descargar Asturias + Embalse de Trasona"
)

class MapTileDownloader(private val context: Context) {

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val asturiasBoundingBox = BoundingBox(
        43.60, // Norte
        -4.50, // Este
        42.85, // Sur
        -7.20  // Oeste
    )

    private val trasonaBoundingBox = BoundingBox(
        43.56, // Norte
        -5.87, // Este
        43.51, // Sur
        -5.93  // Oeste
    )

    fun isNetworkAvailable(): Boolean {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)

            val hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val hasCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val hasBluetooth = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true
            val hasCapability = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            @SuppressLint("MissingPermission")
            val isBtBonded = adapter != null && adapter.isEnabled && (adapter.bondedDevices?.isNotEmpty() == true)

            return hasWifi || hasCellular || hasBluetooth || hasCapability || isBtBonded
        } catch (e: Exception) {
            Log.w(TAG, "Network check exception: ${e.localizedMessage}")
            return true // Fallback to true so downloads are not blocked unnecessarily
        }
    }

    fun downloadAsturiasOfflineMap() {
        if (!isNetworkAvailable()) {
            _downloadState.update {
                it.copy(
                    statusMessage = "Error: Conecta el reloj a Wi-Fi o Bluetooth para descargar."
                )
            }
            return
        }
        if (_downloadState.value.isDownloading) return

        _downloadState.update {
            DownloadState(
                isDownloading = true,
                statusMessage = "Calculando teselas de Asturias y Trasona..."
            )
        }

        scope.launch {
            try {
                val osmdroidDir = java.io.File(context.filesDir, "osmdroid")
                val tilesDir = java.io.File(osmdroidDir, "tiles")
                if (!tilesDir.exists()) tilesDir.mkdirs()

                Configuration.getInstance().osmdroidBasePath = osmdroidDir
                Configuration.getInstance().osmdroidTileCache = tilesDir
                Configuration.getInstance().userAgentValue = context.packageName

                val (cacheManager, tilesAsturias, tilesTrasona) = run {
                    val mapView = MapView(context).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                    }
                    val cm = CacheManager(mapView)
                    val tAst = try { cm.possibleTilesInArea(asturiasBoundingBox, 10, 12) } catch (e: Exception) { 150 }
                    val tTra = try { cm.possibleTilesInArea(trasonaBoundingBox, 13, 14) } catch (e: Exception) { 100 }
                    Triple(cm, tAst, tTra)
                }
                val totalCombinedTiles = tilesAsturias + tilesTrasona

                _downloadState.update {
                    it.copy(
                        totalTiles = totalCombinedTiles,
                        currentPhaseText = "Fase 1/2: Asturias Z10-Z14 ($tilesAsturias teselas)",
                        statusMessage = "Iniciando Fase 1: Asturias Z10-Z14..."
                    )
                }

                cacheManager.downloadAreaAsync(
                    context,
                    asturiasBoundingBox,
                    10,
                    12,
                    object : CacheManager.CacheManagerCallback {
                        override fun onTaskComplete() {
                            _downloadState.update {
                                it.copy(
                                    currentPhaseText = "Fase 2/2: Embalse de Trasona Z13-Z14 ($tilesTrasona teselas)",
                                    statusMessage = "Iniciando Fase 2: Trasona Z13-Z14..."
                                )
                            }

                            cacheManager.downloadAreaAsync(
                                context,
                                trasonaBoundingBox,
                                13,
                                14,
                                object : CacheManager.CacheManagerCallback {
                                    override fun onTaskComplete() {
                                        _downloadState.update {
                                            DownloadState(
                                                isDownloading = false,
                                                progressPercent = 100,
                                                downloadedTiles = totalCombinedTiles,
                                                totalTiles = totalCombinedTiles,
                                                currentPhaseText = "Completado",
                                                statusMessage = "¡Mapa de Asturias y Trasona completado!"
                                            )
                                        }
                                    }

                                    override fun onTaskFailed(errors: Int) {
                                        _downloadState.update {
                                            it.copy(
                                                isDownloading = false,
                                                statusMessage = "Descarga de Trasona completada con $errors aviso(s)."
                                            )
                                        }
                                    }

                                    override fun updateProgress(
                                        progress: Int,
                                        currentZoomLevel: Int,
                                        zoomMin: Int,
                                        zoomMax: Int
                                    ) {
                                        val totalDone = tilesAsturias + progress
                                        val percent = if (totalCombinedTiles > 0) {
                                            ((totalDone.toFloat() / totalCombinedTiles) * 100).toInt()
                                        } else 0

                                        _downloadState.update {
                                            it.copy(
                                                downloadedTiles = totalDone,
                                                progressPercent = percent.coerceIn(0, 100),
                                                statusMessage = "Trasona Z13-Z14: $progress/$tilesTrasona ($percent%)"
                                            )
                                        }
                                    }

                                    override fun downloadStarted() {}
                                    override fun setPossibleTilesInArea(total: Int) {}
                                }
                            )
                        }

                        override fun onTaskFailed(errors: Int) {
                            _downloadState.update {
                                it.copy(
                                    isDownloading = false,
                                    statusMessage = "Descarga de Asturias completada con $errors aviso(s)."
                                )
                            }
                        }

                        override fun updateProgress(
                            progress: Int,
                            currentZoomLevel: Int,
                            zoomMin: Int,
                            zoomMax: Int
                        ) {
                            val percent = if (totalCombinedTiles > 0) {
                                ((progress.toFloat() / totalCombinedTiles) * 100).toInt()
                            } else 0

                            _downloadState.update {
                                it.copy(
                                    downloadedTiles = progress,
                                    progressPercent = percent.coerceIn(0, 100),
                                    statusMessage = "Asturias Z10-Z14: $progress/$tilesAsturias ($percent%)"
                                )
                            }
                        }

                        override fun downloadStarted() {}
                        override fun setPossibleTilesInArea(total: Int) {}
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Download Exception: ${e.localizedMessage}")
                _downloadState.update {
                    DownloadState(
                        isDownloading = false,
                        statusMessage = "Error en descarga: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "MapTileDownloader"
    }
}
