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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.util.UUID

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

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

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val watchHrSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private var isWatchHrActive = false
    private var autoScan10sJob: Job? = null

    private val prefs = context.getSharedPreferences("ble_prefs", Context.MODE_PRIVATE)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetDeviceAddress: String? = null
    private var isAutoReconnectEnabled = false

    // Detect a connected-but-stalled HR GATT connection.
    @Volatile
    private var lastBleMeasurementAtMs: Long = 0L
    private var bleMeasurementWatchdogJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var reconnectJob: Job? = null
    private var lastPulseResetJob: Job? = null

    private val _hrState = MutableStateFlow(
        BleHeartRateState(
            preferredDeviceAddress = prefs.getString(KEY_PREFERRED_ADDRESS, null)
        )
    )
    val hrState: StateFlow<BleHeartRateState> = _hrState.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val scanRecord = result.scanRecord
            val name = device.name ?: scanRecord?.deviceName ?: "BLE HR Sensor"

            // Ensure device advertises standard Heart Rate Service 0x180D or matches preferred MAC address
            val serviceUuids = scanRecord?.serviceUuids
            val isHrDevice = serviceUuids?.any { it.uuid == HEART_RATE_SERVICE_UUID } == true ||
                    name.contains("Polar", ignoreCase = true) ||
                    name.contains("HR", ignoreCase = true) ||
                    name.contains("Heart", ignoreCase = true)

            val preferred = _hrState.value.preferredDeviceAddress

            if (isHrDevice || (preferred != null && device.address.equals(preferred, ignoreCase = true))) {
                Log.d(TAG, "Found Valid HR Device: $name [${device.address}]")
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan failed with code: $errorCode")
            _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT Connected to HR Sensor")
                    val addr = gatt?.device?.address
                    val name = gatt?.device?.name ?: "Polar H7 / HR Strap"

                    savePreferredDevice(addr)

                    _hrState.update {
                        it.copy(
                            connectionState = BleConnectionState.CONNECTED,
                            deviceName = name,
                            deviceAddress = addr,
                            preferredDeviceAddress = addr
                        )
                    }
                    if (bluetoothGatt == gatt && gatt != null) {
                        try {
                            val accepted = gatt.requestConnectionPriority(
                                BluetoothGatt.CONNECTION_PRIORITY_HIGH
                            )
                            Log.d(TAG, "GATT high-priority connection request=$accepted")
                        } catch (e: Exception) {
                            Log.w(TAG, "GATT connection-priority request failed: ${e.localizedMessage}")
                        }
                        gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT Disconnected")
                    _hrState.update {
                        it.copy(
                            connectionState = BleConnectionState.DISCONNECTED,
                            heartRateBpm = 0,
                            hrNotificationsEnabled = false
                        )
                    }
                    try {
                        gatt?.disconnect()
                        gatt?.close()
                    } catch (e: Exception) {}
                    if (bluetoothGatt == gatt) {
                        bluetoothGatt = null
                    }
                    lastBleMeasurementAtMs = 0L
                    bleMeasurementWatchdogJob?.cancel()
                    bleMeasurementWatchdogJob = null

                    // Fall back to internal watch HR sensor immediately when BLE disconnects
                    startWatchHrSensor()

                    if (isAutoReconnectEnabled && targetDeviceAddress != null) {
                        scheduleReconnect()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt == null || gatt != bluetoothGatt) {
                Log.w(TAG, "Ignoring services from stale GATT connection")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServicesDiscovered failed with status: $status")
                return
            }

            val service = gatt.getService(HEART_RATE_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID)
            if (service == null || characteristic == null) {
                Log.e(TAG, "HR service/measurement characteristic not found")
                return
            }

            val supportsNotify = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            val supportsIndicate = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            if (!supportsNotify && !supportsIndicate) {
                Log.e(TAG, "HR characteristic has no NOTIFY/INDICATE property")
                return
            }

            val localEnabled = gatt.setCharacteristicNotification(characteristic, true)
            Log.d(TAG, "Local HR notification registration=$localEnabled")
            if (!localEnabled) return

            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor == null) {
                Log.e(TAG, "CCCD Descriptor 0x2902 is null")
                return
            }

            _hrState.update { it.copy(hrNotificationsEnabled = false) }
            val cccdValue = if (supportsNotify) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val accepted = gatt.writeDescriptor(descriptor, cccdValue)
                Log.d(TAG, "CCCD write requested: $accepted")
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = cccdValue
                @Suppress("DEPRECATION")
                val accepted = gatt.writeDescriptor(descriptor)
                Log.d(TAG, "CCCD write requested: $accepted")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (gatt == null || gatt != bluetoothGatt) return
            if (descriptor?.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "CCCD Descriptor written successfully! HR notifications active.")
                lastBleMeasurementAtMs = System.currentTimeMillis()
                _hrState.update { it.copy(hrNotificationsEnabled = true) }
                startBleMeasurementWatchdog(gatt)
            } else {
                Log.e(TAG, "Failed writing CCCD descriptor. Status: $status")
                _hrState.update { it.copy(hrNotificationsEnabled = false) }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (gatt == null || gatt != bluetoothGatt) return
            if (characteristic?.uuid != HEART_RATE_MEASUREMENT_CHAR_UUID) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val data = characteristic.value?.copyOf() ?: return
                parseHeartRateMeasurement(data)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt != bluetoothGatt) return
            if (characteristic.uuid != HEART_RATE_MEASUREMENT_CHAR_UUID) return
            parseHeartRateMeasurement(value.copyOf())
        }
    }

    private fun parseHeartRateMeasurement(data: ByteArray) {
        if (data.isEmpty()) return

        val flags = data[0].toInt()
        val is16Bit = (flags and 0x01) != 0

        val bpm: Int = if (is16Bit) {
            if (data.size >= 3) {
                ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            } else 0
        } else {
            if (data.size >= 2) {
                data[1].toInt() and 0xFF
            } else 0
        }

        if (bpm in 30..240) {
            lastBleMeasurementAtMs = System.currentTimeMillis()
            stopWatchHrSensor()
            _hrState.update {
                it.copy(
                    heartRateBpm = bpm,
                    isPulseActive = true,
                    isUsingInternalSensor = false
                )
            }
            lastPulseResetJob?.cancel()
            lastPulseResetJob = scope.launch {
                delay(200L)
                _hrState.update { it.copy(isPulseActive = false) }
            }
        }
    }

    private fun startBleMeasurementWatchdog(gatt: BluetoothGatt) {
        bleMeasurementWatchdogJob?.cancel()
        bleMeasurementWatchdogJob = scope.launch {
            while (bluetoothGatt == gatt &&
                _hrState.value.connectionState == BleConnectionState.CONNECTED) {
                delay(BLE_MEASUREMENT_WATCHDOG_INTERVAL_MS)

                if (bluetoothGatt != gatt ||
                    _hrState.value.connectionState != BleConnectionState.CONNECTED) {
                    break
                }

                val age = System.currentTimeMillis() - lastBleMeasurementAtMs
                if (age >= BLE_MEASUREMENT_TIMEOUT_MS) {
                    Log.w(TAG, "No BLE HR measurement for " + age + "ms; reconnecting GATT")
                    try {
                        gatt.disconnect()
                        gatt.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error resetting stalled GATT: " + e.localizedMessage)
                    }
                    if (bluetoothGatt == gatt) bluetoothGatt = null
                    lastBleMeasurementAtMs = 0L
                    _hrState.update {
                        it.copy(
                            connectionState = BleConnectionState.DISCONNECTED,
                            heartRateBpm = 0,
                            hrNotificationsEnabled = false
                        )
                    }
                    bleMeasurementWatchdogJob = null
                    startWatchHrSensor()
                    if (isAutoReconnectEnabled && targetDeviceAddress != null) scheduleReconnect()
                    break
                }
            }
        }
    }

    fun startMonitoring() {
        startWatchHrSensor()
        start10sAutoReconnectLoop()
    }

    private fun startWatchHrSensor() {
        if (!isWatchHrActive && watchHrSensor != null) {
            isWatchHrActive = sensorManager?.registerListener(this, watchHrSensor, SensorManager.SENSOR_DELAY_NORMAL) ?: false
            if (isWatchHrActive) {
                _hrState.update { it.copy(isUsingInternalSensor = true) }
            }
        }
    }

    private fun stopWatchHrSensor() {
        if (isWatchHrActive) {
            sensorManager?.unregisterListener(this)
            isWatchHrActive = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
            val bpm = event.values.getOrNull(0)?.toInt() ?: 0
            if (bpm in 30..240 && _hrState.value.connectionState != BleConnectionState.CONNECTED) {
                _hrState.update {
                    it.copy(
                        heartRateBpm = bpm,
                        isPulseActive = true,
                        isUsingInternalSensor = true,
                        deviceName = "Reloj (Integrado)"
                    )
                }
                lastPulseResetJob?.cancel()
                lastPulseResetJob = scope.launch {
                    delay(200L)
                    _hrState.update { it.copy(isPulseActive = false) }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun connectLastDevice() {
        val preferred = _hrState.value.preferredDeviceAddress ?: targetDeviceAddress
        if (preferred != null) {
            val device = try { bluetoothAdapter?.getRemoteDevice(preferred) } catch (e: Exception) { null }
            if (device != null) {
                connectToDevice(device)
            } else {
                startScanAndConnect()
            }
        } else {
            startScanAndConnect()
        }
    }

    private fun start10sAutoReconnectLoop() {
        autoScan10sJob?.cancel()
        autoScan10sJob = scope.launch {
            while (true) {
                delay(10000L)
                if (_hrState.value.connectionState == BleConnectionState.DISCONNECTED) {
                    val preferred = _hrState.value.preferredDeviceAddress ?: targetDeviceAddress
                    if (preferred != null) {
                        Log.d(TAG, "10s Auto-retry connecting to last BLE device: $preferred")
                        connectLastDevice()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanAndConnect() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled or adapter is null")
            return
        }

        isAutoReconnectEnabled = true
        _hrState.update { it.copy(connectionState = BleConnectionState.SCANNING) }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null")
            _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Scan with filter failed, falling back to unfiltered scan: ${e.localizedMessage}")
            try {
                scanner.startScan(null, scanSettings, scanCallback)
            } catch (ex: Exception) {
                _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
                return
            }
        }

        scope.launch {
            delay(15000L)
            if (_hrState.value.connectionState == BleConnectionState.SCANNING) {
                stopScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {}
        if (_hrState.value.connectionState == BleConnectionState.SCANNING) {
            _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        targetDeviceAddress = device.address
        _hrState.update {
            it.copy(
                connectionState = BleConnectionState.CONNECTING,
                deviceAddress = device.address,
                deviceName = device.name ?: "Polar/BLE Sensor"
            )
        }
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous GATT: ${e.localizedMessage}")
        }
        bleMeasurementWatchdogJob?.cancel()
        bleMeasurementWatchdogJob = null
        lastBleMeasurementAtMs = 0L
        bluetoothGatt = null
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt failed: ${e.localizedMessage}")
            _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
            startWatchHrSensor()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        isAutoReconnectEnabled = false
        reconnectJob?.cancel()
        stopScan()
        bleMeasurementWatchdogJob?.cancel()
        bleMeasurementWatchdogJob = null
        lastBleMeasurementAtMs = 0L
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        bluetoothGatt = null
        _hrState.update {
            it.copy(
                connectionState = BleConnectionState.DISCONNECTED,
                heartRateBpm = 0
            )
        }
    }

    fun close() {
        isAutoReconnectEnabled = false
        autoScan10sJob?.cancel()
        autoScan10sJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        bleMeasurementWatchdogJob?.cancel()
        bleMeasurementWatchdogJob = null
        lastPulseResetJob?.cancel()
        lastPulseResetJob = null
        stopScan()
        stopWatchHrSensor()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing HR GATT: ${e.localizedMessage}")
        }
        bluetoothGatt = null
        scope.cancel()
    }

    fun clearPreferredDevice() {
        prefs.edit().remove(KEY_PREFERRED_ADDRESS).apply()
        _hrState.update { it.copy(preferredDeviceAddress = null) }
    }

    private fun savePreferredDevice(address: String?) {
        if (address != null) {
            prefs.edit().putString(KEY_PREFERRED_ADDRESS, address).apply()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isAutoReconnectEnabled && _hrState.value.connectionState == BleConnectionState.DISCONNECTED) {
                Log.d(TAG, "Attempting auto-reconnect to BLE HR Sensor...")
                delay(3000L)
                val device = targetDeviceAddress?.let {
                    try { bluetoothAdapter?.getRemoteDevice(it) } catch (e: Exception) { null }
                }
                if (device != null) {
                    connectToDevice(device)
                    break
                }
            }
        }
    }

    companion object {
        private const val TAG = "HeartRateManager"
        private const val KEY_PREFERRED_ADDRESS = "preferred_ble_hr_address"
        // The watchdog must not itself create a 3-second cadence in the HR path.
        private const val BLE_MEASUREMENT_WATCHDOG_INTERVAL_MS = 2000L
        private const val BLE_MEASUREMENT_TIMEOUT_MS = 15000L

        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
