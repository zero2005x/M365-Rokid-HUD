package com.m365bleapp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ScooterSettingsWriter].
 *
 * Two properties matter more than the rest and have dedicated tests: the `0x7D`
 * word is **written big-endian while read little-endian**, and each toggle must
 * leave the other bit in the shared word untouched.
 */
class ScooterSettingsWriterTest {

    private fun le16(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    /**
     * Reassembles a big-endian payload back into a word.
     *
     * Written with explicit shifts instead of `a and 0xFF shl 8`, because Kotlin
     * binds `and` tighter than `shl` and that expression silently parses as
     * `(a and 0xFF) shl 8` — a trap this test file originally fell into.
     */
    private fun beWord(payload: ByteArray): Int =
        ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)

    // --------------------------------------------------------------- KERS

    @Test
    fun `kers levels map to the documented codes`() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x00),
            ScooterSettingsWriter.setKers(ScooterSettingsWriter.Kers.WEAK).payload,
        )
        assertArrayEquals(
            byteArrayOf(0x01, 0x00),
            ScooterSettingsWriter.setKers(ScooterSettingsWriter.Kers.MEDIUM).payload,
        )
        assertArrayEquals(
            byteArrayOf(0x02, 0x00),
            ScooterSettingsWriter.setKers(ScooterSettingsWriter.Kers.STRONG).payload,
        )
    }

    @Test
    fun `kers writes target register 0x7B`() {
        assertEquals(
            ScooterSettingsWriter.REG_KERS,
            ScooterSettingsWriter.setKers(ScooterSettingsWriter.Kers.STRONG).register,
        )
    }

    // ------------------------------------------------------------- cruise

    @Test
    fun `cruise writes the engaged flag`() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x00),
            ScooterSettingsWriter.setCruise(true).payload,
        )
        assertArrayEquals(
            byteArrayOf(0x00, 0x00),
            ScooterSettingsWriter.setCruise(false).payload,
        )
    }

    // ---------------------------------------------------- status word 0x7D

    @Test
    fun `the status word is written big endian`() {
        // 0x0102 must go out as 01 02, not 02 01. Getting this backwards sets the
        // wrong bit, and two settings share this word.
        val write = ScooterSettingsWriter.statusWordWrite(0x0102)

        assertArrayEquals(byteArrayOf(0x01, 0x02), write.payload)
    }

    @Test
    fun `the status word read and write encodings are opposites`() {
        // The asymmetry is intentional and must stay: read little-endian, write
        // big-endian. This test documents it so a future "cleanup" cannot unify
        // them without failing.
        val word = 0x0012
        val written = ScooterSettingsWriter.statusWordWrite(word)

        assertArrayEquals(byteArrayOf(0x00, 0x12), written.payload)
        // A little-endian encoding of the same word would be 12 00.
        assertNotEquals(
            ScooterSettingsWriter.statusWordWrite(word).describe(),
            "reg 0x7D <- 12 00",
        )
        // And the read path interprets 00 12 little-endian, i.e. as 0x1200.
        assertEquals(0x1200, EscTelemetryParser.u16(written.payload, 0))
    }

    @Test
    fun `enabling the tail light sets bit one and preserves the rest`() {
        // Start with bit 4 (mph) set and bit 1 clear.
        val read = EscTelemetryParser.MPH_BIT
        val write = ScooterSettingsWriter.setTailLight(read, on = true)

        val word = beWord(write.payload)
        assertTrue("tail light bit must be set", (word and EscTelemetryParser.TAIL_LIGHT_BIT) != 0)
        assertTrue("the mph bit must survive", (word and EscTelemetryParser.MPH_BIT) != 0)
    }

    @Test
    fun `disabling the tail light clears only bit one`() {
        val read = EscTelemetryParser.MPH_BIT or EscTelemetryParser.TAIL_LIGHT_BIT
        val write = ScooterSettingsWriter.setTailLight(read, on = false)

        val word = beWord(write.payload)
        assertFalse("tail light bit must be clear", (word and EscTelemetryParser.TAIL_LIGHT_BIT) != 0)
        assertTrue("the mph bit must survive", (word and EscTelemetryParser.MPH_BIT) != 0)
    }

    @Test
    fun `changing units preserves the tail light bit`() {
        val read = EscTelemetryParser.TAIL_LIGHT_BIT
        val write = ScooterSettingsWriter.setUnits(read, ScooterSettingsWriter.Units.MPH)

        val word = beWord(write.payload)
        assertTrue("mph bit must be set", (word and EscTelemetryParser.MPH_BIT) != 0)
        assertTrue("the tail light bit must survive", (word and EscTelemetryParser.TAIL_LIGHT_BIT) != 0)
    }

    @Test
    fun `selecting kmh clears only the mph bit`() {
        val read = EscTelemetryParser.MPH_BIT or EscTelemetryParser.TAIL_LIGHT_BIT
        val write = ScooterSettingsWriter.setUnits(read, ScooterSettingsWriter.Units.KMH)

        val word = beWord(write.payload)
        assertFalse("mph bit must be clear", (word and EscTelemetryParser.MPH_BIT) != 0)
        assertTrue("the tail light bit must survive", (word and EscTelemetryParser.TAIL_LIGHT_BIT) != 0)
    }

    @Test
    fun `a status write never exceeds sixteen bits`() {
        // A value wider than the word would need a third byte, which this register
        // does not have; masking is what keeps it in range.
        val write = ScooterSettingsWriter.statusWordWrite(0xFFFF_FFFF.toInt())

        assertEquals(2, write.payload.size)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), write.payload)
    }

    // ---------------------------------------------------------- reversibility

    @Test
    fun `the tail light undo restores the original word`() {
        val original = 0x0022 // bit 1 and bit 5
        val reversible = ScooterSettingsWriter.setTailLightReversible(original, on = false)

        val undone = beWord(reversible.undo.payload)
        assertTrue(
            "undo must restore the tail light bit",
            (undone and EscTelemetryParser.TAIL_LIGHT_BIT) != 0,
        )
    }

    @Test
    fun `the units undo restores the original unit`() {
        val wasMph = EscTelemetryParser.MPH_BIT
        val reversible = ScooterSettingsWriter.setUnitsReversible(wasMph, ScooterSettingsWriter.Units.KMH)

        val undone = beWord(reversible.undo.payload)
        assertTrue("undo must restore the mph bit", (undone and EscTelemetryParser.MPH_BIT) != 0)
    }

    @Test
    fun `kers and cruise reversibility is explicit`() {
        // These carry their previous value rather than deriving it, so the caller
        // must have read it first; the undo is just the other setting.
        val kers = ScooterSettingsWriter.setKersReversible(
            previous = ScooterSettingsWriter.Kers.WEAK,
            level = ScooterSettingsWriter.Kers.STRONG,
        )
        assertArrayEquals(byteArrayOf(0x02, 0x00), kers.write.payload)
        assertArrayEquals(byteArrayOf(0x00, 0x00), kers.undo.payload)

        val cruise = ScooterSettingsWriter.setCruiseReversible(previousEngaged = true, engaged = false)
        assertArrayEquals(byteArrayOf(0x00, 0x00), cruise.write.payload)
        assertArrayEquals(byteArrayOf(0x01, 0x00), cruise.undo.payload)
    }

    // ------------------------------------------------------------- read-back

    @Test
    fun `a kers read-back is verified against the written code`() {
        val write = ScooterSettingsWriter.setKers(ScooterSettingsWriter.Kers.STRONG)

        assertTrue(ScooterSettingsWriter.verify(write, byteArrayOf(0x02)))
        assertFalse(ScooterSettingsWriter.verify(write, byteArrayOf(0x01)))
        // A silent no-op must not read as success.
        assertFalse(ScooterSettingsWriter.verify(write, ByteArray(0)))
    }

    @Test
    fun `a cruise read-back is verified`() {
        val write = ScooterSettingsWriter.setCruise(true)

        assertTrue(ScooterSettingsWriter.verify(write, byteArrayOf(0x01)))
        assertFalse(ScooterSettingsWriter.verify(write, byteArrayOf(0x00)))
    }

    @Test
    fun `a status read-back is verified through the little endian read path`() {
        // Register 0x7D is READ little-endian but WRITTEN big-endian. So a write of
        // word 0x0012 goes out as bytes 00 12, and `verifyStatusWord` reads those
        // same bytes back little-endian as 0x1200 — then compares against the
        // big-endian reassembly of its own payload, which is 0x0012.
        val write = ScooterSettingsWriter.statusWordWrite(0x0012)

        // The write payload is unambiguously big-endian.
        assertArrayEquals(byteArrayOf(0x00, 0x12), write.payload)
        assertEquals(0x0012, beWord(write.payload))

        // Read back, those two bytes are 0x1200.
        assertEquals(0x1200, EscTelemetryParser.u16(byteArrayOf(0x00, 0x12), 0))

        // A device that echoes exactly what was written therefore does NOT verify,
        // because the two ends disagree about endianness. That asymmetry is real
        // and is precisely what the read-back check exists to reveal.
        assertFalse(
            "an echo of the written bytes must not verify",
            ScooterSettingsWriter.verifyStatusWord(write, byteArrayOf(0x00, 0x12)),
        )

        // To verify, the device must report a word whose little-endian reading
        // equals 0x0012, i.e. the bytes 12 00.
        assertTrue(
            "bytes 12 00 read little-endian are 0x0012",
            ScooterSettingsWriter.verifyStatusWord(write, byteArrayOf(0x12, 0x00)),
        )
    }

    @Test
    fun `verify rejects an unknown register`() {
        val write = ScooterSettingsWriter.Write(0x99, byteArrayOf(1, 2))

        assertFalse(ScooterSettingsWriter.verify(write, byteArrayOf(1, 2)))
    }

    @Test
    fun `a too short status read-back fails rather than throwing`() {
        val write = ScooterSettingsWriter.statusWordWrite(0x0002)

        assertFalse(ScooterSettingsWriter.verify(write, byteArrayOf(0x01)))
    }

    // ---------------------------------------------------------------- value

    @Test
    fun `writes compare by content not identity`() {
        // ByteArray gives identity equality, so without the override two identical
        // writes would differ and de-duplication or assertions would misbehave.
        val a = ScooterSettingsWriter.setKers(ScooterSettingsWriter.Kers.MEDIUM)
        val b = ScooterSettingsWriter.setKers(ScooterSettingsWriter.Kers.MEDIUM)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, ScooterSettingsWriter.setKers(ScooterSettingsWriter.Kers.WEAK))
    }

    @Test
    fun `describe renders the register and payload`() {
        val text = ScooterSettingsWriter.setKers(ScooterSettingsWriter.Kers.STRONG).describe()

        assertTrue(text.contains("0x7B"))
        assertTrue(text.contains("02"))
    }
}
