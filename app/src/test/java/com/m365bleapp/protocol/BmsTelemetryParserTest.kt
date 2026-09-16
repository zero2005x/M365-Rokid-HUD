package com.m365bleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BmsTelemetryParser].
 *
 * ## What these tests do and do not prove
 *
 * They pin the **byte layout and scaling** so that a future edit cannot silently
 * change how a frame is interpreted. They do **not** prove the layout matches a
 * real scooter: every vector below was constructed from the decompiled Scootbatt
 * parser, not from a capture. See the class doc on [BmsTelemetryParser].
 *
 * Where a value is signed or biased the test says so explicitly, because those
 * are the two places an implementation silently goes wrong.
 */
class BmsTelemetryParserTest {

    /** Builds a little-endian u16 payload from the given values. */
    private fun le16(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    // ---------------------------------------------------------------- 0x31

    @Test
    fun `status decodes mAh percent current and voltage`() {
        // 5300 mAh, 87 %, 1.25 A (125 hundredths), 39.40 V (3940 hundredths)
        val payload = le16(5300, 87, 125, 3940)

        val status = BmsTelemetryParser.parseStatus(payload)

        requireNotNull(status)
        assertEquals(5300, status.remainingMah)
        assertEquals(87, status.percent)
        assertEquals(1.25, status.currentAmps, 1e-9)
        assertEquals(39.40, status.voltageVolts, 1e-9)
    }

    @Test
    fun `status derives power from volts times amps`() {
        // 2 A at 36 V => 72 W
        val payload = le16(5000, 50, 200, 3600)

        val status = requireNotNull(BmsTelemetryParser.parseStatus(payload))

        assertEquals(72.0, status.powerWatts, 1e-9)
    }

    @Test
    fun `status treats current as signed so discharge is negative`() {
        // 0xFF9C == -100 => -1.00 A. This is the single most important
        // signedness check in the file: an unsigned read would report +654.36 A.
        val payload = le16(5000, 50, 0xFF9C, 3600)

        val status = requireNotNull(BmsTelemetryParser.parseStatus(payload))

        assertEquals(-1.00, status.currentAmps, 1e-9)
    }

    @Test
    fun `status clamps an out of range percentage`() {
        // Some packs report >100 % briefly after a full charge.
        val payload = le16(5000, 130, 0, 3600)

        val status = requireNotNull(BmsTelemetryParser.parseStatus(payload))

        assertEquals(100, status.percent)
    }

    @Test
    fun `status rejects a short payload rather than reporting zero`() {
        // One byte short of the 8 bytes this parser reads. Reporting 0 % here
        // would be worse than reporting nothing, because the HUD would show an
        // empty battery on a healthy pack.
        val short = ByteArray(BmsTelemetryParser.STATUS_MIN_LENGTH - 1)

        assertNull(BmsTelemetryParser.parseStatus(short))
    }

    @Test
    fun `status accepts the eight bytes it actually reads`() {
        // The register is documented as 12 bytes but the four fields occupy 8.
        // An 8-byte answer must still parse: rejecting it would discard a good
        // reading from a scooter that pads differently.
        val payload = le16(5300, 87, 125, 3940)

        assertEquals(BmsTelemetryParser.STATUS_MIN_LENGTH, payload.size)
        val status = requireNotNull(BmsTelemetryParser.parseStatus(payload))

        assertEquals(87, status.percent)
    }

    @Test
    fun `status accepts a payload longer than the documented length`() {
        // Real replies may carry trailing bytes; only the first 8 matter.
        // NOTE: concatenate with `+` only between ByteArrays of the *same*
        // static type — mixing in a bare `byteArrayOf(...)` is fine, but
        // `ByteArray + ByteArray` resolves through Iterable and yields a
        // List<Byte>, which is a trap this test originally fell into.
        val payload = le16(5300, 87, 125, 3940) + byteArrayOf(1, 2, 3, 4)

        val status = requireNotNull(BmsTelemetryParser.parseStatus(payload))

        assertEquals(87, status.percent)
    }

    // ---------------------------------------------------------------- 0x40

    @Test
    fun `cells decode ten millivolt readings`() {
        // Ten healthy-ish cells around 3.7 V, transmitted in mV.
        val mv = intArrayOf(3700, 3710, 3695, 3705, 3702, 3698, 3711, 3704, 3699, 3707)
        val payload = le16(*mv)

        val cells = requireNotNull(BmsTelemetryParser.parseCells(payload))

        assertEquals(10, cells.volts.size)
        assertEquals(3.700, cells.volts[0], 1e-9)
        assertEquals(3.707, cells.volts[9], 1e-9)
    }

    @Test
    fun `cells report highest lowest and spread`() {
        val payload = le16(3700, 3750, 3690, 3700, 3700, 3700, 3700, 3700, 3700, 3700)

        val cells = requireNotNull(BmsTelemetryParser.parseCells(payload))

        assertEquals(3.750, requireNotNull(cells.highestVolts), 1e-9)
        assertEquals(3.690, requireNotNull(cells.lowestVolts), 1e-9)
        // 60 mV spread is exactly the kind of imbalance a rider wants surfaced.
        assertEquals(0.060, requireNotNull(cells.spreadVolts), 1e-9)
    }

    @Test
    fun `cells drop trailing zeros because they mean not populated`() {
        // A 6-cell pack padded to the 10-cell block: the last four are zero.
        val payload = le16(3700, 3701, 3702, 3703, 3704, 3705, 0, 0, 0, 0)

        val cells = requireNotNull(BmsTelemetryParser.parseCells(payload))

        assertEquals(6, cells.volts.size)
        assertEquals(3.705, cells.volts.last(), 1e-9)
    }

    @Test
    fun `cells reject a short payload`() {
        val short = ByteArray(BmsTelemetryParser.CELL_VOLTAGE_LENGTH - 1)

        assertNull(BmsTelemetryParser.parseCells(short))
    }

    @Test
    fun `cells with every slot zero yields no cells rather than null`() {
        val payload = ByteArray(BmsTelemetryParser.CELL_VOLTAGE_LENGTH)

        val cells = requireNotNull(BmsTelemetryParser.parseCells(payload))

        assertTrue(cells.volts.isEmpty())
        assertNull(cells.highestVolts)
        assertNull(cells.spreadVolts)
    }

    // ---------------------------------------------------------------- 0x35

    @Test
    fun `temperatures apply the plus twenty bias`() {
        // Wire 45 => 25 °C, wire 20 => 0 °C
        val payload = byteArrayOf(45, 20)

        val temps = requireNotNull(BmsTelemetryParser.parseTemperatures(payload))

        assertEquals(25.0, temps.firstCelsius, 1e-9)
        assertEquals(0.0, temps.secondCelsius, 1e-9)
    }

    @Test
    fun `temperatures represent sub zero correctly`() {
        // Wire 0 => -20 °C. Reading the byte as unsigned-without-bias would give
        // +236 °C, so this test guards the bias direction.
        val payload = byteArrayOf(0, 5)

        val temps = requireNotNull(BmsTelemetryParser.parseTemperatures(payload))

        assertEquals(-20.0, temps.firstCelsius, 1e-9)
        assertEquals(-15.0, temps.secondCelsius, 1e-9)
    }

    @Test
    fun `temperatures reject a one byte payload`() {
        assertNull(BmsTelemetryParser.parseTemperatures(byteArrayOf(45)))
    }

    // ---------------------------------------------------------------- 0x30

    @Test
    fun `charging flag reads bit six`() {
        // 0x40 set => charging
        assertTrue(requireNotNull(BmsTelemetryParser.isCharging(byteArrayOf(0x40))))
        // 0x80 set but not 0x40 => not charging
        assertFalse(requireNotNull(BmsTelemetryParser.isCharging(byteArrayOf(0x80.toByte()))))
        // 0xC0 => both bits, still charging
        assertTrue(requireNotNull(BmsTelemetryParser.isCharging(byteArrayOf(0xC0.toByte()))))
    }

    @Test
    fun `charging flag distinguishes unknown from false`() {
        // An empty payload must not be reported as "not charging": the HUD would
        // show a charger icon state that was never measured.
        assertNull(BmsTelemetryParser.isCharging(ByteArray(0)))
    }

    // ------------------------------------------------- 0x18 / 0x1B / 0x3B

    @Test
    fun `design capacity decodes and rejects zero`() {
        assertEquals(7800, BmsTelemetryParser.parseDesignCapacityMah(le16(7800)))
        // Zero means "not populated", not a 0 mAh pack.
        assertNull(BmsTelemetryParser.parseDesignCapacityMah(le16(0)))
        assertNull(BmsTelemetryParser.parseDesignCapacityMah(ByteArray(1)))
    }

    @Test
    fun `charge counts decode full and partial`() {
        val counts = requireNotNull(BmsTelemetryParser.parseChargeCounts(le16(112, 340)))

        assertEquals(112, counts.first)
        assertEquals(340, counts.second)
    }

    @Test
    fun `charge counts reject a truncated payload`() {
        // Only the full-count half is present.
        assertNull(BmsTelemetryParser.parseChargeCounts(le16(112)))
    }

    @Test
    fun `health percent decodes and clamps`() {
        assertEquals(94, BmsTelemetryParser.parseHealthPercent(byteArrayOf(94)))
        assertEquals(100, BmsTelemetryParser.parseHealthPercent(byteArrayOf(0xFF.toByte())))
        assertNull(BmsTelemetryParser.parseHealthPercent(ByteArray(0)))
    }

    // ------------------------------------------------------------- helpers

    @Test
    fun `readU16 is little endian and bounds checked`() {
        val data = byteArrayOf(0x34, 0x12)

        assertEquals(0x1234, BmsTelemetryParser.readU16(data, 0))
        // One byte before the end cannot hold a u16.
        assertNull(BmsTelemetryParser.readU16(data, 1))
        assertNull(BmsTelemetryParser.readU16(data, -1))
    }

    @Test
    fun `readI16 sign extends`() {
        assertEquals(-1, BmsTelemetryParser.readI16(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 0))
        assertEquals(-32768, BmsTelemetryParser.readI16(byteArrayOf(0x00, 0x80.toByte()), 0))
        assertEquals(32767, BmsTelemetryParser.readI16(byteArrayOf(0xFF.toByte(), 0x7F), 0))
    }
}
