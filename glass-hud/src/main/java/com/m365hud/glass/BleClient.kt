package com.m365hud.glass

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.BatteryManager
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
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
        private const val SCAN_TIMEOUT_MS = 30000L  // 30 seconds per scan cycle (service will retry)
        
        // LATENCY MONITORING: Watchdog timeout for stale data detection
        // If no telemetry received for this long, consider connection stale
        // Increased to 3s to reduce false positive disconnects from brief BLE hiccups
        private const val TELEMETRY_STALE_TIMEOUT_MS = 3000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 1000L
        
        // CONNECTION HEALTH: Auto-reconnect after this many stale checks
        // 5 checks * 1000ms interval = 5 seconds of no data before reconnect attempt
        // Increased to be more tolerant of brief connection issues
        private const val STALE_CHECKS_BEFORE_RECONNECT = 5
        
        // Scan retry without filter after this timeout (some devices don't advertise UUID correctly)
        private const val SCAN_RETRY_WITHOUT_FILTER_MS = 5000L
        
        // GLASSES BATTERY: Send interval for glasses battery to phone
        private const val BATTERY_SEND_INTERVAL_MS = 30000L  // Send every 30 seconds
        
        // === RSSI MONITORING ===
        // Periodically check signal strength for connection quality indication
        private const val RSSI_CHECK_INTERVAL_MS = 5000L    // Check every 5 seconds
        private const val RSSI_THRESHOLD_WEAK_DBM = -80     // Below this = weak signal
        private const val RSSI_THRESHOLD_POOR_DBM = -90     // Below this = very poor signal
    }
    
    // === COROUTINE SCOPE for BLE operations ===
    // Uses IO dispatcher for BLE operations to prevent blocking UI thread
    private val bleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // CONNECTION HEALTH: Count consecutive stale checks
    @Volatile private var consecutiveStaleChecks = 0
    
    // CONNECTION HEALTH: Auto-reconnect enabled flag
    @Volatile private var autoReconnectEnabled = true
    
    // GLASSES BATTERY: Characteristic for sending battery level
    @Volatile private var glassesBatteryCharacteristic: BluetoothGattCharacteristic? = null
    
    // Connection state
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Scanning : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    // === SIGNAL STRENGTH INDICATOR ===
    enum class SignalStrength {
        Good,   // RSSI >= -80 dBm
        Weak,   // RSSI between -90 and -80 dBm
        Poor    // RSSI < -90 dBm
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
    
    // === SIGNAL STRENGTH INDICATOR ===
    private val _signalStrength = MutableStateFlow(SignalStrength.Good)
    val signalStrength: StateFlow<SignalStrength> = _signalStrength.asStateFlow()
    
    // LATENCY MONITORING: Track last telemetry update time
    @Volatile private var lastTelemetryUpdateMs: Long = 0
    @Volatile private var telemetryUpdateCount: Int = 0
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
        
        // Reset connection flag and clear failed devices list to allow retry
        isConnecting = false
        failedDevices.clear()
        Log.i(TAG, "Cleared failed devices list for fresh scan")
        
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
        
        // Use coroutine for delayed operations (more efficient than Handler)
        bleScope.launch {
            // Fallback: Try scanning without UUID filter after 5 seconds if nothing found
            delay(SCAN_RETRY_WITHOUT_FILTER_MS)
            withContext(Dispatchers.Main) {
                if (_connectionState.value == ConnectionState.Scanning && !isConnecting) {
                    Log.w(TAG, "No device found with UUID filter, retrying without filter...")
                    stopScan()
                    startScanWithoutFilter()
                }
            }
        }
        
        // Auto-stop scan after timeout (using coroutine)
        bleScope.launch {
            delay(SCAN_TIMEOUT_MS)
            withContext(Dispatchers.Main) {
                if (_connectionState.value == ConnectionState.Scanning) {
                    stopScan()
                    Log.e(TAG, "Scan timeout - Gateway not found after ${SCAN_TIMEOUT_MS}ms")
                    _connectionState.value = ConnectionState.Error("Gateway not found - make sure HUD Gateway is enabled on phone")
                }
            }
        }
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
        stopBatterySending() // Stop battery sending
        stopRssiMonitoring() // Stop RSSI monitoring
        
        gatt?.let { g ->
            g.disconnect()
            // Wait briefly for disconnect to complete before closing (using coroutine)
            bleScope.launch {
                delay(100)
                withContext(Dispatchers.Main) {
                    try {
                        g.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing GATT: ${e.message}")
                    }
                }
            }
        }
        gatt = null
        targetDevice = null
        glassesBatteryCharacteristic = null
        _connectionState.value = ConnectionState.Disconnected
        _telemetry.value = TelemetryData()
        _timeData.value = TimeData()
        _signalStrength.value = SignalStrength.Good  // Reset signal strength
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
            // Enhanced logging for connection diagnostics
            val statusName = when (status) {
                BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
                8 -> "GATT_CONN_TIMEOUT"
                19 -> "GATT_CONN_TERMINATE_PEER"
                22 -> "GATT_CONN_TERMINATE_LOCAL"
                34 -> "GATT_CONN_LMP_TIMEOUT"
                133 -> "GATT_ERROR"
                else -> "Unknown($status)"
            }
            val stateName = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "Unknown($newState)"
            }
            Log.i(TAG, "onConnectionStateChange: status=$statusName, newState=$stateName")
            
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
                    
                    // Refresh GATT cache to avoid stale service data
                    // This is critical when the phone's GATT server has been restarted
                    try {
                        val refreshMethod = gatt.javaClass.getMethod("refresh")
                        val refreshResult = refreshMethod.invoke(gatt) as Boolean
                        Log.i(TAG, "GATT cache refresh result: $refreshResult")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to refresh GATT cache: ${e.message}")
                    }
                    
                    // Small delay after refresh before discovering services (using coroutine)
                    bleScope.launch {
                        delay(200)
                        withContext(Dispatchers.Main) {
                            gatt.discoverServices()
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server (status=$statusName)")
                    
                    // Log disconnect reason for diagnostics
                    val shouldAutoReconnect = when (status) {
                        8 -> {
                            Log.w(TAG, "DISCONNECT REASON: Connection timeout - phone may be out of range")
                            true // Auto-reconnect on timeout
                        }
                        19 -> {
                            Log.w(TAG, "DISCONNECT REASON: Remote device terminated connection")
                            true // Auto-reconnect when remote disconnected
                        }
                        22 -> {
                            Log.w(TAG, "DISCONNECT REASON: Local device terminated connection")
                            false // Don't auto-reconnect on intentional local disconnect
                        }
                        34 -> {
                            Log.w(TAG, "DISCONNECT REASON: LMP response timeout")
                            true // Auto-reconnect on LMP timeout
                        }
                        133 -> {
                            Log.e(TAG, "DISCONNECT REASON: GATT_ERROR - stack issue, may need device restart")
                            true // Try to recover from GATT error
                        }
                        0 -> {
                            Log.d(TAG, "DISCONNECT REASON: Graceful disconnect")
                            false // Don't auto-reconnect on graceful disconnect
                        }
                        else -> {
                            Log.w(TAG, "DISCONNECT REASON: Unknown status $status")
                            true // Try to recover from unknown errors
                        }
                    }
                    
                    isConnecting = false
                    stopWatchdog()
                    stopBatterySending()
                    stopRssiMonitoring()
                    _connectionState.value = ConnectionState.Disconnected
                    this@BleClient.gatt?.close()
                    this@BleClient.gatt = null
                    
                    // Auto-reconnect if enabled and disconnect was unexpected (using coroutine)
                    if (shouldAutoReconnect && autoReconnectEnabled) {
                        Log.i(TAG, "AUTO-RECONNECT: Will attempt to reconnect in 2 seconds...")
                        bleScope.launch {
                            delay(2000)
                            withContext(Dispatchers.Main) {
                                if (_connectionState.value == ConnectionState.Disconnected) {
                                    Log.i(TAG, "AUTO-RECONNECT: Starting scan...")
                                    startScan()
                                }
                            }
                        }
                    }
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
                
                // Resume scanning to find the correct device (using coroutine)
                _connectionState.value = ConnectionState.Scanning
                bleScope.launch {
                    delay(500) // Brief delay before restarting scan
                    withContext(Dispatchers.Main) {
                        startScan()
                    }
                }
                return
            }
            
            // Subscribe to telemetry notifications
            val telemetryChar = service.getCharacteristic(GattProfile.TELEMETRY_CHAR_UUID)
            val timeChar = service.getCharacteristic(GattProfile.TIME_CHAR_UUID)
            
            // Get glasses battery characteristic for writing our battery level
            glassesBatteryCharacteristic = service.getCharacteristic(GattProfile.GLASSES_BATTERY_CHAR_UUID)
            if (glassesBatteryCharacteristic != null) {
                Log.i(TAG, "Found glasses battery characteristic for sending battery level")
            } else {
                Log.w(TAG, "Glasses battery characteristic not found on phone Gateway")
            }
            
            if (telemetryChar != null) {
                enableNotification(gatt, telemetryChar)
            }
            
            // Enable time notification after telemetry (queue, using coroutine)
            if (timeChar != null) {
                bleScope.launch {
                    delay(500)
                    withContext(Dispatchers.Main) {
                        enableNotification(gatt, timeChar)
                    }
                }
            }
            
            _connectionState.value = ConnectionState.Connected
            
            // Clear failed devices list on successful connection
            failedDevices.clear()
            Log.i(TAG, "Successfully connected, cleared failed devices list")
            
            // LATENCY MONITORING: Start watchdog timer
            startWatchdog()
            
            // GLASSES BATTERY: Start sending battery level to phone
            startBatterySending()
            
            // RSSI MONITORING: Start checking signal strength
            startRssiMonitoring()
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
                    recordPacketReceived() // Record for session statistics
                    
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
                
                // Record RSSI sample for session statistics
                recordRssiSample(rssi)
                
                // === SIGNAL STRENGTH CLASSIFICATION ===
                val strength = when {
                    rssi >= RSSI_THRESHOLD_WEAK_DBM -> SignalStrength.Good
                    rssi >= RSSI_THRESHOLD_POOR_DBM -> SignalStrength.Weak
                    else -> SignalStrength.Poor
                }
                
                // Log warning if signal is degrading
                if (strength != _signalStrength.value) {
                    when (strength) {
                        SignalStrength.Weak -> Log.w(TAG, "SIGNAL: Weak signal detected: $rssi dBm")
                        SignalStrength.Poor -> Log.e(TAG, "SIGNAL: Poor signal detected: $rssi dBm - connection may be unstable")
                        SignalStrength.Good -> Log.i(TAG, "SIGNAL: Signal recovered to good: $rssi dBm")
                    }
                    _signalStrength.value = strength
                }
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
    
    // ========== CONNECTION HEALTH: Watchdog for stale data detection and auto-reconnect ==========
    
    // Watchdog job for coroutine-based monitoring
    @Volatile private var watchdogJob: Job? = null
    
    /**
     * Start the watchdog timer to monitor telemetry freshness.
     * If no telemetry is received for TELEMETRY_STALE_TIMEOUT_MS, marks data as stale.
     * After STALE_CHECKS_BEFORE_RECONNECT consecutive stale checks, attempts auto-reconnect.
     * Uses coroutine for better resource management.
     */
    private fun startWatchdog() {
        stopWatchdog() // Stop any existing watchdog
        
        lastTelemetryUpdateMs = System.currentTimeMillis()
        lastLogTimeMs = System.currentTimeMillis()
        telemetryUpdateCount = 0
        consecutiveStaleChecks = 0
        _isTelemetryFresh.value = true
        
        watchdogJob = bleScope.launch {
            delay(WATCHDOG_CHECK_INTERVAL_MS)
            
            while (isActive && _connectionState.value == ConnectionState.Connected) {
                val now = System.currentTimeMillis()
                val timeSinceLastUpdate = now - lastTelemetryUpdateMs
                
                if (timeSinceLastUpdate > TELEMETRY_STALE_TIMEOUT_MS) {
                    consecutiveStaleChecks++
                    recordStaleEvent() // Record for session statistics
                    
                    if (_isTelemetryFresh.value) {
                        Log.w(TAG, "CONNECTION HEALTH: Telemetry stale! No update for ${timeSinceLastUpdate}ms (check $consecutiveStaleChecks/$STALE_CHECKS_BEFORE_RECONNECT)")
                        _isTelemetryFresh.value = false
                    }
                    
                    // Auto-reconnect if too many stale checks
                    if (consecutiveStaleChecks >= STALE_CHECKS_BEFORE_RECONNECT && autoReconnectEnabled) {
                        Log.e(TAG, "CONNECTION HEALTH: Connection appears lost after $consecutiveStaleChecks stale checks. Initiating auto-reconnect...")
                        withContext(Dispatchers.Main) {
                            initiateAutoReconnect()
                        }
                        break // Stop this watchdog, new one will start after reconnect
                    }
                } else {
                    // Reset stale counter on fresh data
                    if (consecutiveStaleChecks > 0) {
                        Log.i(TAG, "CONNECTION HEALTH: Connection recovered, resetting stale counter")
                        consecutiveStaleChecks = 0
                    }
                    _isTelemetryFresh.value = true
                }
                
                delay(WATCHDOG_CHECK_INTERVAL_MS)
            }
        }
        
        Log.d(TAG, "CONNECTION HEALTH: Watchdog started (stale timeout: ${TELEMETRY_STALE_TIMEOUT_MS}ms, reconnect after: ${STALE_CHECKS_BEFORE_RECONNECT} checks)")
    }
    
    /**
     * Initiate auto-reconnect when connection is detected as lost.
     * Uses a longer delay to ensure the BLE stack fully resets before reconnecting.
     */
    private fun initiateAutoReconnect() {
        Log.i(TAG, "CONNECTION HEALTH: Initiating auto-reconnect...")
        
        // Disable auto-reconnect temporarily to prevent rapid reconnect loops
        val wasAutoReconnectEnabled = autoReconnectEnabled
        autoReconnectEnabled = false
        
        // Disconnect current connection
        disconnect()
        
        // Wait 2 seconds for BLE stack to fully reset before reconnecting (using coroutine)
        bleScope.launch {
            delay(2000) // 2 second delay before reconnect
            withContext(Dispatchers.Main) {
                // Re-enable auto-reconnect
                autoReconnectEnabled = wasAutoReconnectEnabled
                
                if (_connectionState.value == ConnectionState.Disconnected) {
                    Log.i(TAG, "CONNECTION HEALTH: Starting new scan for auto-reconnect")
                    recordReconnect() // Record reconnect for session statistics
                    startScan()
                }
            }
        }
    }
    
    /**
     * Enable or disable auto-reconnect feature.
     */
    fun setAutoReconnectEnabled(enabled: Boolean) {
        autoReconnectEnabled = enabled
        Log.i(TAG, "CONNECTION HEALTH: Auto-reconnect ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Stop the watchdog timer.
     */
    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        consecutiveStaleChecks = 0
        _isTelemetryFresh.value = false
    }
    
    // ========== GLASSES BATTERY: Periodic battery level sending to phone ==========
    
    // Battery sending job for coroutine-based sending
    @Volatile private var batterySendJob: Job? = null
    
    /**
     * Get the glasses battery level using BatteryManager.
     */
    private fun getGlassesBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    
    /**
     * Start periodically sending glasses battery level to phone.
     * This runs every BATTERY_SEND_INTERVAL_MS (30 seconds).
     * Uses coroutine for better resource management.
     */
    private fun startBatterySending() {
        stopBatterySending() // Stop any existing handler
        
        val batteryChar = glassesBatteryCharacteristic
        if (batteryChar == null) {
            Log.w(TAG, "GLASSES BATTERY: Cannot start - characteristic not available")
            return
        }
        
        batterySendJob = bleScope.launch {
            // Send immediately
            withContext(Dispatchers.Main) {
                sendGlassesBattery()
            }
            
            // Then periodic sending
            while (isActive && _connectionState.value == ConnectionState.Connected) {
                delay(BATTERY_SEND_INTERVAL_MS)
                withContext(Dispatchers.Main) {
                    sendGlassesBattery()
                }
            }
        }
        
        Log.i(TAG, "GLASSES BATTERY: Started sending battery level every ${BATTERY_SEND_INTERVAL_MS}ms")
    }
    
    /**
     * Send current glasses battery level to phone via GATT characteristic write.
     */
    @Suppress("DEPRECATION")
    private fun sendGlassesBattery() {
        val gattConnection = gatt
        val batteryChar = glassesBatteryCharacteristic
        
        if (gattConnection == null || batteryChar == null) {
            Log.w(TAG, "GLASSES BATTERY: Cannot send - not connected or characteristic not available")
            return
        }
        
        val batteryLevel = getGlassesBatteryLevel()
        val data = byteArrayOf(batteryLevel.toByte())
        
        // Use deprecated API for compatibility with older Android versions
        batteryChar.value = data
        batteryChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        
        val success = gattConnection.writeCharacteristic(batteryChar)
        if (success) {
            Log.d(TAG, "GLASSES BATTERY: Sent battery level $batteryLevel% to phone")
        } else {
            Log.e(TAG, "GLASSES BATTERY: Failed to send battery level")
        }
    }
    
    /**
     * Stop the battery sending timer.
     */
    private fun stopBatterySending() {
        batterySendJob?.cancel()
        batterySendJob = null
    }
    
    // ========== RSSI MONITORING: Periodic signal strength checking ==========
    
    // RSSI monitoring job for coroutine-based monitoring
    @Volatile private var rssiMonitorJob: Job? = null
    
    // === CONNECTION METRICS: Track session statistics ===
    private var sessionStartTimeMs: Long = 0
    private var totalPacketsReceived: Long = 0
    private var totalStaleEvents: Long = 0
    private var reconnectCount: Int = 0
    private var rssiSamples: MutableList<Int> = mutableListOf()
    
    /**
     * Start periodically checking RSSI (signal strength) to monitor connection quality.
     * This helps detect weak signals before the connection drops.
     * Uses coroutine for better resource management.
     */
    private fun startRssiMonitoring() {
        stopRssiMonitoring() // Stop any existing handler
        
        // Initialize session metrics
        sessionStartTimeMs = System.currentTimeMillis()
        totalPacketsReceived = 0
        totalStaleEvents = 0
        rssiSamples.clear()
        
        rssiMonitorJob = bleScope.launch {
            delay(RSSI_CHECK_INTERVAL_MS) // Initial delay
            
            while (isActive && _connectionState.value == ConnectionState.Connected) {
                // Request RSSI update from the connected device
                withContext(Dispatchers.Main) {
                    gatt?.readRemoteRssi()
                }
                delay(RSSI_CHECK_INTERVAL_MS)
            }
        }
        
        Log.d(TAG, "RSSI MONITORING: Started checking signal strength every ${RSSI_CHECK_INTERVAL_MS}ms")
    }
    
    /**
     * Stop the RSSI monitoring timer and log session summary.
     */
    private fun stopRssiMonitoring() {
        // Cancel coroutine job
        rssiMonitorJob?.cancel()
        rssiMonitorJob = null
        
        // Log session summary before stopping
        if (sessionStartTimeMs > 0 && totalPacketsReceived > 0) {
            val sessionDurationSec = (System.currentTimeMillis() - sessionStartTimeMs) / 1000.0
            val avgRssi = if (rssiSamples.isNotEmpty()) rssiSamples.average() else 0.0
            val minRssi = rssiSamples.minOrNull() ?: 0
            val maxRssi = rssiSamples.maxOrNull() ?: 0
            val packetsPerSec = totalPacketsReceived / sessionDurationSec
            
            Log.i(TAG, "=== CONNECTION SESSION SUMMARY ===")
            Log.i(TAG, "Duration: ${String.format("%.1f", sessionDurationSec)}s")
            Log.i(TAG, "Packets received: $totalPacketsReceived (${String.format("%.1f", packetsPerSec)}/sec)")
            Log.i(TAG, "Stale events: $totalStaleEvents")
            Log.i(TAG, "Reconnects this session: $reconnectCount")
            Log.i(TAG, "RSSI - Avg: ${String.format("%.0f", avgRssi)} dBm, Min: $minRssi dBm, Max: $maxRssi dBm")
            Log.i(TAG, "=================================")
        }
    }
    
    /**
     * Record RSSI sample for session statistics.
     */
    private fun recordRssiSample(rssi: Int) {
        rssiSamples.add(rssi)
        // Keep only last 100 samples to avoid memory issues
        if (rssiSamples.size > 100) {
            rssiSamples.removeAt(0)
        }
    }
    
    /**
     * Increment packet count for session statistics.
     */
    private fun recordPacketReceived() {
        totalPacketsReceived++
    }
    
    /**
     * Record stale event for session statistics.
     */
    private fun recordStaleEvent() {
        totalStaleEvents++
    }
    
    /**
     * Increment reconnect count for session statistics.
     */
    fun recordReconnect() {
        reconnectCount++
        Log.d(TAG, "CONNECTION METRICS: Reconnect count = $reconnectCount")
    }
    
    /**
     * Clean up resources when the BleClient is no longer needed.
     * This should be called when the service/activity is destroyed.
     */
    fun close() {
        Log.i(TAG, "Closing BleClient and releasing resources...")
        disconnect()
        
        // Cancel all coroutines
        bleScope.cancel()
        
        Log.i(TAG, "BleClient closed")
    }
}
