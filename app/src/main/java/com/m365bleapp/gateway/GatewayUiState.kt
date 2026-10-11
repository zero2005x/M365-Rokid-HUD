package com.m365bleapp.gateway

import com.m365bleapp.gateway.wifi.WifiGatewayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Observed gateway state. Screens collect this instead of polling the service. */
object GatewayUiState {
    data class Snapshot(
        val bleRunning: Boolean = false,
        val wifiRunning: Boolean = false,
        val glassesConnected: Boolean = false,
        val compatibility: String = "legacy",
        val connections: Map<String, String> = emptyMap(),
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun refresh() {
        _state.update { it.copy(
            bleRunning = GatewayService.isRunning(),
            wifiRunning = WifiGatewayService.isRunning(),
            glassesConnected = GatewayService.isGlassesConnected() || WifiGatewayService.getConnectedDeviceCount() > 0,
        ) }
    }

    fun publishConnections(connections: Map<String, String>) {
        val summary = when {
            connections.values.any { it == "incompatible" } -> "incompatible"
            connections.values.any { it == "negotiated_v2" } -> "negotiated_v2"
            else -> "legacy"
        }
        _state.update { it.copy(connections = connections.toMap(), compatibility = summary) }
    }
}
