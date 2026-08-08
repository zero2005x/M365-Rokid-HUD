package com.m365hud.glass.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.m365hud.glass.BleClient
import com.m365hud.glass.TelemetryData
import com.m365hud.glass.TimeData
import com.m365hud.glass.cxr.CxrMClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Unified Connection Manager that handles BLE, WiFi, and CXR-M SDK connections.
 * 
 * Strategy:
 * 1. CXR-M SDK when available (official Rokid protocol, best compatibility)
 * 2. WiFi when available (low latency, high bandwidth)
 * 3. Fallback to BLE when other options unavailable
 * 4. Automatic switching based on network conditions
 * 
 * Usage:
 * ```kotlin
 * val manager = UnifiedConnectionManager(context)
 * manager.start()
 * 
 * manager.telemetry.collect { data ->
 *     // Handle telemetry from BLE, WiFi, or CXR-M
 * }
 * ```
 */
class UnifiedConnectionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "UnifiedConnManager"
        
        // Connection preference
        const val PREFER_WIFI = 0
        const val PREFER_BLE = 1
        const val PREFER_AUTO = 2  // Auto-select based on availability and quality
        const val PREFER_CXR = 3   // Prefer CXR-M SDK (official Rokid protocol)
    }
    
    /**
     * Connection type enum
     */
    enum class ConnectionType {
        NONE,
        BLE,
        WIFI,
        CXR_M   // Rokid CXR-M SDK
    }
    
    /**
     * Unified connection state
     */
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        data class Connecting(val type: ConnectionType) : ConnectionState()
        data class Connected(val type: ConnectionType, val info: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    // Clients
    private var bleClient: BleClient? = null
    private var wifiClient: WifiGatewayClient? = null
    private var cxrClient: CxrMClient? = null
    
    // Network monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // State
    // Recreated by start(): stop() cancels this scope permanently, and every
    // later launch into a cancelled scope is silently dropped — so after one
    // stop/start cycle the manager could never connect again.
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var preference = PREFER_AUTO
    @Volatile private var isWifiAvailable = false
    @Volatile private var isCxrAvailable = false
    @Volatile private var isStarted = false
    
    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _activeConnectionType = MutableStateFlow(ConnectionType.NONE)
    val activeConnectionType: StateFlow<ConnectionType> = _activeConnectionType.asStateFlow()
    
    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()
    
    private val _timeData = MutableStateFlow(TimeData())
    val timeData: StateFlow<TimeData> = _timeData.asStateFlow()
    
    private val _isTelemetryFresh = MutableStateFlow(false)
    val isTelemetryFresh: StateFlow<Boolean> = _isTelemetryFresh.asStateFlow()
    
    // Latency comparison
    private val _bleLatencyMs = MutableStateFlow(Long.MAX_VALUE)
    private val _wifiLatencyMs = MutableStateFlow(Long.MAX_VALUE)
    
    /**
     * Start connection manager
     */
    fun start(preference: Int = PREFER_AUTO) {
        // Without this guard a second start() registers another
        // NetworkCallback (leaking the previous registration), builds new
        // clients and launches a duplicate set of collectors.
        if (isStarted) {
            Log.w(TAG, "start() called while already started, ignoring")
            return
        }
        isStarted = true

        this.preference = preference

        Log.i(TAG, "Starting unified connection manager, preference: $preference")

        // Fresh scope for this run (see the field declaration).
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }

        // Setup network monitoring
        setupNetworkMonitoring()
        
        // Initialize clients
        bleClient = BleClient(context)
        wifiClient = WifiGatewayClient(context)
        cxrClient = CxrMClient(context)
        
        // Check CXR-M SDK availability
        isCxrAvailable = cxrClient?.isSdkAvailable() == true
        Log.i(TAG, "CXR-M SDK available: $isCxrAvailable")
        
        // Observe all clients
        observeClients()
        
        // Start connection based on preference
        when (preference) {
            PREFER_CXR -> startCxrConnection()
            PREFER_WIFI -> startWifiConnection()
            PREFER_BLE -> startBleConnection()
            PREFER_AUTO -> startAutoConnection()
        }
    }
    
    /**
     * Stop connection manager
     */
    fun stop() {
        Log.i(TAG, "Stopping unified connection manager")
        
        scope.cancel()
        
        // Cleanup network monitoring
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) { }
        }
        
        // Cleanup clients
        bleClient?.disconnect()
        wifiClient?.disconnect()
        cxrClient?.release()
        
        bleClient = null
        wifiClient = null
        cxrClient = null
        networkCallback = null

        _connectionState.value = ConnectionState.Disconnected
        _activeConnectionType.value = ConnectionType.NONE
        isStarted = false
    }
    
    /**
     * Set connection preference
     */
    fun setPreference(preference: Int) {
        if (this.preference == preference) return
        
        this.preference = preference
        Log.i(TAG, "Preference changed to: $preference")
        
        // Re-evaluate connection
        evaluateConnection()
    }
    
    /**
     * Force switch to specific connection type
     */
    fun switchTo(type: ConnectionType) {
        Log.i(TAG, "Force switching to: $type")

        // Disconnect all first
        bleClient?.disconnect()
        wifiClient?.disconnect()
        cxrClient?.disconnect()

        // Reset the reported state, otherwise the UI keeps showing the previous
        // transport's Connected state while activeConnectionType has already
        // moved on.
        _connectionState.value = if (type == ConnectionType.NONE) {
            ConnectionState.Disconnected
        } else {
            ConnectionState.Connecting(type)
        }

        when (type) {
            ConnectionType.CXR_M -> {
                startCxrConnection()
            }
            ConnectionType.WIFI -> {
                startWifiConnection()
            }
            ConnectionType.BLE -> {
                startBleConnection()
            }
            ConnectionType.NONE -> {
                // Already disconnected above
            }
        }
    }
    
    /**
     * Setup network monitoring to detect WiFi availability
     */
    private fun setupNetworkMonitoring() {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "WiFi network available")
                isWifiAvailable = true
                if (preference == PREFER_AUTO) {
                    evaluateConnection()
                }
            }
            
            override fun onLost(network: Network) {
                Log.i(TAG, "WiFi network lost")
                isWifiAvailable = false
                if (preference == PREFER_AUTO && _activeConnectionType.value == ConnectionType.WIFI) {
                    // Fallback to BLE
                    Log.i(TAG, "WiFi lost, falling back to BLE")
                    startBleConnection()
                }
            }
        }
        
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        
        // Check initial state
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
        isWifiAvailable = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    
    /**
     * Observe all clients and merge data
     */
    private fun observeClients() {
        // Observe BLE client
        scope.launch {
            bleClient?.connectionState?.collect { state ->
                if (_activeConnectionType.value == ConnectionType.BLE) {
                    updateConnectionState(ConnectionType.BLE, state)
                }
            }
        }
        
        scope.launch {
            bleClient?.telemetry?.collect { data ->
                if (_activeConnectionType.value == ConnectionType.BLE) {
                    _telemetry.value = data
                }
            }
        }
        
        scope.launch {
            bleClient?.timeData?.collect { data ->
                if (_activeConnectionType.value == ConnectionType.BLE) {
                    _timeData.value = data
                }
            }
        }
        
        scope.launch {
            bleClient?.isTelemetryFresh?.collect { fresh ->
                if (_activeConnectionType.value == ConnectionType.BLE) {
                    _isTelemetryFresh.value = fresh
                }
            }
        }
        
        // Observe WiFi client
        scope.launch {
            wifiClient?.connectionState?.collect { state ->
                if (_activeConnectionType.value == ConnectionType.WIFI) {
                    updateWifiConnectionState(state)
                }
            }
        }
        
        scope.launch {
            wifiClient?.telemetry?.collect { data ->
                if (_activeConnectionType.value == ConnectionType.WIFI) {
                    _telemetry.value = data
                }
            }
        }
        
        scope.launch {
            wifiClient?.timeData?.collect { data ->
                if (_activeConnectionType.value == ConnectionType.WIFI) {
                    _timeData.value = data
                }
            }
        }
        
        scope.launch {
            wifiClient?.isTelemetryFresh?.collect { fresh ->
                if (_activeConnectionType.value == ConnectionType.WIFI) {
                    _isTelemetryFresh.value = fresh
                }
            }
        }
        
        // Observe CXR-M client
        scope.launch {
            cxrClient?.connectionState?.collect { state ->
                if (_activeConnectionType.value == ConnectionType.CXR_M) {
                    updateCxrConnectionState(state)
                }
            }
        }
        
        scope.launch {
            cxrClient?.telemetry?.collect { data ->
                if (_activeConnectionType.value == ConnectionType.CXR_M && data != null) {
                    // Convert CXR telemetry to unified TelemetryData format
                    _telemetry.value = TelemetryData(
                        speedKmh = data.speed,
                        scooterBattery = data.battery,
                        avgSpeedKmh = data.averageSpeed,
                        remainingRangeKm = data.remainingRange,
                        tripMeters = (data.tripDistance * 1000).toInt(),  // km to meters
                        totalMileageM = (data.totalMileage * 1000).toLong(),  // km to meters
                        tripSeconds = data.tripTime,
                        temperatureC = data.controllerTemp.toFloat(),
                        isValid = true
                    )
                    _isTelemetryFresh.value = true
                }
            }
        }
        
        // Monitor latency for auto-switching
        scope.launch {
            while (true) {
                delay(5000)
                
                if (preference == PREFER_AUTO && _activeConnectionType.value != ConnectionType.NONE) {
                    val wifiLatency = wifiClient?.getLatencyMs() ?: Long.MAX_VALUE
                    _wifiLatencyMs.value = wifiLatency
                    
                    // If WiFi latency is significantly better, consider switching
                    // This is a simplified heuristic
                }
            }
        }
    }
    
    /**
     * Update connection state from BLE client
     */
    private fun updateConnectionState(type: ConnectionType, bleState: BleClient.ConnectionState) {
        _connectionState.value = when (bleState) {
            is BleClient.ConnectionState.Disconnected -> ConnectionState.Disconnected
            is BleClient.ConnectionState.Scanning -> ConnectionState.Connecting(type)
            is BleClient.ConnectionState.Connecting -> ConnectionState.Connecting(type)
            is BleClient.ConnectionState.Connected -> ConnectionState.Connected(type, "BLE Connected")
            is BleClient.ConnectionState.Error -> ConnectionState.Error(bleState.message)
        }
    }
    
    /**
     * Update connection state from WiFi client
     */
    private fun updateWifiConnectionState(wifiState: WifiGatewayClient.ConnectionState) {
        _connectionState.value = when (wifiState) {
            is WifiGatewayClient.ConnectionState.Disconnected -> ConnectionState.Disconnected
            is WifiGatewayClient.ConnectionState.Discovering -> ConnectionState.Connecting(ConnectionType.WIFI)
            is WifiGatewayClient.ConnectionState.Connecting -> ConnectionState.Connecting(ConnectionType.WIFI)
            is WifiGatewayClient.ConnectionState.Connected -> ConnectionState.Connected(ConnectionType.WIFI, "WiFi: ${wifiState.address}")
            is WifiGatewayClient.ConnectionState.Error -> ConnectionState.Error(wifiState.message)
        }
    }
    
    /**
     * Update connection state from CXR-M client
     */
    private fun updateCxrConnectionState(cxrState: CxrMClient.ConnectionState) {
        _connectionState.value = when (cxrState) {
            is CxrMClient.ConnectionState.Disconnected -> ConnectionState.Disconnected
            is CxrMClient.ConnectionState.Connecting -> ConnectionState.Connecting(ConnectionType.CXR_M)
            is CxrMClient.ConnectionState.Connected -> ConnectionState.Connected(ConnectionType.CXR_M, "CXR-M: ${cxrState.channelId}")
            is CxrMClient.ConnectionState.Error -> ConnectionState.Error(cxrState.message)
        }
    }
    
    /**
     * Start CXR-M SDK connection
     */
    private fun startCxrConnection(channelId: String? = null) {
        Log.i(TAG, "Starting CXR-M connection")
        _activeConnectionType.value = ConnectionType.CXR_M
        
        scope.launch {
            val client = cxrClient ?: return@launch
            
            if (!client.initialize()) {
                Log.w(TAG, "CXR-M SDK not available, falling back")
                fallbackFromCxr()
                return@launch
            }

            // Channel discovery is not implemented (see discoverCxrChannel), so
            // without an explicit channelId this always falls back.
            val channel = channelId ?: discoverCxrChannel()

            if (channel != null) {
                client.connect(channel)
            } else {
                Log.w(TAG, "No CXR channel found, falling back")
                fallbackFromCxr()
            }
        }
    }
    
    /**
     * Discover CXR channel from phone (placeholder - needs phone-side implementation)
     */
    /**
     * Discovers the ARTC channel id advertised by the phone.
     *
     * NOT IMPLEMENTED. Until it is, CXR-M can only be used when the caller
     * passes an explicit `channelId` to [startCxrConnection]; otherwise the
     * connection falls back to the next available transport. Options for a real
     * implementation:
     * 1. mDNS/NSD, alongside the existing `_m365hud._tcp.` service
     * 2. Exchange the channel id over the existing BLE link
     * 3. QR code scanning
     * 4. Manual input
     */
    private suspend fun discoverCxrChannel(): String? {
        return null
    }

    /**
     * Chooses the next transport when CXR-M cannot be used.
     *
     * Previously this unconditionally called [startWifiConnection], which could
     * select WiFi even when no WiFi network was available.
     */
    private fun fallbackFromCxr() {
        if (isWifiAvailable) {
            startWifiConnection()
        } else {
            Log.i(TAG, "WiFi unavailable, falling back to BLE")
            startBleConnection()
        }
    }
    
    /**
     * Start WiFi connection
     */
    private fun startWifiConnection() {
        Log.i(TAG, "Starting WiFi connection")
        _activeConnectionType.value = ConnectionType.WIFI
        wifiClient?.startDiscovery()
    }
    
    /**
     * Start BLE connection
     */
    private fun startBleConnection() {
        Log.i(TAG, "Starting BLE connection")
        _activeConnectionType.value = ConnectionType.BLE
        bleClient?.startScan()
    }
    
    /**
     * Start auto connection (prefer CXR-M if SDK available, then WiFi, then BLE)
     */
    private fun startAutoConnection() {
        Log.i(TAG, "Starting auto connection, CXR available: $isCxrAvailable, WiFi available: $isWifiAvailable")
        
        // Priority: CXR-M > WiFi > BLE
        when {
            isCxrAvailable -> {
                startCxrConnection()
                
                // Fallback chain if CXR fails
                scope.launch {
                    delay(5000)
                    if (_connectionState.value is ConnectionState.Disconnected ||
                        _connectionState.value is ConnectionState.Error) {
                        Log.i(TAG, "CXR-M failed, trying WiFi")
                        if (isWifiAvailable) {
                            startWifiConnection()
                        } else {
                            startBleConnection()
                        }
                    }
                }
            }
            isWifiAvailable -> {
                startWifiConnection()
                
                // Fallback to BLE
                scope.launch {
                    delay(5000)
                    if (_connectionState.value is ConnectionState.Disconnected ||
                        _connectionState.value is ConnectionState.Error) {
                        Log.i(TAG, "WiFi failed, falling back to BLE")
                        startBleConnection()
                    }
                }
            }
            else -> {
                startBleConnection()
            }
        }
    }
    
    /**
     * Evaluate and potentially switch connection
     */
    private fun evaluateConnection() {
        when (preference) {
            PREFER_CXR -> {
                if (_activeConnectionType.value != ConnectionType.CXR_M) {
                    switchTo(ConnectionType.CXR_M)
                }
            }
            PREFER_WIFI -> {
                if (_activeConnectionType.value != ConnectionType.WIFI) {
                    switchTo(ConnectionType.WIFI)
                }
            }
            PREFER_BLE -> {
                if (_activeConnectionType.value != ConnectionType.BLE) {
                    switchTo(ConnectionType.BLE)
                }
            }
            PREFER_AUTO -> {
                // In auto mode, prefer CXR-M > WiFi > BLE
                when {
                    isCxrAvailable && _activeConnectionType.value != ConnectionType.CXR_M -> {
                        Log.i(TAG, "CXR-M available, switching to CXR-M")
                        switchTo(ConnectionType.CXR_M)
                    }
                    isWifiAvailable && _activeConnectionType.value == ConnectionType.BLE -> {
                        Log.i(TAG, "WiFi became available, switching from BLE")
                        switchTo(ConnectionType.WIFI)
                    }
                }
            }
        }
    }
    
    /**
     * Get connection info string
     */
    fun getConnectionInfo(): String {
        return when (val state = _connectionState.value) {
            is ConnectionState.Connected -> {
                val type = when (_activeConnectionType.value) {
                    ConnectionType.CXR_M -> "CXR-M"
                    ConnectionType.WIFI -> "WiFi"
                    ConnectionType.BLE -> "BLE"
                    ConnectionType.NONE -> "None"
                }
                val latency = if (_activeConnectionType.value == ConnectionType.WIFI) {
                    wifiClient?.getLatencyMs()?.let { "${it}ms" } ?: "N/A"
                } else {
                    "N/A"
                }
                "$type | Latency: $latency"
            }
            is ConnectionState.Connecting -> "Connecting via ${state.type}..."
            is ConnectionState.Error -> "Error: ${state.message}"
            else -> "Disconnected"
        }
    }
}
