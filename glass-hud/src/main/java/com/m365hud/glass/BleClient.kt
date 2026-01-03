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
 * - LATENCY MONITORING: Tracks telemetry freshness and auto-reconnects on stale data
 */
@SuppressLint("MissingPermission")
class BleClient(private val context: Context) {
    
    companion object {
        private const val TAG = "BleClient"
        private const val SCAN_TIMEOUT_MS = 30000L
        
        // LATENCY MONITORING: Watchdog timeout for stale data detection
        // If no telemetry received for this long, consider connection stale
        private const val TELEMETRY_STALE_TIMEOUT_MS = 3000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 1000L
        
        // Scan retry without filter after this timeout (some devices don't advertise UUID correctly)
        private const val SCAN_RETRY_WITHOUT_FILTER_MS = 5000L
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
    
    // LATENCY MONITORING: Track last telemetry update time
    @Volatile private var lastTelemetryUpdateMs: Long = 0
    @Volatile private var telemetryUpdateCount: Int = 0
    @Volatile private var watchdogHandler: android.os.Handler? = null
    @Volatile private var lastLogTimeMs: Long = 0
    
    // LATENCY MONITORING: Telemetry freshness indicator (true = receiving data normally)
    private val _isTelemetryFresh = MutableStateFlow(false)
    val isTelemetryFresh: StateFlow<Boolean> = _isTelemetryFresh.asStateFlow()
    
    /**
     * Start scanning for the M365 HUD Gateway
     */
    fun startScan() {
        if (scanner == null) {
            Log.e(TAG, "Bluetooth scanner not available")
            _connectionState.value = ConnectionState.Error("Bluetooth not available")
            return
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is not enabled")
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }
        
        // Reset connection flag
        isConnecting = false
        
        _connectionState.value = ConnectionState.Scanning
        Log.i(TAG, "Starting scan for M365 HUD Gateway with UUID filter...")
        Log.d(TAG, "Looking for Service UUID: ${GattProfile.SERVICE_UUID}")
        
        // First try: Filter for our custom service UUID
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(GattProfile.SERVICE_UUID))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        try {
            scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan with filter: ${e.message}")
            // Try without filter as fallback
            startScanWithoutFilter()
            return
        }
        
        // Fallback: Try scanning without UUID filter after 5 seconds if nothing found
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (_connectionState.value == ConnectionState.Scanning && !isConnecting) {
                Log.w(TAG, "No device found with UUID filter, retrying without filter...")
                stopScan()
                startScanWithoutFilter()
            }
        }, SCAN_RETRY_WITHOUT_FILTER_MS)
        
        // Auto-stop scan after timeout
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (_connectionState.value == ConnectionState.Scanning) {
                stopScan()
                Log.e(TAG, "Scan timeout - Gateway not found after ${SCAN_TIMEOUT_MS}ms")
                _connectionState.value = ConnectionState.Error("Gateway not found - make sure HUD Gateway is enabled on phone")
            }
        }, SCAN_TIMEOUT_MS)
    }
    
    /**
     * Start scanning without UUID filter (broader scan for debugging)
     */
    private fun startScanWithoutFilter() {
        if (_connectionState.value != ConnectionState.Scanning) return
        
        Log.i(TAG, "Starting scan WITHOUT UUID filter (will match device name)...")
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        try {
            scanner?.startScan(null, scanSettings, scanCallbackNoFilter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start unfiltered scan: ${e.message}")
            _connectionState.value = ConnectionState.Error("Scan failed: ${e.message}")
        }
    }
    
    /**
     * Scan callback for unfiltered scan (matches by device name or UUID)
     */
    private val scanCallbackNoFilter = object : ScanCallback() {
        private val seenDevices = mutableSetOf<String>()
        
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: result.scanRecord?.deviceName
            val address = result.device.address
            val serviceUuids = result.scanRecord?.serviceUuids
            
            // Log each unique device once with INFO level for debugging
            if (!seenDevices.contains(address)) {
                seenDevices.add(address)
                Log.i(TAG, "Unfiltered scan found device: name=$deviceName, addr=$address, UUIDs=$serviceUuids, RSSI=${result.rssi}")
            }
            
            // Skip devices that previously failed service discovery
            if (failedDevices.contains(address)) {
                Log.d(TAG, "Skipping previously failed device: $address")
                return
            }
            
            // Check if this device is advertising our service
            val hasOurService = serviceUuids?.any { it.uuid == GattProfile.SERVICE_UUID } == true
            
            // Also try matching by device name as fallback (for some Android versions, service UUID may not be advertised)
            val hasMatchingName = deviceName?.contains("M365 HUD", ignoreCase = true) == true ||
                                  deviceName?.contains("Redmi", ignoreCase = true) == true ||
                                  deviceName?.contains("Xiaomi", ignoreCase = true) == true
            
            if (hasOurService) {
                Log.i(TAG, "Found Gateway device (by UUID): $deviceName ($address)")
                synchronized(this@BleClient) {
                    if (_connectionState.value == ConnectionState.Scanning && !isConnecting) {
                        isConnecting = true
                        stopScan()
                        connect(result.device)
                    }
                }
            } else if (hasMatchingName) {
                Log.i(TAG, "Found potential Gateway device (by name): $deviceName ($address) - will attempt connection")
                synchronized(this@BleClient) {
                    if (_connectionState.value == ConnectionState.Scanning && !isConnecting) {
                        isConnecting = true
                        stopScan()
                        connect(result.device)
                    }
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error $errorCode"
            }
            Log.e(TAG, "Unfiltered scan failed: $errorMsg")
            isConnecting = false
            _connectionState.value = ConnectionState.Error("Scan failed: $errorMsg")
        }
    }
    
    /**
     * Stop scanning
     */
    fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
            scanner?.stopScan(scanCallbackNoFilter)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping scan: ${e.message}")
        }
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
        stopWatchdog() // Stop telemetry monitoring
        
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
    
    // Track devices that failed service discovery (to avoid reconnecting to them)
    private val failedDevices = mutableSetOf<String>()
    
    // Flag to prevent multiple connection attempts during scan
    @Volatile
    private var isConnecting = false
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: result.scanRecord?.deviceName ?: "Unknown"
            val address = result.device.address
            
            // Skip devices that previously failed service discovery
            if (failedDevices.contains(address)) {
                Log.d(TAG, "Skipping previously failed device: $address")
                return
            }
            
            Log.i(TAG, "Found Gateway via UUID filter: name=$deviceName, addr=$address, RSSI=${result.rssi}")
            
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
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error $errorCode"
            }
            Log.e(TAG, "Filtered scan failed: $errorMsg")
            isConnecting = false
            // Try without filter as fallback
            startScanWithoutFilter()
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    _connectionState.value = ConnectionState.Connecting
                    
                    // LATENCY OPTIMIZATION: Request high connection priority for faster updates
                    // This reduces the BLE connection interval from default (~30-50ms) to minimum (~7.5-15ms)
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        Log.d(TAG, "Requested HIGH connection priority for low latency")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to request connection priority: ${e.message}")
                    }
                    
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
            
            // Log all discovered services for debugging
            val allServices = gatt.services
            Log.d(TAG, "Found ${allServices.size} services:")
            allServices.forEach { svc ->
                Log.d(TAG, "  Service: ${svc.uuid}")
            }
            
            val service = gatt.getService(GattProfile.SERVICE_UUID)
            Log.d(TAG, "Looking for Service UUID: ${GattProfile.SERVICE_UUID}")
            if (service == null) {
                val deviceAddress = gatt.device?.address ?: "unknown"
                Log.e(TAG, "HUD service not found on device $deviceAddress, adding to failed list and retrying scan")
                
                // Add this device to failed list so we don't connect to it again
                failedDevices.add(deviceAddress)
                Log.i(TAG, "Failed devices list: $failedDevices")
                
                // Disconnect and clean up
                gatt.disconnect()
                gatt.close()
                this@BleClient.gatt = null
                targetDevice = null
                isConnecting = false
                
                // Resume scanning to find the correct device
                _connectionState.value = ConnectionState.Scanning
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startScan()
                }, 500) // Brief delay before restarting scan
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
            
            // Clear failed devices list on successful connection
            failedDevices.clear()
            Log.i(TAG, "Successfully connected, cleared failed devices list")
            
            // LATENCY MONITORING: Start watchdog timer
            startWatchdog()
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                GattProfile.TELEMETRY_CHAR_UUID -> {
                    // LATENCY MONITORING: Track update timing
                    val now = System.currentTimeMillis()
                    telemetryUpdateCount++
                    
                    // Log update frequency every 5 seconds
                    if (now - lastLogTimeMs >= 5000L) {
                        val intervalSec = (now - lastLogTimeMs) / 1000.0
                        val updatesPerSec = telemetryUpdateCount / intervalSec
                        val lastDelta = now - lastTelemetryUpdateMs
                        Log.d(TAG, "LATENCY STATS: ${telemetryUpdateCount} updates in ${intervalSec}s = ${String.format("%.1f", updatesPerSec)} updates/sec, last delta: ${lastDelta}ms")
                        telemetryUpdateCount = 0
                        lastLogTimeMs = now
                    }
                    lastTelemetryUpdateMs = now
                    _isTelemetryFresh.value = true
                    
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
    
    // ========== LATENCY MONITORING: Watchdog for stale data detection ==========
    
    /**
     * Start the watchdog timer to monitor telemetry freshness.
     * If no telemetry is received for TELEMETRY_STALE_TIMEOUT_MS, marks data as stale.
     */
    private fun startWatchdog() {
        stopWatchdog() // Stop any existing watchdog
        
        lastTelemetryUpdateMs = System.currentTimeMillis()
        lastLogTimeMs = System.currentTimeMillis()
        telemetryUpdateCount = 0
        _isTelemetryFresh.value = true
        
        watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val watchdogRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val timeSinceLastUpdate = now - lastTelemetryUpdateMs
                
                if (timeSinceLastUpdate > TELEMETRY_STALE_TIMEOUT_MS) {
                    if (_isTelemetryFresh.value) {
                        Log.w(TAG, "WATCHDOG: Telemetry stale! No update for ${timeSinceLastUpdate}ms")
                        _isTelemetryFresh.value = false
                    }
                }
                
                // Continue checking while connected
                if (_connectionState.value == ConnectionState.Connected) {
                    watchdogHandler?.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS)
                }
            }
        }
        
        watchdogHandler?.postDelayed(watchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)
        Log.d(TAG, "Watchdog started with ${TELEMETRY_STALE_TIMEOUT_MS}ms timeout")
    }
    
    /**
     * Stop the watchdog timer.
     */
    private fun stopWatchdog() {
        watchdogHandler?.removeCallbacksAndMessages(null)
        watchdogHandler = null
        _isTelemetryFresh.value = false
    }
}
