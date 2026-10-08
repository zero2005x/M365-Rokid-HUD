package io.github.zero2005x.pev.core.codec.begode

import io.github.zero2005x.pev.core.codec.begode.BegodeA2Codec.Event
import io.github.zero2005x.pev.core.codec.begode.BegodeA2Codec.Profile
import io.github.zero2005x.pev.core.codec.begode.BegodeA2Codec.RawBody
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic envelope vectors only. No test here validates A2 physical units or behavior. */
class BegodeA2CodecTest {
    private fun frame(type: Int = 0, discriminator: Int = 24, vararg words: Int): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 0x55
        bytes[1] = 0xAA.toByte()
        for (slot in 0..7) {
            val word = words.getOrElse(slot) { slot + 1 }
            bytes[2 + slot * 2] = (word ushr 8).toByte()
            bytes[3 + slot * 2] = word.toByte()
        }
        bytes[18] = type.toByte()
        bytes[19] = discriminator.toByte()
        for (offset in 20..23) bytes[offset] = 0x5A
        return bytes
    }

    private fun frames(events: List<Event>) = events.filterIsInstance<Event.Frame>().map { it.diagnostic }

    @Test
    fun everySplitBoundaryAndBytewiseFeedYieldsExactlyOneUnmodifiedFrame() {
        val bytes = frame()
        for (split in 0..24) {
            val codec = BegodeA2Codec(Profile.A2)
            val events = codec.feed(bytes.copyOfRange(0, split)) + codec.feed(bytes.copyOfRange(split, 24))
            assertEquals(1, events.size)
            assertTrue(frames(events).single().raw.contentEquals(bytes))
        }
        val codec = BegodeA2Codec(Profile.A2, maxBuffer = 24)
        val events = bytes.flatMap { codec.feed(byteArrayOf(it)) }
        assertEquals(1, events.size)
        assertTrue(frames(events).single().raw.contentEquals(bytes))
        assertTrue(codec.feed(byteArrayOf()).isEmpty())
    }

    @Test
    fun coalescedFramesAndTwentyByteNotificationPatternPreserveAllFourBranches() {
        val bytes = frame(0, 24) + frame(1, 0) + frame(4, 24) + frame(7, 24)
        val coalesced = frames(BegodeA2Codec(Profile.A2).feed(bytes))
        assertEquals(listOf(0, 1, 4, 7), coalesced.map { it.type })
        assertEquals(listOf(24, 0, 24, 24), coalesced.map { it.discriminator })
        assertTrue(coalesced[0].body is RawBody.Live)
        assertEquals(RawBody.BatteryUnresolved, coalesced[1].body)
        assertTrue(coalesced[2].body is RawBody.Settings)
        assertTrue(coalesced[3].body is RawBody.Advanced)
        val codec = BegodeA2Codec(Profile.A2)
        val fragmented = (0 until 96 step 20).flatMap { offset ->
            codec.feed(bytes.copyOfRange(offset, minOf(offset + 20, 96)))
        }
        assertEquals(listOf(0, 1, 4, 7), frames(fragmented).map { it.type })
        assertEquals(4, fragmented.size)
    }

    @Test
    fun liveChannelsRemainSeparateBigEndianWordsWithSignedRawFields() {
        val words = intArrayOf(0xF123, 0x8000, 0x1234, 0xABCD, 0xFFFF, 0x7FFF, 0xBEEF, 0xFEDC)
        val diagnostic = frames(BegodeA2Codec(Profile.A2).feed(frame(0, 24, *words))).single()
        assertEquals(words.toList(), diagnostic.channels)
        val live = diagnostic.body as RawBody.Live
        assertEquals(0xF123, live.voltageWord)
        assertEquals(-32768, live.speedRaw)
        assertEquals(0x1234, live.rangeWord)
        assertEquals(0xABCD, live.distanceWord)
        assertEquals(-1, live.currentRaw)
        assertEquals(32767, live.temperatureRaw)
        assertEquals(0xBEEF, live.modeWord)
        assertEquals(0xFEDC, live.flagsWord)
        assertEquals(Evidence.SYNTHETIC, diagnostic.inputEvidence)
        assertEquals(Evidence.WIRE_CAPTURED, diagnostic.layoutEvidence)
        assertTrue(diagnostic.telemetry.fields.isEmpty())
        FieldId.entries.forEach { assertEquals(FieldState.NOT_PROVIDED, diagnostic.telemetry[it].state) }
    }

    @Test
    fun settingsDistinguishesStateHighByteFromHeadlightLowByteWithoutInventingIdentity() {
        val diagnostic = frames(BegodeA2Codec(Profile.A2).feed(frame(4, 24, 1, 2, 3, 4, 5, 6, 0xABCD, 18))).single()
        val settings = diagnostic.body as RawBody.Settings
        assertEquals(1, settings.odometerHighWord)
        assertEquals(2, settings.odometerLowWord)
        assertEquals(3, settings.settingsWord)
        assertEquals(4, settings.timerWord)
        assertEquals(5, settings.pedalSensitivityWord)
        assertEquals(6, settings.ambientModeWord)
        assertEquals(0xAB, settings.stateByte)
        assertEquals(0xCD, settings.headlightByte)
        assertEquals(18, settings.vehicleTypeWord)
        assertEquals(Profile.A2, diagnostic.profile)
        assertTrue(diagnostic.telemetry.fields.isEmpty())
    }

    @Test
    fun advancedRawFieldsKeepTheirOwnChannelsAndSignedness() {
        val diagnostic = frames(BegodeA2Codec(Profile.A2).feed(frame(7, 24, 0xFFFF, 0xFEDC, 0x8000, 0x7FFF, 5, 6, 7, 8))).single()
        val advanced = diagnostic.body as RawBody.Advanced
        assertEquals(-1, advanced.phaseCurrentRaw)
        assertEquals(0xFEDC, advanced.configurationWord)
        assertEquals(-32768, advanced.motorTemperatureRaw)
        assertEquals(32767, advanced.pwmRaw)
        assertEquals(listOf(5, 6, 7, 8), advanced.reservedWords)
        assertTrue(diagnostic.telemetry.fields.isEmpty())
    }

    @Test
    fun unknownProfileAndOtherTypeDiscriminatorPairsNeverAcquireInterpretations() {
        for ((type, discriminator) in listOf(0 to 0, 1 to 24, 4 to 0, 7 to 0, 255 to 24, 0 to 255)) {
            val diagnostic = frames(BegodeA2Codec(Profile.A2).feed(frame(type, discriminator))).single()
            assertEquals(RawBody.Unresolved, diagnostic.body)
            assertEquals(type, diagnostic.type)
            assertEquals(discriminator, diagnostic.discriminator)
            assertEquals(8, diagnostic.channels.size)
            assertTrue(diagnostic.telemetry.fields.isEmpty())
        }
        for ((type, discriminator) in listOf(0 to 24, 1 to 0, 4 to 24, 7 to 24)) {
            val diagnostic = frames(BegodeA2Codec(Profile.UNKNOWN).feed(frame(type, discriminator))).single()
            assertEquals(Profile.UNKNOWN, diagnostic.profile)
            assertEquals(RawBody.Unresolved, diagnostic.body)
            assertTrue(diagnostic.telemetry.fields.isEmpty())
        }
    }

    @Test
    fun noiseAndEveryCorruptTailByteResynchronizeToTheNextFrame() {
        val good = frame()
        val ascii = "GWsynthetic".toByteArray(Charsets.US_ASCII)
        val noisy = BegodeA2Codec(Profile.A2).feed(ascii + good)
        assertEquals(ascii.size, noisy.filterIsInstance<Event.Malformed>().size)
        assertTrue(frames(noisy).single().raw.contentEquals(good))
        assertTrue(noisy.filterIsInstance<Event.Malformed>().flatMap { it.raw.toList() }.toByteArray().contentEquals(ascii))
        for (offset in 20..23) {
            val bad = good.copyOf().also { it[offset] = 0 }
            val events = BegodeA2Codec(Profile.A2).feed(bad + good)
            val malformed = events.filterIsInstance<Event.Malformed>()
            assertEquals(24, malformed.size)
            assertEquals("bad tail", malformed.first().reason)
            assertTrue(malformed.flatMap { it.raw.toList() }.toByteArray().contentEquals(bad))
            assertTrue(frames(events).single().raw.contentEquals(good))
        }
    }

    @Test
    fun malformedHeaderPartialHeaderAndEmbeddedResyncCandidateAreHandled() {
        val good = frame()
        val codec = BegodeA2Codec(Profile.A2)
        assertTrue(codec.feed(byteArrayOf(0x55)).isEmpty())
        val events = codec.feed(byteArrayOf(0x55) + good.copyOfRange(1, 24))
        assertEquals(1, events.filterIsInstance<Event.Malformed>().size)
        assertEquals("bad header", (events.first() as Event.Malformed).reason)
        assertTrue(frames(events).single().raw.contentEquals(good))

        val bad = good.copyOf().also { it[20] = 0; it[5] = 0x55; it[6] = 0xAA.toByte() }
        assertTrue(frames(BegodeA2Codec(Profile.A2).feed(bad + good)).single().raw.contentEquals(good))
    }

    @Test
    fun resetDiscardsPartialFramesAcrossSessions() {
        val good = frame()
        val codec = BegodeA2Codec(Profile.A2)
        assertTrue(codec.feed(good.copyOfRange(0, 12)).isEmpty())
        codec.reset()
        assertTrue(frames(codec.feed(good.copyOfRange(12, 24))).isEmpty())
        assertTrue(frames(codec.feed(good)).single().raw.contentEquals(good))
        codec.reset()
        assertTrue(codec.feed(byteArrayOf()).isEmpty())
    }

    @Test
    fun smallHostBudgetReportsOverflowWithoutUnboundedAccumulation() {
        val codec = BegodeA2Codec(Profile.A2, maxBuffer = 8)
        val prefix = frame().copyOfRange(0, 8)
        val events = codec.feed(prefix + prefix + prefix)
        assertEquals(2, events.size)
        assertTrue(events.all { it is Event.Overflow && it.dropped == 8 })
        codec.reset()
        assertTrue(codec.feed(byteArrayOf()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroHostBudgetIsRejected() {
        BegodeA2Codec(Profile.A2, maxBuffer = 0)
    }

    @Test
    fun callerLabelsInputEvidenceWithoutChangingRawDecodeOrLayoutScope() {
        val diagnostic = frames(BegodeA2Codec(Profile.A2, Evidence.WIRE_CAPTURED).feed(frame())).single()
        assertEquals(Evidence.WIRE_CAPTURED, diagnostic.inputEvidence)
        assertEquals(Evidence.WIRE_CAPTURED, diagnostic.layoutEvidence)
        assertTrue(diagnostic.telemetry.fields.isEmpty())
    }
}
