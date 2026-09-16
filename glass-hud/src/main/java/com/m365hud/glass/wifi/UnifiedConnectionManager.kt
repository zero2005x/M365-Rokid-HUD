package com.m365hud.glass.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.m365hud.glass.BleClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Service-owned BLE/WiFi lifecycle. BLE remains available while WiFi discovers
 * the phone, and only a connected WiFi socket takes priority in the HUD.
 * CXR-M is a phone-side SDK and is deliberately not started on the glasses.
 */
class UnifiedConnectionManager(private val context: Context) {
    private var scope: CoroutineScope? = null
    private var ble: BleClient? = null
    private var wifi: WifiGatewayClient? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val bleState = MutableStateFlow(HudConnectionSnapshot())
    private val wifiState = MutableStateFlow(HudConnectionSnapshot(transport = "WiFi"))
    private val _hudState = MutableStateFlow(HudConnectionSnapshot())
    val hudState: StateFlow<HudConnectionSnapshot> = _hudState.asStateFlow()
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun start() {
        if (scope != null) return
        val run = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = run
        val b = BleClient(context)
        val w = WifiGatewayClient(context)
        ble = b
        wifi = w
        run.launch { HudConnectionRouter(bleState, wifiState, run).state.collect { _hudState.value = it } }
        run.launch { b.connectionState.collect { value -> bleState.update { it.copy(connection = value) } } }
        run.launch { b.telemetry.collect { value -> bleState.update { it.copy(telemetry = value) } } }
        run.launch { b.timeData.collect { value -> bleState.update { it.copy(time = value) } } }
        run.launch { b.displayPrefs.collect { value -> bleState.update { it.copy(preferences = value) } } }
        run.launch { b.isTelemetryFresh.collect { value -> bleState.update { it.copy(fresh = value) } } }
        run.launch { b.signalStrength.collect { value -> bleState.update { it.copy(signal = value) } } }
        run.launch {
            w.connectionState.collect { value ->
                val state = when (value) {
                    WifiGatewayClient.ConnectionState.Disconnected -> BleClient.ConnectionState.Disconnected
                    WifiGatewayClient.ConnectionState.Discovering -> BleClient.ConnectionState.Scanning
                    is WifiGatewayClient.ConnectionState.Connecting -> BleClient.ConnectionState.Connecting
                    is WifiGatewayClient.ConnectionState.Connected -> BleClient.ConnectionState.Connected
                    is WifiGatewayClient.ConnectionState.Error -> BleClient.ConnectionState.Error(value.message)
                }
                wifiState.update { it.copy(connection = state) }
            }
        }
        run.launch { w.telemetry.collect { value -> wifiState.update { it.copy(telemetry = value) } } }
        run.launch { w.timeData.collect { value -> wifiState.update { it.copy(time = value) } } }
        run.launch { w.displayPrefs.collect { value -> wifiState.update { it.copy(preferences = value) } } }
        run.launch { w.isTelemetryFresh.collect { value -> wifiState.update { it.copy(fresh = value) } } }
        run.launch {
            w.signalStrength.collect { value ->
                val signal = when (value) {
                    WifiGatewayClient.SignalStrength.Excellent, WifiGatewayClient.SignalStrength.Good -> BleClient.SignalStrength.Good
                    WifiGatewayClient.SignalStrength.Fair -> BleClient.SignalStrength.Weak
                    WifiGatewayClient.SignalStrength.Poor -> BleClient.SignalStrength.Poor
                }
                wifiState.update { it.copy(signal = signal) }
            }
        }
        val networks = mutableSetOf<Network>()
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                run.launch {
                    if (networks.add(network)) w.startDiscovery()
                }
            }
            override fun onLost(network: Network) {
                run.launch {
                    networks.remove(network)
                    if (networks.isEmpty()) w.disconnect()
                }
            }
        }
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback)
            callback = networkCallback
        } catch (e: RuntimeException) {
            Log.w("UnifiedConnection", "WiFi monitoring unavailable; keeping BLE active", e)
        }
        b.startScan()
        // BLE has a finite scan window. Keep retrying while the phone is absent,
        // without restarting a live BLE link or interrupting WiFi discovery.
        run.launch {
            while (isActive) {
                delay(5000)
                if (networks.isNotEmpty()) w.startDiscovery()
                val state = b.connectionState.value
                if (w.connectionState.value !is WifiGatewayClient.ConnectionState.Connected &&
                    (state is BleClient.ConnectionState.Error || state is BleClient.ConnectionState.Disconnected)) {
                    b.startScan()
                }
            }
        }
    }

    fun stop() {
        callback?.let { connectivity.unregisterNetworkCallback(it) }
        callback = null
        scope?.cancel()
        scope = null
        ble?.close()
        wifi?.disconnect()
        ble = null
        wifi = null
        bleState.value = HudConnectionSnapshot()
        wifiState.value = HudConnectionSnapshot(transport = "WiFi")
        _hudState.value = HudConnectionSnapshot()
    }

    fun reconnect() {
        stop()
        start()
    }
}
