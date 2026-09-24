package com.openkayak.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

enum class BleConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

data class BleHeartRateState(
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val heartRateBpm: Int = 0,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val preferredDeviceAddress: String? = null,
    val isPulseActive: Boolean = false,
    val isUsingInternalSensor: Boolean = false,
    val hrNotificationsEnabled: Boolean = false
)

class HeartRateManager(private val context: Context) : SensorEventListener {
    companion object {
        private const val TAG = "OpenKayakBLE"
        private const val PREFS = "ble_prefs"
        private const val KEY_ADDRESS = "preferred_ble_hr_address"
        private const val SCAN_TIMEOUT = 15_000L
        private const val RECONNECT_BASE_DELAY = 2_000L
        private const val RECONNECT_MAX_DELAY = 30_000L
        private const val WATCHDOG_INTERVAL = 5_000L
        private const val NOTIFICATION_TIMEOUT = 20_000L
        val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_CHAR_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter = bluetoothManager?.adapter
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val watchSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    private var gatt: BluetoothGatt? = null
    private var scanJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var watchdogJob: Job? = null
    private var pulseJob: Job? = null
    private var targetAddress: String? = null
    private var autoReconnect = false
    private var lastMeasurement = 0L
    private var watchSensorActive = false

    private val _hrState = MutableStateFlow(
        BleHeartRateState(preferredDeviceAddress = prefs.getString(KEY_ADDRESS, null))
    )
    val hrState: StateFlow<BleHeartRateState> = _hrState.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val services = result.scanRecord?.serviceUuids.orEmpty()
            val preferred = _hrState.value.preferredDeviceAddress
            val advertisesHr = services.any { it.uuid == HEART_RATE_SERVICE_UUID }
            val matchesPreferred = preferred?.equals(device.address, true) == true
            // Some HR straps omit 0x180D from advertisements. If no preferred
            // address exists, allow candidates through and validate them over GATT.
            if (matchesPreferred || advertisesHr || preferred == null) {
                stopScan()
                connectToDevice(device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (gatt != g) { g.close(); return }
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                val address = g.device.address
                targetAddress = address
                prefs.edit().putString(KEY_ADDRESS, address).apply()
                _hrState.update {
                    it.copy(
                        connectionState = BleConnectionState.CONNECTED,
                        deviceName = g.device.name ?: "BLE Heart Rate",
                        deviceAddress = address,
                        preferredDeviceAddress = address,
                        hrNotificationsEnabled = false
                    )
                }
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                reconnectAttempts = 0
                lastMeasurement = 0L
                watchdogJob?.cancel()
                gattScope.launch {
                    runCatching { g.discoverServices() }
                        .onFailure { e ->
                            Log.e(TAG, "discoverServices failed", e)
                            handleDisconnect(g)
                            if (autoReconnect) scheduleReconnect()
                        }
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                handleDisconnect(g)
                if (autoReconnect) scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g != gatt || status != BluetoothGatt.GATT_SUCCESS) {
                handleDisconnect(g); return
            }
            val service = g.getService(HEART_RATE_SERVICE_UUID)
            val ch = service?.getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID)
            if (ch == null) { handleDisconnect(g); return }
            val notify = ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!notify && !indicate || !g.setCharacteristicNotification(ch, true)) {
                handleDisconnect(g); return
            }
            val cccd = ch.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (cccd == null) { handleDisconnect(g); return }
            val value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            try {
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value)
                else {
                    @Suppress("DEPRECATION") cccd.value = value
                    @Suppress("DEPRECATION") g.writeDescriptor(cccd)
                }
            } catch (e: Exception) {
                Log.e(TAG, "CCCD write failed", e); handleDisconnect(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g != gatt || descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                lastMeasurement = System.currentTimeMillis()
                _hrState.update { it.copy(hrNotificationsEnabled = true) }
                startWatchdog(g)
            } else {
                handleDisconnect(g)
                if (autoReconnect) scheduleReconnect()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleMeasurement(g, characteristic, characteristic.value?.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleMeasurement(g, characteristic, value.copyOf())
        }
    }

    private fun handleMeasurement(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray?) {
        if (g != gatt || ch.uuid != HEART_RATE_MEASUREMENT_CHAR_UUID || data == null || data.size < 2) return
        val flags = data[0].toInt() and 0xff
        val bpm = if (flags and 1 == 0) data[1].toInt() and 0xff
                  else if (data.size >= 3) (data[1].toInt() and 0xff) or ((data[2].toInt() and 0xff) shl 8)
                  else return
        if (bpm !in 30..240) return
        lastMeasurement = System.currentTimeMillis()
        stopWatchSensor()
        _hrState.update { it.copy(heartRateBpm = bpm, isPulseActive = true, isUsingInternalSensor = false) }
        pulseJob?.cancel()
        pulseJob = scope.launch { delay(350); _hrState.update { it.copy(isPulseActive = false) } }
    }

    private fun startWatchdog(g: BluetoothGatt) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && gatt == g && _hrState.value.connectionState == BleConnectionState.CONNECTED) {
                delay(WATCHDOG_INTERVAL)
                val age = System.currentTimeMillis() - lastMeasurement
                if (lastMeasurement != 0L && age > NOTIFICATION_TIMEOUT) {
                    Log.w(TAG, "HR notification stalled for $age ms")
                    handleDisconnect(g)
                    if (autoReconnect) scheduleReconnect()
                    break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanAndConnect() {
        val bt = adapter ?: return
        if (!bt.isEnabled) return
        autoReconnect = true
        stopScan()
        _hrState.update { it.copy(connectionState = BleConnectionState.SCANNING) }
        val scanner = bt.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build()
        try { scanner.startScan(listOf(filter), settings, scanCallback) }
        catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
            _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
            return
        }
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SCAN_TIMEOUT)
            if (_hrState.value.connectionState == BleConnectionState.SCANNING) stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (_hrState.value.connectionState == BleConnectionState.SCANNING) {
            _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectLastDevice() {
        val address = _hrState.value.preferredDeviceAddress ?: targetAddress
        val device = address?.let { try { adapter?.getRemoteDevice(it) } catch (_: Exception) { null } }
        if (device != null) { autoReconnect = true; connectToDevice(device) } else startScanAndConnect()
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        autoReconnect = true
        targetAddress = device.address
        val old = gatt
        gatt = null
        try { old?.disconnect(); old?.close() } catch (_: Exception) {}
        watchdogJob?.cancel()
        _hrState.update {
            it.copy(
                connectionState = BleConnectionState.CONNECTING,
                deviceName = device.name ?: "BLE Heart Rate",
                deviceAddress = device.address,
                hrNotificationsEnabled = false
            )
        }
        try {
            gatt = if (Build.VERSION.SDK_INT >= 23)
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            else {
                @Suppress("DEPRECATION") device.connectGatt(context, false, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt failed", e)
            _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
            scheduleReconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDisconnect(g: BluetoothGatt) {
        if (gatt == g) gatt = null
        watchdogJob?.cancel()
        lastMeasurement = 0L
        try { g.disconnect(); g.close() } catch (_: Exception) {}
        _hrState.update {
            it.copy(
                connectionState = BleConnectionState.DISCONNECTED,
                heartRateBpm = 0,
                hrNotificationsEnabled = false
            )
        }
        startWatchSensor()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            if (!autoReconnect || _hrState.value.connectionState != BleConnectionState.DISCONNECTED) return@launch
            val address = targetAddress ?: _hrState.value.preferredDeviceAddress
            val device = address?.let { try { adapter?.getRemoteDevice(it) } catch (_: Exception) { null } }
            if (device != null) connectToDevice(device) else startScanAndConnect()
        }
    }

    fun startMonitoring() { startWatchSensor() }

    private fun startWatchSensor() {
        if (!watchSensorActive && watchSensor != null) {
            watchSensorActive = sensorManager?.registerListener(this, watchSensor, SensorManager.SENSOR_DELAY_NORMAL) == true
            if (watchSensorActive) _hrState.update { it.copy(isUsingInternalSensor = true) }
        }
    }

    private fun stopWatchSensor() {
        if (watchSensorActive) {
            sensorManager?.unregisterListener(this)
            watchSensorActive = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_HEART_RATE || _hrState.value.connectionState == BleConnectionState.CONNECTED) return
        val bpm = event.values.firstOrNull()?.toInt() ?: return
        if (bpm !in 30..240) return
        _hrState.update { it.copy(heartRateBpm = bpm, isPulseActive = true, isUsingInternalSensor = true, deviceName = "Reloj") }
        pulseJob?.cancel()
        pulseJob = scope.launch { delay(350); _hrState.update { it.copy(isPulseActive = false) } }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @SuppressLint("MissingPermission")
    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        stopScan()
        val old = gatt
        gatt = null
        try { old?.disconnect(); old?.close() } catch (_: Exception) {}
        startWatchSensor()
        _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED, heartRateBpm = 0, hrNotificationsEnabled = false) }
    }

    fun clearPreferredDevice() {
        prefs.edit().remove(KEY_ADDRESS).apply()
        targetAddress = null
        _hrState.update { it.copy(preferredDeviceAddress = null) }
    }
}
