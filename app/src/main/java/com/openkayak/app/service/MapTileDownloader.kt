package com.openkayak.app.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView

data class DownloadState(
    val isDownloading: Boolean = false,
    val progressPercent: Int = 0,
    val downloadedTiles: Int = 0,
    val totalTiles: Int = 0,
    val statusMessage: String = "Listo para descargar mapa de Asturias"
)

class MapTileDownloader(private val context: Context) {

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Bounding Box for Asturias, Spain
    private val asturiasBoundingBox = BoundingBox(
        43.60, // North
        -4.50, // East
        42.85, // South
        -7.20  // West
    )

    @SuppressLint("MissingPermission")
    fun isConnectedViaBluetooth(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

        // Check connected bluetooth profiles (Headset, A2DP, GATT, etc.)
        val isBtConnected = adapter != null && adapter.isEnabled && (
            adapter.getProfileConnectionState(BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED ||
            adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED ||
            adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        )

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
        val isBtNetwork = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true

        return isBtConnected || isBtNetwork
    }

    fun downloadAsturiasOfflineMap() {
        if (!isConnectedViaBluetooth()) {
            _downloadState.update {
                it.copy(
                    statusMessage = "Error: El reloj debe estar conectado por Bluetooth para descargar."
                )
            }
            return
        }

        if (_downloadState.value.isDownloading) return

        _downloadState.update {
            DownloadState(
                isDownloading = true,
                statusMessage = "Calculando teselas del mapa de Asturias..."
            )
        }

        scope.launch {
            try {
                val mapView = MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                }
                val cacheManager = CacheManager(mapView)

                val minZoom = 10
                val maxZoom = 14

                val totalTiles = cacheManager.possibleTilesInArea(
                    asturiasBoundingBox,
                    minZoom,
                    maxZoom
                )

                _downloadState.update {
                    it.copy(
                        totalTiles = totalTiles,
                        statusMessage = "Descargando Asturias ($totalTiles teselas)..."
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
                                    statusMessage = "¡Mapa de Asturias descargado y listo offline!"
                                )
                            }
                        }

                        override fun onTaskFailed(errors: Int) {
                            _downloadState.update {
                                it.copy(
                                    isDownloading = false,
                                    statusMessage = "Descarga pausada o fallo de red ($errors errores)."
                                )
                            }
                        }

                        override fun updateProgress(
                            progress: Int,
                            currentZoomLevel: Int,
                            zoomMin: Int,
                            zoomMax: Int
                        ) {
                            val percent = if (totalTiles > 0) ((progress.toFloat() / totalTiles) * 100).toInt() else 0
                            _downloadState.update {
                                it.copy(
                                    downloadedTiles = progress,
                                    progressPercent = percent.coerceIn(0, 100),
                                    statusMessage = "Descargando Asturias: $percent%"
                                )
                            }
                        }

                        override fun downloadStarted() {}
                        override fun setPossibleTilesInArea(total: Int) {}
                    }
                )
            } catch (e: Exception) {
                _downloadState.update {
                    DownloadState(
                        isDownloading = false,
                        statusMessage = "Error en descarga: ${e.localizedMessage}"
                    )
                }
            }
        }
    }
}
