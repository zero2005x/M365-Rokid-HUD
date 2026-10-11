package com.m365bleapp.gateway

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
}
