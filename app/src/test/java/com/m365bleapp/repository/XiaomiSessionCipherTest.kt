package com.m365bleapp.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiSessionCipherTest {
    @Test fun `every request under a login consumes a distinct nonce including failed encryption`() {
        val counters = mutableListOf<Long>()
        val cipher = XiaomiSessionCipher(nativeEncrypt = { bytes, counter ->
            counters.add(counter)
            if (counter == 1L) null else bytes
        })
        assertNotNull(cipher.encrypt(byteArrayOf(1)))
        assertNull(cipher.encrypt(byteArrayOf(2)))
        assertNotNull(cipher.encrypt(byteArrayOf(3)))
        assertEquals(listOf(0L, 1L, 2L), counters)
    }

    @Test fun `wire counter exhaustion never wraps or calls native encryption again`() {
        val counters = mutableListOf<Long>()
        val cipher = XiaomiSessionCipher({ bytes, counter -> counters.add(counter); bytes }, 0xFFFFL)
        assertNotNull(cipher.encrypt(byteArrayOf(1)))
        repeat(2) {
            val failure = assertThrows(IllegalStateException::class.java) { cipher.encrypt(byteArrayOf(2)) }
            assertTrue(failure.message.orEmpty().contains("new login"))
        }
        assertEquals(listOf(0xFFFFL), counters)
    }

    @Test fun `first counter outside the 16-bit wire field is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { XiaomiSessionCipher({ bytes, _ -> bytes }, 0x10000L) }
        assertThrows(IllegalArgumentException::class.java) { XiaomiSessionCipher({ bytes, _ -> bytes }, -1L) }
    }
}
