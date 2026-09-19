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

/**
 * Estado de descarga del mapa offline de OpenKayak.
 */
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

    /*
     * 1. BoundingBox General de Asturias (Z10 a Z14):
     *    Cubre toda la comunidad autónoma de Asturias para navegación navegable general.
     */
    private val asturiasBoundingBox = BoundingBox(
        43.60, // Norte
        -4.50, // Este
        42.85, // Sur
        -7.20  // Oeste
    )

    /*
     * 2. BoundingBox de Alta Resolución para el Embalse de Trasona, Asturias (Z15 a Z16):
     *    Cubre el Embalse de Trasona completo junto con un margen perimetral amplio.
     *    Se limita deliberadamente Z15-Z16 a este cuadrante específico para controlar
     *    estrictamente el tamaño en disco del reloj inteligente y evitar generar miles de teselas
     *    innecesarias fuera de la zona de entrenamiento intensivo de kayak.
     */
    private val trasonaBoundingBox = BoundingBox(
        43.56, // Norte (margen superior de Trasona / Corvera)
        -5.87, // Este
        43.51, // Sur
        -5.93  // Oeste
    )

    @SuppressLint("MissingPermission")
    fun isConnectedViaBluetooth(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

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

    /**
     * Descarga de mapas en 2 Fases:
     * - Fase 1: Asturias (Z10 - Z14)
     * - Fase 2: Embalse de Trasona (Z15 - Z16)
     *
     * Nota de Zoom: Aunque la vista AOD utiliza Zoom 16.5, se descarga hasta Z16 porque las teselas
     * de mapa se sirven en niveles enteros. Osmdroid escala/interpola suavemente la imagen de Z16 a Z16.5
     * sin consumir almacenamiento adicional.
     */
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
                statusMessage = "Calculando teselas de Asturias y Trasona..."
            )
        }

        scope.launch {
            try {
                val mapView = MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                }
                val cacheManager = CacheManager(mapView)

                // Cálculo de teselas previa
                val tilesAsturias = cacheManager.possibleTilesInArea(asturiasBoundingBox, 10, 14)
                val tilesTrasona = cacheManager.possibleTilesInArea(trasonaBoundingBox, 15, 16)
                val totalCombinedTiles = tilesAsturias + tilesTrasona

                _downloadState.update {
                    it.copy(
                        totalTiles = totalCombinedTiles,
                        currentPhaseText = "Fase 1/2: Asturias Z10-Z14 ($tilesAsturias teselas)",
                        statusMessage = "Iniciando Fase 1: Asturias Z10-Z14..."
                    )
                }

                // Iniciar Fase 1: Asturias Z10-Z14
                cacheManager.downloadAreaAsync(
                    context,
                    asturiasBoundingBox,
                    10,
                    14,
                    object : CacheManager.CacheManagerCallback {
                        override fun onTaskComplete() {
                            // Fase 1 completada, iniciar Fase 2: Embalse de Trasona Z15-Z16
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
                                                statusMessage = "¡Mapa de Asturias y Embalse de Trasona listos!"
                                            )
                                        }
                                    }

                                    override fun onTaskFailed(errors: Int) {
                                        _downloadState.update {
                                            it.copy(
                                                isDownloading = false,
                                                statusMessage = "Error en Fase 2 (Trasona): $errors fallos"
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
                                    statusMessage = "Error en Fase 1 (Asturias): $errors fallos"
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
