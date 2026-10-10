// SPDX-License-Identifier: MIT
package com.m365bleapp.repository

import io.github.zero2005x.pev.core.codec.xiaomi.StatusWordWriteOrder
import io.github.zero2005x.pev.core.command.CommandOutcome
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class XiaomiPhoneSessionTest {
    @Test fun `reported throttle fault revokes consent and blocks every setting without radio writes`() {
        val link = SyntheticXiaomiPhoneLink()
        link.stationary()
        assertTrue(link.session.enableM365Experimental())
        link.session.observe(SyntheticXiaomiPhoneLink.reply(0xB0, ByteArray(32).also { it[0] = 14 }), link.now)
        assertEquals(14, link.session.faultCode)
        assertFalse(link.session.enableM365Experimental())
        for (setting in listOf(XiaomiSetting.Kers(1), XiaomiSetting.Cruise(true),
            XiaomiSetting.TailLight(true, StatusWordWriteOrder.LITTLE_ENDIAN),
            XiaomiSetting.Units(true, StatusWordWriteOrder.BIG_ENDIAN))) {
            assertEquals(CommandOutcome.REJECTED, link.session.execute(setting).outcome)
        }
        assertTrue(link.writes.isEmpty())
        assertTrue(link.transport.connected)
        link.stationary()
        assertEquals(CommandOutcome.REJECTED, link.session.execute(XiaomiSetting.Kers(1)).outcome)
        assertTrue(link.writes.isEmpty())
        assertTrue(link.session.enableM365Experimental())
        assertEquals(CommandOutcome.READBACK_CONFIRMED, link.session.execute(XiaomiSetting.Kers(1)).outcome)
        link.session.close()
    }

    @Test fun `authenticated transport alone does not authorize settings`() {
        val link = SyntheticXiaomiPhoneLink()
        link.stationary()
        assertEquals(CommandOutcome.REJECTED, link.session.execute(XiaomiSetting.Kers(1)).outcome)
        assertEquals(CommandOutcome.REJECTED, link.session.execute(XiaomiSetting.Cruise(true)).outcome)
        assertTrue(link.writes.isEmpty())
        link.session.close()
    }

    @Test fun `opt in does not lift unknown moving stale or future speed gate`() {
        for (setting in listOf(XiaomiSetting.Kers(1), XiaomiSetting.Cruise(true))) {
            val unknown = SyntheticXiaomiPhoneLink()
            unknown.session.enableM365Experimental()
            assertEquals(CommandOutcome.REJECTED, unknown.session.execute(setting).outcome)
            assertTrue(unknown.writes.isEmpty())
            unknown.session.close()
            for ((rawSpeed, observedAt) in listOf(501 to 10_000L, 0xFE0B to 10_000L,
                0 to 7_999L, 0 to 10_001L)) {
                val link = SyntheticXiaomiPhoneLink()
                link.session.enableM365Experimental()
                link.speed(rawSpeed, observedAt)
                assertEquals("speed=$rawSpeed at=$observedAt", CommandOutcome.REJECTED,
                    link.session.execute(setting).outcome)
                assertTrue(link.writes.isEmpty())
                link.session.close()
            }
        }
    }

    @Test fun `wrong reply direction type or length cannot establish stationary evidence`() {
        for (raw in listOf(
            SyntheticXiaomiPhoneLink.reply(0xB0, ByteArray(32), direction = 0x22),
            SyntheticXiaomiPhoneLink.reply(0xB0, ByteArray(32), type = 2),
            SyntheticXiaomiPhoneLink.reply(0xB0, ByteArray(31)),
        )) {
            val link = SyntheticXiaomiPhoneLink()
            link.session.enableM365Experimental()
            link.session.observe(raw, link.now)
            assertEquals(CommandOutcome.REJECTED, link.session.execute(XiaomiSetting.Kers(0)).outcome)
            assertTrue(link.writes.isEmpty())
            link.session.close()
        }
    }

    @Test fun `KERS uses audited write opcode and exact readback without retries`() {
        val link = SyntheticXiaomiPhoneLink()
        link.session.enableM365Experimental()
        link.stationary()
        assertEquals(CommandOutcome.READBACK_CONFIRMED, link.session.execute(XiaomiSetting.Kers(2)).outcome)
        assertEquals(2, link.writes.size)
        assertArrayEquals(bytes(4, 0x20, 2, 0x7B, 2, 0), link.writes[0])
        assertArrayEquals(bytes(3, 0x20, 1, 0x7B, 2), link.writes[1])
        assertEquals(0, link.poisonCount)
        link.session.close()
    }

    @Test fun `cruise requires matching value readback not submission success`() {
        val link = SyntheticXiaomiPhoneLink()
        link.session.enableM365Experimental()
        link.stationary()
        assertEquals(CommandOutcome.READBACK_CONFIRMED, link.session.execute(XiaomiSetting.Cruise(true)).outcome)
        assertArrayEquals(bytes(4, 0x20, 2, 0x7C, 1, 0), link.writes[0])
        assertArrayEquals(bytes(3, 0x20, 1, 0x7C, 2), link.writes[1])
        link.session.close()
    }

    @Test fun `tail light reads fresh whole word and preserves unknown bits for both explicit orders`() {
        for (order in StatusWordWriteOrder.entries) {
            val link = SyntheticXiaomiPhoneLink(order)
            link.words[0x7D] = 0xA591
            link.session.enableM365Experimental()
            assertEquals(CommandOutcome.READBACK_CONFIRMED,
                link.session.execute(XiaomiSetting.TailLight(true, order)).outcome)
            assertEquals(3, link.writes.size)
            assertArrayEquals(bytes(3, 0x20, 1, 0x7D, 2), link.writes[0])
            val payload = if (order == StatusWordWriteOrder.BIG_ENDIAN) bytes(0xA5, 0x93) else bytes(0x93, 0xA5)
            assertArrayEquals(bytes(4, 0x20, 2, 0x7D) + payload, link.writes[1])
            assertArrayEquals(bytes(3, 0x20, 1, 0x7D, 2), link.writes[2])
            assertEquals(0xA593, link.words[0x7D])
            link.session.close()
        }
    }

    @Test fun `unit change clears only mph bit and leaves tail light and unknown bits`() {
        for (order in StatusWordWriteOrder.entries) {
            val link = SyntheticXiaomiPhoneLink(order)
            link.words[0x7D] = 0xA593
            link.session.enableM365Experimental()
            assertEquals(CommandOutcome.READBACK_CONFIRMED,
                link.session.execute(XiaomiSetting.Units(false, order)).outcome)
            val payload = if (order == StatusWordWriteOrder.BIG_ENDIAN) bytes(0xA5, 0x83) else bytes(0x83, 0xA5)
            assertArrayEquals(bytes(4, 0x20, 2, 0x7D) + payload, link.writes[1])
            assertEquals(0xA583, link.words[0x7D])
            assertEquals(3, link.writes.size)
            link.session.close()
        }
    }

    @Test fun `status word older than observation lifetime refuses write`() {
        val link = SyntheticXiaomiPhoneLink()
        link.session.enableM365Experimental()
        link.afterReply = { link.now += 2_001 }
        assertEquals(CommandOutcome.REJECTED,
            link.session.execute(XiaomiSetting.TailLight(true, StatusWordWriteOrder.LITTLE_ENDIAN)).outcome)
        assertEquals(1, link.writes.size)
        assertEquals(1, link.writes[0][2].toInt())
        link.session.close()
    }

    @Test fun `accepted setting with differing readback times out and poisons epoch without retry`() {
        val link = SyntheticXiaomiPhoneLink()
        link.session.enableM365Experimental()
        link.stationary()
        link.onSubmit = { request ->
            if (request[2].toInt() == 1) link.emit(SyntheticXiaomiPhoneLink.reply(0x7B, bytes(1, 0)))
            true
        }
        assertEquals(CommandOutcome.TIMEOUT, link.session.execute(XiaomiSetting.Kers(2)).outcome)
        assertEquals(2, link.writes.size)
        assertEquals(1, link.poisonCount)
        assertFalse(link.transport.connected)
        link.session.execute(XiaomiSetting.Kers(2))
        assertEquals(2, link.writes.size)
        link.session.close()
    }

    @Test fun `rejected submission is not retried and invalid parameter never submits`() {
        val link = SyntheticXiaomiPhoneLink()
        link.session.enableM365Experimental()
        link.stationary()
        assertEquals(CommandOutcome.REJECTED, link.session.execute(XiaomiSetting.Kers(3)).outcome)
        assertTrue(link.writes.isEmpty())
        link.onSubmit = { false }
        assertEquals(CommandOutcome.DISCONNECTED, link.session.execute(XiaomiSetting.Kers(1)).outcome)
        assertEquals(1, link.writes.size)
        assertEquals(1, link.poisonCount)
        link.session.close()
    }

    @Test fun `gate change after readback submission poisons unresolved transaction`() {
        val link = SyntheticXiaomiPhoneLink()
        link.session.enableM365Experimental()
        link.stationary()
        val originalSubmit = link.onSubmit
        link.onSubmit = { request ->
            if (request[2].toInt() == 1) link.speed(1_000, link.now)
            originalSubmit(request)
        }
        assertEquals(CommandOutcome.REJECTED, link.session.execute(XiaomiSetting.Kers(1)).outcome)
        assertEquals(2, link.writes.size)
        assertFalse(link.transport.connected)
        assertEquals(1, link.poisonCount)
        link.stationary()
        link.session.execute(XiaomiSetting.Kers(1))
        assertEquals(2, link.writes.size)
        link.session.close()
    }

    @Test fun `closed session cannot send or restore consent by enabling again`() {
        val link = SyntheticXiaomiPhoneLink()
        link.session.enableM365Experimental()
        link.stationary()
        link.session.close()
        link.session.enableM365Experimental()
        assertEquals(CommandOutcome.DISCONNECTED, link.session.execute(XiaomiSetting.Kers(0)).outcome)
        assertTrue(link.writes.isEmpty())
    }

    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
}

