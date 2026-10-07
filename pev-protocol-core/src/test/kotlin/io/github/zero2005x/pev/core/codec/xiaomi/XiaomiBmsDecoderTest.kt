package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All vectors here are synthetic layout checks, not vehicle captures. */
class XiaomiBmsDecoderTest {
    private val now = 100L

    private fun words(vararg values: Int): ByteArray = values.flatMap {
        listOf(it.toByte(), (it ushr 8).toByte())
    }.toByteArray()

    private fun assertReading(reading: Reading, value: Double?, state: FieldState, register: String) {
        if (value == null) assertNull(reading.value) else assertEquals(value, reading.value!!, 1e-9)
        assertEquals(state, reading.state)
        assertEquals(now, reading.observedAtMs)
        assertEquals(Evidence.VENDOR_STATIC, reading.evidence)
        assertEquals("xiaomi.M365.BMS.$register", reading.source)
    }

    @Test
    fun statusUsesUnsignedChargeAndVoltageSignedBatteryCurrentAndPercent() {
        val status = XiaomiBmsDecoder.decodeStatus(words(0xFEDC, 75, -1234, 0xF123), now)
        assertReading(status.remainingMah, 65244.0, FieldState.VALID, "31")
        assertReading(status.telemetry[FieldId.SOC_PERCENT], 75.0, FieldState.VALID, "31")
        assertReading(status.telemetry[FieldId.BATTERY_CURRENT], -12.34, FieldState.VALID, "31")
        assertReading(status.telemetry[FieldId.PACK_VOLTAGE], 617.31, FieldState.VALID, "31")
        assertEquals(FieldState.NOT_PROVIDED, status.telemetry[FieldId.PHASE_CURRENT].state)
        assertEquals(XiaomiBmsDecoder.STATUS_MIN_LENGTH, 8)
        assertEquals(XiaomiBmsDecoder.STATUS_LENGTH, 12)
    }

    @Test
    fun fullStatusAndTrailingBytesDoNotAlterTheFirstEightBytes() {
        val minimal = words(5000, 60, 125, 4200)
        val expected = XiaomiBmsDecoder.decodeStatus(minimal, now)
        for (length in listOf(8, 9, 10, 11, 12, 20)) {
            val padded = minimal + ByteArray(length - minimal.size) { 0xFF.toByte() }
            assertEquals(expected, XiaomiBmsDecoder.decodeStatus(padded, now))
        }
    }

    @Test
    fun everyTruncatedStatusInvalidatesAllItsFields() {
        for (length in 0 until XiaomiBmsDecoder.STATUS_MIN_LENGTH) {
            val status = XiaomiBmsDecoder.decodeStatus(words(2000, 50, 123, 4000).copyOf(length), now)
            assertNull(status.rawSocPercent)
            assertReading(status.remainingMah, null, FieldState.INVALID, "31")
            for (field in listOf(FieldId.SOC_PERCENT, FieldId.BATTERY_CURRENT, FieldId.PACK_VOLTAGE)) {
                assertReading(status.telemetry[field], null, FieldState.INVALID, "31")
            }
        }
    }

    @Test
    fun socBoundsAreValidatedInsteadOfClamped() {
        for (raw in listOf(0, 100, 101, 255, 65535)) {
            val status = XiaomiBmsDecoder.decodeStatus(words(0, raw, 0, 0), now)
            assertEquals(raw, status.rawSocPercent)
            val valid = raw <= 100
            assertReading(
                status.telemetry[FieldId.SOC_PERCENT],
                if (valid) raw.toDouble() else null,
                if (valid) FieldState.VALID else FieldState.INVALID,
                "31",
            )
            assertReading(status.remainingMah, 0.0, FieldState.VALID, "31")
            assertReading(status.telemetry[FieldId.PACK_VOLTAGE], 0.0, FieldState.VALID, "31")
        }
    }

    @Test
    fun signedCurrentCoversBothExtremesAndZero() {
        for (raw in listOf(-32768, -1, 0, 1, 32767)) {
            val status = XiaomiBmsDecoder.decodeStatus(words(1, 1, raw, 1), now)
            assertReading(status.telemetry[FieldId.BATTERY_CURRENT], raw / 100.0, FieldState.VALID, "31")
        }
    }

