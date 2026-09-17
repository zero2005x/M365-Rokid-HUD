package com.m365bleapp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [NinebotHandshake].
 *
 * The state machine is pure, so the whole sequence is exercised here: no radio,
 * no scooter. What these tests cannot say is whether a real scooter accepts
 * [NinebotHandshake.APP_DATA] — that is listed as unverified in the class doc.
 */
class NinebotHandshakeTest {

    private val name = "MIScooter1234\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun handshake() = NinebotHandshake(name)

    /** Builds a step-1 reply payload of exactly the required length. */
    private fun preCommReply(): ByteArray {
        val payload = ByteArray(NinebotHandshake.PRE_COMM_REPLY_LENGTH)
        // Fill so the UID and ble_data regions are distinguishable.
        for (i in payload.indices) payload[i] = (i + 1).toByte()
        return payload
    }

    /** A reply payload long enough to be accepted by steps 2 and 3. */
    private fun shortReply(): ByteArray = byteArrayOf(0x21, 0x3E, 0x5C, 0x01)

    // ------------------------------------------------------------ step 1

    @Test
    fun `the first frame is the pre comm request`() {
        val frame = requireNotNull(handshake().nextFrame())

        assertArrayEquals(
            byteArrayOf(FrameCodec.BT_ID, NinebotHandshake.ADDRESS_BLE, NinebotHandshake.ACTION_PRE_COMM, 0x00),
            frame,
        )
    }

    @Test
    fun `the pre comm frame carries no payload`() {
        // APP_DATA belongs to step 2; sending it in step 1 would confuse a scooter
        // that takes the payload as the password.
        assertEquals(4, requireNotNull(handshake().nextFrame()).size)
    }

    @Test
    fun `the pre comm stage waits the documented interval`() {
        val h = handshake()
        h.nextFrame()

        assertEquals(NinebotHandshake.PRE_COMM_INTERVAL_MS, h.retryIntervalMs)
    }

    @Test
    fun `a pre comm reply advances to set password`() {
        val h = handshake()
        h.nextFrame()

        assertTrue(h.acceptReply(preCommReply()))

        assertEquals(NinebotHandshake.Stage.AWAITING_SET_PWD, h.stage)
    }

    @Test
    fun `a pre comm reply yields the uid and ble data`() {
        val h = handshake()
        h.nextFrame()
        h.acceptReply(preCommReply())

        val uid = requireNotNull(h.uid)
        val ble = requireNotNull(h.bleData)

        assertEquals(NinebotHandshake.UID_LENGTH, uid.size)
        assertEquals(NinebotHandshake.BLE_DATA_LENGTH, ble.size)
        // The reply was filled with 1..30, so the UID starts at value 17 (index 16).
        assertEquals(17, uid[0].toInt() and 0xFF)
        // ble_data starts at index 7, i.e. value 8.
        assertEquals(8, ble[0].toInt() and 0xFF)
    }

    @Test
    fun `a short pre comm reply does not advance and is not fatal`() {
        // Scootbatt resends on a wrong length rather than giving up, so this must
        // report no progress without failing the handshake.
        val h = handshake()
        h.nextFrame()

        assertFalse(h.acceptReply(ByteArray(NinebotHandshake.PRE_COMM_REPLY_LENGTH - 1)))

        assertEquals(NinebotHandshake.Stage.AWAITING_PRE_COMM, h.stage)
        assertNotNull(h.failureReason)
        assertNull(h.uid)
    }

    @Test
    fun `a long pre comm reply does not advance`() {
        // A longer reply means the framing is out of sync; reading a UID from it
        // would silently derive the wrong key.
        val h = handshake()
        h.nextFrame()

        assertFalse(h.acceptReply(ByteArray(NinebotHandshake.PRE_COMM_REPLY_LENGTH + 1)))

        assertEquals(NinebotHandshake.Stage.AWAITING_PRE_COMM, h.stage)
        assertNull(h.uid)
    }

    @Test
    fun `an empty reply is ignored`() {
        val h = handshake()
        h.nextFrame()

        assertFalse(h.acceptReply(ByteArray(0)))

        assertEquals(NinebotHandshake.Stage.AWAITING_PRE_COMM, h.stage)
    }

    // ------------------------------------------------------------ step 2

    @Test
    fun `the set password frame carries the app data constant`() {
        val h = handshake()
        h.nextFrame()
        h.acceptReply(preCommReply())

        val frame = requireNotNull(h.nextFrame())

        assertEquals(4 + NinebotHandshake.APP_DATA.size, frame.size)
        assertArrayEquals(NinebotHandshake.APP_DATA, frame.copyOfRange(4, frame.size))
        assertEquals(NinebotHandshake.ACTION_SET_PWD, frame[2])
    }

