package com.m365bleapp.repository

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [MotorInfoParser].
 *
 * Two kinds of fixture, deliberately:
 *
 * * `packet()` is synthetic and keeps every field pairwise distinct, so a wrong
 *   offset shows up as a wrong value rather than as a coincidence.
 * * the `hex(...)` payloads are **real 32-byte 0xB0 replies captured from a
 *   Xiaomi M365** on 2026-09-20. They are what stops this file from agreeing
 *   with the parser's own assumptions: an earlier revision asserted that speed
 *   was signed, which passed here and put negative speeds on the HUD.
 */
class MotorInfoParserTest {

    private fun hex(s: String) =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Pairwise-distinct fields make incorrect offsets visible. Odometer 100000
    // exceeds 16 bits; average speed 40000 exercises unsigned decoding.
    private fun packet(): ByteArray = byteArrayOf(
        1, 2, 3, 4, 5, 6, 7, 79,
        85, 0,                         // battery
        0xD4.toByte(), 0x62,            // speed: 25300 -> 25.3 km/h
        0x40, 0x9C.toByte(),            // average speed: 40000
        0xA0.toByte(), 0x86.toByte(), 1, 0, // odometer: 100000
        0x34, 0x12, 0x78, 0x56,        // ignored trip fields
        0xCE.toByte(), 0xFF.toByte()    // temperature: -50 -> -5.0 C
    )

    /** Real capture, wheel stationary: battery 43, speed 0, odo 400.871 km, 45.0 C. */
    private val capturedAtRest =
        "00000000000800002b000000c447e71d06004a00a701c2010000000000000000"

    /** Real capture, wheel turning: battery 51, speed 0.15 km/h, odo 400.144 km, 31.0 C. */
    private val capturedMoving =
        "0000000000080000330096000000101b06000000660036010000000000000000"

    /** The same real reply with B5 replaced by the ESC's no-estimate sentinel. */
    private fun withRawSpeed(raw: Int): ByteArray {
        val data = hex(capturedMoving)
        data[10] = raw.toByte()
        data[11] = (raw ushr 8).toByte()
        return data
    }

    // ------------------------------------------------------------ happy path

    @Test
    fun `decodes production offsets`() {
        val result = MotorInfoParser.parse(packet())!!
        assertEquals(25.3, result.speed, 0.00001)
        assertEquals(40.0, result.avgSpeed, 0.00001)
        assertEquals(100.0, result.mileage, 0.00001)
        assertEquals(85, result.battery)
        assertEquals(-5.0, result.temp, 0.00001)
    }

    @Test
    fun `decodes real captured payloads`() {
        val rest = MotorInfoParser.parse(hex(capturedAtRest))!!
        assertEquals(0.0, rest.speed, 0.00001)
        assertEquals(400.871, rest.mileage, 0.00001)
        assertEquals(43, rest.battery)
        assertEquals(45.0, rest.temp, 0.00001)

        val moving = MotorInfoParser.parse(hex(capturedMoving))!!
        assertEquals(0.15, moving.speed, 0.00001)
        assertEquals(400.144, moving.mileage, 0.00001)
        assertEquals(51, moving.battery)
        assertEquals(31.0, moving.temp, 0.00001)
    }

    // ------------------------------------------- speed: unsigned + sentinel

    @Test
    fun `speed above 32 km per hour is not negative`() {
        // B5 is an unsigned m/h speed. Read as a Short, everything from 0x8000 up
        // came back negative, so a downhill or an over-speed moment rendered as
        // "-5 km/h" on the HUD while avgSpeed two fields over was masked correctly.
        assertEquals(32.768, MotorInfoParser.parse(withRawSpeed(0x8000))!!.speed, 0.00001)
        assertEquals(40.0, MotorInfoParser.parse(withRawSpeed(0x9C40))!!.speed, 0.00001)
        assertEquals(60.022, MotorInfoParser.parse(withRawSpeed(0xEA76))!!.speed, 0.00001)
    }

    @Test
    fun `the no-estimate sentinel reads as zero rather than 65 km per hour`() {
        // Values observed on hardware while the wheel was not turning. Masking the
        // sign without this filter would turn each of them into ~65 km/h, which is
        // worse than the ~-0.1 km/h the signed read produced.
        for (raw in intArrayOf(0xFF3E, 0xFF61, 0xFF9A, 0xFF9D, 0xFFA6, 0xFFF4, 0xFFFF)) {
            assertEquals(
                "raw 0x${raw.toString(16)} should mean 'no estimate'",
                0.0,
                MotorInfoParser.parse(withRawSpeed(raw))!!.speed,
                0.00001
            )
        }
    }

    @Test
    fun `the sentinel cut-off sits exactly at the documented constant`() {
        // Largest accepted value, then the first rejected one. Pinned so a future
        // change to the threshold has to be deliberate.
        assertEquals(65.279, MotorInfoParser.parse(withRawSpeed(0xFEFF))!!.speed, 0.00001)
        assertEquals(0.0, MotorInfoParser.parse(withRawSpeed(0xFF00))!!.speed, 0.00001)
    }

    @Test
    fun `a sentinel only zeroes the speed, not the rest of the sample`() {
        // The wheel being unmeasurable says nothing about battery or odometer.
        val result = MotorInfoParser.parse(withRawSpeed(0xFFF4))!!
        assertEquals(0.0, result.speed, 0.00001)
        assertEquals(51, result.battery)
        assertEquals(400.144, result.mileage, 0.00001)
        assertEquals(31.0, result.temp, 0.00001)
    }

    // ------------------------------------------------------------- contracts

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
    fun `trailing bytes are ignored and temperature stays signed`() {
        val data = packet() + byteArrayOf(99, 98)
        assertEquals(25.3, MotorInfoParser.parse(data)!!.speed, 0.00001)
        assertEquals(-5.0, MotorInfoParser.parse(data)!!.temp, 0.0)
    }
}
