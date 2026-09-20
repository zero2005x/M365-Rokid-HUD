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
 * ## Why the `direction byte` case is the important one
 *
 * The decrypted buffer carries **no size byte** — `encrypt_uart` keeps it outside
 * the ciphertext and `decrypt_uart` does not put it back — so it begins with the
 * direction byte, `0x23` = 35. An earlier revision read that as a length and
 * mis-sized every reply: the 9- and 11-byte replies were rejected outright, and
 * the 39-byte `0xB0` reply passed by luck but had every field shifted one byte,
 * so its `attribute` read the first data byte and never matched `0xB0`.
 *
 * Every test below that touches a buffer therefore goes through [ScooterReply.build],
 * which now mirrors `decrypt_uart`'s output exactly. A builder that models the
 * *wire* frame instead is precisely what let these tests pass while the real path
 * rejected every real reply.
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

    // ------------------------------------- regression: no size byte in the buffer

    @Test
    fun `the direction byte 0x23 is not read as a 35-byte frame length`() {
        // 0x23 == 35. Reading it as a size byte rejected every reply shorter than
        // 35 bytes with "size byte says 35 bytes but the frame is only N".
        for (dataLen in intArrayOf(2, 4, 32)) {
            val frame = valid(0xB0, ByteArray(dataLen))

            assertEquals("frame should be header + data + padding",
                ScooterReply.HEADER_LEN + dataLen + ScooterReply.PADDING_LEN, frame.size)

            val result = ScooterReply.parse(frame)
            assertTrue(
                "a ${frame.size}-byte reply must not be rejected: $result",
                result is ScooterReplyValidation.Valid
            )
        }
    }

    @Test
    fun `the three real poll replies decrypt to the captured lengths and parse`() {
        // Lengths observed on hardware (Xiaomi M365, MIScooter8964) on 2026-09-20:
        // the 0x25, 0x3A and 0xB0 replies decrypted to 9, 11 and 39 bytes.
        val cases = listOf(
            Triple(0x25, 2, 9),   // remaining km  -> 2-byte payload
            Triple(0x3A, 4, 11),  // trip info     -> 4-byte payload
            Triple(0xB0, 32, 39), // motor info    -> 32-byte payload
        )

        for ((attribute, payload, expectedLength) in cases) {
            val frame = valid(attribute, ByteArray(payload) { (it + 1).toByte() })
            assertEquals("0x${attribute.toString(16)} length", expectedLength, frame.size)

            val reply = requireNotNull(
                ScooterReply.parseOrNull(frame)
            ) { "0x${attribute.toString(16)} should parse" }

            // The whole point: the attribute survives, so the dispatcher can route.
            assertEquals("0x${attribute.toString(16)} attribute", attribute, reply.attribute)
            assertEquals("0x${attribute.toString(16)} data length", payload, reply.data.size)
        }
    }

    @Test
    fun `a mis-sized reply is still rejected`() {
        // The truncation guard has to survive the re-layout: a frame with fewer
        // than header + one data byte + padding must never reach a field offset.
        for (len in 0 until ScooterReply.MIN_FRAME_LEN) {
            val result = ScooterReply.parse(ByteArray(len))
            assertTrue("length $len should be rejected", result is ScooterReplyValidation.Rejected)
        }
    }

    @Test
    fun `the shortest accepted frame still yields one data byte`() {
        val frame = ByteArray(ScooterReply.MIN_FRAME_LEN)
        val reply = requireNotNull(ScooterReply.parseOrNull(frame))
        assertEquals(1, reply.data.size)
    }

    @Test
    fun `an empty frame is rejected with a distinct reason`() {
        val result = ScooterReply.parse(ByteArray(0))

        assertTrue(result is ScooterReplyValidation.Rejected)
        assertEquals("empty frame", (result as ScooterReplyValidation.Rejected).reason)
    }

    // ------------------------------------------------------------- contract

    @Test
    fun `parseOrNull mirrors parse for both outcomes`() {
        assertNotNull(ScooterReply.parseOrNull(valid(0xB0, ByteArray(4))))
        assertNull(ScooterReply.parseOrNull(ByteArray(0)))
        assertNull(ScooterReply.parseOrNull(ByteArray(ScooterReply.MIN_FRAME_LEN - 1)))
    }

    @Test
    fun `the builder emits header, data and padding and no size byte`() {
        // If the builder ever went back to emitting a size byte, it would describe
        // the wire frame rather than what `decrypt_uart` returns — and the whole
        // suite would pass while the real path broke. Hence the explicit offsets.
        for (len in 1..40) {
            val data = ByteArray(len) { (it and 0xFF).toByte() }
            val frame = ScooterReply.build(esc, readReply, 0xB0, data)

            assertEquals(
                "frame is header + data + padding",
                ScooterReply.HEADER_LEN + len + ScooterReply.PADDING_LEN,
                frame.size
            )
            assertEquals("byte 0 is the direction", esc, frame[0].toInt() and 0xFF)
            assertEquals("byte 1 is the type", readReply, frame[1].toInt() and 0xFF)
            assertEquals("byte 2 is the attribute, not a length", 0xB0, frame[2].toInt() and 0xFF)
            assertArrayEquals("data starts at HEADER_LEN", data, frame.copyOfRange(ScooterReply.HEADER_LEN, ScooterReply.HEADER_LEN + len))
        }
    }

    @Test
    fun `header and padding constants match the decrypted layout`() {
        // Three, not four: direction, type, attribute. See the class docs.
        assertEquals(3, ScooterReply.HEADER_LEN)
        assertEquals(4, ScooterReply.PADDING_LEN)
        // Header plus at least one byte of data plus the random tail.
        assertEquals(8, ScooterReply.MIN_FRAME_LEN)
    }
}