    @Test
    fun `the app data constant is the documented sixteen bytes`() {
        // Asserted explicitly because swapping this for a random value would break
        // the determinism the whole scheme relies on.
        val expected = byteArrayOf(
            0x4A, 0xEE.toByte(), 0xBD.toByte(), 0x73, 0xE2.toByte(), 0x16, 0x1C, 0x11,
            0x2D, 0x06, 0x5A, 0x49, 0xCC.toByte(), 0x6E, 0x8B.toByte(), 0xB7.toByte(),
        )
        assertArrayEquals(expected, NinebotHandshake.APP_DATA)
        assertEquals(16, NinebotHandshake.APP_DATA.size)
    }

    @Test
    fun `pairing stages wait the shorter interval`() {
        val h = handshake()
        h.nextFrame()
        h.acceptReply(preCommReply())

        assertEquals(NinebotHandshake.PAIRING_INTERVAL_MS, h.retryIntervalMs)
    }

    @Test
    fun `a set password reply builds the cipher and advances`() {
        val h = handshake()
        h.nextFrame()
        h.acceptReply(preCommReply())
        h.nextFrame()

        assertTrue(h.acceptReply(shortReply()))

        assertEquals(NinebotHandshake.Stage.AWAITING_AUTH, h.stage)
        assertNotNull(h.cipher)
    }

    @Test
    fun `the cipher is built from the captured ble data`() {
        // Two handshakes with identical replies must produce identical ciphers,
        // which is what makes a session-key-less scheme work at all.
        val a = handshake()
        a.nextFrame(); a.acceptReply(preCommReply()); a.nextFrame(); a.acceptReply(shortReply())

        val b = handshake()
        b.nextFrame(); b.acceptReply(preCommReply()); b.nextFrame(); b.acceptReply(shortReply())

        val frame = byteArrayOf(0x5A, 0xA5.toByte(), 0x06) + ByteArray(6)
        assertArrayEquals(
            requireNotNull(a.cipher).encrypt(frame),
            requireNotNull(b.cipher).encrypt(frame),
        )
    }

    @Test
    fun `a too short set password reply does not advance`() {
        val h = handshake()
        h.nextFrame()
        h.acceptReply(preCommReply())
        h.nextFrame()

        assertFalse(h.acceptReply(byteArrayOf(0x01)))

        assertEquals(NinebotHandshake.Stage.AWAITING_SET_PWD, h.stage)
        assertNull(h.cipher)
    }

    // ------------------------------------------------------------ step 3

    @Test
    fun `the auth frame carries the last uid byte`() {
        val h = handshake()
        h.nextFrame()
        h.acceptReply(preCommReply())
        h.nextFrame()
        h.acceptReply(shortReply())

        val frame = requireNotNull(h.nextFrame())
        val uid = requireNotNull(h.uid)

        assertEquals(NinebotHandshake.ACTION_AUTH, frame[2])
        assertEquals(1, frame.size - 4)
        assertEquals(uid[uid.size - 1], frame[4])
    }

    @Test
    fun `the auth reply completes the handshake`() {
        val h = handshake()
        h.nextFrame()
        h.acceptReply(preCommReply())
        h.nextFrame()
        h.acceptReply(shortReply())
        h.nextFrame()

        assertTrue(h.acceptReply(shortReply()))

        assertEquals(NinebotHandshake.Stage.PAIRED, h.stage)
        assertTrue(h.isPaired)
    }

    @Test
    fun `nextFrame returns null once paired`() {
        val h = paired()

        assertNull(h.nextFrame())
    }

    @Test
    fun `replies are ignored once paired`() {
        // Accepting further handshake replies could restart a completed pairing.
        val h = paired()

        assertFalse(h.acceptReply(shortReply()))

        assertEquals(NinebotHandshake.Stage.PAIRED, h.stage)
    }

    @Test
    fun `a too short auth reply does not complete`() {
        val h = handshake()
        h.nextFrame()
        h.acceptReply(preCommReply())
        h.nextFrame()
        h.acceptReply(shortReply())
        h.nextFrame()

        assertFalse(h.acceptReply(ByteArray(2)))

        assertEquals(NinebotHandshake.Stage.AWAITING_AUTH, h.stage)
    }

    // -------------------------------------------------------------- failure

    @Test
    fun `fail records the reason`() {
        val h = handshake()
        h.nextFrame()

        h.fail("no reply within 900 ms")

        assertEquals(NinebotHandshake.Stage.FAILED, h.stage)
        assertEquals("no reply within 900 ms", h.failureReason)
        assertFalse(h.isPaired)
    }

    @Test
    fun `fail is refused once paired`() {
        // A late timeout must not undo a completed handshake.
        val h = paired()

        h.fail("stale timeout")

        assertEquals(NinebotHandshake.Stage.PAIRED, h.stage)
    }

    @Test
    fun `nextFrame returns null after failure`() {
        val h = handshake()
        h.nextFrame()
        h.fail("giving up")

        assertNull(h.nextFrame())
    }

    /** Runs the handshake to completion. */
    private fun paired(): NinebotHandshake {
        val h = handshake()
        h.nextFrame()
        h.acceptReply(preCommReply())
        h.nextFrame()
        h.acceptReply(shortReply())
        h.nextFrame()
        h.acceptReply(shortReply())
        return h
    }
}
