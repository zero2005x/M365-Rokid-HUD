package com.m365hud.glass

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE Client for connecting to the M365 HUD Gateway (phone)
 * 
 * This class handles:
 * - Scanning for the Gateway service
 * - Connecting to the phone
 * - Subscribing to telemetry notifications
 * - Parsing incoming data
 */
@SuppressLint("MissingPermission")
class BleClient(private val context: Context) {
    
    companion object {
        private const val TAG = "BleClient"
        private const val SCAN_TIMEOUT_MS = 30000L
    }
    
    // Connection state
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Scanning : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    
    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    
    // State flows for UI observation
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()
    
    private val _timeData = MutableStateFlow(TimeData())
    val timeData: StateFlow<TimeData> = _timeData.asStateFlow()
    
    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()
    
    /**
     * Start scanning for the M365 HUD Gateway
     */
    fun startScan() {
        if (scanner == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth not available")
            return
        }
        
        // Reset connection flag
        isConnecting = false
        
        _connectionState.value = ConnectionState.Scanning
        Log.i(TAG, "Starting scan for M365 HUD Gateway...")
        
        // Filter for our custom service UUID
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(GattProfile.SERVICE_UUID))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        
        // Auto-stop scan after timeout
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (_connectionState.value == ConnectionState.Scanning) {
                stopScan()
                _connectionState.value = ConnectionState.Error("Gateway not found")
            }
        }, SCAN_TIMEOUT_MS)
    }
    
    /**
     * Stop scanning
     */
    fun stopScan() {
        scanner?.stopScan(scanCallback)
        Log.i(TAG, "Scan stopped")
    }
    
    /**
     * Connect to a discovered Gateway device
     */
    fun connect(device: BluetoothDevice) {
        stopScan()
        targetDevice = device
        _connectionState.value = ConnectionState.Connecting
        
        Log.i(TAG, "Connecting to ${device.address}...")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }
    
    /**
     * Disconnect from the Gateway
     */
    fun disconnect() {
        isConnecting = false
        stopScan() // Ensure scan is stopped
        
        gatt?.let { g ->
            g.disconnect()
            // Wait briefly for disconnect to complete before closing
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                g.close()
            }, 100)
        }
        gatt = null
        targetDevice = null
        _connectionState.value = ConnectionState.Disconnected
        _telemetry.value = TelemetryData()
        _timeData.value = TimeData()
    }
    
    /**
     * Request RSSI update
     */
    fun readRssi() {
        gatt?.readRemoteRssi()
    }
    
    // ========== Callbacks ==========
    
    // Flag to prevent multiple connection attempts during scan
    @Volatile
    private var isConnecting = false
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i(TAG, "Found device: ${result.device.address}, RSSI: ${result.rssi}")
            
            // Auto-connect to the first device with our service
            // Use synchronized check to prevent race condition
            synchronized(this@BleClient) {
                if (_connectionState.value == ConnectionState.Scanning && !isConnecting) {
                    isConnecting = true
                    // Stop scan immediately before attempting connection
                    stopScan()
                    connect(result.device)
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            isConnecting = false
            _connectionState.value = ConnectionState.Error("Scan failed: $errorCode")
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    _connectionState.value = ConnectionState.Connecting
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server")
                    isConnecting = false
                    _connectionState.value = ConnectionState.Disconnected
                    this@BleClient.gatt?.close()
                    this@BleClient.gatt = null
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _connectionState.value = ConnectionState.Error("Service discovery failed")
                return
            }
            
            Log.i(TAG, "Services discovered")
            
            val service = gatt.getService(GattProfile.SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "HUD service not found")
                _connectionState.value = ConnectionState.Error("HUD service not found")
                return
            }
            
            // Subscribe to telemetry notifications
            val telemetryChar = service.getCharacteristic(GattProfile.TELEMETRY_CHAR_UUID)
            val timeChar = service.getCharacteristic(GattProfile.TIME_CHAR_UUID)
            
            if (telemetryChar != null) {
                enableNotification(gatt, telemetryChar)
            }
            
            // Enable time notification after telemetry (queue)
            if (timeChar != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    enableNotification(gatt, timeChar)
                }, 500)
            }
            
            _connectionState.value = ConnectionState.Connected
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                GattProfile.TELEMETRY_CHAR_UUID -> {
                    val data = TelemetryData.fromBytes(value)
                    Log.d(TAG, "Telemetry: speed=${data.speedKmh}, battery=${data.scooterBattery}%")
                    _telemetry.value = data
                }
                GattProfile.TIME_CHAR_UUID -> {
                    val data = TimeData.fromBytes(value)
                    Log.d(TAG, "Time: ${data.formatTime()}, phoneBattery=${data.phoneBattery}%")
                    _timeData.value = data
                }
            }
        }
        
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { value ->
                onCharacteristicChanged(gatt, characteristic, value)
            }
        }
        
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _rssi.value = rssi
            }
        }
    }
    
    /**
     * Enable notification for a characteristic
     * Uses deprecated API for compatibility with older Android versions (Rokid glasses)
     */
    @Suppress("DEPRECATION")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (!success) {
            Log.e(TAG, "Failed to set notification for ${characteristic.uuid}")
            return false
        }
        
        // Write to CCCD to enable notifications
        // Using deprecated API for compatibility with Android < 13 (Rokid glasses)
        val descriptor = characteristic.getDescriptor(GattProfile.CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            Log.i(TAG, "Enabled notification for ${characteristic.uuid}")
        }
        
        return true
    }
}
