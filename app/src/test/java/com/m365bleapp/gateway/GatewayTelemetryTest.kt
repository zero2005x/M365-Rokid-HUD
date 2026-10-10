package com.m365bleapp.gateway

import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.repository.MotorInfo
import io.github.zero2005x.pev.core.gateway.GatewayV1Frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayTelemetryTest {
    @Test
    fun reverseSpeedSurvivesPhoneToGlassesWireRoundTrip() {
        val info = MotorInfo(speed = -2.5, battery = 50, temp = 20.0, mileage = 1.0)
        val frame = info.toGatewayFrame(M365HudGattProfile.STATE_READY)
        assertEquals(-2.5, GatewayV1Frame.fromBytes(frame.toBytes())!!.speedKmh, 0.01)
    }

    @Test
    fun motorInfoKeepsLegacyUnitsAndWireFields() {
        val info = MotorInfo(
            speed = 23.45, battery = 76, temp = 31.2, mileage = 123.456,
            avgSpeed = 17.89, tripMeters = 4567, tripSeconds = 321, remainingKm = 12.3,
        )
        val expected = GatewayV1Frame(
            speedKmh = 23.45, batteryPercent = 76, temperatureC = 31.2,
            totalDistanceMeters = 123456L, avgSpeedKmh = 17.89, remainingRangeKm = 12.3,
            connectionState = M365HudGattProfile.STATE_READY, tripMeters = 4567, tripSeconds = 321,
        )
        val actual = info.toGatewayFrame(M365HudGattProfile.STATE_READY)
        assertEquals(expected, actual)
        assertArrayEquals(expected.toBytes(), actual.toBytes())
    }

    @Test
    fun missingMotorInfoPreservesHeartbeatConnectionState() {
        val expected = GatewayV1Frame(0.0, 0, 0.0, 0L, 0.0, 0.0,
            M365HudGattProfile.STATE_CONNECTING, 0, 0)
        assertArrayEquals(expected.toBytes(), null.toGatewayFrame(M365HudGattProfile.STATE_CONNECTING).toBytes())
        assertEquals(M365HudGattProfile.STATE_DISCONNECTED,
            null.toGatewayFrame(M365HudGattProfile.STATE_DISCONNECTED).connectionState)
    }

    @Test
    fun repositoryStatesKeepTheirLegacyGatewayMeaning() {
        assertEquals(M365HudGattProfile.STATE_DISCONNECTED, ConnectionState.Disconnected.toGatewayState())
        assertEquals(M365HudGattProfile.STATE_CONNECTING, ConnectionState.Connecting.toGatewayState())
        assertEquals(M365HudGattProfile.STATE_CONNECTING, ConnectionState.Handshaking("Pairing").toGatewayState())
        assertEquals(M365HudGattProfile.STATE_READY, ConnectionState.Ready.toGatewayState())
        assertEquals(M365HudGattProfile.STATE_DISCONNECTED, ConnectionState.Error("Lost").toGatewayState())
        assertEquals(M365HudGattProfile.STATE_DISCONNECTED, null.toGatewayState())
    }
}
