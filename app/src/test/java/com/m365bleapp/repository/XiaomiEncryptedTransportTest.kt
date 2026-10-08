// SPDX-License-Identifier: MIT
package com.m365bleapp.repository

import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiPdu
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class XiaomiEncryptedTransportTest {
    /** Synthetic envelope with fake ciphertext, authenticated only by our injected test port. */
    private fun frame(marker: Int = 0x7D, dataLength: Int = 2): ByteArray {
        val bytes = ByteArray(dataLength + 18)
        bytes[0] = 0x55; bytes[1] = 0xAB.toByte(); bytes[2] = (dataLength + 2).toByte()
        bytes[5] = marker.toByte()
        val checksum = bytes.sliceArray(2 until bytes.size - 2).sumOf { it.toInt() and 0xFF }.inv() and 0xFFFF
        bytes[bytes.size - 2] = checksum.toByte(); bytes[bytes.size - 1] = (checksum ushr 8).toByte()
        return bytes
    }

    private class Harness(val capacity: Int = 64) {
        var live = true
        var poisoned = 0
        var submitted = 0
        var acceptWrite = true
        var at = 10L
        var auth = true
        var cipher = true
        val observed = mutableListOf<Pair<ByteArray, Long>>()
        val transport = XiaomiEncryptedTransport("device", "epoch", { live }, { 23 },
            encrypt = { if (cipher) it else null },
            submit = { submitted++; acceptWrite },
            decrypt = { wire -> if (!auth) null else ByteArray((wire[2].toInt() and 0xFF) + 5).also {
                it[0] = 0x23; it[1] = 0x01; it[2] = wire[5]
            } },
            onReply = { raw, at -> observed.add(raw to at) }, onPoison = { poisoned++ }, nowMs = { at }, maxReplies = capacity)
    }

    @Test fun `canonical logical bytes use captured connection and submit once`() {
        val h = Harness()
        assertEquals(23, h.transport.mtu)
        assertEquals("device", h.transport.deviceId)
        assertFalse(h.transport.writeForConnection(XiaomiPdu.read(0x7D, 2), "replacement"))
        assertFalse(h.transport.write(byteArrayOf(3, 0x20, 3, 0x70, 1)))
        assertFalse(h.transport.write(byteArrayOf(4, 0x20, 1, 0x7D, 2)))
        assertTrue(h.transport.write(XiaomiPdu.read(0x7D, 2)))
        assertEquals(1, h.submitted)
        h.acceptWrite = false
        assertFalse(h.transport.write(XiaomiPdu.read(0x7D, 2)))
        assertEquals(2, h.submitted)
        assertEquals(1, h.poisoned)
        assertFalse(h.transport.connected)
        assertFalse(h.transport.write(XiaomiPdu.read(0x7D, 2)))
        h.transport.poison()
        assertEquals(1, h.poisoned)
    }

    @Test fun `absent native encryption and disconnected session never submit`() {
        val h = Harness()
        h.cipher = false
        assertFalse(h.transport.write(XiaomiPdu.read(0x7D, 2)))
        h.cipher = true; h.live = false
        assertFalse(h.transport.write(XiaomiPdu.read(0x7D, 2)))
        assertEquals(0, h.submitted)
    }

    @Test fun `every split preserves first byte ingress cursor and timestamp`() {
        val wire = frame()
        for (cut in 0..wire.size) {
            val h = Harness()
            h.transport.accept(wire.copyOfRange(0, cut))
            h.at = 999
            h.transport.accept(wire.copyOfRange(cut, wire.size))
            val reply = requireNotNull(h.transport.awaitNotifyAfter(5, 0))
            assertEquals(1, reply.sequence)
            assertEquals(if (cut == 0) 999L else 10L, h.transport.lastReplyAtMs)
            assertEquals(wire.size.toLong(), h.transport.notificationSequence)
            assertEquals(1, h.observed.size)
        }
    }

    @Test fun `old fragmented reply completed after a write cannot confirm that write`() {
        val h = Harness()
        val wire = frame()
        h.transport.accept(wire.copyOfRange(0, 6))
        val cursor = h.transport.notificationSequence
        assertTrue(h.transport.write(XiaomiPdu.read(0x7D, 2)))
        h.transport.accept(wire.copyOfRange(6, wire.size))
        assertNull(h.transport.awaitNotifyAfter(2, cursor))
        h.transport.accept(frame(0x7B))
        val reply = requireNotNull(h.transport.awaitNotifyAfter(5, cursor))
        assertTrue(reply.sequence > cursor)
        assertEquals(0x7B, reply.bytes[2].toInt() and 0xFF)
    }

    @Test fun `bytewise and coalesced replies retain separate increasing cursors`() {
        val h = Harness()
        frame().forEach { h.transport.accept(byteArrayOf(it)) }
        h.transport.accept(frame(0x7B) + frame(0x7C))
        var cursor = 0L
        for (register in listOf(0x7D, 0x7B, 0x7C)) {
            val reply = requireNotNull(h.transport.awaitNotifyAfter(5, cursor))
            assertEquals(register, reply.bytes[2].toInt() and 0xFF)
            assertTrue(reply.sequence > cursor)
            cursor = reply.sequence
            val raw = reply.bytes
            raw[2] = 0
            assertEquals(register, reply.bytes[2].toInt() and 0xFF)
        }
    }

    @Test fun `noise bad prefix short length and checksum corruption resynchronize`() {
        val h = Harness()
        val bad = frame().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        h.transport.accept(byteArrayOf(0, 0x55, 0x55, 0xAB.toByte(), 0) + bad + frame(0x7C))
        assertEquals(0x7C, requireNotNull(h.transport.awaitNotify(5))[2].toInt() and 0xFF)
        assertEquals(1, h.observed.size)
    }

    @Test fun `failed native authentication is never a telemetry reply`() {
        val h = Harness()
        h.auth = false
        h.transport.accept(frame())
        assertNull(h.transport.awaitNotify(2))
        assertTrue(h.observed.isEmpty())
    }

    @Test fun `overflow poisons rather than silently evicting earlier replies`() {
        val h = Harness(1)
        h.transport.accept(frame() + frame())
        assertEquals(1, h.poisoned)
        assertNull(h.transport.awaitNotify(5))
        assertFalse(h.transport.connected)
        h.transport.accept(frame())
        assertEquals(1, h.observed.size)
        val oversized = Harness()
        oversized.transport.accept(ByteArray(4097))
        assertEquals(1, oversized.poisoned)
    }

    @Test fun `close wakes a waiting receiver without waiting for its full deadline`() {
        val h = Harness()
        val entered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val receiver = executor.submit<Any?> { entered.countDown(); h.transport.awaitNotify(30_000) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            h.transport.close()
            assertNull(receiver.get(5, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
