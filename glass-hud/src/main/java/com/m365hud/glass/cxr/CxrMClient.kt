package com.m365hud.glass.cxr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CXR-M SDK Client Wrapper
 * 
 * Wraps the Rokid CXR-M SDK for real-time communication with the phone app.
 * Uses ARTC (Alibaba Real-Time Communication) protocol for low-latency data transfer.
 * 
 * Protocol flow:
 * 1. Phone app creates an ARTC channel and shares the channelId
 * 2. Glasses join the channel using this client
 * 3. Data is exchanged via ARTC data channels
 * 
 * Note: This is a wrapper that can use either:
 * - Official CXR-M SDK (when available)
 * - Fallback to custom socket implementation
 */
class CxrMClient(private val context: Context) {
    
    companion object {
        private const val TAG = "CxrMClient"
        
        // Data channel message types (same as WiFi Gateway)
        const val MSG_TYPE_TELEMETRY = 0x01.toByte()
        const val MSG_TYPE_TIME = 0x02.toByte()
        const val MSG_TYPE_COMMAND = 0x03.toByte()
        const val MSG_TYPE_HEARTBEAT = 0x04.toByte()
        const val MSG_TYPE_GLASSES_BATTERY = 0x05.toByte()
    }
    
    // Connection state
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        data class Connecting(val channelId: String) : ConnectionState()
        data class Connected(val channelId: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Telemetry data
    data class TelemetryData(
        val speed: Float,           // km/h
        val battery: Int,           // %
        val averageSpeed: Float,    // km/h
        val remainingRange: Float,  // km
        val tripDistance: Float,    // km
        val totalMileage: Float,    // km
        val tripTime: Int,          // seconds
        val controllerTemp: Int,    // °C
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val _telemetry = MutableStateFlow<TelemetryData?>(null)
    val telemetry: StateFlow<TelemetryData?> = _telemetry.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // CXR-M SDK components (lazy initialization)
    private var artcClient: Any? = null
    private var isInitialized = false
    
    /**
     * Initialize the CXR-M SDK.
     * 
     * @return true if initialization successful
     */
    fun initialize(): Boolean {
        if (isInitialized) return true
        
        return try {
            // Try to load CXR-M SDK classes
            val sdkAvailable = checkSdkAvailability()
            
            if (sdkAvailable) {
                Log.i(TAG, "CXR-M SDK available, initializing...")
                initializeSdk()
            } else {
                Log.w(TAG, "CXR-M SDK not available, will use fallback")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CXR-M SDK: ${e.message}")
            false
        }
    }
    
    /**
     * Check if CXR-M SDK is available in the classpath.
     */
    private fun checkSdkAvailability(): Boolean {
        return try {
            // Check for main SDK class
            Class.forName("com.rokid.cxr.CxrClient")
            true
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "CXR-M SDK classes not found")
            false
        }
    }
    
    /**
     * Initialize the actual SDK.
     */
    private fun initializeSdk(): Boolean {
        return try {
            // Use reflection to initialize SDK to avoid compile-time dependency issues
            val cxrClientClass = Class.forName("com.rokid.cxr.CxrClient")
            val initMethod = cxrClientClass.getMethod("initialize", Context::class.java)
            initMethod.invoke(null, context)
            
            isInitialized = true
            Log.i(TAG, "CXR-M SDK initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SDK initialization failed: ${e.message}")
            false
        }
    }
    
    /**
     * Connect to an ARTC channel.
     * 
     * @param channelId The channel ID to join (provided by phone app)
     * @param token Authentication token (if required)
     */
    suspend fun connect(channelId: String, token: String? = null): Boolean {
        if (!isInitialized) {
            if (!initialize()) {
                _connectionState.value = ConnectionState.Error("SDK not initialized")
                return false
            }
        }
        
        _connectionState.value = ConnectionState.Connecting(channelId)
        
        return try {
            // Use reflection to call SDK methods
            val joined = joinChannel(channelId, token)
            
            if (joined) {
                _connectionState.value = ConnectionState.Connected(channelId)
                startDataListener()
                Log.i(TAG, "Connected to channel: $channelId")
                true
            } else {
                _connectionState.value = ConnectionState.Error("Failed to join channel")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            false
        }
    }
    
    /**
     * Join ARTC channel using SDK.
     */
    private suspend fun joinChannel(channelId: String, token: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cxrClientClass = Class.forName("com.rokid.cxr.CxrClient")
                val getInstance = cxrClientClass.getMethod("getInstance")
                val client = getInstance.invoke(null)
                
                val joinMethod = cxrClientClass.getMethod(
                    "joinChannel", 
                    String::class.java, 
                    String::class.java
                )
                val result = joinMethod.invoke(client, channelId, token ?: "")
                
                artcClient = client
                result as? Boolean ?: false
            } catch (e: Exception) {
                Log.e(TAG, "joinChannel failed: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Start listening for data from the channel.
     */
    private fun startDataListener() {
        scope.launch {
            try {
                val cxrClientClass = Class.forName("com.rokid.cxr.CxrClient")
                val client = artcClient ?: return@launch
                
                // Set up data callback using reflection
                val setDataCallbackMethod = cxrClientClass.getMethod(
                    "setDataCallback",
                    Class.forName("com.rokid.cxr.DataCallback")
                )
                
                // Create callback proxy
                val callbackClass = Class.forName("com.rokid.cxr.DataCallback")
                val callback = java.lang.reflect.Proxy.newProxyInstance(
                    callbackClass.classLoader,
                    arrayOf(callbackClass)
                ) { _, method, args ->
                    when (method.name) {
                        "onDataReceived" -> {
                            val data = args?.get(0) as? ByteArray
                            if (data != null) {
                                handleReceivedData(data)
                            }
                            null
                        }
                        "onDisconnected" -> {
                            _connectionState.value = ConnectionState.Disconnected
                            null
                        }
                        else -> null
                    }
                }
                
                setDataCallbackMethod.invoke(client, callback)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set up data listener: ${e.message}")
            }
        }
    }
    
    /**
     * Handle received data from the channel.
     */
    private fun handleReceivedData(data: ByteArray) {
        if (data.size < 5) return // Minimum: 4 bytes length + 1 byte type
        
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.int
        val type = buffer.get()
        
        when (type) {
            MSG_TYPE_TELEMETRY -> parseTelemetry(data, 5)
            MSG_TYPE_TIME -> parseTime(data, 5)
            MSG_TYPE_HEARTBEAT -> handleHeartbeat()
            else -> Log.d(TAG, "Unknown message type: $type")
        }
    }
    
    /**
     * Parse telemetry data (same format as WiFi Gateway).
     */
    private fun parseTelemetry(data: ByteArray, offset: Int) {
        if (data.size < offset + 20) return
        
        val buffer = ByteBuffer.wrap(data, offset, 20).order(ByteOrder.LITTLE_ENDIAN)
        
        _telemetry.value = TelemetryData(
            speed = buffer.short.toFloat() / 100f,
            battery = buffer.get().toInt() and 0xFF,
            averageSpeed = buffer.short.toFloat() / 100f,
            remainingRange = buffer.short.toFloat() / 100f,
            tripDistance = buffer.short.toFloat() / 100f,
            totalMileage = buffer.int.toFloat() / 100f,
            tripTime = buffer.short.toInt() and 0xFFFF,
            controllerTemp = buffer.get().toInt()
        )
    }
    
    /**
     * Parse time data.
     */
    private fun parseTime(data: ByteArray, offset: Int) {
        if (data.size < offset + 8) return
        
        val buffer = ByteBuffer.wrap(data, offset, 8).order(ByteOrder.LITTLE_ENDIAN)
        val timestamp = buffer.long
        
        Log.d(TAG, "Time sync: $timestamp")
    }
    
    /**
     * Handle heartbeat.
     */
    private fun handleHeartbeat() {
        Log.v(TAG, "Heartbeat received")
    }
    
    /**
     * Send glasses battery level to phone.
     */
    fun sendBatteryLevel(level: Int) {
        if (_connectionState.value !is ConnectionState.Connected) return
        
        scope.launch {
            try {
                val payload = ByteBuffer.allocate(6)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(1) // length
                    .put(MSG_TYPE_GLASSES_BATTERY)
                    .put(level.toByte())
                    .array()
                
                sendData(payload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send battery level: ${e.message}")
            }
        }
    }
    
    /**
     * Send command to phone.
     */
    fun sendCommand(command: ByteArray) {
        if (_connectionState.value !is ConnectionState.Connected) return
        
        scope.launch {
            try {
                val payload = ByteBuffer.allocate(5 + command.size)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(1 + command.size)
                    .put(MSG_TYPE_COMMAND)
                    .put(command)
                    .array()
                
                sendData(payload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send command: ${e.message}")
            }
        }
    }
    
    /**
     * Send raw data through the channel.
     */
    private suspend fun sendData(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val cxrClientClass = Class.forName("com.rokid.cxr.CxrClient")
                val client = artcClient ?: return@withContext
                
                val sendMethod = cxrClientClass.getMethod("sendData", ByteArray::class.java)
                sendMethod.invoke(client, data)
            } catch (e: Exception) {
                Log.e(TAG, "sendData failed: ${e.message}")
            }
        }
    }
    
    /**
     * Disconnect from the channel.
     */
    fun disconnect() {
        scope.launch {
            try {
                if (artcClient != null) {
                    val cxrClientClass = Class.forName("com.rokid.cxr.CxrClient")
                    val leaveMethod = cxrClientClass.getMethod("leaveChannel")
                    leaveMethod.invoke(artcClient)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error: ${e.message}")
            } finally {
                artcClient = null
                _connectionState.value = ConnectionState.Disconnected
                _telemetry.value = null
            }
        }
    }
    
    /**
     * Release all resources.
     */
    fun release() {
        disconnect()
        scope.cancel()
        isInitialized = false
    }
    
    /**
     * Check if SDK is available for use.
     */
    fun isSdkAvailable(): Boolean = checkSdkAvailability()
}
