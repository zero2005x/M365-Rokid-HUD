package com.m365hud.glass.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.m365hud.glass.DisplayPrefs
import com.m365hud.glass.TelemetryData
import com.m365hud.glass.TimeData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Service-owned NSD discovery and a single reconnecting TCP session. */
class WifiGatewayClient(private val context: Context) {
    companion object {
        private const val TAG = "WifiGatewayClient"
        const val SERVICE_TYPE = "_m365hud._tcp."
        const val DEFAULT_PORT = 8365
        const val MSG_TYPE_TELEMETRY: Byte = 0x01
        const val MSG_TYPE_TIME: Byte = 0x02
        const val MSG_TYPE_COMMAND: Byte = 0x03
        const val MSG_TYPE_HEARTBEAT: Byte = 0x04
        const val MSG_TYPE_GLASSES_BATTERY: Byte = 0x05
        const val MSG_TYPE_DISPLAY_PREFS: Byte = 0x06
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 10000
        const val HEARTBEAT_INTERVAL_MS = 3000L
        const val MAX_RECONNECT_DELAY_MS = 30000L
        const val TELEMETRY_STALE_MS = 3000L
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Discovering : ConnectionState()
        data class Connecting(val address: String) : ConnectionState()
        data class Connected(val address: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    enum class SignalStrength { Excellent, Good, Fair, Poor }

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private val generation = AtomicLong()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var lastTelemetryMs = 0L
    @Volatile private var latencyMs = Long.MAX_VALUE
    private var discovery: NsdManager.DiscoveryListener? = null
    private var nsd: NsdManager? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()
    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry = _telemetry.asStateFlow()
    private val _timeData = MutableStateFlow(TimeData())
    val timeData = _timeData.asStateFlow()
    private val _displayPrefs = MutableStateFlow(DisplayPrefs())
    val displayPrefs = _displayPrefs.asStateFlow()
    private val _signalStrength = MutableStateFlow(SignalStrength.Poor)
    val signalStrength = _signalStrength.asStateFlow()
    private val _isTelemetryFresh = MutableStateFlow(false)
    val isTelemetryFresh = _isTelemetryFresh.asStateFlow()

    // NOSONAR kotlin:S3776 — the NSD discovery listener is one object whose
    // nested callbacks all share the same epoch/generation guard; pulling them
    // apart would scatter that guard and the resolve→connect hand-off.
    @Synchronized
    fun startDiscovery() { // NOSONAR
        if (discovery != null || connectionJob?.isActive == true) return
        val epoch = generation.get()
        _connectionState.value = ConnectionState.Discovering
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit // The socket detects actual loss.
            override fun onStopDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "NSD stop failed: $code")
            }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                synchronized(this@WifiGatewayClient) {
                    if (generation.get() != epoch) return
                    discovery = null
                    _connectionState.value = ConnectionState.Error("Discovery failed: $code")
                }
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (generation.get() != epoch || !service.serviceName.contains("M365-HUD")) return
                @Suppress("DEPRECATION")
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                        Log.w(TAG, "NSD resolve failed: $code")
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = if (Build.VERSION.SDK_INT >= 34) {
                            info.hostAddresses.firstOrNull()?.hostAddress
                        } else {
                            @Suppress("DEPRECATION")
                            info.host?.hostAddress
                        }
                        synchronized(this@WifiGatewayClient) {
                            // A delayed NSD callback from a stopped run must not reconnect it.
                            if (generation.get() == epoch && host != null) connect(host, info.port)
                        }
                    }
                })
            }
        }
        discovery = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            discovery = null
            _connectionState.value = ConnectionState.Error(e.message ?: "Discovery failed")
        }
    }

    // NOSONAR kotlin:S3776 — a single TCP reconnect state machine: connect,
    // concurrent heartbeat + freshness + read loops inside one coroutineScope,
    // exponential backoff, and epoch-guarded socket teardown. The generation
    // checks are only correct while these stay in one lexical scope; decomposing
    // would move shared mutable state (socket/output) across the guard.
    @Synchronized
    fun connect(address: String, port: Int = DEFAULT_PORT) { // NOSONAR
        if (connectionJob?.isActive == true) return
        val epoch = generation.get()
        connectionJob = scope.launch {
            var retryMs = 1000L
            while (isActive && generation.get() == epoch) {
                val current = Socket()
                synchronized(this@WifiGatewayClient) {
                    if (generation.get() != epoch) {
                        current.close()
                        return@launch
                    }
                    socket = current // disconnect can interrupt even a pending connect.
                    _connectionState.value = ConnectionState.Connecting(address)
                }
                try {
                    current.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                    current.soTimeout = READ_TIMEOUT_MS
                    current.tcpNoDelay = true
                    ensureActive()
                    val writer = DataOutputStream(current.getOutputStream())
                    synchronized(this@WifiGatewayClient) {
                        if (generation.get() != epoch) return@launch
                        output = writer
                        _connectionState.value = ConnectionState.Connected(address)
                        _isTelemetryFresh.value = false
                        lastTelemetryMs = 0
                    }
                    retryMs = 1000L
                    coroutineScope {
                        val heartbeat = launch {
                            while (isActive) {
                                delay(HEARTBEAT_INTERVAL_MS)
                                send(writer, MSG_TYPE_HEARTBEAT,
                                    ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array())
                            }
                        }
                        val freshness = launch {
                            while (isActive) {
                                delay(500)
                                synchronized(this@WifiGatewayClient) {
                                    if (generation.get() == epoch) {
                                        _isTelemetryFresh.value = lastTelemetryMs != 0L &&
                                            SystemClock.elapsedRealtime() - lastTelemetryMs < TELEMETRY_STALE_MS
                                    }
                                }
                            }
                        }
                        // A failed heartbeat must interrupt a blocking socket read.
                        heartbeat.invokeOnCompletion { if (it != null) current.close() }
                        try {
                            val input = DataInputStream(current.getInputStream())
                            while (isActive) {
                                val frame = WifiHudProtocol.readFrame(input)
                                synchronized(this@WifiGatewayClient) {
                                    if (generation.get() == epoch) process(frame)
                                }
                            }
                        } finally {
                            heartbeat.cancel()
                            freshness.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    synchronized(this@WifiGatewayClient) {
                        if (generation.get() == epoch) {
                            _connectionState.value = ConnectionState.Error(e.message ?: "Connection lost")
                        }
                    }
                } finally {
                    current.close()
                    synchronized(this@WifiGatewayClient) {
                        if (generation.get() == epoch) {
                            output = null
                            socket = null
                            _isTelemetryFresh.value = false
                        }
                    }
                }
                // Read timeouts also close the session: a partial TCP frame cannot
                // safely be resumed at its length prefix after a timeout.
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
        }
    }

    private fun process(frame: WifiHudProtocol.Frame) {
        when (frame.type) {
            MSG_TYPE_TELEMETRY -> WifiHudProtocol.telemetry(frame.payload)?.let {
                _telemetry.value = it
                lastTelemetryMs = SystemClock.elapsedRealtime()
                _isTelemetryFresh.value = true
            }
            MSG_TYPE_TIME -> if (frame.payload.size >= 4) {
                _timeData.value = TimeData.fromBytes(frame.payload)
            }
            MSG_TYPE_DISPLAY_PREFS -> _displayPrefs.value = DisplayPrefs.fromBytes(frame.payload)
            MSG_TYPE_HEARTBEAT -> if (frame.payload.size >= 8) {
                latencyMs = (System.currentTimeMillis() - ByteBuffer.wrap(frame.payload).long).coerceAtLeast(0)
                _signalStrength.value = when {
                    latencyMs < 10 -> SignalStrength.Excellent
                    latencyMs < 30 -> SignalStrength.Good
                    latencyMs < 100 -> SignalStrength.Fair
                    else -> SignalStrength.Poor
                }
            }
        }
    }

    private fun send(writer: DataOutputStream, type: Byte, payload: ByteArray) {
        synchronized(writer) {
            writer.writeInt(payload.size + 1)
            writer.writeByte(type.toInt())
            writer.write(payload)
            writer.flush()
        }
    }

    fun sendGlassesBattery(level: Int) {
        val writer = output ?: return
        scope.launch {
            try { send(writer, MSG_TYPE_GLASSES_BATTERY, byteArrayOf(level.coerceIn(0, 100).toByte())) }
            catch (e: Exception) { Log.w(TAG, "Battery report failed", e) }
        }
    }

    @Synchronized
    fun disconnect() {
        generation.incrementAndGet()
        discovery?.let { listener ->
            try { nsd?.stopServiceDiscovery(listener) }
            catch (e: Exception) { Log.w(TAG, "NSD cleanup failed", e) }
        }
        discovery = null
        nsd = null
        scope.cancel()
        socket?.close()
        socket = null
        output = null
        connectionJob = null
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        _connectionState.value = ConnectionState.Disconnected
        _isTelemetryFresh.value = false
        _telemetry.value = TelemetryData()
        _timeData.value = TimeData()
        _displayPrefs.value = DisplayPrefs()
        _signalStrength.value = SignalStrength.Poor
    }

    fun isConnected(): Boolean = connectionState.value is ConnectionState.Connected
    fun getLatencyMs(): Long = latencyMs
}
