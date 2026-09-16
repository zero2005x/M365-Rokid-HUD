package com.m365bleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EscTelemetryParser].
 *
 * These pin the offsets and scales so a later edit cannot quietly change how a
 * register is interpreted. Signedness and bias get their own tests because those
 * are where a decoder goes wrong without looking wrong.
 */
class EscTelemetryParserTest {

    private fun le16(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    // ---------------------------------------------------------------- 0xB5

    @Test
    fun `speed uses the tenths scale by default`() {
        // 221 raw => 22.1 km/h
        assertEquals(22.1, requireNotNull(EscTelemetryParser.speedKmh(le16(221))), 1e-9)
    }

    @Test
    fun `speed uses the thousandths scale on Xiaomi models`() {
        // 22100 raw => 22.1 km/h under the Xiaomi scale. Getting the model wrong
        // here would report 2210 km/h.
        assertEquals(
            22.1,
            requireNotNull(EscTelemetryParser.speedKmh(le16(22100), xiaomi = true)),
            1e-9
        )
    }

    @Test
    fun `speed is signed so a reversing scooter reads negative`() {
        // 0xFFFF == -1 => -0.1 km/h under the default scale.
        assertEquals(-0.1, requireNotNull(EscTelemetryParser.speedKmh(le16(0xFFFF))), 1e-9)
    }

    @Test
    fun `speed rejects a short payload`() {
        assertNull(EscTelemetryParser.speedKmh(ByteArray(1)))
    }

    // ------------------------------------------------------- scaled registers

    @Test
    fun `scaled100 divides by one hundred`() {
        assertEquals(12.34, requireNotNull(EscTelemetryParser.scaled100(le16(1234))), 1e-9)
        assertEquals(12.34, requireNotNull(EscTelemetryParser.scaled100Alt(le16(1234))), 1e-9)
        assertEquals(12.34, requireNotNull(EscTelemetryParser.scaled100Distance(le16(1234))), 1e-9)
    }

    @Test
    fun `odometer divides a signed 32 bit value by one thousand`() {
        assertEquals(1234.567, requireNotNull(EscTelemetryParser.odometerKm(le32(1234567))), 1e-9)
    }

    @Test
    fun `odometer is signed so a negative does not become four billion km`() {
        // -1000 thousandths == -1 km. Read unsigned this would be ~4294966 km.
        assertEquals(-1.0, requireNotNull(EscTelemetryParser.odometerKm(le32(-1000))), 1e-9)
    }

    @Test
    fun `temperature divides by ten`() {
        assertEquals(28.5, requireNotNull(EscTelemetryParser.escTemperatureC(le16(285))), 1e-9)
    }

    @Test
    fun `temperature is signed so sub zero readings work`() {
        // 0xFF38 == -200 => -20.0 C
        assertEquals(-20.0, requireNotNull(EscTelemetryParser.escTemperatureC(le16(0xFF38))), 1e-9)
    }

    @Test
    fun `phase current divides by one hundred and is signed`() {
        assertEquals(1.25, requireNotNull(EscTelemetryParser.phaseCurrentA(le16(125))), 1e-9)
        // Regenerative braking makes this negative.
        assertEquals(-2.5, requireNotNull(EscTelemetryParser.phaseCurrentA(le16(0xFF06))), 1e-9)
    }

    @Test
    fun `system voltage divides by one hundred`() {
        assertEquals(39.4, requireNotNull(EscTelemetryParser.systemVoltageV(le16(3940))), 1e-9)
    }

    // -------------------------------------------------------------- 0x1B

    @Test
    fun `error code decodes straight through`() {
        assertEquals(39, EscTelemetryParser.errorCode(le16(39)))
    }

    @Test
    fun `error combines code description and severity`() {
        val error = requireNotNull(EscTelemetryParser.error(le16(39)))

        assertEquals(39, error.code)
        assertEquals("Battery overheat", error.description)
        assertEquals(ScooterErrorCodes.Severity.FAULT, error.severity)
        assertFalse(error.isHealthy)
    }

    @Test
    fun `a healthy error reads as ok`() {
        val error = requireNotNull(EscTelemetryParser.error(le16(0)))

        assertTrue(error.isHealthy)
        assertEquals(ScooterErrorCodes.Severity.OK, error.severity)
    }

    @Test
    fun `an unknown error code is still surfaced as a fault`() {
        val error = requireNotNull(EscTelemetryParser.error(le16(9999)))

        assertEquals(9999, error.code)
        assertFalse(error.isHealthy)
        assertEquals(ScooterErrorCodes.Severity.FAULT, error.severity)
        assertTrue(error.description.contains("9999"))
    }

    @Test
    fun `error rejects a short payload`() {
        assertNull(EscTelemetryParser.error(ByteArray(1)))
    }

    // -------------------------------------------------------------- 0x75

    @Test
    fun `ride mode maps the three documented values`() {
        assertEquals(
            EscTelemetryParser.RideMode.NORMAL,
            EscTelemetryParser.rideMode(byteArrayOf(0))
        )
        assertEquals(
            EscTelemetryParser.RideMode.ECO,
            EscTelemetryParser.rideMode(byteArrayOf(1))
        )
        assertEquals(
            EscTelemetryParser.RideMode.SPORT,
            EscTelemetryParser.rideMode(byteArrayOf(2))
        )
    }

    @Test
    fun `an undocumented ride mode does not throw`() {
        // A future firmware adding a fourth mode must not crash the parser.
        assertEquals(
            EscTelemetryParser.RideMode.NOT_DEFINED,
            EscTelemetryParser.rideMode(byteArrayOf(9))
        )
    }

    // -------------------------------------------------------------- 0x7B

    @Test
    fun `kers level maps the three documented values`() {
        assertEquals(EscTelemetryParser.KersLevel.WEAK, EscTelemetryParser.kersLevel(byteArrayOf(0)))
        assertEquals(
            EscTelemetryParser.KersLevel.MEDIUM,
            EscTelemetryParser.kersLevel(byteArrayOf(1))
        )
        assertEquals(
            EscTelemetryParser.KersLevel.STRONG,
            EscTelemetryParser.kersLevel(byteArrayOf(2))
        )
        assertEquals(
            EscTelemetryParser.KersLevel.UNKNOWN,
            EscTelemetryParser.kersLevel(byteArrayOf(7))
        )
    }

    // -------------------------------------------------------------- 0x7C

    @Test
    fun `cruise is engaged only for the value one`() {
        assertEquals(true, EscTelemetryParser.cruiseEngaged(byteArrayOf(1)))
        assertEquals(false, EscTelemetryParser.cruiseEngaged(byteArrayOf(0)))
        // Anything else is "not the engaged encoding", not an error.
        assertEquals(false, EscTelemetryParser.cruiseEngaged(byteArrayOf(2)))
        assertNull(EscTelemetryParser.cruiseEngaged(ByteArray(0)))
    }

    // -------------------------------------------------------------- 0x7D

    @Test
    fun `status bits decode the tail light and mph bits`() {
        // bit1 = tail light, bit4 = mph => 0b0001_0010 == 0x12
        val bits = requireNotNull(EscTelemetryParser.statusBits(le16(0x12)))

        assertTrue(bits.tailLightAlwaysOn)
        assertTrue(bits.milesPerHour)
        assertEquals(0x12, bits.raw)
    }

    @Test
    fun `status bits are independent`() {
        val tailOnly = requireNotNull(EscTelemetryParser.statusBits(le16(EscTelemetryParser.TAIL_LIGHT_BIT)))
        assertTrue(tailOnly.tailLightAlwaysOn)
        assertFalse(tailOnly.milesPerHour)

        val mphOnly = requireNotNull(EscTelemetryParser.statusBits(le16(EscTelemetryParser.MPH_BIT)))
        assertFalse(mphOnly.tailLightAlwaysOn)
        assertTrue(mphOnly.milesPerHour)

        val neither = requireNotNull(EscTelemetryParser.statusBits(le16(0)))
        assertFalse(neither.tailLightAlwaysOn)
        assertFalse(neither.milesPerHour)
    }

    @Test
    fun `status bits are read little endian`() {
        // 0x0100 little-endian is bytes 00 01 => value 256 => bit 8, which is
        // neither of our bits. Reading big-endian would wrongly set bit 1.
        val bits = requireNotNull(EscTelemetryParser.statusBits(byteArrayOf(0x00, 0x01)))
        assertEquals(0x0100, bits.raw)
        assertFalse(bits.tailLightAlwaysOn)
    }

    // -------------------------------------------------------------- 0x66

    @Test
    fun `firmware versions decode three u16 values`() {
        // data[4] and data[5] double as the version nibbles, so the third u16 is
        // built from those same two bytes rather than a separate value.
        val payload = le16(1, 2) + byteArrayOf(0x01, 0x01)

        val versions = requireNotNull(EscTelemetryParser.firmwareVersions(payload))

        assertEquals(1, versions.first)
        assertEquals(2, versions.second)
        // 0x0101 little-endian == 257
        assertEquals(0x0101, versions.third)
    }

    @Test
    fun `firmware version plausibility window is applied`() {
        // The formula reads data[4] and data[5], so the plausibility bytes must be
        // at those offsets. Scootbatt computes:
        //   derived = ((b[4] & 0xF0) >> 4) * 10 + b[5] * 100 + (b[4] & 0x0F)
        //
        // b[4]=0x01, b[5]=0x01 => 0*10 + 100 + 1 = 101, inside 72..200.
        val plausible = le16(1, 2) + byteArrayOf(0x01, 0x01)
        assertFalse(requireNotNull(EscTelemetryParser.firmwareVersions(plausible)).implausible)

        // b[4]=0x00, b[5]=0x00 => 0, below 72.
        val tooLow = le16(1, 2) + byteArrayOf(0x00, 0x00)
        assertTrue(requireNotNull(EscTelemetryParser.firmwareVersions(tooLow)).implausible)

        // b[5]=0xFF => 25500 + nibbles, above 200.
        val tooHigh = le16(1, 2) + byteArrayOf(0x00, 0xFF.toByte())
        assertTrue(requireNotNull(EscTelemetryParser.firmwareVersions(tooHigh)).implausible)
    }

    @Test
    fun `firmware versions reject a short payload`() {
        assertNull(EscTelemetryParser.firmwareVersions(ByteArray(5)))
    }

    // -------------------------------------------------------------- 0x39

    @Test
    fun `identity builds the plain and dotted forms`() {
        val payload = byteArrayOf(12, 34, 56, 78)

        val identity = requireNotNull(EscTelemetryParser.identity(payload))

        assertEquals("123456", identity.plain)
        assertEquals("12.34.56", identity.dotted)
        assertEquals(123456, identity.numeric)
    }

    @Test
    fun `identity rejects a short payload`() {
        // Scootbatt requires at least four bytes for this register.
        assertNull(EscTelemetryParser.identity(byteArrayOf(12, 34, 56)))
    }

    // -------------------------------------------------------------- 0xFF

    @Test
    fun `u16 excluding sentinels rejects zero and 255`() {
        // Both mean "not set" rather than a genuine zero or 255 reading.
        assertNull(EscTelemetryParser.u16ExcludingSentinels(le16(0)))
        assertNull(EscTelemetryParser.u16ExcludingSentinels(le16(255)))
        assertEquals(300, EscTelemetryParser.u16ExcludingSentinels(le16(300)))
    }

    // ------------------------------------------------------------- helpers

    @Test
    fun `u16 i16 and i32 are little endian and bounds checked`() {
        val data = byteArrayOf(0x34, 0x12, 0x78, 0x56)

        assertEquals(0x1234, EscTelemetryParser.u16(data, 0))
        assertEquals(0x5678, EscTelemetryParser.u16(data, 2))
        assertNull(EscTelemetryParser.u16(data, 3))
        assertNull(EscTelemetryParser.u16(data, -1))

        assertNull(EscTelemetryParser.i32(data, 1))
        assertEquals(0x56781234, EscTelemetryParser.i32(data, 0))
    }

    @Test
    fun `u8 is bounds checked`() {
        assertEquals(0xAB, EscTelemetryParser.u8(byteArrayOf(0xAB.toByte()), 0))
        assertNull(EscTelemetryParser.u8(ByteArray(0), 0))
        assertNull(EscTelemetryParser.u8(byteArrayOf(1), 1))
    }

    @Test
    fun `every decoder tolerates an empty payload without throwing`() {
        // A truncated frame must produce nulls, never an exception: the A3
        // validator drops the truly malformed frames, but a register parser that
        // threw on a short payload would still take the app down.
        val empty = ByteArray(0)

        assertNull(EscTelemetryParser.speedKmh(empty))
        assertNull(EscTelemetryParser.scaled100(empty))
        assertNull(EscTelemetryParser.odometerKm(empty))
        assertNull(EscTelemetryParser.escTemperatureC(empty))
        assertNull(EscTelemetryParser.phaseCurrentA(empty))
        assertNull(EscTelemetryParser.systemVoltageV(empty))
        assertNull(EscTelemetryParser.errorCode(empty))
        assertNull(EscTelemetryParser.error(empty))
        assertNull(EscTelemetryParser.rideMode(empty))
        assertNull(EscTelemetryParser.kersLevel(empty))
        assertNull(EscTelemetryParser.cruiseEngaged(empty))
        assertNull(EscTelemetryParser.statusBits(empty))
        assertNull(EscTelemetryParser.firmwareVersions(empty))
        assertNull(EscTelemetryParser.uptimeSeconds(empty))
        assertNull(EscTelemetryParser.identity(empty))
        assertNull(EscTelemetryParser.u16ExcludingSentinels(empty))
        assertNotNull(EscTelemetryParser.KersLevel.UNKNOWN)
    }
}
