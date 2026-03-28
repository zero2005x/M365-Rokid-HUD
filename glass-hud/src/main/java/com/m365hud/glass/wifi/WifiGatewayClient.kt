package com.m365hud.glass.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.m365hud.glass.TelemetryData
import com.m365hud.glass.TimeData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WiFi Client for connecting to M365 HUD Gateway (phone) over WiFi.
 * 
 * Features:
 * - mDNS/NSD service discovery (automatic gateway detection)
 * - Manual IP connection support
 * - Automatic reconnection with exponential backoff
 * - Same data format as BLE for compatibility
 * - Lower latency than BLE (typically < 10ms vs < 50ms)
 * 
 * Usage:
 * 1. Create instance: val client = WifiGatewayClient(context)
 * 2. Start discovery: client.startDiscovery()
 * 3. Or connect directly: client.connect("192.168.1.100", 8365)
 * 4. Observe: client.telemetry.collect { ... }
 */
class WifiGatewayClient(private val context: Context) {
    
    companion object {
        private const val TAG = "WifiGatewayClient"
        
        // Service Discovery
        const val SERVICE_TYPE = "_m365hud._tcp."
        const val DEFAULT_PORT = 8365
        
        // Message Types (must match server)
        const val MSG_TYPE_TELEMETRY: Byte = 0x01
        const val MSG_TYPE_TIME: Byte = 0x02
        const val MSG_TYPE_COMMAND: Byte = 0x03
        const val MSG_TYPE_HEARTBEAT: Byte = 0x04
        const val MSG_TYPE_GLASSES_BATTERY: Byte = 0x05
        
        // Connection settings
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 10000
        const val HEARTBEAT_INTERVAL_MS = 3000L
        const val MAX_RECONNECT_DELAY_MS = 30000L
        
        // Data sizes
        const val TELEMETRY_DATA_SIZE = 20
        const val TIME_DATA_SIZE = 12
    }
    
    // Connection state
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Discovering : ConnectionState()
        data class Connecting(val address: String) : ConnectionState()
        data class Connected(val address: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    // Signal strength (based on latency for WiFi)
    enum class SignalStrength {
        Excellent,  // < 10ms
        Good,       // < 30ms
        Fair,       // < 100ms
        Poor        // > 100ms
    }
    
    // State
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // NSD
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    
    // Discovered gateway
    @Volatile private var gatewayAddress: String? = null
    @Volatile private var gatewayPort: Int = DEFAULT_PORT
    
    // Reconnection
    @Volatile private var reconnectDelay = 1000L
    @Volatile private var shouldReconnect = true
    
    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()
    
    private val _timeData = MutableStateFlow(TimeData())
    val timeData: StateFlow<TimeData> = _timeData.asStateFlow()
    
    private val _signalStrength = MutableStateFlow(SignalStrength.Good)
    val signalStrength: StateFlow<SignalStrength> = _signalStrength.asStateFlow()
    
    private val _isTelemetryFresh = MutableStateFlow(false)
    val isTelemetryFresh: StateFlow<Boolean> = _isTelemetryFresh.asStateFlow()
    
    // Latency monitoring
    @Volatile private var lastTelemetryUpdateMs: Long = 0
    @Volatile private var telemetryUpdateCount: Int = 0
    @Volatile private var lastLogTimeMs: Long = 0
    @Volatile private var lastHeartbeatLatencyMs: Long = 0
    
    /**
     * Start NSD discovery to find gateway automatically
     */
    fun startDiscovery() {
        Log.i(TAG, "Starting NSD discovery...")
        _connectionState.value = ConnectionState.Discovering
        
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.i(TAG, "NSD discovery started for $serviceType")
                }
                
                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.i(TAG, "Service found: ${service.serviceName}")
                    if (service.serviceName.contains("M365-HUD")) {
                        resolveService(service)
                    }
                }
                
                override fun onServiceLost(service: NsdServiceInfo) {
                    Log.w(TAG, "Service lost: ${service.serviceName}")
                    if (gatewayAddress != null) {
                        // Gateway lost, trigger reconnection
                        handleDisconnection()
                    }
                }
                
