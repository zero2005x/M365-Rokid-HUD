package com.m365bleapp.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PlaintextRegisterSession].
 *
 * These are the first tests in the project that exercise [FrameCodec] through a
 * real consumer rather than in isolation, which is the whole point of the class:
 * before it existed, `FrameCodec` had 19 passing tests and zero call sites.
 *
 * The BLE write is injected, so every path here runs without a scooter.
 */
class PlaintextRegisterSessionTest {

    /** Records what the session tried to send and answers with canned frames. */
    private class FakeLink(private val reply: ByteArray) : PlaintextRegisterSession.Write {
        val sent = mutableListOf<ByteArray>()
        override suspend fun send(frame: ByteArray) {
            sent.add(frame)
        }

        fun lastSent(): ByteArray = sent.last()
        fun replyBytes(): ByteArray = reply
    }

    /**
     * Builds a synthetic reply for [register] in [protocol].
     *
     * Uses [FrameCodec.encodeRequest] and then flips the frame into reply shape
     * by swapping source and destination. The reply *argument* differs per
     * vendor, which is exactly what [FrameCodec.isReplyFor] keys on, so the tests
     * below assert both conventions rather than assuming one.
     */
    private fun replyFrame(
        protocol: FrameCodec.Protocol,
        register: Byte,
        argument: Byte,
        payload: ByteArray = ByteArray(0),
        source: Byte = PlaintextRegisterSession.ADDRESS_ESC,
        destination: Byte = PlaintextRegisterSession.DEFAULT_SOURCE,
    ): ByteArray = FrameCodec.encodeRequest(
        protocol = protocol,
        source = source,
        destination = destination,
        register = register,
        argument = argument,
        payload = payload,
    )

    private fun session(
        protocol: FrameCodec.Protocol = FrameCodec.Protocol.P2,
        link: FakeLink,
    ) = PlaintextRegisterSession(protocol = protocol, write = link)

    // ------------------------------------------------------------ framing

    @Test
    fun `buildRead produces a frame FrameCodec can decode back`() {
        val link = FakeLink(ByteArray(0))
        val subject = session(link = link)

        val frame = subject.buildRead(register = 0xB0.toByte(), argument = 0x20)

        val decoded = FrameCodec.decode(frame)
        assertEquals(0xB0.toByte(), decoded.command)
        assertEquals(0x20.toByte(), decoded.argument)
        assertEquals(PlaintextRegisterSession.ADDRESS_ESC, decoded.destination)
        assertEquals(FrameCodec.Protocol.P2, decoded.protocol)
    }

    @Test
    fun `P1 and P2 produce different frames for the same read`() {
        // If these ever match, the generation is not actually being honoured and
        // one family of scooters would silently receive the wrong framing.
        val p1 = session(FrameCodec.Protocol.P1, FakeLink(ByteArray(0)))
            .buildRead(0x25.toByte(), 0x02)
        val p2 = session(FrameCodec.Protocol.P2, FakeLink(ByteArray(0)))
            .buildRead(0x25.toByte(), 0x02)

        assertFalse(p1.contentEquals(p2))
        assertEquals(FrameCodec.SYNC_1_HI, p1[0])
        assertEquals(FrameCodec.SYNC_2_HI, p2[0])
    }

    // ------------------------------------------------------- readRegister

    @Test
    fun `readRegister sends the request then returns the matching reply`() = runBlocking {
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val reply = replyFrame(
            protocol = FrameCodec.Protocol.P2,
            register = 0xB0.toByte(),
            argument = FrameCodec.REPLY_ARG_NINEBOT,
            payload = payload,
        )
        val link = FakeLink(reply)
        val subject = session(link = link)

        val frame = subject.readRegister(0xB0.toByte(), 0x20) { link.replyBytes() }

        assertNotNull(frame)
        assertArrayEquals(payload, frame!!.payload)
        // The request must have gone out before the reply was inspected.
        assertEquals(1, link.sent.size)
        val sentDecoded = FrameCodec.decode(link.lastSent())
        assertEquals(0xB0.toByte(), sentDecoded.command)
    }

