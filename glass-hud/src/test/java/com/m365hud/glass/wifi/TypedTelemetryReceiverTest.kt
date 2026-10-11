package com.m365hud.glass.wifi

import io.github.zero2005x.pev.core.gateway.*
import io.github.zero2005x.pev.core.telemetry.*
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class TypedTelemetryReceiverTest {
    private fun packet(sequence: Long, observed: Long, generated: Long, generation: Long = 1): ByteArray {
        val bytes = GatewayV2Frame(1, 2, sequence, generated, mapOf(
            GatewayField.SPEED_KMH to Reading.valid(12.0, observed, Evidence.SYNTHETIC, "test"),
            GatewayField.SOC_PERCENT to Reading.valid(80.0, observed, Evidence.SYNTHETIC, "test")
        )).toBytes()
        return ByteBuffer.allocate(8 + bytes.size).putLong(generation).put(bytes).array()
    }
    @Test fun `stale packet clears previous speed and battery without using glasses clock`() {
        val receiver = TypedTelemetryReceiver()
        assertEquals(12f, receiver.applyTyped(packet(1, 1000, 1100))!!.speedKmh, 0f)
        val stale = receiver.applyTyped(packet(2, 1000, 20000))!!
        assertTrue(stale.speedKmh.isNaN())
        assertEquals(-1, stale.scooterBattery)
        assertFalse(stale.fieldValid(GatewayField.SPEED_KMH))
        assertTrue(stale.temperatureC.isNaN())
    }
    @Test fun `duplicate and older sequences are dropped and new phone generation resets sequence`() {
        val receiver = TypedTelemetryReceiver()
        assertNotNull(receiver.applyTyped(packet(5, 1000, 1100)))
        assertNull(receiver.applyTyped(packet(5, 1000, 1100)))
        assertNull(receiver.applyTyped(packet(4, 1000, 1100)))
        assertNotNull(receiver.applyTyped(packet(1, 1000, 1100, 2)))
        assertNull(receiver.applyTyped(packet(6, 1000, 1100, 1)))
        receiver.reset()
        assertNotNull(receiver.applyTyped(packet(1, 1000, 1100)))
    }
}