    @Test
    fun temperaturesPreserveBothUnsignedSensorBytesAndIgnoreExtras() {
        val temperatures = XiaomiBmsDecoder.decodeTemperatures(byteArrayOf(0, 0xFF.toByte(), 99), now)
        assertReading(temperatures.firstCelsius, -20.0, FieldState.VALID, "35")
        assertReading(temperatures.secondCelsius, 235.0, FieldState.VALID, "35")
        val ordinary = XiaomiBmsDecoder.decodeTemperatures(byteArrayOf(45, 52), now)
        assertReading(ordinary.firstCelsius, 25.0, FieldState.VALID, "35")
        assertReading(ordinary.secondCelsius, 32.0, FieldState.VALID, "35")
    }

    @Test
    fun truncatedTemperaturesInvalidatesBothSensors() {
        for (length in 0 until XiaomiBmsDecoder.TEMPERATURE_LENGTH) {
            val temperatures = XiaomiBmsDecoder.decodeTemperatures(ByteArray(length), now)
            assertReading(temperatures.firstCelsius, null, FieldState.INVALID, "35")
            assertReading(temperatures.secondCelsius, null, FieldState.INVALID, "35")
        }
    }

    @Test
    fun cellsPreserveInteriorAndTrailingGapsAndUseUnsignedLittleEndianMillivolts() {
        val raw = intArrayOf(4100, 0, 3900, 0xFEDC, 1, 0, 4200, 4095, 0, 0)
        val cells = XiaomiBmsDecoder.decodeCells(words(*raw) + byteArrayOf(1, 2), now)
        assertEquals(XiaomiBmsDecoder.CELL_COUNT, cells.volts.size)
        for (slot in raw.indices) {
            assertReading(
                cells.volts[slot],
                if (raw[slot] == 0) null else raw[slot] / 1000.0,
                if (raw[slot] == 0) FieldState.NOT_PROVIDED else FieldState.VALID,
                "40",
            )
        }
    }

    @Test
    fun everyTruncatedCellBlockStillHasTenInvalidSlots() {
        assertEquals(20, XiaomiBmsDecoder.CELL_VOLTAGE_LENGTH)
        for (length in 0 until XiaomiBmsDecoder.CELL_VOLTAGE_LENGTH) {
            val cells = XiaomiBmsDecoder.decodeCells(ByteArray(length) { 1 }, now)
            assertEquals(10, cells.volts.size)
            cells.volts.forEach { assertReading(it, null, FieldState.INVALID, "40") }
        }
    }

    @Test
    fun allZeroCellsRemainTenMissingObservations() {
        XiaomiBmsDecoder.decodeCells(ByteArray(20), now).volts.forEach {
            assertReading(it, null, FieldState.NOT_PROVIDED, "40")
        }
    }

    @Test
    fun chargingReadsOnlyBitSixAndKeepsTypedUnknownMetadata() {
        for (raw in 0..255) {
            val charging = XiaomiBmsDecoder.decodeCharging(byteArrayOf(raw.toByte(), 0x40), now)
            assertEquals(raw and 0x40 != 0, charging.value)
            assertEquals(FieldState.VALID, charging.state)
            assertEquals(now, charging.observedAtMs)
            assertEquals(Evidence.VENDOR_STATIC, charging.evidence)
            assertEquals("xiaomi.M365.BMS.30", charging.source)
            assertTrue(charging.usable)
        }
        val unknown = XiaomiBmsDecoder.decodeCharging(byteArrayOf(), now)
        assertNull(unknown.value)
        assertEquals(FieldState.INVALID, unknown.state)
        assertEquals(Evidence.VENDOR_STATIC, unknown.evidence)
        assertFalse(unknown.usable)
    }

    @Test
    fun designCapacityIsUnsignedMahWithoutAnAssumedZeroSentinel() {
        for (raw in listOf(0, 7800, 65535)) {
            assertReading(XiaomiBmsDecoder.decodeDesignCapacity(words(raw) + byteArrayOf(3), now), raw.toDouble(), FieldState.VALID, "18")
        }
        for (length in 0..1) {
            assertReading(XiaomiBmsDecoder.decodeDesignCapacity(ByteArray(length), now), null, FieldState.INVALID, "18")
        }
    }

    @Test
    fun chargeCountersAreUnsignedDistinctAndRequireBothWords() {
        val counts = XiaomiBmsDecoder.decodeChargeCounts(words(0xABCD, 0xFEDC) + byteArrayOf(1), now)
        assertReading(counts.full, 43981.0, FieldState.VALID, "1B")
        assertReading(counts.partial, 65244.0, FieldState.VALID, "1B")
        val zero = XiaomiBmsDecoder.decodeChargeCounts(words(0, 0), now)
        assertReading(zero.full, 0.0, FieldState.VALID, "1B")
        assertReading(zero.partial, 0.0, FieldState.VALID, "1B")
        for (length in 0..3) {
            val truncated = XiaomiBmsDecoder.decodeChargeCounts(ByteArray(length), now)
            assertReading(truncated.full, null, FieldState.INVALID, "1B")
            assertReading(truncated.partial, null, FieldState.INVALID, "1B")
        }
    }