    @Test
    fun `readRegister accepts a Xiaomi reply argument too`() = runBlocking {
        // The whole reason FrameCodec.isReplyFor exists: hard-coding one vendor's
        // reply byte makes the parser blind to the other.
        val reply = replyFrame(
            protocol = FrameCodec.Protocol.P1,
            register = 0x25.toByte(),
            argument = FrameCodec.REPLY_ARG_XIAOMI,
            payload = byteArrayOf(0x0A, 0x00),
        )
        val link = FakeLink(reply)
        val subject = session(FrameCodec.Protocol.P1, link)

        val frame = subject.readRegister(0x25.toByte(), 0x02) { link.replyBytes() }

        assertNotNull(frame)
        assertEquals(FrameCodec.ReplyKind.XIAOMI, frame!!.replyKind)
    }

    @Test
    fun `readRegister returns null for a reply to a different register`() = runBlocking {
        // A stale notification from the previous request is the common real-world
        // case. Returning null is what stops it being parsed as this reply.
        val staleReply = replyFrame(
            protocol = FrameCodec.Protocol.P2,
            register = 0x29.toByte(),
            argument = FrameCodec.REPLY_ARG_NINEBOT,
        )
        val link = FakeLink(staleReply)
        val subject = session(link = link)

        val frame = subject.readRegister(0xB0.toByte(), 0x20) { link.replyBytes() }

        assertNull(frame)
        // The request was still sent; only the reply was rejected.
        assertEquals(1, link.sent.size)
    }

    @Test
    fun `readRegister propagates a malformed reply instead of guessing`() {
        // JUnit requires a void test method, so the assertions run inside
        // runBlocking rather than returning assertThrows' value.
        runBlocking {
            val corrupt = replyFrame(
                protocol = FrameCodec.Protocol.P2,
                register = 0xB0.toByte(),
                argument = FrameCodec.REPLY_ARG_NINEBOT,
                payload = byteArrayOf(1, 2, 3, 4),
            )
            // Flip one payload byte so the checksum no longer matches.
            corrupt[corrupt.size - 3] = (corrupt[corrupt.size - 3].toInt() xor 0xFF).toByte()
            val link = FakeLink(corrupt)
            val subject = session(link = link)

            assertThrows(FrameCodec.FrameException::class.java) {
                runBlocking { subject.readRegister(0xB0.toByte(), 0x20) { link.replyBytes() } }
            }
        }
    }

    // ------------------------------------------------------- reassembly

