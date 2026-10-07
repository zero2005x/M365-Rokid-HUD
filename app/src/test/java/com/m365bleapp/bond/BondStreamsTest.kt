package com.m365bleapp.bond

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class BondStreamsTest {
    private fun header(size: Int) = ByteBuffer.allocate(size).put("RFBOND".toByteArray()).put(1.toByte()).putInt(100_000).array()
    @Test fun readsValidHeaderWithoutDependingOnFilename() {
        val file = header(55)
        assertArrayEquals(file, BondStreams.read(ByteArrayInputStream(file)))
    }
    @Test fun acceptsMaximumFileSize() {
        assertEquals(BondEnvelope.MAX_BYTES, BondStreams.read(ByteArrayInputStream(header(BondEnvelope.MAX_BYTES))).size)
    }
    @Test fun stopsOversizeProviderAfterLimitPlusOneByte() {
        var count = 0
        val unlimited = object : InputStream() {
            override fun read(): Int { count++; return 0 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill(0, offset, offset + length); count += length; return length
            }
        }
        try { BondStreams.read(unlimited); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(BondEnvelope.MAX_BYTES + 1, count)
    }
    @Test fun zeroLengthBulkReadDoesNotSpinForever() {
        val source = ByteArrayInputStream(header(55))
        val stalled = object : InputStream() {
            override fun read() = source.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
        }
        assertEquals(55, BondStreams.read(stalled).size)
    }
    @Test fun invalidMagicIsRejectedBeforePasswordPrompt() {
        try { BondStreams.read(ByteArrayInputStream(ByteArray(55))); fail() } catch (_: IllegalArgumentException) {}
    }
}
