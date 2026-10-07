package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import org.junit.Assert.*
import org.junit.Test

/** Synthetic unless explicitly labelled; no vector here grants vehicle verification. */
class XiaomiEscDecoderTest {
    @Test fun `trip capture example has seconds then metres`() {
        // Existing MIT ninebot-ble/doc/protocol.md 0x3A literal capture example.
        val trip = XiaomiEscDecoder.trip(byteArrayOf(0x7B, 0x02, 0x0A, 0x00), 7)
        assertEquals(635.0, trip[FieldId.TRIP_TIME_S].value)
        assertEquals(10.0, trip[FieldId.TRIP_DISTANCE_M].value)
        assertEquals(Evidence.VENDOR_STATIC, trip[FieldId.TRIP_TIME_S].evidence)
        assertEquals(7L, trip[FieldId.TRIP_TIME_S].observedAtMs)
    }

    @Test fun `trip rejects all short lengths without inventing stop or zero distance`() {
        for (size in 0..3) {
            val trip = XiaomiEscDecoder.trip(ByteArray(size), 10)
            for (field in listOf(FieldId.TRIP_TIME_S, FieldId.TRIP_DISTANCE_M)) {
                assertEquals(FieldState.INVALID, trip[field].state)
                assertNull(trip[field].value)
            }
        }
    }

    @Test fun `trip counters are unsigned and age independently`() {
        val trip = XiaomiEscDecoder.trip(byteArrayOf(-1, -1, 0, -128), 10).aged(21, 10)
        assertEquals(65535.0, trip[FieldId.TRIP_TIME_S].value)
        assertEquals(32768.0, trip[FieldId.TRIP_DISTANCE_M].value)
        assertEquals(FieldState.STALE, trip[FieldId.TRIP_TIME_S].state)
        assertNull(trip.usableValue(FieldId.TRIP_DISTANCE_M))
    }

    @Test fun `range remains raw while scaling is disputed`() {
        val raw = XiaomiEscDecoder.rangeDiagnostic(byteArrayOf(0x26, 0x07), 10)
        assertEquals(1830, raw.value)
        assertEquals(1830, raw.raw)
        assertEquals("xiaomi.M365.ESC.25", raw.source)
        assertEquals(FieldState.INVALID, XiaomiEscDecoder.rangeDiagnostic(ByteArray(1), 10).state)
    }

    @Test fun `error code is unsigned and a missing code is invalid`() {
        assertEquals(65535, XiaomiEscDecoder.errorCode(byteArrayOf(-1, -1), 10).value)
        assertNull(XiaomiEscDecoder.errorCode(ByteArray(0), 10).value)
    }

    @Test fun `kers rejects undocumented enumerations but retains raw`() {
        for (level in 0..2) assertEquals(level, XiaomiEscDecoder.kers(byteArrayOf(level.toByte()), 1).value)
        val unknown = XiaomiEscDecoder.kers(byteArrayOf(-1), 1)
        assertEquals(255, unknown.raw)
        assertNull(unknown.value)
        assertEquals(FieldState.INVALID, unknown.state)
        assertNull(XiaomiEscDecoder.kers(ByteArray(0), 1).raw)
    }

    @Test fun `cruise distinguishes false true and unknown`() {
        assertEquals(false, XiaomiEscDecoder.cruise(byteArrayOf(0), 1).value)
        assertEquals(true, XiaomiEscDecoder.cruise(byteArrayOf(1), 1).value)
        assertNull(XiaomiEscDecoder.cruise(byteArrayOf(2), 1).value)
        assertNull(XiaomiEscDecoder.cruise(ByteArray(0), 1).value)
    }

    @Test fun `status is little endian and preserves unrelated bits`() {
        val result = XiaomiEscDecoder.statusWord(byteArrayOf(0x12, -128), 1)
        val word = requireNotNull(result.value)
        assertEquals(0x8012, word.raw)
        assertTrue(word.tailLightAlwaysOn)
        assertTrue(word.milesPerHour)
        val off = requireNotNull(XiaomiEscDecoder.statusWord(byteArrayOf(0, 1), 1).value)
        assertFalse(off.tailLightAlwaysOn)
        assertFalse(off.milesPerHour)
        assertNull(XiaomiEscDecoder.statusWord(ByteArray(1), 1).value)
    }

    @Test fun `firmware exposes raw words without inventing board identity`() {
        val words = requireNotNull(XiaomiEscDecoder.firmwareWords(byteArrayOf(1, 2, 3, 4, -1, -1), 1).value)
        assertEquals(XiaomiEscDecoder.FirmwareWords(513, 1027, 65535), words)
        for (size in 0..5) assertNull(XiaomiEscDecoder.firmwareWords(ByteArray(size), 1).value)
    }

    @Test fun `version requires static minimum and never identifies a model`() {
        val version = requireNotNull(XiaomiEscDecoder.versionComponents(byteArrayOf(1, -1, 3, 4, 0), 1).value)
        assertEquals(XiaomiEscDecoder.VersionComponents(1, 255, 3, 4), version)
        for (size in 0..4) assertNull(XiaomiEscDecoder.versionComponents(ByteArray(size), 1).value)
    }

    @Test fun `observation aging leaves valid boundary and invalid data alone`() {
        val value = XiaomiEscDecoder.errorCode(byteArrayOf(0, 0), 10)
        assertEquals(FieldState.VALID, value.aged(20, 10).state)
        assertEquals(FieldState.STALE, value.aged(21, 10).state)
        assertEquals(FieldState.STALE, value.aged(21, 10).aged(30, 10).state)
        val invalid = XiaomiEscDecoder.errorCode(ByteArray(0), 10)
        assertEquals(invalid, invalid.aged(30, 10))
    }
}
