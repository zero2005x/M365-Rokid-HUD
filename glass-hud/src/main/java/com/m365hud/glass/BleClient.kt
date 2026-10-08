package com.m365hud.glass

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.BatteryManager
import android.os.ParcelUuid
import android.os.SystemClock
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
class BleClient(
    private val context: Context,
    // Injected (with production defaults) so coroutine builders receive provided
    // dispatchers instead of hardcoded ones — Sonar S6310.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    
    companion object {
        private const val TAG = "BleClient"
        private const val FAILED_DEVICE_TTL_MS = 15000L  // retry a device that lacked the HUD service after this long
        private const val SCAN_TIMEOUT_MS = 30000L // 30 seconds per scan cycle (service will retry)
        
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
    private val bleScope = CoroutineScope(ioDispatcher + SupervisorJob())
    
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
    
    private val connectionOwner = BleSessionOwner<BluetoothGatt>()
    private var gatt: BluetoothGatt?
        get() = connectionOwner.current
        set(value) { connectionOwner.replace(value) }
    private var closed = false
    private var targetDevice: BluetoothDevice? = null
    
    // State flows for UI observation
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()
    
    private val _timeData = MutableStateFlow(TimeData())
    val timeData: StateFlow<TimeData> = _timeData.asStateFlow()

    private val _displayPrefs = MutableStateFlow(DisplayPrefs())
    /**
     * Which fields the phone wants rendered.
     *
     * Defaults to the historical layout, so the HUD looks exactly as it did
     * before this feature until the phone sends a preference.
     */
    val displayPrefs: StateFlow<DisplayPrefs> = _displayPrefs.asStateFlow()
    
    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()
    
    // === SIGNAL STRENGTH INDICATOR ===
    private val _signalStrength = MutableStateFlow(SignalStrength.Good)
    val signalStrength: StateFlow<SignalStrength> = _signalStrength.asStateFlow()
    
    // LATENCY MONITORING: Track last telemetry update time
    @Volatile private var lastTelemetryUpdateMs: Long = 0
    @Volatile private var receivedValidSessionTelemetry = false
    @Volatile private var telemetryUpdateCount: Int = 0
    @Volatile private var lastLogTimeMs: Long = 0
    
    // LATENCY MONITORING: Telemetry freshness indicator (true = receiving data normally)
    private val _isTelemetryFresh = MutableStateFlow(false)
    val isTelemetryFresh: StateFlow<Boolean> = _isTelemetryFresh.asStateFlow()
    
    /**
     * Start scanning for the M365 HUD Gateway
     */
    fun startScan() {
        connectionOwner.locked { startScanLocked() }
    }

    private fun startScanLocked() {
        if (closed || gatt != null) return
        gatt = null // A new scan supersedes an older timeout/reconnect intent.
        val scanRevision = connectionOwner.revision
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
        
        stopScan()
        // Reset the connection flag only. Do NOT clear failedDevices here:
        // onServicesDiscovered adds a device to that list and restarts the
        // scan, so wiping it on every scan made the very same device get
        // retried immediately ??an endless connect / discover-fail / rescan
        // loop. The list is already cleared on a successful connection.
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
            val callback = createFilteredScanCallback()
            scanCallback = callback
            scanner.startScan(listOf(scanFilter), scanSettings, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan with filter: ${e.message}")
            // Try without filter as fallback.
            stopScan()
            startScanWithoutFilter()
        }
        
        // Use coroutine for delayed operations (more efficient than Handler)
        bleScope.launch {
            // Fallback: Try scanning without UUID filter after 5 seconds if nothing found
            delay(SCAN_RETRY_WITHOUT_FILTER_MS)
            withContext(mainDispatcher) {
                connectionOwner.locked {
                    if (!closed && connectionOwner.revision == scanRevision &&
                        _connectionState.value == ConnectionState.Scanning && !isConnecting && scanCallback != null) {
                        Log.w(TAG, "No device found with UUID filter, retrying without filter...")
                        stopScan()
                        startScanWithoutFilter()
                    }
                }
            }
        }
        
        // Auto-stop scan after timeout (using coroutine)
        bleScope.launch {
            delay(SCAN_TIMEOUT_MS)
            withContext(mainDispatcher) {
                connectionOwner.locked {
                    if (!closed && connectionOwner.revision == scanRevision &&
                        _connectionState.value == ConnectionState.Scanning) {
                        stopScan()
                        Log.e(TAG, "Scan timeout - Gateway not found after ${SCAN_TIMEOUT_MS}ms")
                        _connectionState.value = ConnectionState.Error("Gateway not found - make sure HUD Gateway is enabled on phone")
                    }
                }
            }
        }
    }
    
    /**
     * Start scanning without UUID filter (broader scan for debugging)
     */
    private fun startScanWithoutFilter() {
        connectionOwner.locked { startScanWithoutFilterLocked() }
    }

    private fun startScanWithoutFilterLocked() {
        if (closed || _connectionState.value != ConnectionState.Scanning) return
        
        Log.i(TAG, "Starting scan WITHOUT UUID filter (will match device name)...")
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        try {
            val callback = createUnfilteredScanCallback()
            scanCallbackNoFilter = callback
            scanner?.startScan(null, scanSettings, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start unfiltered scan: ${e.message}")
            _connectionState.value = ConnectionState.Error("Scan failed: ${e.message}")
        }
    }
    
    /**
     * Scan callback for unfiltered scan (matches by device name or UUID)
     */
    private var scanCallback: ScanCallback? = null
    private var scanCallbackNoFilter: ScanCallback? = null

    private fun createUnfilteredScanCallback(): ScanCallback = object : ScanCallback() {
        private val seenDevices = mutableSetOf<String>()
        
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            withCurrentScan(this) { handleScanResult(result) }
        }

        private fun handleScanResult(result: ScanResult) {
            val deviceName = result.device.name ?: result.scanRecord?.deviceName
            val address = result.device.address
            val serviceUuids = result.scanRecord?.serviceUuids
            
            // Log each unique device once with INFO level for debugging
            if (!seenDevices.contains(address)) {
                seenDevices.add(address)
                Log.i(TAG, "Unfiltered scan found device: name=$deviceName, addr=$address, UUIDs=$serviceUuids, RSSI=${result.rssi}")
            }
            
            // Skip devices that previously failed service discovery
            if (isRecentlyFailed(address)) {
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
                connectionOwner.locked {
                    if (_connectionState.value == ConnectionState.Scanning && !isConnecting) {
                        isConnecting = true
                        stopScan()
                        connect(result.device)
                    }
                }
            } else if (hasMatchingName) {
                Log.i(TAG, "Found potential Gateway device (by name): $deviceName ($address) - will attempt connection")
                connectionOwner.locked {
                    if (_connectionState.value == ConnectionState.Scanning && !isConnecting) {
                        isConnecting = true
                        stopScan()
                        connect(result.device)
                    }
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            withCurrentScan(this) { handleScanFailed(errorCode) }
        }

        private fun handleScanFailed(errorCode: Int) {
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
        connectionOwner.locked {
            val filtered = scanCallback
            val unfiltered = scanCallbackNoFilter
            scanCallback = null
            scanCallbackNoFilter = null
            // Invalidate callbacks before asking the stack to stop. Queued scan results/failures
            // cannot claim a later scan that happens to have the same UI state.
            for (callback in listOfNotNull(filtered, unfiltered)) {
                try { scanner?.stopScan(callback) }
                catch (error: Exception) { Log.w(TAG, "Error stopping scan: ${error.message}") }
            }
        }
        Log.i(TAG, "Scan stopped")
    }
    
    /**
     * Connect to a discovered Gateway device
     */
    fun connect(device: BluetoothDevice) {
        val previous = connectionOwner.locked {
            if (closed) return@locked null
            stopScan()
            val old = gatt
            gatt = null // Invalidate admitted callbacks before resetting state.
            stopWatchdog()
            stopBatterySending()
            stopRssiMonitoring()
            _telemetry.value = TelemetryData()
            _timeData.value = TimeData()
            _displayPrefs.value = DisplayPrefs()
            _rssi.value = 0
            _signalStrength.value = SignalStrength.Good
            glassesBatteryCharacteristic = null
            targetDevice = device
            isConnecting = true
            _connectionState.value = ConnectionState.Connecting
            Log.i(TAG, "Connecting to HUD gateway...")
            // Submit while transitions are serialized. Binder callbacks cannot publish until
            // the returned handle has been installed; no coroutine wait occurs under the lock.
            gatt = try {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (error: SecurityException) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted", error)
                null
            }
            if (gatt == null) {
                isConnecting = false
                targetDevice = null
                _connectionState.value = ConnectionState.Error("Bluetooth connection could not start")
            }
            old
        }
        closeGatt(previous, disconnectFirst = true)
    }
    
    /**
     * Disconnect from the Gateway
     */
    fun disconnect() {
        val previous = connectionOwner.locked {
            val old = gatt
            gatt = null
            isConnecting = false
            stopScan()
            stopWatchdog()
            stopBatterySending()
            stopRssiMonitoring()
            targetDevice = null
            glassesBatteryCharacteristic = null
            _connectionState.value = ConnectionState.Disconnected
            _telemetry.value = TelemetryData()
            _timeData.value = TimeData()
            _displayPrefs.value = DisplayPrefs()
            _rssi.value = 0
            _signalStrength.value = SignalStrength.Good
            old
        }
        // Close the detached object now. A deferred close would be cancelled by close(),
        // and callbacks from this object can no longer change a new connection's state.
        closeGatt(previous, disconnectFirst = true)
    }
    
    /**
     * Request RSSI update
     */
    fun readRssi() {
        connectionOwner.locked {
            gatt?.let { current ->
                try { current.readRemoteRssi() }
                catch (error: SecurityException) { Log.w(TAG, "RSSI permission missing", error) }
            }
        }
    }
    
    // ========== Callbacks ==========
    
    // Devices that failed service discovery, with the time they failed. The entry
    // expires: the real gateway phone can briefly lack the HUD service (gateway
    // restarted, app swiped away) and must be retried once it is back, instead of
    // being blacklisted until the glasses app restarts.
    private val failedDevices = mutableMapOf<String, Long>()

    private fun isRecentlyFailed(address: String): Boolean = synchronized(failedDevices) {
        val failedAt = failedDevices[address] ?: return false
        if (System.currentTimeMillis() - failedAt >= FAILED_DEVICE_TTL_MS) {
            failedDevices.remove(address)
            false
        } else {
            true
        }
    }
    
    // Flag to prevent multiple connection attempts during scan
    @Volatile
    private var isConnecting = false
    
    private fun createFilteredScanCallback(): ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            withCurrentScan(this) { handleScanResult(result) }
        }

        private fun handleScanResult(result: ScanResult) {
            val deviceName = result.device.name ?: result.scanRecord?.deviceName ?: "Unknown"
            val address = result.device.address
            
            // Skip devices that previously failed service discovery
            if (isRecentlyFailed(address)) {
                return
            }

            Log.i(TAG, "Found Gateway via UUID filter: name=$deviceName, addr=$address, RSSI=${result.rssi}")
            
            // Auto-connect to the first device with our service
            // Use synchronized check to prevent race condition
            connectionOwner.locked {
                if (_connectionState.value == ConnectionState.Scanning && !isConnecting) {
                    isConnecting = true
                    // Stop scan immediately before attempting connection
                    stopScan()
                    connect(result.device)
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            withCurrentScan(this) { handleScanFailed(errorCode) }
        }

        private fun handleScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error $errorCode"
            }
            Log.e(TAG, "Filtered scan failed: $errorMsg")
            isConnecting = false
            // Try without filter as fallback.
            stopScan()
            startScanWithoutFilter()
        }
    }
    
    private val gattCallback = object : BluetoothGattCallback() {
        
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val admitted = withCurrentGatt(gatt) {
                handleConnectionStateChange(gatt, status, newState)
            }
            if (!admitted && newState == BluetoothProfile.STATE_DISCONNECTED) closeGatt(gatt)
        }

        private fun handleConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
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
                        withContext(mainDispatcher) {
                            connectionOwner.ifCurrent(gatt) {
                                try { gatt.discoverServices() }
                                catch (error: SecurityException) { Log.w(TAG, "Discovery permission missing", error) }
                            }
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
                    this@BleClient.gatt = null
                    targetDevice = null
                    glassesBatteryCharacteristic = null
                    closeGatt(gatt)
                    
                    if (shouldAutoReconnect && autoReconnectEnabled) {
                        scheduleReconnect(connectionOwner.revision)
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            withCurrentGatt(gatt) { handleServicesDiscovered(gatt, status) }
        }

        private fun handleServicesDiscovered(gatt: BluetoothGatt, status: Int) {
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
                synchronized(failedDevices) {
                    failedDevices[deviceAddress] = System.currentTimeMillis()
                }
                Log.i(TAG, "Failed devices (retry after ${FAILED_DEVICE_TTL_MS}ms): $deviceAddress")
                
                // Disconnect and clean up
                this@BleClient.gatt = null
                closeGatt(gatt, disconnectFirst = true)
                targetDevice = null
                isConnecting = false
                
                // Resume scanning to find the correct device (using coroutine)
                _connectionState.value = ConnectionState.Scanning
                val failedRevision = connectionOwner.revision
                bleScope.launch {
                    delay(500)
                    withContext(mainDispatcher) {
                        connectionOwner.locked {
                            if (!closed && connectionOwner.revision == failedRevision &&
                                _connectionState.value == ConnectionState.Scanning) startScan()
                        }
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
            
            if (telemetryChar == null) {
                // Telemetry is the entire point of this client. Reporting
                // Connected without it leaves the UI showing a healthy link
                // that can never receive data.
                Log.e(TAG, "Telemetry characteristic not found - cannot operate")
                _connectionState.value = ConnectionState.Error("Telemetry characteristic missing")
                this@BleClient.gatt = null
                targetDevice = null
                glassesBatteryCharacteristic = null
                isConnecting = false
                stopWatchdog()
                stopBatterySending()
                stopRssiMonitoring()
                closeGatt(gatt, disconnectFirst = true)
                return
            }

            if (!enableNotification(gatt, telemetryChar)) {
                rejectConnection(gatt, "Telemetry subscription failed")
                return
            }

            // Enable time notification after telemetry (queue, using coroutine)
            if (timeChar != null) {
                bleScope.launch {
                    delay(500)
                    withContext(mainDispatcher) {
                        enableNotification(gatt, timeChar)
                    }
                }
            }

            // Display preferences are OPTIONAL: their absence only means the
            // phone app predates the feature, in which case the glasses keep
            // their default layout. So a missing characteristic is logged at
            // debug level and never treated as a connection failure.
            //
            // Subscribe only ??no explicit read. A GATT read issued while the
            // CCCD descriptor write is still in flight gets dropped by some
            // Android stacks (the stack serialises GATT operations and does not
            // queue a read behind a descriptor write reliably). The phone side
            // therefore notifies the current value as soon as it sees us
            // subscribe, which covers the reconnect case without a read.
            val prefsChar = service.getCharacteristic(GattProfile.DISPLAY_PREFS_CHAR_UUID)
            if (prefsChar != null) {
                bleScope.launch {
                    delay(750)
                    withContext(mainDispatcher) {
                        enableNotification(gatt, prefsChar)
                    }
                }
            } else {
                Log.d(TAG, "No display-prefs characteristic (older phone app); keeping default HUD layout")
            }
            
            _connectionState.value = ConnectionState.Connected
            
            // Clear failed devices list on successful connection
            synchronized(failedDevices) { failedDevices.clear() }
            Log.i(TAG, "Successfully connected, cleared failed devices list")
            
            // LATENCY MONITORING: Start watchdog timer
            startWatchdog(gatt)
            
            // GLASSES BATTERY: Start sending battery level to phone
            startBatterySending(gatt)
            
            // RSSI MONITORING: Start checking signal strength
            startRssiMonitoring(gatt)
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            withCurrentGatt(gatt) { handleCharacteristicChanged(characteristic, value) }
        }

        private fun handleCharacteristicChanged(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                GattProfile.TELEMETRY_CHAR_UUID -> {
                    // LATENCY MONITORING: Track update timing
                    val now = SystemClock.elapsedRealtime()
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
                    val data = TelemetryData.fromBytes(value)
                    if (!data.isValid) {
                        // Corrupt or malformed frame: keep the previous reading
                        // rather than blanking the HUD.
                        Log.w(TAG, "Discarding telemetry frame that failed CRC validation")
                        return
                    }
                    Log.d(TAG, "Telemetry: speed=${data.speedKmh}, battery=${data.scooterBattery}%")
                    _telemetry.value = data
                    lastTelemetryUpdateMs = now
                    receivedValidSessionTelemetry = true
                    _isTelemetryFresh.value = true
                }
                GattProfile.TIME_CHAR_UUID -> {
                    val data = TimeData.fromBytes(value)
                    Log.d(TAG, "Time: ${data.formatTime()}, phoneBattery=${data.phoneBattery}%")
                    _timeData.value = data
                }
                GattProfile.DISPLAY_PREFS_CHAR_UUID -> {
                    val prefs = DisplayPrefs.fromBytes(value)
                    Log.i(TAG, "Display prefs: mask=0x${prefs.mask.toString(16)}, scale=${prefs.textScalePercent}%")
                    _displayPrefs.value = prefs
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
            withCurrentGatt(gatt) { handleReadRemoteRssi(rssi, status) }
        }

        private fun handleReadRemoteRssi(rssi: Int, status: Int) {
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
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        var enabled = false
        connectionOwner.ifCurrent(gatt) {
            enabled = try { enableNotificationLocked(gatt, characteristic) }
            catch (error: SecurityException) {
                Log.w(TAG, "Notification permission missing", error)
                false
            }
        }
        return enabled
    }

    @Suppress("DEPRECATION")
    private fun enableNotificationLocked(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (!success) {
            Log.e(TAG, "Failed to set notification for ${characteristic.uuid}")
            return false
        }
        
        // Write to CCCD to enable notifications
        // Using deprecated API for compatibility with Android < 13 (Rokid glasses)
        val descriptor = characteristic.getDescriptor(GattProfile.CCCD_UUID)
        if (descriptor == null) return false
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val accepted = gatt.writeDescriptor(descriptor)
        if (accepted) Log.i(TAG, "Submitted notification subscription for ${characteristic.uuid}")
        return accepted
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
    private fun startWatchdog(expectedGatt: BluetoothGatt) {
        stopWatchdog()
        val now = SystemClock.elapsedRealtime()
        lastTelemetryUpdateMs = now
        lastLogTimeMs = now
        telemetryUpdateCount = 0
        receivedValidSessionTelemetry = false
        _isTelemetryFresh.value = false
        watchdogJob = bleScope.launch {
            delay(WATCHDOG_CHECK_INTERVAL_MS)
            while (isActive) {
                var reconnect = false
                val admitted = connectionOwner.ifCurrent(expectedGatt) {
                    reconnect = checkTelemetryFreshness()
                }
                if (!admitted) break
                if (reconnect) {
                    withContext(mainDispatcher) {
                        connectionOwner.ifCurrent(expectedGatt) { initiateAutoReconnect() }
                    }
                    break
                }
                delay(WATCHDOG_CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Initiate auto-reconnect when connection is detected as lost.
     * Uses a longer delay to ensure the BLE stack fully resets before reconnecting.
     */
    private fun initiateAutoReconnect() {
        if (!autoReconnectEnabled) return
        Log.i(TAG, "CONNECTION HEALTH: Initiating auto-reconnect...")
        disconnect()
        scheduleReconnect(connectionOwner.revision)
    }

    private fun checkTelemetryFreshness(): Boolean {
        if (_connectionState.value != ConnectionState.Connected) return false
        val elapsed = SystemClock.elapsedRealtime() - lastTelemetryUpdateMs
        if (elapsed > TELEMETRY_STALE_TIMEOUT_MS) {
            consecutiveStaleChecks++
            recordStaleEvent()
            _isTelemetryFresh.value = false
            return consecutiveStaleChecks >= STALE_CHECKS_BEFORE_RECONNECT && autoReconnectEnabled
        }
        consecutiveStaleChecks = 0
        _isTelemetryFresh.value = receivedValidSessionTelemetry
        return false
    }

    private fun scheduleReconnect(expectedRevision: Long) {
        bleScope.launch {
            delay(2000)
            withContext(mainDispatcher) {
                connectionOwner.locked {
                    if (!closed && autoReconnectEnabled && connectionOwner.revision == expectedRevision &&
                        _connectionState.value == ConnectionState.Disconnected) {
                        recordReconnect()
                        startScan()
                    }
                }
            }
        }
    }

    private fun withCurrentScan(callback: ScanCallback, action: () -> Unit) {
        connectionOwner.locked {
            if (closed || _connectionState.value != ConnectionState.Scanning ||
                (scanCallback !== callback && scanCallbackNoFilter !== callback)) return@locked
            try { action() }
            catch (error: SecurityException) {
                isConnecting = false
                _connectionState.value = ConnectionState.Error("Bluetooth permission missing")
                Log.w(TAG, "Scan callback permission missing", error)
            }
        }
    }

    private fun withCurrentGatt(handle: BluetoothGatt, action: () -> Unit): Boolean =
        connectionOwner.ifCurrent(handle) {
            try { action() }
            catch (error: SecurityException) {
                rejectConnection(handle, "Bluetooth permission missing")
                Log.w(TAG, "GATT callback permission missing", error)
            }
        }

    private fun rejectConnection(handle: BluetoothGatt, message: String) {
        gatt = null
        targetDevice = null
        glassesBatteryCharacteristic = null
        isConnecting = false
        stopWatchdog()
        stopBatterySending()
        stopRssiMonitoring()
        _connectionState.value = ConnectionState.Error(message)
        closeGatt(handle, disconnectFirst = true)
    }

    private fun closeGatt(handle: BluetoothGatt?, disconnectFirst: Boolean = false) {
        if (handle == null) return
        if (disconnectFirst) {
            try { handle.disconnect() }
            catch (error: SecurityException) { Log.w(TAG, "Disconnect permission missing", error) }
        }
        try { handle.close() }
        catch (error: SecurityException) { Log.w(TAG, "Close permission missing", error) }
    }

    /**
     * Enable or disable auto-reconnect feature.
     */
    fun setAutoReconnectEnabled(enabled: Boolean) {
        connectionOwner.locked {
            autoReconnectEnabled = enabled
            if (!enabled) connectionOwner.replace(gatt) // Cancel delayed reconnect intent.
            Log.i(TAG, "CONNECTION HEALTH: Auto-reconnect ${if (enabled) "enabled" else "disabled"}")
        }
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
    private fun startBatterySending(expectedGatt: BluetoothGatt) {
        stopBatterySending()
        if (glassesBatteryCharacteristic == null) return
        batterySendJob = bleScope.launch {
            while (isActive) {
                var connected = false
                withContext(mainDispatcher) {
                    connected = connectionOwner.ifCurrent(expectedGatt) { sendGlassesBattery(expectedGatt) }
                }
                if (!connected) break
                delay(BATTERY_SEND_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Send current glasses battery level to phone via GATT characteristic write.
     */
    @Suppress("DEPRECATION")
    private fun sendGlassesBattery(gattConnection: BluetoothGatt) {
        val batteryChar = glassesBatteryCharacteristic
        
        if (batteryChar == null) {
            Log.w(TAG, "GLASSES BATTERY: Cannot send - not connected or characteristic not available")
            return
        }
        
        val batteryLevel = getGlassesBatteryLevel()
        if (batteryLevel !in 0..100) return // BatteryManager may return an unknown sentinel.
        val data = byteArrayOf(batteryLevel.toByte())
        
        // Use deprecated API for compatibility with older Android versions
        batteryChar.value = data
        batteryChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        
        val success = try { gattConnection.writeCharacteristic(batteryChar) }
        catch (error: SecurityException) {
            Log.w(TAG, "Battery feedback permission missing", error)
            false
        }
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
    private fun startRssiMonitoring(expectedGatt: BluetoothGatt) {
        stopRssiMonitoring()
        sessionStartTimeMs = System.currentTimeMillis()
        totalPacketsReceived = 0
        totalStaleEvents = 0
        rssiSamples.clear()
        rssiMonitorJob = bleScope.launch {
            delay(RSSI_CHECK_INTERVAL_MS)
            while (isActive) {
                var connected = false
                withContext(mainDispatcher) {
                    connected = connectionOwner.ifCurrent(expectedGatt) { readRssi() }
                }
                if (!connected) break
                delay(RSSI_CHECK_INTERVAL_MS)
            }
        }
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
        connectionOwner.locked {
            reconnectCount++
            Log.d(TAG, "CONNECTION METRICS: Reconnect count = $reconnectCount")
        }
    }
    
    /**
     * Clean up resources when the BleClient is no longer needed.
     * This should be called when the service/activity is destroyed.
     */
    fun close() {
        connectionOwner.locked {
            closed = true
            autoReconnectEnabled = false
        }
        disconnect()
        bleScope.cancel()
        Log.i(TAG, "BleClient closed")
    }
}
