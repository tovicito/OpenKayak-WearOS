package com.openkayak.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.openkayak.app.tracking.TrackStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BlePhase { IDLE, SCANNING, CONNECTING, CONNECTED, ERROR }
data class BleCandidate(val name: String, val address: String)
data class BleHrState(
    val phase: BlePhase = BlePhase.IDLE,
    val candidates: List<BleCandidate> = emptyList(),
    val name: String = "",
    val address: String = "",
    val bpm: Int = 0,
    val lastSampleAt: Long = 0L,
    val notificationsEnabled: Boolean = false,
    val detail: String = "Banda desconectada"
) {
    val isFresh: Boolean get() =
        phase == BlePhase.CONNECTED && bpm in 25..240 &&
            lastSampleAt > 0L && System.currentTimeMillis() - lastSampleAt < 12_000L
}

class BleHeartRateManager(context: Context) {
    companion object {
        val HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFS = "openkayak_ble"
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(BleHrState())
    val state: StateFlow<BleHrState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var currentGatt: BluetoothGatt? = null
    private var targetAddress: String? = prefs.getString("preferred_address", null)
    private var autoReconnect = false
    private var reconnectAttempts = 0
    private var lastDataOrEnableAt = 0L

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisesHeartRate =
                result.scanRecord?.serviceUuids?.any { it.uuid == HEART_RATE_SERVICE } == true
            if (!advertisesHeartRate) return
            val device = result.device
            val name = runCatching { device.name }.getOrNull()
                ?: result.scanRecord?.deviceName ?: "Banda de pulso"
            val candidate = BleCandidate(name, device.address)
            _state.update { old ->
                old.copy(candidates = (old.candidates.filterNot { it.address == candidate.address } + candidate).takeLast(8))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(phase = BlePhase.ERROR, detail = "Falló la búsqueda BLE ($errorCode)") }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== currentGatt) {
                runCatching { g.close() }
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                _state.update { it.copy(phase = BlePhase.CONNECTING, detail = "Leyendo servicios de la banda…") }
                val started = runCatching { g.discoverServices() }.getOrDefault(false)
                if (!started) failConnection(g, "No se pudieron descubrir los servicios BLE")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val shouldReconnect = autoReconnect
                currentGatt = null
                runCatching { g.close() }
                watchdogJob?.cancel()
                _state.update {
                    it.copy(
                        phase = BlePhase.IDLE,
                        bpm = 0,
                        lastSampleAt = 0L,
                        notificationsEnabled = false,
                        detail = "Banda desconectada"
                    )
                }
                if (shouldReconnect) scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== currentGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection(g, "Error leyendo servicios de la banda")
                return
            }
            val characteristic = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (characteristic == null) {
                failConnection(g, "El dispositivo no ofrece el servicio estándar de pulso")
                return
            }
            val notify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if ((!notify && !indicate) || !g.setCharacteristicNotification(characteristic, true)) {
                failConnection(g, "No se pudieron activar las notificaciones de pulso")
                return
            }
            val descriptor = characteristic.getDescriptor(CCCD)
            if (descriptor == null) {
                failConnection(g, "Falta el descriptor de notificaciones BLE")
                return
            }
            val descriptorValue = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    val result = g.writeDescriptor(descriptor, descriptorValue)
                    if (result != BluetoothStatusCodes.SUCCESS) {
                        failConnection(g, "El reloj rechazó la activación de notificaciones BLE")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = descriptorValue
                    @Suppress("DEPRECATION")
                    if (!g.writeDescriptor(descriptor)) failConnection(g, "No se pudo escribir el descriptor BLE")
                }
            } catch (_: SecurityException) {
                failConnection(g, "Permiso Bluetooth denegado")
            } catch (_: Exception) {
                failConnection(g, "Fallo al activar el flujo de pulso")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == HEART_RATE_MEASUREMENT) {
                handleMeasurement(g, characteristic.value?.copyOf())
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT) handleMeasurement(g, value.copyOf())
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== currentGatt || descriptor.uuid != CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                reconnectAttempts = 0
                lastDataOrEnableAt = System.currentTimeMillis()
                _state.update {
                    it.copy(phase = BlePhase.CONNECTED, notificationsEnabled = true, detail = "Banda conectada · esperando pulso")
                }
                startWatchdog()
            } else {
                failConnection(g, "La banda rechazó las notificaciones de pulso")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        stopScan()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter.isEnabled != true) {
            _state.update { it.copy(phase = BlePhase.ERROR, detail = "Activa Bluetooth en el reloj") }
            return
        }
        try {
            _state.update { it.copy(phase = BlePhase.SCANNING, candidates = emptyList(), detail = "Buscando bandas de pulso…") }
            scanner.startScan(
                listOf(android.bluetooth.le.ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback
            )
            scanJob = scope.launch {
                delay(15_000L)
                stopScan()
                _state.update {
                    if (it.phase == BlePhase.SCANNING) it.copy(
                        phase = BlePhase.IDLE,
                        detail = if (it.candidates.isEmpty()) "No se encontraron bandas compatibles" else "Elige la banda que llevas puesta"
                    ) else it
                }
            }
        } catch (_: SecurityException) {
            _state.update { it.copy(phase = BlePhase.ERROR, detail = "Concede el permiso de Bluetooth") }
        } catch (_: Exception) {
            _state.update { it.copy(phase = BlePhase.ERROR, detail = "No se pudo iniciar la búsqueda") }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        stopScan()
        autoReconnect = true
        targetAddress = address
        prefs.edit().putString("preferred_address", address).apply()
        reconnectJob?.cancel()
        reconnectAttempts = 0
        connectInternal(address)
    }

    @SuppressLint("MissingPermission")
    fun connectSavedIfPresent() {
        val address = targetAddress ?: return
        autoReconnect = true
        connectInternal(address)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String) {
        if (adapter?.isEnabled != true) {
            _state.update { it.copy(phase = BlePhase.ERROR, detail = "Bluetooth está apagado") }
            return
        }
        closeGatt()
        _state.update {
            it.copy(phase = BlePhase.CONNECTING, bpm = 0, lastSampleAt = 0L,
                address = address, notificationsEnabled = false, detail = "Conectando con la banda…")
        }
        try {
            val device: BluetoothDevice = adapter.getRemoteDevice(address)
            currentGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (currentGatt == null) scheduleReconnect()
        } catch (_: SecurityException) {
            _state.update { it.copy(phase = BlePhase.ERROR, detail = "Concede el permiso de Bluetooth") }
        } catch (_: Exception) {
            _state.update { it.copy(phase = BlePhase.ERROR, detail = "No se pudo abrir la conexión BLE") }
            if (autoReconnect) scheduleReconnect()
        }
    }

    private fun handleMeasurement(g: BluetoothGatt, bytes: ByteArray?) {
        if (g !== currentGatt) return
        val bpm = HeartRateParser.parse(bytes) ?: return
        val now = System.currentTimeMillis()
        lastDataOrEnableAt = now
        _state.update { it.copy(phase = BlePhase.CONNECTED, bpm = bpm, lastSampleAt = now, detail = "Pulso actualizado") }
        TrackStore.recordHeartRate(appContext, bpm, now)
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(3000L)
                val current = _state.value
                if (current.phase == BlePhase.CONNECTED &&
                    System.currentTimeMillis() - lastDataOrEnableAt > 20_000L) {
                    val stale = currentGatt
                    if (stale != null) {
                        currentGatt = null
                        runCatching { stale.disconnect() }
                        runCatching { stale.close() }
                    }
                    _state.update {
                        it.copy(phase = BlePhase.IDLE, bpm = 0, lastSampleAt = 0L,
                            notificationsEnabled = false, detail = "Sin pulso reciente · reconectando…")
                    }
                    if (autoReconnect) scheduleReconnect()
                    break
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || targetAddress == null || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val factor = 1L shl reconnectAttempts.coerceIn(0, 4)
            val waitMs = (2000L * factor).coerceAtMost(30_000L)
            reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(5)
            delay(waitMs)
            val address = targetAddress
            if (autoReconnect && address != null) connectInternal(address)
        }
    }

    private fun failConnection(g: BluetoothGatt, message: String) {
        if (g !== currentGatt) return
        currentGatt = null
        watchdogJob?.cancel()
        runCatching { g.disconnect() }
        runCatching { g.close() }
        _state.update { it.copy(phase = BlePhase.ERROR, bpm = 0, lastSampleAt = 0L, notificationsEnabled = false, detail = message) }
        if (autoReconnect) scheduleReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val old = currentGatt
        currentGatt = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
        watchdogJob?.cancel()
    }

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        watchdogJob?.cancel()
        stopScan()
        closeGatt()
        _state.value = BleHrState(detail = "Banda desconectada")
    }

    fun close() {
        disconnect()
        scope.cancel()
    }
}
