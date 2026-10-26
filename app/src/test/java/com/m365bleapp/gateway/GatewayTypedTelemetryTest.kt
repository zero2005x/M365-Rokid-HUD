package com.m365bleapp.gateway

import com.m365bleapp.protocol.BegodeA2Session
import com.m365bleapp.protocol.ScooterModel
import com.m365bleapp.repository.MotorInfo
import com.m365bleapp.repository.MotorInfoParser
import io.github.zero2005x.pev.core.gateway.*
import io.github.zero2005x.pev.core.telemetry.*
import org.junit.Assert.*
import org.junit.Test

class GatewayTypedTelemetryTest {
    @Test fun `observation is parser receipt rather than send time`() {
        val info = MotorInfoParser.parse(ByteArray(32))!!
        val now = info.receivedAtMs!! + 1000
        val frame = GatewayV2Frame.fromBytesForRemoteDisplay(GatewayTypedTelemetry.m365OrNull(info, 2, 1, now)!!)!!
        assertEquals(info.receivedAtMs, frame[GatewayField.SPEED_KMH].observedAtMs)
        assertEquals(now, frame.generatedAtMs)
        assertEquals(FieldState.VALID, frame[GatewayField.TEMP_FRAME].state)
        assertEquals(0.0, frame[GatewayField.TOTAL_DISTANCE_M].value!!, 0.0)
    }

    @Test fun `primitive defaults and absent temperature never become valid zero`() {
        val info = MotorInfoParser.parse(ByteArray(22))!!
        val frame = GatewayV2Frame.fromBytesForRemoteDisplay(GatewayTypedTelemetry.m365OrNull(info, 2, 1, info.receivedAtMs!!)!!)!!
        assertEquals(FieldState.NOT_PROVIDED, frame[GatewayField.TEMP_FRAME].state)
        assertNull(frame[GatewayField.TEMP_FRAME].value)
        assertNull(frame[GatewayField.PACK_VOLTAGE].value)
        val empty = GatewayV2Frame.fromBytesForRemoteDisplay(GatewayTypedTelemetry.m365OrNull(MotorInfo(0.0, 0, 0.0, 0.0), 2, 2, 1000)!!)!!
        assertNull(empty[GatewayField.SPEED_KMH].value)
    }

    @Test fun `Begode A2 passive stream session feeds GatewayTypedTelemetry with VEHICLE_BEGODE and ages after 1s`() {
        val session = BegodeA2Session()
        assertFalse(session.isReady)

        // 9-octet ASCII device-info reply is captured without triggering Ready or binary frame parse
        val devInfo = session.onNotification("GW1511014".toByteArray(Charsets.US_ASCII), nowMs = 1_000L)
        assertTrue(devInfo is BegodeA2Session.Update.DeviceInfo)
        assertEquals("GW1511014", session.deviceInfoAscii)
        assertFalse(session.isReady)

        // Build a 24-octet Begode A2 0x00 live frame split across two notifications (20 + 4 bytes)
        val raw = ByteArray(24)
        raw[0] = 0x55
        raw[1] = 0xAA.toByte()
        // words[0] = 6120 (61.20 V)
        raw[2] = (6120 ushr 8).toByte()
        raw[3] = (6120 and 0xFF).toByte()
        // words[5] = -1600 (~31.82 C)
        val tempWord = (-1600) and 0xFFFF
        raw[12] = (tempWord ushr 8).toByte()
        raw[13] = (tempWord and 0xFF).toByte()
        // words[6] = 137 (packed mode word, never PWM)
        raw[14] = 0
        raw[15] = 137.toByte()
        raw[18] = 0x00
        raw[19] = 24
        for (i in 20..23) raw[i] = 0x5A

        assertEquals(
            BegodeA2Session.Update.AwaitingCompleteFrame,
            session.onNotification(raw.copyOfRange(0, 20), nowMs = 2_000L)
        )
        assertFalse(session.isReady)

        val readyUpdate = session.onNotification(raw.copyOfRange(20, 24), nowMs = 2_050L)
            as BegodeA2Session.Update.Telemetry
        assertTrue(readyUpdate.newlyReady)
        assertTrue(session.isReady)

        val info = readyUpdate.motorInfo
        val caps = ScooterModel.BEGODE_A2.capabilities
        assertNull(info.validSpeedOrNull(caps))
        assertNull(info.validBatteryOrNull(caps))
        assertEquals(61.20, info.validPackVoltageOrNull(caps)!!, 0.001)
        assertEquals(-1600 / 340.0 + 36.53, info.validTempOrNull(caps)!!, 0.001)

        val vehicleType = GatewayCodec.vehicleTypeForFamily(ScooterModel.BEGODE_A2.family)
        assertEquals(GatewayProtocol.VEHICLE_BEGODE_EUC, vehicleType)
        assertFalse(GatewayCodec.supportsLegacyV1(vehicleType))

        val v2Bytes = GatewayTypedTelemetry.fromMotorInfoOrNull(
            info = info,
            vehicleType = vehicleType,
            connectionState = GatewayProtocol.STATE_READY,
            sequence = 1L,
            nowMs = 2_200L,
            maxAgeMs = ScooterModel.BEGODE_A2.telemetryMaxAgeMs,
        )!!
        val decoded = GatewayV2Frame.fromBytesForRemoteDisplay(v2Bytes)!!
        assertEquals(GatewayProtocol.VEHICLE_BEGODE_EUC, decoded.vehicleType)
        assertEquals(FieldState.VALID, decoded[GatewayField.PACK_VOLTAGE].state)
        assertEquals(61.20, decoded[GatewayField.PACK_VOLTAGE].value!!, 0.001)
        assertEquals(FieldState.VALID, decoded[GatewayField.TEMP_IMU].state)
        assertEquals(FieldState.NOT_PROVIDED, decoded[GatewayField.SPEED_KMH].state)
        assertEquals(FieldState.NOT_PROVIDED, decoded[GatewayField.SOC_PERCENT].state)

        // Stream stall > 1000 ms transitions readings to STALE
        val staleInfo = session.ageIfStale(nowMs = 3_100L)!!
        assertNull(staleInfo.validPackVoltageOrNull(caps))
        assertNull(staleInfo.validTempOrNull(caps))
        assertEquals(FieldState.STALE, staleInfo.typedFields[FieldId.PACK_VOLTAGE]?.state)
    }
}
