package com.m365bleapp.gateway.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.m365bleapp.gateway.M365HudGattProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WiFi Gateway Server for low-latency telemetry streaming to Rokid Glasses.
 * 
 * Features:
 * - TCP Socket Server for reliable data transfer
 * - mDNS/NSD service discovery (no manual IP configuration needed)
 * - Multiple client support (connect multiple glasses)
 * - Automatic reconnection handling
 * - Binary protocol for efficient data transfer
 * 
 * Protocol: Same telemetry format as BLE GATT but over TCP
 * - Message format: [Length:4bytes][Type:1byte][Payload:variable]
 */
class WifiGatewayServer(private val context: Context) {
    
    companion object {
        private const val TAG = "WifiGatewayServer"
        
        // Service Discovery
        const val SERVICE_TYPE = "_m365hud._tcp."
        const val SERVICE_NAME = "M365-HUD-Gateway"
        const val DEFAULT_PORT = 8365
        
        // Message Types
        const val MSG_TYPE_TELEMETRY: Byte = 0x01
        const val MSG_TYPE_TIME: Byte = 0x02
        const val MSG_TYPE_COMMAND: Byte = 0x03
        const val MSG_TYPE_HEARTBEAT: Byte = 0x04
        const val MSG_TYPE_GLASSES_BATTERY: Byte = 0x05
        
        // Telemetry data size (same as BLE)
        const val TELEMETRY_DATA_SIZE = 20
        const val TIME_DATA_SIZE = 12

        /** Largest accepted message; anything else is a protocol violation. */
        const val MAX_MESSAGE_LENGTH = 1024

        /** Read timeout per client; a silent client is treated as disconnected. */
        const val CLIENT_READ_TIMEOUT_MS = 30_000
        
        // Connection state constants
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_READY = 2
    }
    
    // Server state
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    
    private val isRunning = AtomicBoolean(false)

    // Recreated on every start(): stop() cancels this scope, and a cancelled
    // scope silently swallows every later launch, so a restarted server would
    // report Running while never accepting a single client.
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Connected clients
    private val connectedClients = ConcurrentHashMap<String, ClientConnection>()
    
    // State flows
    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()
    
    private val _connectedDeviceCount = MutableStateFlow(0)
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()
    
    // Glasses battery level (from connected glasses)
    private val _glassesBatteryLevel = MutableStateFlow(-1)
    val glassesBatteryLevel: StateFlow<Int> = _glassesBatteryLevel.asStateFlow()
    
    // Latency monitoring. AtomicInteger because updateTelemetry() can be called
    // from more than one coroutine and @Volatile does not make ++ atomic.
    @Volatile private var lastTelemetryUpdateMs: Long = 0
    private val telemetryUpdateCount = java.util.concurrent.atomic.AtomicInteger(0)
    
    /**
     * Server state sealed class
     */
    sealed class ServerState {
        object Stopped : ServerState()
        data class Starting(val port: Int) : ServerState()
        data class Running(val port: Int, val localAddress: String) : ServerState()
        data class Error(val message: String) : ServerState()
    }
    
