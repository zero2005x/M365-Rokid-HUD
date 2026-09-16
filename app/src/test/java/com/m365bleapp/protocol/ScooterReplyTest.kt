package com.m365bleapp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ScooterReply] frame validation (improvement-plan stage A3).
 *
 * The central case is `size byte claims more data than the frame carries`: that
 * is the shape of the bug this class exists to stop, and it is the one that
 * reached a field-offset calculation before.
 */
class ScooterReplyTest {

    private val esc = 0x23
    private val bms = 0x25
    private val readReply = 0x01

    private fun valid(attribute: Int, data: ByteArray) =
        ScooterReply.build(direction = esc, type = readReply, attribute = attribute, data = data)

    // ------------------------------------------------------------ happy path

    @Test
    fun `a well formed reply parses and exposes its fields`() {
        val data = byteArrayOf(0x11, 0x22, 0x33, 0x44)

        val reply = requireNotNull(ScooterReply.parseOrNull(valid(0xB0, data)))

        assertEquals(esc, reply.direction)
        assertEquals(readReply, reply.type)
        assertEquals(0xB0, reply.attribute)
        assertEquals(0xB0, reply.register)
        assertArrayEquals(data, reply.data)
    }

    @Test
    fun `build and parse round trip for every data length from one to forty`() {
        // Round-tripping through the shared builder is what keeps the tests from
        // diverging from the parser's real expectation.
        for (len in 1..40) {
            val data = ByteArray(len) { (it and 0xFF).toByte() }
            val reply = requireNotNull(ScooterReply.parseOrNull(valid(0x31, data))) {
                "failed at length $len"
            }
            assertArrayEquals("length $len", data, reply.data)
        }
    }

    @Test
    fun `the padding is excluded from the data`() {
        // A distinct padding pattern proves it is not leaking into `data`.
        val data = byteArrayOf(0x01, 0x02)
        val frame = ScooterReply.build(
            direction = esc,
            type = readReply,
            attribute = 0x25,
            data = data,
            padding = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
        )

        val reply = requireNotNull(ScooterReply.parseOrNull(frame))

        assertArrayEquals(data, reply.data)
    }

    @Test
    fun `a BMS reply keeps its own direction byte`() {
        val frame = ScooterReply.build(bms, readReply, 0x31, ByteArray(12))
        val reply = requireNotNull(ScooterReply.parseOrNull(frame))
        assertEquals(bms, reply.direction)
    }

    // ------------------------------------------------- the truncation hazard

    @Test
    fun `a size byte claiming more data than the frame carries is rejected`() {
        // THE bug this class exists for: a 0xB0 reply that announces 32 bytes of
        // motor info but delivers almost nothing. Previously this produced an
        // empty data array and the parser read past its end.
        val lying = byteArrayOf(
            32,                              // size byte claims 32 bytes total
            esc.toByte(), readReply.toByte(), 0xB0.toByte(),
            0x01,                            // ...but only one data byte follows
            0, 0, 0, 0,                      // padding
        )

        val result = ScooterReply.parse(lying)

        assertTrue("expected rejection, got $result", result is ScooterReplyValidation.Rejected)
        assertTrue(
            "reason should mention the size mismatch",
            (result as ScooterReplyValidation.Rejected).reason.contains("size byte says")
        )
    }

    @Test
    fun `a frame longer than its size byte is accepted because of the padding`() {
        // IMPORTANT: the size byte counts the original message and does NOT count
        // the 4-byte random tail `encrypt_uart` appends. An equality check here
        // would reject every real reply, so the frame being longer is expected.
        val frame = ScooterReply.build(esc, readReply, 0xB0, ByteArray(20))

        val reply = requireNotNull(ScooterReply.parseOrNull(frame))

        assertEquals(20, reply.data.size)
    }

    @Test
    fun `a frame shorter than the header plus padding is rejected`() {
        // size + direction + type + attribute + 1 data + 4 padding == 9 minimum.
        for (len in 0 until 9) {
            val result = ScooterReply.parse(ByteArray(len))
            assertTrue("length $len should be rejected", result is ScooterReplyValidation.Rejected)
        }
    }

    @Test
    fun `an empty frame is rejected with a distinct reason`() {
        val result = ScooterReply.parse(ByteArray(0))

        assertTrue(result is ScooterReplyValidation.Rejected)
        assertEquals("empty frame", (result as ScooterReplyValidation.Rejected).reason)
    }

    @Test
    fun `a size byte below the header length is rejected`() {
        // size=2 would imply a negative data length. Padded to nine bytes so the
        // size-byte check fires rather than the length check.
        val frame = byteArrayOf(
            2, esc.toByte(), readReply.toByte(), 0xB0.toByte(),
            0, 0, 0, 0, 0,
        )

        val result = ScooterReply.parse(frame)

        assertTrue(result is ScooterReplyValidation.Rejected)
        assertTrue((result as ScooterReplyValidation.Rejected).reason.contains("below"))
    }

    @Test
    fun `a header only frame is rejected as having no data`() {
        // size == HEADER_LEN declares a header and nothing else, so there is
        // genuinely nothing to parse. It is caught by the minimum-size check.
        // Nine bytes so the frame is long enough for the pad, letting the
        // size-byte check — the more specific one — be what rejects it.
        val frame = byteArrayOf(
            ScooterReply.HEADER_LEN.toByte(),
            esc.toByte(), readReply.toByte(), 0xB0.toByte(),
            0xAA.toByte(), 0, 0, 0, 0,
        )

        val result = ScooterReply.parse(frame)

        assertTrue(result is ScooterReplyValidation.Rejected)
        assertTrue(
            "unexpected reason: ${(result as ScooterReplyValidation.Rejected).reason}",
            result.reason.contains("below")
        )
    }

    // ------------------------------------------------------------- contract

    @Test
    fun `parseOrNull mirrors parse for both outcomes`() {
        assertNotNull(ScooterReply.parseOrNull(valid(0xB0, ByteArray(4))))
        assertNull(ScooterReply.parseOrNull(ByteArray(0)))
        assertNull(ScooterReply.parseOrNull(byteArrayOf(32, 0x23, 1, 0xB0.toByte(), 1, 0, 0, 0, 0)))
    }

    @Test
    fun `the builder size byte equals header plus data, excluding padding`() {
        // If the builder ever started counting the padding, the validator would
        // reject frames it had just produced — and every test above would still
        // pass, which is why this contract is asserted explicitly.
        for (len in 1..40) {
            val frame = valid(0xB0, ByteArray(len))
            assertEquals(
                "length $len",
                ScooterReply.HEADER_LEN + len,
                frame[0].toInt() and 0xFF
            )
            assertEquals(
                "frame should carry the padding on top of the declared size",
                ScooterReply.HEADER_LEN + len + ScooterReply.PADDING_LEN,
                frame.size
            )
        }
    }

    @Test
    fun `header and padding constants match the documented layout`() {
        assertEquals(4, ScooterReply.HEADER_LEN)
        assertEquals(4, ScooterReply.PADDING_LEN)
        // Header plus at least one byte of data.
        assertEquals(5, ScooterReply.MIN_SIZE_BYTE)
    }
}