    @Test
    fun healthIsUnsignedPercentWithInvalidRatherThanClampedOutOfRangeValues() {
        for (raw in listOf(0, 100, 101, 128, 255)) {
            val valid = raw <= 100
            assertEquals(raw, XiaomiBmsDecoder.rawHealthPercent(byteArrayOf(raw.toByte())))
            assertReading(
                XiaomiBmsDecoder.decodeHealth(byteArrayOf(raw.toByte(), 50), now),
                if (valid) raw.toDouble() else null,
                if (valid) FieldState.VALID else FieldState.INVALID,
                "3B",
            )
        }
        assertReading(XiaomiBmsDecoder.decodeHealth(byteArrayOf(), now), null, FieldState.INVALID, "3B")
        assertNull(XiaomiBmsDecoder.rawHealthPercent(byteArrayOf()))
    }

    @Test
    fun publicRawWordHelpersGuardAllOffsetsIncludingIntegerOverflow() {
        val bytes = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte())
        assertEquals(0xFF01, XiaomiBmsDecoder.readU16(bytes, 0))
        assertEquals(-255, XiaomiBmsDecoder.readI16(bytes, 0))
        assertEquals(0xFEFF, XiaomiBmsDecoder.readU16(bytes, 1))
        assertEquals(-257, XiaomiBmsDecoder.readI16(bytes, 1))
        for (offset in listOf(-1, 2, 3, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertNull(XiaomiBmsDecoder.readU16(bytes, offset))
            assertNull(XiaomiBmsDecoder.readI16(bytes, offset))
        }
        assertNull(XiaomiBmsDecoder.readU16(byteArrayOf(), 0))
        assertNull(XiaomiBmsDecoder.readI16(byteArrayOf(1), 0))
    }

    @Test
    fun allDtoObservationsCanAgeWithoutLosingValuesProvenanceOrSlotIdentity() {
        val status = XiaomiBmsDecoder.decodeStatus(words(5000, 50, -10, 4200), now)
        assertEquals(status, status.aged(110, 10))
        val stale = status.aged(111, 10)
        assertEquals(FieldState.STALE, stale.remainingMah.state)
        assertEquals(status.remainingMah.value, stale.remainingMah.value)
        stale.telemetry.fields.values.forEach { assertEquals(FieldState.STALE, it.state) }

        val temperatures = XiaomiBmsDecoder.decodeTemperatures(byteArrayOf(45, 46), now)
        assertEquals(temperatures, temperatures.aged(110, 10))
        assertEquals(FieldState.STALE, temperatures.aged(111, 10).firstCelsius.state)
        assertEquals(FieldState.STALE, temperatures.aged(111, 10).secondCelsius.state)

        val cells = XiaomiBmsDecoder.decodeCells(words(4100, 0, 4200, 0, 4100, 0, 4100, 0, 4100, 0), now)
        assertEquals(cells, cells.aged(110, 10))
        val agedCells = cells.aged(111, 10)
        assertEquals(FieldState.STALE, agedCells.volts[0].state)
        assertEquals(FieldState.NOT_PROVIDED, agedCells.volts[1].state)
        assertEquals(Evidence.VENDOR_STATIC, agedCells.volts[1].evidence)

        val counts = XiaomiBmsDecoder.decodeChargeCounts(words(1, 2), now)
        assertEquals(counts, counts.aged(110, 10))
        assertEquals(FieldState.STALE, counts.aged(111, 10).full.state)
        assertEquals(FieldState.STALE, counts.aged(111, 10).partial.state)

        val charging = XiaomiBmsDecoder.decodeCharging(byteArrayOf(0x40), now)
        assertEquals(charging, charging.aged(110, 10))
        assertTrue(charging.usable)
        val agedCharging = charging.aged(111, 10)
        assertEquals(FieldState.STALE, agedCharging.state)
        assertEquals(true, agedCharging.value)
        assertEquals(charging.evidence, agedCharging.evidence)
        assertFalse(agedCharging.usable)
        assertEquals(agedCharging, agedCharging.aged(200, 10))
        val unknown = XiaomiBmsDecoder.decodeCharging(byteArrayOf(), now)
        assertEquals(unknown, unknown.aged(200, 10))
        assertFalse(charging.copy(value = null).usable)
    }
}