    /**
     * Start the WiFi Gateway Server
     */
    fun start(port: Int = DEFAULT_PORT): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Server already running")
            return true
        }
        
        try {
            _serverState.value = ServerState.Starting(port)

            // Fresh scope for this run (see the field declaration).
            if (!scope.isActive) {
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            }

            // Create server socket
            serverSocket = ServerSocket(port)
            serverSocket?.reuseAddress = true
            
            val localAddress = getLocalIpAddress() ?: "unknown"
            Log.i(TAG, "WiFi Gateway Server started on $localAddress:$port")
            
            isRunning.set(true)
            _serverState.value = ServerState.Running(port, localAddress)
            
            // Start accepting connections
            scope.launch {
                acceptConnections()
            }
            
            // Register NSD service for discovery
            registerNsdService(port)
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            _serverState.value = ServerState.Error(e.message ?: "Unknown error")
            return false
        }
    }
    
    /**
     * Stop the server
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }
        
        Log.i(TAG, "Stopping WiFi Gateway Server...")
        
        // Unregister NSD
        unregisterNsdService()
        
        // Close all client connections
        connectedClients.values.forEach { it.close() }
        connectedClients.clear()
        _connectedDeviceCount.value = 0
        
        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        
        scope.cancel()
        _serverState.value = ServerState.Stopped
        
        Log.i(TAG, "WiFi Gateway Server stopped")
    }
    
    /**
     * Accept incoming connections
     */
    private suspend fun acceptConnections() {
        while (isRunning.get()) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                
                val clientId = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                Log.i(TAG, "New client connected: $clientId")
                
                val connection = ClientConnection(clientSocket, clientId)
                connectedClients[clientId] = connection
                _connectedDeviceCount.value = connectedClients.size
                
                // Handle client in separate coroutine
                scope.launch {
                    handleClient(connection)
                }
                
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting connection", e)
                }
            }
        }
    }
    
    /**
     * Handle a connected client
     */
    private suspend fun handleClient(connection: ClientConnection) {
        try {
            // Enforce liveness: without a read timeout, a client that stops
            // sending but keeps the socket open pins a coroutine and a file
            // descriptor forever, and keeps receiving broadcasts.
            connection.socket.soTimeout = CLIENT_READ_TIMEOUT_MS
            val input = DataInputStream(connection.socket.inputStream)

            while (isRunning.get() && connection.isConnected) {
                try {
                    // Read message length (4 bytes, big-endian)
                    val length = input.readInt()
                    if (length <= 0 || length > MAX_MESSAGE_LENGTH) {
                        // The payload bytes cannot be skipped reliably once the
                        // declared length is bogus, so the stream can never be
                        // resynchronised: `continue` would leave every later
                        // readInt() reading from the middle of a message.
                        Log.w(TAG, "Invalid message length: $length, closing connection ${connection.clientId}")
                        break
                    }

                    // Read message type
                    val type = input.readByte()
                    
                    // Read payload
                    val payload = ByteArray(length - 1)
                    input.readFully(payload)
                    
                    // Process message
                    processMessage(connection, type, payload)
                    
                } catch (e: java.io.EOFException) {
                    Log.i(TAG, "Client disconnected: ${connection.clientId}")
                    break
                } catch (e: java.net.SocketTimeoutException) {
                    Log.i(TAG, "Client timed out (no data for ${CLIENT_READ_TIMEOUT_MS}ms): ${connection.clientId}")
                    break
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client ${connection.clientId}", e)
        } finally {
            connection.close()
            connectedClients.remove(connection.clientId)
            _connectedDeviceCount.value = connectedClients.size
            Log.i(TAG, "Client removed: ${connection.clientId}, remaining: ${connectedClients.size}")
        }
    }
    
    /**
     * Process incoming message from client
     */
    private fun processMessage(connection: ClientConnection, type: Byte, payload: ByteArray) {
        when (type) {
            MSG_TYPE_HEARTBEAT -> {
                // Client heartbeat - respond immediately
                sendHeartbeat(connection)
            }
            MSG_TYPE_GLASSES_BATTERY -> {
                // Glasses battery level update
                if (payload.isNotEmpty()) {
                    val batteryLevel = payload[0].toInt() and 0xFF
                    _glassesBatteryLevel.value = batteryLevel
                    Log.d(TAG, "Glasses battery: $batteryLevel%")
                }
            }
            MSG_TYPE_COMMAND -> {
                // Command from glasses (future use)
                Log.d(TAG, "Command received: ${payload.contentToString()}")
            }
            else -> {
                Log.w(TAG, "Unknown message type: $type")
            }
        }
    }
    
    /**
     * Send telemetry to all connected clients
     */
    fun updateTelemetry(
        speedKmh: Double,
        scooterBattery: Int,
        tempC: Double,
        totalMileageM: Long,
        avgSpeedKmh: Double,
        remainingKm: Double,
        connectionState: Int,
        tripMeters: Int,
        tripSeconds: Int
    ) {
        val now = System.currentTimeMillis()
        val updates = telemetryUpdateCount.incrementAndGet()

        // Log stats every 5 seconds
        if (now - lastTelemetryUpdateMs >= 5000L && lastTelemetryUpdateMs > 0) {
            val rate = updates * 1000.0 / (now - lastTelemetryUpdateMs)
            Log.d(TAG, "WIFI STATS: ${String.format("%.1f", rate)} updates/sec, clients: ${connectedClients.size}")
            telemetryUpdateCount.set(0)
            lastTelemetryUpdateMs = now
        }
        if (lastTelemetryUpdateMs == 0L) lastTelemetryUpdateMs = now
        
        // Build telemetry packet (same format as BLE)
        val buffer = ByteBuffer.allocate(TELEMETRY_DATA_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.putShort((speedKmh * 100).toInt().toShort())           // 0-1
        buffer.put(scooterBattery.coerceIn(0, 100).toByte())          // 2
        buffer.putShort((tempC * 10).toInt().toShort())               // 3-4
        buffer.putInt(totalMileageM.toInt())                          // 5-8
        buffer.putShort((avgSpeedKmh * 100).toInt().toShort())        // 9-10
        buffer.putShort((remainingKm * 10).toInt().toShort())         // 11-12
        buffer.put(connectionState.toByte())                          // 13
        buffer.putShort(tripMeters.toShort())                         // 14-15
        buffer.putShort(tripSeconds.toShort())                        // 16-17
        
        val telemetryData = buffer.array()

        // Real CRC over bytes 0..17, matching M365HudGattProfile.CRC16_SPEC.
        // A hardcoded 0 meant a client that validates the checksum rejected
        // every frame, and one that does not silently accepted corrupt frames.
        buffer.putShort(18, calculateCrc16(telemetryData, M365HudGattProfile.TELEMETRY_CRC_COVERED_BYTES))

        // Send to all connected clients
        sendToAll(MSG_TYPE_TELEMETRY, telemetryData)
    }

    /**
     * CRC-16/MODBUS over the first [length] bytes.
     * See [M365HudGattProfile.CRC16_SPEC] for the authoritative parameters.
     */
    private fun calculateCrc16(data: ByteArray, length: Int): Short {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return (crc and 0xFFFF).toShort()
    }
    
    /**
     * Send time data to all connected clients
     */
    fun updateTimeData(hour: Int, minute: Int, second: Int, phoneBattery: Int) {
        val buffer = ByteBuffer.allocate(TIME_DATA_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(hour.toByte())
        buffer.put(minute.toByte())
        buffer.put(second.toByte())
        buffer.put(phoneBattery.coerceIn(0, 100).toByte())
        // Padding
        buffer.putLong(0)
        
        sendToAll(MSG_TYPE_TIME, buffer.array())
    }
    
    /**
     * Send heartbeat to a specific client
     */
    private fun sendHeartbeat(connection: ClientConnection) {
        val timestamp = System.currentTimeMillis()
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(timestamp)
        sendTo(connection, MSG_TYPE_HEARTBEAT, buffer.array())
    }
    
    /**
     * Send message to all connected clients
     */
    private fun sendToAll(type: Byte, payload: ByteArray) {
        connectedClients.values.forEach { connection ->
            sendTo(connection, type, payload)
        }
    }
    
    /**
     * Send message to a specific client
     */
    private fun sendTo(connection: ClientConnection, type: Byte, payload: ByteArray) {
        if (!connection.isConnected) return
        
        try {
            val output = connection.outputStream ?: return
            
            synchronized(output) {
                // Write length (type + payload)
                output.writeInt(1 + payload.size)
                // Write type
                output.writeByte(type.toInt())
                // Write payload
                output.write(payload)
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending to ${connection.clientId}", e)
            connection.close()
        }
    }
    
    /**
     * Register NSD (Network Service Discovery) for mDNS
     */
    private fun registerNsdService(port: Int) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = SERVICE_NAME
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "NSD service registered: ${info.serviceName}")
                }
                
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD registration failed: $errorCode")
                }
                
                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "NSD service unregistered")
                }
                
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD unregistration failed: $errorCode")
                }
            }
            
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }
    
    /**
     * Unregister NSD service
     */
    private fun unregisterNsdService() {
        try {
            registrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering NSD service", e)
        }
        nsdManager = null
        registrationListener = null
    }
    
    /**
     * Get local IP address
     */
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return null
    }
    
    /**
     * Check if any client is connected
     */
    fun isDeviceConnected(): Boolean = connectedClients.isNotEmpty()
    
    /**
     * Get connected device count
     */
    fun getConnectedDeviceCount(): Int = connectedClients.size
    
    /**
     * Get glasses battery level
     */
    fun getGlassesBatteryLevel(): Int = _glassesBatteryLevel.value
    
    /**
     * Client connection holder
     */
    inner class ClientConnection(
        val socket: Socket,
        val clientId: String
    ) {
        var isConnected: Boolean = true
            private set
        
        val outputStream: DataOutputStream? by lazy {
            try {
                DataOutputStream(socket.getOutputStream())
            } catch (e: Exception) {
                null
            }
        }
        
        fun close() {
            isConnected = false
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
