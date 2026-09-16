package com.m365bleapp.repository

import org.junit.Assert.*
import org.junit.Test

/** Synthetic regression fixtures for current behavior, not hardware evidence. */
class MotorInfoParserTest {
    // Pairwise-distinct fields make incorrect offsets visible. Odometer 100000
    // exceeds 16 bits; average speed 40000 exercises unsigned decoding.
    private fun packet(): ByteArray = byteArrayOf(
        1, 2, 3, 4, 5, 6, 7, 79,
        85, 0,                         // battery
        0xD4.toByte(), 0x62,            // speed: 25300
        0x40, 0x9C.toByte(),            // average speed: 40000
        0xA0.toByte(), 0x86.toByte(), 1, 0, // odometer: 100000
        0x34, 0x12, 0x78, 0x56,        // ignored trip fields
        0xCE.toByte(), 0xFF.toByte()    // temperature: -50
    )

    @Test
    fun `decodes production offsets and signedness`() {
        val result = MotorInfoParser.parse(packet())!!
        assertEquals(25.3, result.speed, 0.00001)
        assertEquals(40.0, result.avgSpeed, 0.00001)
        assertEquals(100.0, result.mileage, 0.00001)
        assertEquals(85, result.battery)
        assertEquals(-5.0, result.temp, 0.00001)
    }

    @Test
    fun `rejects every payload shorter than 22 bytes`() {
        for (length in 0 until 22) {
            assertNull("length=$length", MotorInfoParser.parse(packet().copyOf(length)))
        }
    }

    @Test
    fun `accepts missing temperature without reading past packet`() {
        for (length in 22..23) {
            val result = MotorInfoParser.parse(packet().copyOf(length))!!
            assertEquals(0.0, result.temp, 0.0)
            assertEquals(100.0, result.mileage, 0.0)
        }
    }

    @Test
    fun `preserves separately polled trip and range`() {
        val existing = MotorInfo(speed = 1.0, battery = 50, temp = 20.0, mileage = 2.0,
            tripSeconds = 600, tripMeters = 2500, remainingKm = 12.5)
        val result = MotorInfoParser.parse(packet(), existing)!!
        assertEquals(600, result.tripSeconds)
        assertEquals(2500, result.tripMeters)
        assertEquals(12.5, result.remainingKm, 0.0)
    }

    @Test
    fun `defaults trip and range before separate poll`() {
        val result = MotorInfoParser.parse(packet())!!
        assertEquals(0, result.tripSeconds)
        assertEquals(0, result.tripMeters)
        assertEquals(0.0, result.remainingKm, 0.0)
    }

    @Test
    fun `preserves alternate battery fallback including zero`() {
        for (battery in listOf(0, 101, 65535)) {
            val data = packet()
            data[8] = battery.toByte()
            data[9] = (battery ushr 8).toByte()
            assertEquals(79, MotorInfoParser.parse(data)!!.battery)
        }
        for (battery in listOf(1, 100)) {
            val data = packet()
            data[8] = battery.toByte()
            assertEquals(battery, MotorInfoParser.parse(data)!!.battery)
        }
    }

    @Test
    fun `speed remains signed and trailing bytes are ignored`() {
        val data = packet() + byteArrayOf(99, 98)
        data[10] = 0x18
        data[11] = 0xFC.toByte() // -1000
        assertEquals(-1.0, MotorInfoParser.parse(data)!!.speed, 0.0)
        assertEquals(-5.0, MotorInfoParser.parse(data)!!.temp, 0.0)
    }
}