/** Synthetic fake crypto: inner reply bytes are embedded in an independently checksummed envelope.
 * This fixture asserts transaction semantics, not AES or any captured vehicle bytes. */
internal class SyntheticXiaomiPhoneLink(
    private val statusOrder: StatusWordWriteOrder = StatusWordWriteOrder.LITTLE_ENDIAN,
) {
    @Volatile var now = 10_000L
    @Volatile var live = true
    @Volatile var poisonCount = 0
    val writes: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    val words = mutableMapOf(0x7B to 0, 0x7C to 0, 0x7D to 0, 0x3A to 0, 0x25 to 0)
    var afterReply: () -> Unit = {}
    var onSubmit: (ByteArray) -> Boolean = { request ->
        val register = request[3].toInt() and 0xFF
        if (request[2].toInt() == 2) {
            val first = request[4].toInt() and 0xFF
            val second = request[5].toInt() and 0xFF
            words[register] = if (register == 0x7D && statusOrder == StatusWordWriteOrder.BIG_ENDIAN)
                (first shl 8) or second else first or (second shl 8)
        } else {
            val length = request[4].toInt() and 0xFF
            val word = words[register] ?: 0
            val data = ByteArray(length)
            if (length > 0) data[0] = word.toByte()
            if (length > 1) data[1] = (word ushr 8).toByte()
            emit(reply(register, data))
        }
        true
    }
    val transport = XiaomiEncryptedTransport(
        deviceId = "synthetic-device", connectionId = "synthetic-login-1",
        live = { live }, currentMtu = { 23 }, encrypt = { it.copyOf() },
        submit = { writes.add(it.copyOf()); onSubmit(it.copyOf()) },
        decrypt = { frame -> frame.copyOfRange(3, 3 + (frame[2].toInt() and 0xFF) + 5) },
        onReply = { raw, at -> observeReply(raw, at) },
        onPoison = { poisonCount++; live = false }, nowMs = { now },
    )
    val session = XiaomiPhoneSession(transport) { now }

    private fun observeReply(raw: ByteArray, at: Long) { session.observe(raw, at); afterReply() }
    fun emit(raw: ByteArray) { transport.accept(envelope(raw)) }
    fun stationary() = speed(0, now)
    fun speed(rawSpeed: Int, at: Long) {
        val data = ByteArray(32)
        data[8] = 50
        data[10] = rawSpeed.toByte()
        data[11] = (rawSpeed ushr 8).toByte()
        session.observe(reply(0xB0, data), at)
    }

    companion object {
        fun reply(register: Int, data: ByteArray, direction: Int = 0x23, type: Int = 1): ByteArray =
            byteArrayOf(direction.toByte(), type.toByte(), register.toByte()) + data + ByteArray(4)

        fun envelope(raw: ByteArray): ByteArray {
            val size = raw.size - 5
            require(size in 3..255)
            val frame = ByteArray(size + 16)
            frame[0] = 0x55
            frame[1] = 0xAB.toByte()
            frame[2] = size.toByte()
            raw.copyInto(frame, 3)
            val checksum = frame.sliceArray(2 until frame.size - 2)
                .sumOf { it.toInt() and 0xFF }.inv() and 0xFFFF
            frame[frame.size - 2] = checksum.toByte()
            frame[frame.size - 1] = (checksum ushr 8).toByte()
            return frame
        }
    }
}
