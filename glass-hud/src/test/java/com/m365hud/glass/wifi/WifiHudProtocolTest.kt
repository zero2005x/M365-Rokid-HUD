package com.m365hud.glass.wifi

import com.m365hud.glass.DisplayField
import com.m365hud.glass.DisplayPrefs
import com.m365hud.glass.TimeData
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class WifiHudProtocolTest {
    // Synthetic phone-format packet, CRC-16/MODBUS independently calculated.
    private fun telemetryBytes() = "e20955ceff005ed0b2409c7b000250c360ea9abb".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun frame(type: Int, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(payload.size + 1)
                writeByte(type)
                write(payload)
            }
        }.toByteArray()

    @Test fun `phone telemetry uses CRC and unsigned fields`() {
        val result = WifiHudProtocol.telemetry(telemetryBytes())!!
        assertTrue(result.isValid)
        assertEquals(25.3f, result.speedKmh, 0.001f)
        assertEquals(-5f, result.temperatureC, 0.001f)
        assertEquals(85, result.scooterBattery)
        assertEquals(3000000000L, result.totalMileageM)
        assertEquals(400f, result.avgSpeedKmh, 0.001f)
        assertEquals(50000, result.tripMeters)
        assertEquals(60000, result.tripSeconds)
    }

    @Test fun `corruption and truncated telemetry are rejected`() {
        val bytes = telemetryBytes()
        for (length in 0 until bytes.size) assertNull(WifiHudProtocol.telemetry(bytes.copyOf(length)))
        bytes[4] = (bytes[4].toInt() xor 1).toByte()
        assertNull(WifiHudProtocol.telemetry(bytes))
    }

    @Test fun `fragmented TCP reads retain message boundaries`() {
        val prefs = byteArrayOf(1, 1, 0, 0, 0, 120, 0)
        val bytes = frame(1, telemetryBytes()) + frame(6, prefs) + frame(2, byteArrayOf(12, 34, 56, 90))
        val fragmented = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        val input = DataInputStream(fragmented)
        val telemetry = WifiHudProtocol.readFrame(input)
        assertEquals(1.toByte(), telemetry.type)
        assertNotNull(WifiHudProtocol.telemetry(telemetry.payload))
        val settings = WifiHudProtocol.readFrame(input)
        assertEquals(6.toByte(), settings.type)
        assertEquals(DisplayPrefs(DisplayField.SPEED, 120), DisplayPrefs.fromBytes(settings.payload))
        val time = WifiHudProtocol.readFrame(input)
        assertEquals(2.toByte(), time.type)
        assertEquals(TimeData(12, 34, 56, 90), TimeData.fromBytes(time.payload))
        assertEquals(-1, input.read())
    }

    @Test fun `invalid lengths fail instead of desynchronizing stream`() {
        for (length in listOf(-1, 0, 1025, Int.MAX_VALUE)) {
            val bytes = ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(length) }.toByteArray()
            assertThrows(IOException::class.java) { WifiHudProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes))) }
        }
    }

    @Test fun `partial payload fails with EOF`() {
        val bytes = frame(1, telemetryBytes()).dropLast(1).toByteArray()
        assertThrows(EOFException::class.java) { WifiHudProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes))) }
    }
}
