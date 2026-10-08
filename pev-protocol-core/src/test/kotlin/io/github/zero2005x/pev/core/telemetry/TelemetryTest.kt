package io.github.zero2005x.pev.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {
    private val v = Reading.valid(1.0, 1_000, Evidence.SYNTHETIC)

    @Test
    fun missingFieldIsNotProvidedNeverZero() {
        val r = TelemetrySnapshot()[FieldId.SPEED_KMH]
        assertEquals(FieldState.NOT_PROVIDED, r.state)
        assertNull(r.value)
    }

    @Test
    fun agingMarksStaleAndBlocksUsableValue() {
        val s = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to v)).aged(5_000, 2_000)
        assertEquals(FieldState.STALE, s[FieldId.SPEED_KMH].state)
        assertNull(s.usableValue(FieldId.SPEED_KMH))
    }

    @Test
    fun freshReadingStaysUsable() {
        val s = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to v)).aged(1_500, 2_000)
        assertEquals(1.0, s.usableValue(FieldId.SPEED_KMH)!!, 0.0)
    }

    @Test
    fun readingWithoutTimestampIsNotAged() {
        assertEquals(Reading.UNSUPPORTED, Reading.UNSUPPORTED.aged(9, 1))
    }

    @Test
    fun invalidAndUnsupportedAreNotUsable() {
        assertFalse(Reading.invalid(1).usable)
        assertFalse(Reading.UNSUPPORTED.usable)
        assertTrue(v.usable)
    }

    @Test
    fun mergeOverridesAndKeepsOthers() {
        val a = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to v, FieldId.SOC_PERCENT to v))
        val b = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to Reading.invalid(2_000)))
        val m = a.merge(b)
        assertEquals(FieldState.INVALID, m[FieldId.SPEED_KMH].state)
        assertTrue(m[FieldId.SOC_PERCENT].usable)
    }

    @Test
    fun currentsAndTemperaturesAreDistinctFields() {
        val names = FieldId.entries.map { it.name }
        assertTrue("PHASE_CURRENT" in names && "BATTERY_CURRENT" in names)
        assertEquals(5, names.count { it.startsWith("TEMP_") })
        assertEquals(PhysUnit.AMP, FieldId.PHASE_CURRENT.unit)
    }
}

