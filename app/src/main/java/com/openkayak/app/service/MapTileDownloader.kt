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

    private val scope = CoroutineScope(Dispatchers.IO + Job())

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

    @SuppressLint("MissingPermission")
    fun isConnectedViaBluetooth(): Boolean {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()

            // Check if Bluetooth is enabled and has paired/bonded devices (watch paired to phone)
            val isBtEnabled = adapter != null && adapter.isEnabled && (adapter.bondedDevices?.isNotEmpty() == true)

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
            val isBtNetwork = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true

            return isBtEnabled || isBtNetwork
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth check exception: ${e.localizedMessage}")
            return true // Fallback to true so download is not blocked on watches with non-standard Bluetooth stacks
        }
    }

    fun downloadAsturiasOfflineMap() {
        if (!isConnectedViaBluetooth()) {
            _downloadState.update {
                it.copy(
                    statusMessage = "Error: Conecta el reloj por Bluetooth para descargar."
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
                // Ensure Osmdroid configuration user agent is set properly
                Configuration.getInstance().userAgentValue = context.packageName

                val (cacheManager, tilesAsturias, tilesTrasona) = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    val mapView = MapView(context).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                    }
                    val cm = CacheManager(mapView)
                    val tAst = cm.possibleTilesInArea(asturiasBoundingBox, 10, 14)
                    val tTra = cm.possibleTilesInArea(trasonaBoundingBox, 15, 16)
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
                    14,
                    object : CacheManager.CacheManagerCallback {
                        override fun onTaskComplete() {
                            _downloadState.update {
                                it.copy(
                                    currentPhaseText = "Fase 2/2: Embalse de Trasona Z15-Z16 ($tilesTrasona teselas)",
                                    statusMessage = "Iniciando Fase 2: Trasona Z15-Z16..."
                                )
                            }

                            cacheManager.downloadAreaAsync(
                                context,
                                trasonaBoundingBox,
                                15,
                                16,
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
                                                statusMessage = "Trasona Z15-Z16: $progress/$tilesTrasona ($percent%)"
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
                // Log detailed exception internally for debugging, but expose a sanitized error message to the user
                Log.e(TAG, "Download Exception", e)
                _downloadState.update {
                    DownloadState(
                        isDownloading = false,
                        statusMessage = "Error en la descarga del mapa."
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "MapTileDownloader"
    }
}