    @Test
    fun `reassemble concatenates fragments in order`() {
        val subject = session(link = FakeLink(ByteArray(0)))

        val joined = subject.reassemble(
            listOf(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4, 5, 6))
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), joined)
    }

    @Test
    fun `reassemble of an empty list is empty`() {
        val subject = session(link = FakeLink(ByteArray(0)))
        assertEquals(0, subject.reassemble(emptyList()).size)
    }

    @Test
    fun `declaredTotalLength matches a real encoded frame`() {
        val subject = session(link = FakeLink(ByteArray(0)))
        val frame = subject.buildRead(0xB0.toByte(), 0x20)

        // A read has no payload: 2 sync + 1 len + 4 counted + 2 checksum = 9.
        assertEquals(frame.size, subject.declaredTotalLength(frame))
    }

    @Test
    fun `declaredTotalLength accounts for a payload`() {
        val subject = session(link = FakeLink(ByteArray(0)))
        val payload = ByteArray(12) { it.toByte() }
        val frame = replyFrame(
            protocol = FrameCodec.Protocol.P2,
            register = 0x31.toByte(),
            argument = FrameCodec.REPLY_ARG_NINEBOT,
            payload = payload,
        )

        assertEquals(frame.size, subject.declaredTotalLength(frame))
    }

    @Test
    fun `declaredTotalLength returns null before the length byte arrives`() {
        val subject = session(link = FakeLink(ByteArray(0)))

        assertNull(subject.declaredTotalLength(ByteArray(0)))
        assertNull(subject.declaredTotalLength(byteArrayOf(0x5A)))
        // P2 needs three bytes before the length byte is present.
        assertNull(subject.declaredTotalLength(byteArrayOf(0x5A, 0xA5.toByte())))
        assertNotNull(subject.declaredTotalLength(byteArrayOf(0x5A, 0xA5.toByte(), 0x3E, 0x04)))
    }

    @Test
    fun `isComplete tracks partial fragments until the whole frame is present`() {
        val subject = session(link = FakeLink(ByteArray(0)))
        val frame = subject.buildRead(0xB0.toByte(), 0x20)

        // P2 needs four bytes before its length byte is even present, so a
        // three-byte prefix is "unknown", not "incomplete".
        assertNull(subject.isComplete(frame.copyOfRange(0, 3)))
        assertFalse(requireNotNull(subject.isComplete(frame.copyOfRange(0, 4))))
        assertFalse(requireNotNull(subject.isComplete(frame.copyOfRange(0, frame.size - 1))))
        assertTrue(requireNotNull(subject.isComplete(frame)))
    }

    @Test
    fun `isComplete is unknown before the length byte`() {
        val subject = session(link = FakeLink(ByteArray(0)))
        assertNull(subject.isComplete(byteArrayOf(0x5A)))
    }

    @Test
    fun `a P1 frame declares its length one byte earlier than P2`() {
        // P1 has no BT_ID, so three bytes are already enough to read the length.
        // Getting this wrong would stall reassembly on one generation only.
        val p1 = session(FrameCodec.Protocol.P1, FakeLink(ByteArray(0)))
        val p1Frame = p1.buildRead(0xB0.toByte(), 0x20)
        assertNotNull(p1.declaredTotalLength(p1Frame.copyOfRange(0, 3)))

        val p2 = session(FrameCodec.Protocol.P2, FakeLink(ByteArray(0)))
        val p2Frame = p2.buildRead(0xB0.toByte(), 0x20)
        assertNull(p2.declaredTotalLength(p2Frame.copyOfRange(0, 3)))
        assertNotNull(p2.declaredTotalLength(p2Frame.copyOfRange(0, 4)))
    }

    // ------------------------------------------------------------ guards

    @Test
    fun `identical source and destination is rejected at construction`() {
        // Both being 0x3E would produce a frame no scooter answers, and the
        // failure would look like a dead link rather than a coding mistake.
        assertThrows(IllegalArgumentException::class.java) {
            PlaintextRegisterSession(
                protocol = FrameCodec.Protocol.P2,
                source = 0x20,
                destination = 0x20,
                write = FakeLink(ByteArray(0)),
            )
        }
    }

    @Test
    fun `a read can target the BMS instead of the ESC`() = runBlocking {
        val reply = replyFrame(
            protocol = FrameCodec.Protocol.P2,
            register = 0x31.toByte(),
            argument = FrameCodec.REPLY_ARG_NINEBOT,
            payload = ByteArray(12),
            source = PlaintextRegisterSession.ADDRESS_BMS,
        )
        val link = FakeLink(reply)
        val subject = session(link = link)

        val frame = subject.readRegister(
            register = 0x31.toByte(),
            argument = 0x0C,
            destination = PlaintextRegisterSession.ADDRESS_BMS,
        ) { link.replyBytes() }

        assertNotNull(frame)
        assertEquals(PlaintextRegisterSession.ADDRESS_BMS, frame!!.source)
        val sent = FrameCodec.decode(link.lastSent())
        assertEquals(PlaintextRegisterSession.ADDRESS_BMS, sent.destination)
    }
}
