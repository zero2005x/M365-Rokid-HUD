package com.m365bleapp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the legacy `55AA` / `5AA5` frame codec.
 *
 * Two properties matter most here and are easy to get wrong in ways that only
 * show up on hardware:
 *
 *  1. **The checksum is a 15-bit inverted sum, not a CRC.** Encoder and decoder
 *     agreeing with each other proves nothing if both are wrong, so the sum is
 *     pinned against hand-computed values.
 *  2. **The two vendors answer with different argument bytes.** A parser that
 *     accepts only one silently drops half the devices.
 */
class FrameCodecTest {

    // ---- checksum ---------------------------------------------------------

    @Test
    fun `checksum is the 15-bit inverted sum of the covered bytes`() {
        // 0x01 + 0x02 + 0x03 = 6; ~6 & 0xFFFF = 0xFFF9.
        assertEquals(0xFFF9, FrameCodec.checksum(byteArrayOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte())))
    }

    @Test
    fun `checksum can skip the leading length byte`() {
        // Protocol 1 does not cover its own length byte, so skipping it must
        // give the same answer as summing the content alone.
        val withLength = byteArrayOf(0xFF.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte())
        assertEquals(
            FrameCodec.checksum(byteArrayOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte()), skipLeading = 0),
            FrameCodec.checksum(withLength, skipLeading = 1)
        )
    }

    @Test
    fun `checksum folds the overflow bit back in rather than truncating`() {
        // 300 bytes of 0xFF sum to 76500 = 0x12AD4, which is wider than the
        // 15-bit accumulator, so the fold runs:
        //   folded   = (0x12AD4 & 0x7FFF) + (0x12AD4 >> 15) = 0x2AD4 + 2 = 0x2AD6
        //   checksum = ~0x2AD6 & 0xFFFF = 0xD529
        //
        // Truncating to 15 bits without folding gives ~0x2AD4 = 0xD52B, so this
        // pins the fold rather than merely "a sum".
        val body = ByteArray(300) { 0xFF.toByte() }
        assertEquals(0xD529, FrameCodec.checksum(body, skipLeading = 0))
    }

    @Test
    fun `checksum of an all-zero body is all ones`() {
        assertEquals(0xFFFF, FrameCodec.checksum(byteArrayOf(0.toByte(), 0.toByte(), 0.toByte())))
    }

    @Test
    fun `checksum rejects a skip longer than the body`() {
        try {
            FrameCodec.checksum(byteArrayOf(1.toByte(), 2.toByte()), skipLeading = 5)
            throw AssertionError("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // as intended
        }
    }

    // ---- protocol 1 round trip -------------------------------------------

    @Test
    fun `protocol 1 request round trips`() {
        val encoded = FrameCodec.encodeRequest(
            protocol = FrameCodec.Protocol.P1,
            source = 0x3E.toByte(),
            destination = 0x20.toByte(),
            register = 0xB0.toByte(),
            argument = 0x20.toByte()
        )

        // 2 sync + 1 len + 4 body content + 2 checksum
        assertEquals(9, encoded.size)
        assertEquals(FrameCodec.SYNC_1_HI, encoded[0])
        assertEquals(FrameCodec.SYNC_1_LO, encoded[1])
        assertEquals(4, encoded[2].toInt()) // len counts src+dst+cmd+arg

        val decoded = FrameCodec.decode(encoded)
        assertEquals(FrameCodec.Protocol.P1, decoded.protocol)
        assertEquals(0x3E.toByte(), decoded.source)
        assertEquals(0x20.toByte(), decoded.destination)
        assertEquals(0xB0.toByte(), decoded.command)
        assertEquals(0x20.toByte(), decoded.argument)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `protocol 1 request with payload round trips`() {
        val payload = byteArrayOf(0x0A.toByte(), 0x0B.toByte(), 0x0C.toByte())
        val encoded = FrameCodec.encodeRequest(
            protocol = FrameCodec.Protocol.P1,
            source = 0x3E.toByte(),
            destination = 0x20.toByte(),
            register = 0xB0.toByte(),
            argument = 0x20.toByte(),
            payload = payload
        )
        assertEquals(7, encoded[2].toInt()) // 4 + 3

        val decoded = FrameCodec.decode(encoded)
        assertArrayEquals(payload, decoded.payload)
    }

    // ---- protocol 2 round trip -------------------------------------------

    @Test
    fun `protocol 2 inserts BT_ID and covers it with the checksum`() {
        val encoded = FrameCodec.encodeRequest(
            protocol = FrameCodec.Protocol.P2,
            source = 0x3E.toByte(),
            destination = 0x20.toByte(),
            register = 0xB0.toByte(),
            argument = 0x20.toByte()
        )

        assertEquals(FrameCodec.SYNC_2_HI, encoded[0])
        assertEquals(FrameCodec.SYNC_2_LO, encoded[1])
        // Layout is [sync][sync][BT_ID][len][counted…][checksum].
        // BT_ID sits immediately after the sync bytes, BEFORE the length byte,
        // so the length byte does not count it — but the checksum does.
        assertEquals(FrameCodec.BT_ID, encoded[2])
        assertEquals(4.toByte(), encoded[3]) // len counts src+dst+cmd+arg

        val decoded = FrameCodec.decode(encoded)
        assertEquals(FrameCodec.Protocol.P2, decoded.protocol)
        assertEquals(0x3E.toByte(), decoded.source)
        assertEquals(0xB0.toByte(), decoded.command)

        // Corrupting BT_ID must break verification, which is what proves it is
        // inside the checksummed range for P2.
        val tampered = encoded.copyOf()
        tampered[2] = 0x00
        assertThrows { FrameCodec.decode(tampered) }
    }

    @Test
    fun `the two generations produce different frames for the same request`() {
        val p1 = FrameCodec.encodeRequest(FrameCodec.Protocol.P1, 0x3E.toByte(), 0x20.toByte(), 0xB0.toByte(), 0x20.toByte())
        val p2 = FrameCodec.encodeRequest(FrameCodec.Protocol.P2, 0x3E.toByte(), 0x20.toByte(), 0xB0.toByte(), 0x20.toByte())
        assertNotEquals(p1.size, p2.size)
        assertFalse(p1.contentEquals(p2))
    }

    // ---- the vendor reply trap -------------------------------------------

    @Test
    fun `xiaomi reply argument is recognised`() {
        val frame = replyFrame(register = 0xB0.toByte(), argument = FrameCodec.REPLY_ARG_XIAOMI)
        assertEquals(FrameCodec.ReplyKind.XIAOMI, frame.replyKind)
        assertTrue(FrameCodec.isReplyFor(frame, 0xB0.toByte()))
    }

    @Test
    fun `ninebot reply argument is recognised for the same register`() {
        // This is the case a single-vendor parser drops.
        val frame = replyFrame(register = 0xB0.toByte(), argument = FrameCodec.REPLY_ARG_NINEBOT)
        assertEquals(FrameCodec.ReplyKind.NINEBOT, frame.replyKind)
        assertTrue(FrameCodec.isReplyFor(frame, 0xB0.toByte()))
    }

    @Test
    fun `an unrecognised argument is not treated as a reply`() {
        val frame = replyFrame(register = 0xB0.toByte(), argument = 0x7F.toByte())
        assertEquals(FrameCodec.ReplyKind.OTHER, frame.replyKind)
        assertFalse(FrameCodec.isReplyFor(frame, 0xB0.toByte()))
    }

    @Test
    fun `a reply for a different register is rejected`() {
        val frame = replyFrame(register = 0xB0.toByte(), argument = FrameCodec.REPLY_ARG_XIAOMI)
        assertFalse(FrameCodec.isReplyFor(frame, 0x3A.toByte()))
    }

    // ---- rejection of malformed input ------------------------------------

    @Test
    fun `unknown sync word is rejected`() {
        assertThrows { FrameCodec.decode(byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x3E.toByte(), 0x20.toByte(), 0xB0.toByte(), 0x20.toByte(), 0x00.toByte(), 0x00.toByte())) }
    }

    @Test
    fun `truncated frame is rejected`() {
        val full = FrameCodec.encodeRequest(FrameCodec.Protocol.P1, 0x3E.toByte(), 0x20.toByte(), 0xB0.toByte(), 0x20.toByte())
        assertThrows { FrameCodec.decode(full.copyOfRange(0, full.size - 1)) }
    }

    @Test
    fun `length byte disagreeing with the actual size is rejected`() {
        val full = FrameCodec.encodeRequest(FrameCodec.Protocol.P1, 0x3E.toByte(), 0x20.toByte(), 0xB0.toByte(), 0x20.toByte())
        val tampered = full.copyOf()
        tampered[2] = 9 // claims 9 body bytes
        assertThrows { FrameCodec.decode(tampered) }
    }

    @Test
    fun `a corrupted payload fails the checksum`() {
        val full = FrameCodec.encodeRequest(
            FrameCodec.Protocol.P1, 0x3E.toByte(), 0x20.toByte(), 0xB0.toByte(), 0x20.toByte(), byteArrayOf(1.toByte(), 2.toByte(), 3.toByte())
        )
        val tampered = full.copyOf()
        tampered[5] = (tampered[5] + 1).toByte()
        assertThrows { FrameCodec.decode(tampered) }
    }

    @Test
    fun `frames shorter than the minimum are rejected`() {
        assertThrows { FrameCodec.decode(ByteArray(0)) }
        assertThrows { FrameCodec.decode(byteArrayOf(0x55.toByte(), 0xAA.toByte())) }
        assertThrows { FrameCodec.decode(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x04.toByte(), 0x3E.toByte())) }
    }

    @Test
    fun `an undersized length byte is rejected`() {
        // len = 3 cannot hold src+dst+cmd+arg (which is 4). The frame is sized
        // consistently with the length byte, so it is the length check that
        // rejects it rather than the size check.
        val counted = byteArrayOf(0x3E.toByte(), 0x20.toByte(), 0xB0.toByte())
        val checksum = FrameCodec.checksum(counted, skipLeading = 0)
        val frame = byteArrayOf(
            FrameCodec.SYNC_1_HI, FrameCodec.SYNC_1_LO,
            3.toByte(),
            *counted,
            (checksum and 0xFF).toByte(), ((checksum shr 8) and 0xFF).toByte()
        )
        assertThrows { FrameCodec.decode(frame) }
    }

    // ---- helpers ----------------------------------------------------------

    /** Builds a well-formed reply frame: `cmd` = register, `arg` = status. */
    private fun replyFrame(register: Byte, argument: Byte): FrameCodec.Frame {
        val body = byteArrayOf(
            4.toByte(),      // len: src + dst + cmd + arg
            0x20.toByte(),   // src = ESC
            0x3E.toByte(),   // dst = phone
            register,        // cmd = which register was read
            argument         // arg = status / vendor marker
        )
        val checksum = FrameCodec.checksum(body, skipLeading = 1)  // P1: ignore len
        val frame = byteArrayOf(
            FrameCodec.SYNC_1_HI, FrameCodec.SYNC_1_LO,
            *body,
            (checksum and 0xFF).toByte(), ((checksum shr 8) and 0xFF).toByte()
        )
        return FrameCodec.decode(frame)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            assertTrue("expected FrameException, got ${e::class.simpleName}", e is FrameCodec.FrameException)
            return
        }
        throw AssertionError("expected a FrameException but nothing was thrown")
    }
}
