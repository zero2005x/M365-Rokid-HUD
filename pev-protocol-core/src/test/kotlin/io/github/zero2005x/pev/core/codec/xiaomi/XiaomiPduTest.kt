package io.github.zero2005x.pev.core.codec.xiaomi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiPduTest {
    @Test
    fun readMatchesCapturedQuery() {
        // captured plaintext query for 0xB0: 03 20 01 B0 20
        assertArrayEquals(byteArrayOf(3, 0x20, 1, 0xB0.toByte(), 0x20), XiaomiPdu.read(0xB0, 0x20))
    }

    @Test
    fun writeLengthCountsPayloadPlusTwo() {
        assertArrayEquals(byteArrayOf(4, 0x20, 2, 0x7B, 1, 0), XiaomiPdu.write(0x7B, byteArrayOf(1, 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedPayloadRejected() {
        XiaomiPdu.write(0x7B, ByteArray(254))
    }

    @Test
    fun replyParseStripsHeaderAndPadding() {
        val raw = byteArrayOf(0x23, 1, 0xB0.toByte(), 9, 8, 0, 0, 0, 0)
        val r = XiaomiReply.parse(raw)!!
        assertEquals(0xB0, r.register)
        assertEquals(0x23, r.direction)
        assertEquals(1, r.type)
        assertArrayEquals(byteArrayOf(9, 8), r.data)
    }

    @Test
    fun shortReplyIsRejected() {
        assertNull(XiaomiReply.parse(ByteArray(XiaomiReply.MIN_LEN - 1)))
        assertNotNull(XiaomiReply.parse(ByteArray(XiaomiReply.MIN_LEN)))
    }

    @Test
    fun leReadersAreBounded() {
        val d = byteArrayOf(0x34, 0x12)
        assertEquals(0x1234, Le.u16(d, 0))
        assertNull(Le.u16(d, 1))
        assertNull(Le.u16(d, -1))
        assertNull(Le.u8(d, 2))
        assertNull(Le.u32(d, 0))
        assertEquals(0xFFFFFFFFL, Le.u32(ByteArray(4) { -1 }, 0))
        assertEquals(-2, Le.i16(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), 0))
    }
}
