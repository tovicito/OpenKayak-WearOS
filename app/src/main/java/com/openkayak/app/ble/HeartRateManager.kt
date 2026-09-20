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
    val isUsingInternalSensor: Boolean = false
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
    private var hrNotificationsEnabled = false

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var reconnectJob: Job? = null

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

                    hrNotificationsEnabled = false
                    _hrState.update {
                        it.copy(
                            connectionState = BleConnectionState.CONNECTED,
                            deviceName = name,
                            deviceAddress = addr,
                            preferredDeviceAddress = addr
                        )
                    }
                    gatt?.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT Disconnected")
                    hrNotificationsEnabled = false
                    _hrState.update {
                        it.copy(
                            connectionState = BleConnectionState.DISCONNECTED,
                            heartRateBpm = 0,
                            isPulseActive = false
                        )
                    }
                    gatt?.close()
                    if (bluetoothGatt === gatt) {
                        bluetoothGatt = null
                    }

                    if (isAutoReconnectEnabled && targetDeviceAddress != null) {
                        scheduleReconnect()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(HEART_RATE_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID)

                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                    if (descriptor != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(
                                descriptor,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                } else {
                    Log.w(TAG, "HR Measurement Characteristic not found on GATT device")
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                Log.w(TAG, "HR service discovery failed: status=$status")
                return
            }

            val service = gatt.getService(HEART_RATE_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_CHAR_UUID)

            if (characteristic == null) {
                Log.w(TAG, "HR Measurement Characteristic not found on GATT device")
                return
            }

            val properties = characteristic.properties
            val supportsNotify = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            if (!supportsNotify) {
                Log.w(TAG, "HR Measurement Characteristic does not support notifications")
                return
            }

            val enabledLocally = gatt.setCharacteristicNotification(characteristic, true)
            if (!enabledLocally) {
                Log.w(TAG, "setCharacteristicNotification() failed")
                return
            }

            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor == null) {
                Log.w(TAG, "CCCD descriptor not found on HR characteristic")
                return
            }

            // The local notification flag is not enough: the CCCD on the peripheral
            // must also be written. Do this once services are discovered and wait for
            // onDescriptorWrite() before considering notifications active.
            hrNotificationsEnabled = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "CCCD write could not be queued: status=$result")
                }
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                val started = gatt.writeDescriptor(descriptor)
                if (!started) {
                    Log.w(TAG, "CCCD write could not be queued")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (descriptor?.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                hrNotificationsEnabled = true
                Log.d(TAG, "HR notifications enabled successfully")
            } else {
                hrNotificationsEnabled = false
                Log.w(TAG, "Failed to enable HR notifications: status=$status")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic?.uuid != HEART_RATE_MEASUREMENT_CHAR_UUID) return
            val value = characteristic.value ?: return
            parseHeartRateMeasurement(value.copyOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
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

        if (bpm > 0) {
            // StateFlow is updated directly from the Bluetooth callback so every
            // measurement is immediately visible to Compose collectors.
            _hrState.update { state ->
                state.copy(
                    heartRateBpm = bpm,
                    isPulseActive = true,
                    isUsingInternalSensor = false
                )
            }
            scope.launch {
                delay(150L)
                _hrState.update { it.copy(isPulseActive = false) }
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
            if (bpm > 0 && _hrState.value.connectionState != BleConnectionState.CONNECTED) {
                _hrState.update {
                    it.copy(
                        heartRateBpm = bpm,
                        isPulseActive = true,
                        isUsingInternalSensor = true,
                        deviceName = "Reloj (Integrado)"
                    )
                }
                scope.launch {
                    delay(150L)
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
            // Never keep an old GATT connection around while replacing it.
            bluetoothGatt?.let { oldGatt ->
                try {
                    oldGatt.disconnect()
                    oldGatt.close()
                } catch (_: Exception) {}
            }
            hrNotificationsEnabled = false
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt failed: ${e.localizedMessage}")
            _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        isAutoReconnectEnabled = false
        reconnectJob?.cancel()
        stopScan()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        hrNotificationsEnabled = false
        bluetoothGatt = null
        _hrState.update {
            it.copy(
                connectionState = BleConnectionState.DISCONNECTED,
                heartRateBpm = 0
            )
        }
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

        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
