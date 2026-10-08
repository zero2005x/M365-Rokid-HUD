package io.github.zero2005x.pev.core.codec.inmotioni1

import io.github.zero2005x.pev.core.telemetry.Evidence
import org.junit.Assert.*
import org.junit.Test

/** Independently authored synthetic framing contracts; no hardware or physical-scale oracle. */
class InmotionI1CodecTest {
    private val standard = hex("AA AA 01 01 06 0F 01 02 03 04 05 06 07 08 08 05 01 00 49 55 55")

    @Test fun literalStandardVectorHasUnsignedIdAndRawCanData() {
        val frame = frames(InmotionI1Codec().feed(standard)).single()
        assertEquals(0x0F060101L, frame.canId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), frame.canData)
        assertEquals(8, frame.lengthCode)
        assertEquals(5, frame.channel)
        assertEquals(1, frame.format)
        assertEquals(0, frame.type)
        assertFalse(frame.checksumEscaped)
        assertNull(frame.extendedData)
        assertTrue(frame.telemetry.fields.isEmpty())
        assertEquals(Evidence.SYNTHETIC, frame.inputEvidence)
    }

    @Test fun everyCutIncludingAnEscapePairProducesExactlyOneExtendedFrame() {
        val data = byteArrayOf(0xAA.toByte(), 0x55, 0xA5.toByte(), 0, 1)
        val wire = packet(extension = data)
        for (cut in 0..wire.size) {
            val codec = InmotionI1Codec(inputEvidence = Evidence.VENDOR_STATIC)
            val events = codec.feed(wire.copyOfRange(0, cut)) + codec.feed(wire.copyOfRange(cut, wire.size))
            val frame = frames(events).single()
            assertArrayEquals(data, frame.extendedData)
            assertEquals(Evidence.VENDOR_STATIC, frame.inputEvidence)
            assertFalse(events.any { it is InmotionI1Codec.Event.Malformed })
        }
    }

    @Test fun coalescedAndBytewiseNotificationsAreEquivalent() {
        val wire = standard + packet(extension = ByteArray(96) { it.toByte() }) + standard
        val codec = InmotionI1Codec()
        val bytewise = wire.flatMap { codec.feed(byteArrayOf(it)) }
        val coalesced = InmotionI1Codec().feed(wire)
        assertEquals(3, frames(bytewise).size)
        frames(bytewise).zip(frames(coalesced)).forEach { (a, b) -> assertArrayEquals(a.raw, b.raw) }
    }

    @Test fun rawAndEscapedReservedChecksumsHaveUnambiguousBoundaries() {
        for (checksum in listOf(0x55, 0xAA, 0xA5)) {
            for (escapeCheck in listOf(false, true)) {
                val wire = packet(checksum = checksum, escapeCheck = escapeCheck)
                for (cut in 0..wire.size) {
                    val codec = InmotionI1Codec()
                    val first = codec.feed(wire.copyOfRange(0, cut))
                    val second = codec.feed(wire.copyOfRange(cut, wire.size) + standard)
                    val decoded = frames(first + second)
                    assertEquals(2, decoded.size)
                    assertEquals(escapeCheck, decoded.first().checksumEscaped)
                    assertArrayEquals(wire, decoded.first().raw)
                }
            }
        }
    }

    @Test fun truncatedFramesCannotCreateTelemetryOrLeakAcrossReset() {
        for (cut in 1 until standard.size) {
            val codec = InmotionI1Codec()
            assertTrue(codec.feed(standard.copyOfRange(0, cut)).isEmpty())
            codec.reset()
            assertEquals(1, frames(codec.feed(standard)).size)
        }
    }

    @Test fun noiseBadEscapesAndUnescapedDelimitersResynchronize() {
        val badEscape = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0xA5.toByte(), 0x20)
        val unescaped = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x55)
        val events = InmotionI1Codec().feed(byteArrayOf(0, 0xAA.toByte(), 0) + badEscape + unescaped + standard)
        assertEquals(1, frames(events).size)
        val malformed = events.filterIsInstance<InmotionI1Codec.Event.Malformed>()
        assertTrue(malformed.any { it.reason == "invalid escape pair" })
        assertTrue(malformed.any { it.reason == "unescaped body delimiter" })
        assertTrue(malformed.any { it.reason == "bad preamble" })
    }

    @Test fun eachCorruptedChecksumOrTrailerIsRejectedBeforeNextFrame() {
        for (index in standard.size - 3 until standard.size) {
            val corrupt = standard.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            val events = InmotionI1Codec().feed(corrupt + standard)
            assertEquals(1, frames(events).size)
            assertTrue(events.any { it is InmotionI1Codec.Event.Malformed })
        }
    }

    @Test fun incorrectEscapedChecksumIsRejected() {
        val wire = packet(checksum = 0x55)
        wire[wire.size - 3] = 0x54
        val events = InmotionI1Codec().feed(wire + standard)
        assertEquals(1, frames(events).size)
        assertTrue(events.any { it is InmotionI1Codec.Event.Malformed })
    }

    @Test fun zeroAndMaximumConfiguredExtensionsAreAccepted() {
        for (length in listOf(0, 32)) {
            val data = ByteArray(length) { 7 }
            val frame = frames(InmotionI1Codec(maxExtendedBytes = 32).feed(packet(extension = data))).single()
            assertArrayEquals(data, frame.extendedData)
        }
    }

    @Test fun oversizedAndUnsignedExtensionLengthsDoNotAllocateFromWire() {
        for (length in listOf(33L, 0x80000000L, 0xFFFFFFFFL)) {
            val header = body(extension = byteArrayOf()).also { bytes ->
                for (index in 0..3) bytes[4 + index] = (length ushr (index * 8)).toByte()
            }
            val events = InmotionI1Codec(maxExtendedBytes = 32).feed(wrap(header) + standard)
            assertEquals(1, frames(events).size)
            assertTrue(events.filterIsInstance<InmotionI1Codec.Event.Malformed>().any {
                it.reason == "extended data exceeds budget"
            })
        }
    }

    @Test fun invalidBudgetsAreRejectedAndSmallBufferReportsOverflow() {
        for (budget in listOf(-1, 16385)) {
            assertThrows(IllegalArgumentException::class.java) { InmotionI1Codec(maxExtendedBytes = budget) }
        }
        assertThrows(IllegalArgumentException::class.java) { InmotionI1Codec(maxBuffer = 0) }
        val events = InmotionI1Codec(maxBuffer = 4).feed(standard)
        assertTrue(events.any { it is InmotionI1Codec.Event.Overflow })
        assertTrue(frames(events).isEmpty())
    }

    @Test fun diagnosticBytesDoNotAliasInputOrFuturePackets() {
        val input = standard.copyOf()
        val codec = InmotionI1Codec()
        val frame = frames(codec.feed(input)).single()
        input.fill(0)
        frame.raw.fill(0)
        frame.canData.fill(0)
        assertArrayEquals(standard, frame.raw)
        val data = byteArrayOf(5, 6, 7)
        val extended = frames(codec.feed(packet(extension = data))).single()
        extended.extendedData?.fill(0)
        assertArrayEquals(data, extended.extendedData)
        assertArrayEquals(standard, frame.raw)
    }

    @Test fun differentChannelAndUnknownIdRemainStructuralDiagnostics() {
        val body = body().also {
            it[0] = 0xFF.toByte(); it[1] = 0xFF.toByte(); it[2] = 0xFF.toByte(); it[3] = 0xFF.toByte()
            it[13] = 0xFF.toByte(); it[14] = 0xEE.toByte(); it[15] = 0xDD.toByte()
        }
        val frame = frames(InmotionI1Codec().feed(wrap(body))).single()
        assertEquals(0xFFFFFFFFL, frame.canId)
        assertEquals(8, frame.lengthCode)
        assertEquals(255, frame.channel)
        assertEquals(238, frame.format)
        assertEquals(221, frame.type)
        assertNull(frame.extendedData)
        assertTrue(frame.telemetry.fields.isEmpty())
    }

    @Test fun unrecognizedLengthCodesAreRejectedWithoutGuessingTheirBodySize() {
        for (length in listOf(0, 7, 9, 255)) {
            val unsupported = body().also { it[12] = length.toByte() }
            val events = InmotionI1Codec().feed(wrap(unsupported) + standard)
            assertEquals(1, frames(events).size)
            assertTrue(events.filterIsInstance<InmotionI1Codec.Event.Malformed>().any {
                it.reason == "unsupported length code"
            })
        }
    }

    @Test fun largeGarbageAndCoalescedStreamsStayBounded() {
        val codec = InmotionI1Codec(maxBuffer = 24)
        val stream = ByteArray(10_000) { 0 } + List(100) { standard }.reduce(ByteArray::plus)
        val events = codec.feed(stream)
        assertEquals(100, frames(events).size)
        assertFalse(events.any { it is InmotionI1Codec.Event.Overflow })
    }

    @Test fun sharedMagicDoesNotTurnAnI2XorEnvelopeIntoI1Telemetry() {
        val i2 = hex("AA AA 14 02 04 00 12")
        val events = InmotionI1Codec().feed(i2 + standard)
        assertEquals(1, frames(events).size)
        assertArrayEquals(standard, frames(events).single().raw)
        assertTrue(frames(events).single().telemetry.fields.isEmpty())
    }

    private fun frames(events: List<InmotionI1Codec.Event>): List<InmotionI1Codec.Packet> =
        events.filterIsInstance<InmotionI1Codec.Event.Frame>().map { it.packet }

    private fun packet(extension: ByteArray? = null, checksum: Int? = null, escapeCheck: Boolean = true): ByteArray {
        val bytes = body(extension)
        if (checksum != null) {
            val current = bytes.sumOf { it.toInt() and 0xFF } and 0xFF
            bytes[11] = ((bytes[11].toInt() and 0xFF) + checksum - current).toByte()
        }
        return wrap(bytes, escapeCheck)
    }

    private fun body(extension: ByteArray? = null): ByteArray {
        val bytes = hex("13 01 55 0F 00 00 00 00 00 00 00 00 08 05 01 00")
        if (extension == null) return bytes
        bytes[12] = 0xFE.toByte()
        for (index in 0..3) bytes[4 + index] = (extension.size ushr (index * 8)).toByte()
        return bytes + extension
    }

    private fun wrap(body: ByteArray, escapeCheck: Boolean = true): ByteArray {
        val bytes = ArrayList<Byte>()
        bytes += listOf(0xAA.toByte(), 0xAA.toByte())
        fun append(value: Byte, escape: Boolean) {
            if (escape && (value.toInt() and 0xFF) in listOf(0xAA, 0x55, 0xA5)) bytes += 0xA5.toByte()
            bytes += value
        }
        body.forEach { append(it, true) }
        append((body.sumOf { it.toInt() and 0xFF } and 0xFF).toByte(), escapeCheck)
        bytes += listOf(0x55, 0x55)
        return bytes.toByteArray()
    }

    private fun hex(value: String): ByteArray = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
