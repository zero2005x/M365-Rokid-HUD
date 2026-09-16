package com.m365bleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for MTU-aware fragmentation.
 *
 * These exist because the failure mode is silent: a write larger than
 * `ATT_MTU − 3` is dropped by the peer with no error surfaced anywhere, so a
 * regression here cannot be caught by observation — only by pinning the
 * arithmetic.
 */
class MtuFragmenterTest {

    @Test
    fun `default chunk size is ATT overhead below the mandatory minimum MTU`() {
        // 23 is the only ATT_MTU every LE stack must support, so it is the only
        // safe assumption before negotiation completes.
        assertEquals(20, MtuFragmenter.DEFAULT_CHUNK_SIZE)
        assertEquals(20, MtuFragmenter.chunkSizeFor(23))
    }

    @Test
    fun `chunk size is three bytes below the negotiated MTU up to the protocol cap`() {
        assertEquals(20, MtuFragmenter.chunkSizeFor(23))
        assertEquals(29, MtuFragmenter.chunkSizeFor(32))
        assertEquals(61, MtuFragmenter.chunkSizeFor(64))
        assertEquals(182, MtuFragmenter.chunkSizeFor(185))
        // 512 - 3 = 509, but the frame length byte is a single byte, so the
        // cap (255) wins. This is the boundary where the ATT limit stops being
        // the binding constraint.
        assertEquals(MtuFragmenter.MAX_CHUNK_SIZE, MtuFragmenter.chunkSizeFor(512))
        assertEquals(255, MtuFragmenter.chunkSizeFor(258))
        assertEquals(252, MtuFragmenter.chunkSizeFor(255))
    }

    @Test
    fun `chunk size is clamped so a payload can never exceed one byte of length`() {
        // The protocol length field is one byte, so a chunk above 255 bytes
        // could not be framed even if the link allowed it.
        assertEquals(MtuFragmenter.MAX_CHUNK_SIZE, MtuFragmenter.chunkSizeFor(1024))
        assertEquals(MtuFragmenter.MAX_CHUNK_SIZE, MtuFragmenter.chunkSizeFor(65535))
    }

    @Test
    fun `a non-positive or too-small MTU falls back rather than producing a zero chunk`() {
        // Some stacks report 0 before negotiation. A chunk size of 0 would make
        // the fragmentation loop never advance.
        for (bad in intArrayOf(0, -1, 1, 2, 3)) {
            assertEquals(
                "MTU $bad should fall back",
                MtuFragmenter.DEFAULT_CHUNK_SIZE,
                MtuFragmenter.chunkSizeFor(bad)
            )
            assertTrue(MtuFragmenter.chunkSizeFor(bad) > 0)
        }
    }

    @Test
    fun `empty payload produces no writes`() {
        assertTrue(MtuFragmenter.fragment(ByteArray(0), 23).isEmpty())
        assertEquals(0, MtuFragmenter.chunkCount(0, 23))
    }

    @Test
    fun `payload smaller than the chunk travels in a single write`() {
        for (size in 1 until MtuFragmenter.DEFAULT_CHUNK_SIZE) {
            val chunks = MtuFragmenter.fragment(ByteArray(size) { it.toByte() }, 23)
            assertEquals("size $size should be one chunk", 1, chunks.size)
            assertEquals(size, chunks[0].size)
        }
    }

    @Test
    fun `exactly one chunk at the boundary and two just past it`() {
        // The off-by-one that matters: a payload of exactly chunkSize must NOT
        // produce a trailing empty write.
        val atBoundary = MtuFragmenter.fragment(ByteArray(20), 23)
        assertEquals(1, atBoundary.size)
        assertEquals(20, atBoundary[0].size)

        val pastBoundary = MtuFragmenter.fragment(ByteArray(21), 23)
        assertEquals(2, pastBoundary.size)
        assertEquals(20, pastBoundary[0].size)
        assertEquals(1, pastBoundary[1].size)
    }

    @Test
    fun `no write ever exceeds the negotiated limit`() {
        // The whole point of the file. Sweep payload sizes and MTUs.
        for (mtu in intArrayOf(23, 32, 64, 185, 512)) {
            val limit = MtuFragmenter.chunkSizeFor(mtu)
            for (size in intArrayOf(1, 19, 20, 21, 27, 40, 100, 255, 256, 1000)) {
                val chunks = MtuFragmenter.fragment(ByteArray(size), mtu)
                chunks.forEach { chunk ->
                    assertTrue(
                        "chunk of ${chunk.size} exceeds limit $limit for mtu $mtu, payload $size",
                        chunk.size <= limit
                    )
                }
            }
        }
    }

    @Test
    fun `fragmentation is lossless and order preserving`() {
        val payload = ByteArray(1000) { (it % 256).toByte() }
        for (mtu in intArrayOf(23, 64, 512)) {
            val chunks = MtuFragmenter.fragment(payload, mtu)
            val rejoined = chunks.reduce { acc, c -> acc + c }
            assertArrayEquals("round trip failed for mtu $mtu", payload, rejoined)
        }
    }

    @Test
    fun `chunk count agrees with the number of chunks produced`() {
        for (mtu in intArrayOf(23, 64, 512)) {
            for (size in intArrayOf(1, 20, 21, 100, 511, 512, 513)) {
                assertEquals(
                    "count mismatch for size $size mtu $mtu",
                    MtuFragmenter.chunkCount(size, mtu),
                    MtuFragmenter.fragment(ByteArray(size), mtu).size
                )
            }
        }
    }

    @Test
    fun `a 27 byte pairing frame splits the way the protocol expects`() {
        // The concrete case from the field report: a 27-byte AUTH frame must go
        // out as 20 + 7 on an un-negotiated link, not as one 27-byte write.
        val authFrame = ByteArray(27)
        val chunks = MtuFragmenter.fragment(authFrame, 23)
        assertEquals(2, chunks.size)
        assertEquals(20, chunks[0].size)
        assertEquals(7, chunks[1].size)
    }
}
