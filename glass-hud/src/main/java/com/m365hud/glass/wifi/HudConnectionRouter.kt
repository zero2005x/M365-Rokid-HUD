package com.m365hud.glass.wifi

import com.m365hud.glass.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

/** One coherent HUD snapshot; a transport switch also replays its cached preferences. */
data class HudConnectionSnapshot(
    val transport: String = "BLE",
    val connection: BleClient.ConnectionState = BleClient.ConnectionState.Disconnected,
    val telemetry: TelemetryData = TelemetryData(),
    val time: TimeData = TimeData(),
    val preferences: DisplayPrefs = DisplayPrefs(),
    val fresh: Boolean = false,
    val signal: BleClient.SignalStrength = BleClient.SignalStrength.Good
)

/** WiFi discovery alone must never replace an established BLE connection. */
internal class HudConnectionRouter(
    ble: StateFlow<HudConnectionSnapshot>,
    wifi: StateFlow<HudConnectionSnapshot>,
    scope: CoroutineScope
) {
    val state = combine(ble, wifi) { b, w ->
        val selected = when {
            w.connection is BleClient.ConnectionState.Connected -> w
            b.connection is BleClient.ConnectionState.Connected -> b
            b.connection is BleClient.ConnectionState.Scanning ||
                b.connection is BleClient.ConnectionState.Connecting -> b
            w.connection is BleClient.ConnectionState.Scanning ||
                w.connection is BleClient.ConnectionState.Connecting -> w
            else -> b
        }
        selected.copy(fresh = selected.fresh &&
            selected.connection is BleClient.ConnectionState.Connected)
    }.stateIn(scope, SharingStarted.Eagerly, HudConnectionSnapshot())
}
