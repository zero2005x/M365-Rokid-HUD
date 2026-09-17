package com.m365hud.glass.wifi

import com.m365hud.glass.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class HudConnectionRouterTest {
    private fun withRouter(test: suspend (MutableStateFlow<HudConnectionSnapshot>, MutableStateFlow<HudConnectionSnapshot>, HudConnectionRouter) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ble = MutableStateFlow(HudConnectionSnapshot(
            connection = BleClient.ConnectionState.Connected,
            telemetry = TelemetryData(speedKmh = 10f, isValid = true),
            preferences = DisplayPrefs(DisplayField.SPEED, 100), fresh = true))
        val wifi = MutableStateFlow(HudConnectionSnapshot(
            transport = "WiFi", connection = BleClient.ConnectionState.Scanning))
        val router = HudConnectionRouter(ble, wifi, scope)
        try { yield(); test(ble, wifi, router) } finally { scope.cancel() }
    }

    @Test fun `WiFi discovery does not interrupt BLE`() = withRouter { _, wifi, router ->
        assertEquals("BLE", router.state.value.transport)
        wifi.value = wifi.value.copy(connection = BleClient.ConnectionState.Connecting)
        yield()
        assertEquals(10f, router.state.value.telemetry.speedKmh, 0f)
        assertTrue(router.state.value.fresh)
    }

    @Test fun `WiFi selection replays already received settings and time`() = withRouter { _, wifi, router ->
        val prefs = DisplayPrefs(DisplayField.TIME, 140)
        wifi.value = wifi.value.copy(preferences = prefs, time = TimeData(1, 2, 3, 80))
        yield()
        wifi.value = wifi.value.copy(connection = BleClient.ConnectionState.Connected)
        yield()
        assertEquals("WiFi", router.state.value.transport)
        assertEquals(prefs, router.state.value.preferences)
        assertEquals(TimeData(1, 2, 3, 80), router.state.value.time)
        assertFalse(router.state.value.fresh)
    }

    @Test fun `only selected transport updates the HUD`() = withRouter { ble, wifi, router ->
        wifi.value = wifi.value.copy(connection = BleClient.ConnectionState.Connected,
            telemetry = TelemetryData(speedKmh = 25f, isValid = true), fresh = true,
            signal = BleClient.SignalStrength.Weak)
        yield()
        ble.value = ble.value.copy(telemetry = TelemetryData(speedKmh = 99f))
        yield()
        assertEquals(25f, router.state.value.telemetry.speedKmh, 0f)
        assertEquals(BleClient.SignalStrength.Weak, router.state.value.signal)
        wifi.value = wifi.value.copy(fresh = false)
        yield()
        assertFalse(router.state.value.fresh)
    }

    @Test fun `WiFi loss restores complete cached BLE snapshot`() = withRouter { ble, wifi, router ->
        wifi.value = wifi.value.copy(connection = BleClient.ConnectionState.Connected, fresh = true)
        yield()
        wifi.value = wifi.value.copy(connection = BleClient.ConnectionState.Error("socket closed"))
        yield()
        assertEquals(ble.value, router.state.value)
    }

    @Test fun `disconnected transports never show fresh telemetry`() = withRouter { ble, wifi, router ->
        ble.value = ble.value.copy(connection = BleClient.ConnectionState.Disconnected)
        wifi.value = wifi.value.copy(connection = BleClient.ConnectionState.Disconnected, fresh = true)
        yield()
        assertFalse(router.state.value.fresh)
        assertEquals(BleClient.ConnectionState.Disconnected, router.state.value.connection)
    }
}
