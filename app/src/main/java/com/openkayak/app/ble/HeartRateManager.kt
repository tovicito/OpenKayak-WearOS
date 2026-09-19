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
    val isPulseActive: Boolean = false
)

class HeartRateManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("ble_prefs", Context.MODE_PRIVATE)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetDeviceAddress: String? = null
    private var isAutoReconnectEnabled = false

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
            val name = device.name ?: result.scanRecord?.deviceName ?: "Polar/BLE HR"
            Log.d(TAG, "Found HR Device: $name [${device.address}]")

            val preferred = _hrState.value.preferredDeviceAddress
            // Connect immediately if preferred address matches or if no preference set
            if (preferred == null || device.address.equals(preferred, ignoreCase = true)) {
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
                    bluetoothGatt?.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT Disconnected")
                    _hrState.update {
                        it.copy(
                            connectionState = BleConnectionState.DISCONNECTED,
                            heartRateBpm = 0
                        )
                    }
                    gatt?.close()
                    bluetoothGatt = null

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
                    descriptor?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(
                                it,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(it)
                        }
                    }
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.value?.let { parseHeartRateMeasurement(it) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parseHeartRateMeasurement(value)
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
            _hrState.update {
                it.copy(
                    heartRateBpm = bpm,
                    isPulseActive = true
                )
            }
            scope.launch {
                delay(150L)
                _hrState.update { it.copy(isPulseActive = false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanAndConnect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return

        isAutoReconnectEnabled = true
        _hrState.update { it.copy(connectionState = BleConnectionState.SCANNING) }

        // If preferred device is saved, attempt direct connection first
        val preferredAddr = _hrState.value.preferredDeviceAddress
        if (preferredAddr != null) {
            try {
                val dev = bluetoothAdapter.getRemoteDevice(preferredAddr)
                if (dev != null) {
                    connectToDevice(dev)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to preferred device: $e")
            }
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

        scope.launch {
            delay(15000L)
            if (_hrState.value.connectionState == BleConnectionState.SCANNING) {
                stopScan()
                _hrState.update { it.copy(connectionState = BleConnectionState.DISCONNECTED) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
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
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        isAutoReconnectEnabled = false
        reconnectJob?.cancel()
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
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
                val device = targetDeviceAddress?.let { bluetoothAdapter?.getRemoteDevice(it) }
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