                override fun onDiscoveryStopped(serviceType: String) {
                    Log.i(TAG, "NSD discovery stopped")
                }
                
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "NSD discovery start failed: $errorCode")
                    _connectionState.value = ConnectionState.Error("Discovery failed: $errorCode")
                }
                
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "NSD discovery stop failed: $errorCode")
                }
            }
            
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Discovery error")
        }
    }
    
    /**
     * Resolve discovered service
     */
    private fun resolveService(service: NsdServiceInfo) {
        resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                val port = serviceInfo.port
                Log.i(TAG, "Service resolved: $host:$port")
                
                if (host != null) {
                    gatewayAddress = host
                    gatewayPort = port
                    
                    // Connect to resolved address
                    scope.launch {
                        connect(host, port)
                    }
                }
            }
            
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Service resolve failed: $errorCode")
            }
        }
        
        nsdManager?.resolveService(service, resolveListener)
    }
    
    /**
     * Connect to gateway directly by IP and port
     */
    fun connect(address: String, port: Int = DEFAULT_PORT) {
        if (isRunning.get()) {
            Log.w(TAG, "Already connected or connecting")
            return
        }
        
        scope.launch {
            connectInternal(address, port)
        }
    }
    
    /**
     * Internal connection logic
     */
    private suspend fun connectInternal(address: String, port: Int) {
        try {
            Log.i(TAG, "Connecting to $address:$port...")
            _connectionState.value = ConnectionState.Connecting(address)
            
            // Create socket with timeout
            socket = Socket()
            socket?.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            socket?.soTimeout = READ_TIMEOUT_MS
            socket?.tcpNoDelay = true  // Disable Nagle's algorithm for lower latency
            
            outputStream = DataOutputStream(socket?.getOutputStream())
            inputStream = DataInputStream(socket?.getInputStream())
            
            isRunning.set(true)
            shouldReconnect = true
            reconnectDelay = 1000L  // Reset backoff
            
            gatewayAddress = address
            gatewayPort = port
            
            _connectionState.value = ConnectionState.Connected(address)
            Log.i(TAG, "Connected to gateway at $address:$port")
            
            // Start heartbeat
            startHeartbeat()
            
            // Start receiving data
            receiveLoop()
            
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            handleDisconnection()
        }
    }
    
    /**
     * Receive loop - read incoming messages
     */
    private suspend fun receiveLoop() {
        try {
            val input = inputStream ?: return
            
            while (isRunning.get()) {
                try {
                    // Read message length
                    val length = input.readInt()
                    if (length <= 0 || length > 1024) {
                        Log.w(TAG, "Invalid message length: $length")
                        continue
                    }
                    
                    // Read message type
                    val type = input.readByte()
                    
                    // Read payload
                    val payload = ByteArray(length - 1)
                    input.readFully(payload)
                    
                    // Process message
                    processMessage(type, payload)
                    
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout - check if we should continue
                    if (!isRunning.get()) break
                } catch (e: java.io.EOFException) {
                    Log.i(TAG, "Server disconnected")
                    break
                }
            }
            
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Receive loop error", e)
            }
        } finally {
            handleDisconnection()
        }
    }
    
    /**
     * Process incoming message
     */
    private fun processMessage(type: Byte, payload: ByteArray) {
        when (type) {
            MSG_TYPE_TELEMETRY -> {
                parseTelemetry(payload)
            }
            MSG_TYPE_TIME -> {
                parseTimeData(payload)
            }
            MSG_TYPE_HEARTBEAT -> {
                // Calculate latency from heartbeat response
                if (payload.size >= 8) {
                    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                    val sentTime = buffer.long
                    lastHeartbeatLatencyMs = System.currentTimeMillis() - sentTime
                    updateSignalStrength(lastHeartbeatLatencyMs)
                }
            }
            else -> {
                Log.w(TAG, "Unknown message type: $type")
            }
        }
    }
    
    /**
     * Parse telemetry data (same format as BLE)
     */
    private fun parseTelemetry(payload: ByteArray) {
        if (payload.size < TELEMETRY_DATA_SIZE) {
            Log.w(TAG, "Invalid telemetry size: ${payload.size}")
            return
        }
        
        val now = System.currentTimeMillis()
        telemetryUpdateCount++
        
        // Log stats every 5 seconds
        if (now - lastLogTimeMs >= 5000L && lastLogTimeMs > 0) {
            val rate = telemetryUpdateCount * 1000.0 / (now - lastLogTimeMs)
            Log.d(TAG, "WIFI STATS: ${String.format("%.1f", rate)} updates/sec, latency: ${lastHeartbeatLatencyMs}ms")
            telemetryUpdateCount = 0
            lastLogTimeMs = now
        }
        if (lastLogTimeMs == 0L) lastLogTimeMs = now
        lastTelemetryUpdateMs = now
        _isTelemetryFresh.value = true
        
        // Parse data
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        
        val data = TelemetryData(
            speedKmh = buffer.short.toInt() / 100f,
            scooterBattery = buffer.get().toInt() and 0xFF,
            temperatureC = buffer.short.toInt() / 10f,
            totalMileageM = buffer.int.toLong(),
            avgSpeedKmh = buffer.short.toInt() / 100f,
            remainingRangeKm = buffer.short.toInt() / 10f,
            connectionState = buffer.get().toInt(),
            tripMeters = buffer.short.toInt(),
            tripSeconds = buffer.short.toInt()
        )
        
        _telemetry.value = data
    }
    
    /**
     * Parse time data
     */
    private fun parseTimeData(payload: ByteArray) {
        if (payload.size < TIME_DATA_SIZE) {
            Log.w(TAG, "Invalid time data size: ${payload.size}")
            return
        }
        
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        
        val data = TimeData(
            hour = buffer.get().toInt() and 0xFF,
            minute = buffer.get().toInt() and 0xFF,
            second = buffer.get().toInt() and 0xFF,
            phoneBattery = buffer.get().toInt() and 0xFF
        )
        
        _timeData.value = data
    }
    
    /**
     * Update signal strength based on latency
     */
    private fun updateSignalStrength(latencyMs: Long) {
        _signalStrength.value = when {
            latencyMs < 10 -> SignalStrength.Excellent
            latencyMs < 30 -> SignalStrength.Good
            latencyMs < 100 -> SignalStrength.Fair
            else -> SignalStrength.Poor
        }
    }
    
    /**
     * Start heartbeat coroutine
     */
    private fun startHeartbeat() {
        scope.launch {
            while (isRunning.get()) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHeartbeat()
            }
        }
    }
    
    /**
     * Send heartbeat to server
     */
    private fun sendHeartbeat() {
        try {
            val output = outputStream ?: return
            val timestamp = System.currentTimeMillis()
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            buffer.putLong(timestamp)
            
            synchronized(output) {
                output.writeInt(9)  // 1 byte type + 8 bytes timestamp
                output.writeByte(MSG_TYPE_HEARTBEAT.toInt())
                output.write(buffer.array())
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send heartbeat", e)
        }
    }
    
    /**
     * Send glasses battery level to server
     */
    fun sendGlassesBattery(batteryLevel: Int) {
        try {
            val output = outputStream ?: return
            
            synchronized(output) {
                output.writeInt(2)  // 1 byte type + 1 byte battery
                output.writeByte(MSG_TYPE_GLASSES_BATTERY.toInt())
                output.writeByte(batteryLevel)
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send glasses battery", e)
        }
    }
    
    /**
     * Handle disconnection and trigger reconnection
     */
    private fun handleDisconnection() {
        val wasRunning = isRunning.getAndSet(false)
        
        // Cleanup
        try {
            socket?.close()
        } catch (e: Exception) { }
        socket = null
        outputStream = null
        inputStream = null
        
        _isTelemetryFresh.value = false
        _connectionState.value = ConnectionState.Disconnected
        
        // Reconnect if should
        if (wasRunning && shouldReconnect && gatewayAddress != null) {
            scope.launch {
                Log.i(TAG, "Reconnecting in ${reconnectDelay}ms...")
                delay(reconnectDelay)
                
                // Exponential backoff
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                
                gatewayAddress?.let { address ->
                    connectInternal(address, gatewayPort)
                }
            }
        }
    }
    
    /**
     * Disconnect and stop
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting...")
        shouldReconnect = false
        isRunning.set(false)
        
        // Stop discovery
        try {
            discoveryListener?.let { listener ->
                nsdManager?.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) { }
        
        // Close socket
        try {
            socket?.close()
        } catch (e: Exception) { }
        
        socket = null
        outputStream = null
        inputStream = null
        nsdManager = null
        discoveryListener = null
        resolveListener = null
        
        scope.cancel()
        _connectionState.value = ConnectionState.Disconnected
    }
    
    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isRunning.get() && socket?.isConnected == true
    
    /**
     * Get last known latency
     */
    fun getLatencyMs(): Long = lastHeartbeatLatencyMs
}
