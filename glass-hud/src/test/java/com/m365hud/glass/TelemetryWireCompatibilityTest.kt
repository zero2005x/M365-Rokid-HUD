package com.m365hud.glass

import org.junit.Assert.*
import org.junit.Test

class TelemetryWireCompatibilityTest {
    // Fixed legacy producer vector: Double 1.15 truncates to 114 centi-km/h;
    // trip 65536/65537 wraps to 0/1. This is a software wire contract, not a capture.
    private fun legacyFrame() = byteArrayOf(
        0x72, 0x00, 0x33, 0xcb.toByte(), 0xff.toByte(), 0xe7.toByte(), 0x1d, 0x06, 0x00,
        0xd2.toByte(), 0x04, 0x80.toByte(), 0x00, 0x02, 0x00, 0x00, 0x01, 0x00,
        0xd1.toByte(), 0x86.toByte(),
    )

    @Test fun legacyProducerVectorKeepsSignedTemperatureAndUnsignedCounters() {
        val parsed = TelemetryData.fromBytes(legacyFrame())
        assertTrue(parsed.isValid)
        assertEquals(1.14f, parsed.speedKmh, 0f)
        assertEquals(51, parsed.scooterBattery)
        assertEquals(-5.3f, parsed.temperatureC, 0f)
        assertEquals(400871L, parsed.totalMileageM)
        assertEquals(12.34f, parsed.avgSpeedKmh, 0f)
        assertEquals(12.8f, parsed.remainingRangeKm, 0f)
        assertEquals(2, parsed.connectionState)
        assertEquals(0, parsed.tripMeters)
        assertEquals(1, parsed.tripSeconds)
    }

    @Test fun malformedFramesDoNotExposeMeasurements() {
        val valid = legacyFrame()
        for (length in 0 until valid.size) {
            assertEquals(TelemetryData(), TelemetryData.fromBytes(valid.copyOf(length)))
        }
        for (index in valid.indices) {
            val corrupt = valid.copyOf()
            corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
            assertEquals(TelemetryData(), TelemetryData.fromBytes(corrupt))
        }
        // Preserve the legacy parser's prefix behavior; transport envelopes bound their payload.
        assertEquals(TelemetryData.fromBytes(valid), TelemetryData.fromBytes(valid + byteArrayOf(0)))
    }
}
