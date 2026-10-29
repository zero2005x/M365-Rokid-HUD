package com.m365bleapp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM protocol tests: they do not imply a real G30 handshake succeeded. */
class NinebotShuFrameTest {
    @Test fun `read builder matches classic G30 SHU layout`() {
        assertArrayEquals(
            byteArrayOf(0x5A, 0xA5.toByte(), 1, 0x3E, 0x20, 1, 0xB5.toByte(), 2),
            NinebotShuFrame.read(0xB5, 2)
        )
    }

    @Test fun `encrypted length includes the 6 byte trailer`() {
        assertEquals(14, NinebotShuFrame.encryptedLength(NinebotShuFrame.read(0xB5, 2)))
        assertNull(NinebotShuFrame.encryptedLength(byteArrayOf(0x5A, 0xA5.toByte())))
    }

    @Test fun `accept only complete matching ESC register responses`() {
        val full = byteArrayOf(0x5A, 0xA5.toByte(), 2, 0x20, 0x3E, 1,
            0xB5.toByte(), 0x10, 0x27)
        assertArrayEquals(byteArrayOf(0x10, 0x27), NinebotShuFrame.replyPayload(full, 0xB5))
        assertNull(NinebotShuFrame.replyPayload(full, 0xB0))
        assertNull(NinebotShuFrame.replyPayload(full.copyOfRange(0, 8), 0xB5))
        assertNull(NinebotShuFrame.replyPayload(full.copyOf().also { it[3] = 0x3E }, 0xB5))
    }

    @Test fun `BLE fragments may split the two sync bytes and payload`() {
        val reply = byteArrayOf(0x5A, 0xA5.toByte(), 2, 0x20, 0x3E, 1,
            0xB5.toByte(), 0x10, 0x27) + ByteArray(6)
        val buffer = NinebotEncryptedFrameBuffer()
        assertNull(buffer.push(reply.copyOfRange(0, 1)))
        assertNull(buffer.push(reply.copyOfRange(1, 2)))
        assertNull(buffer.push(reply.copyOfRange(2, 7)))
        assertArrayEquals(reply, buffer.push(reply.copyOfRange(7, reply.size)))
    }

    @Test fun `overflow cannot be sent to the decryptor`() {
        val buffer = NinebotEncryptedFrameBuffer()
        val header = byteArrayOf(0x5A, 0xA5.toByte(), 1)
        assertNull(buffer.push(header))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            buffer.push(ByteArray(100))
        }
    }

    @Test fun `cipher can encrypt legal minimum SHU frames`() {
        val keyName = "NBScooter2020".toByteArray(Charsets.US_ASCII)
        val cipher = NinebotCryptoCipher.afterHandshake(keyName, ByteArray(16) { it.toByte() })
        val session = NinebotEncryptedReadSession(cipher)
        val request = session.buildRead(0xB5, 2)
        assertEquals(14, request.size)
        assertEquals(0x5A, request[0].toInt() and 255)
        assertEquals(0xA5, request[1].toInt() and 255)
        assertEquals(1, request[2].toInt())
        assertTrue(cipher.currentMsgIt > 0)
    }
}
