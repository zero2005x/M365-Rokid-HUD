package com.m365bleapp.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [NinebotCryptoCipher].
 *
 * Where the Rust reference publishes a vector, that vector is reused verbatim so
 * the port can be checked against the original rather than only against itself.
 * The four cross-language vectors are marked `RUST VECTOR`.
 */
class NinebotCryptoCipherTest {

    /** The name the Rust tests use. */
    private val name = "MIScooter1234\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

    /** A fixed 16-byte `ble_data` for deterministic tests. */
    private val bleData = ByteArray(16) { (it + 1).toByte() }

    private fun cipher() = NinebotCryptoCipher.afterHandshake(name, bleData)

    /**
     * Builds a plaintext frame, padding the payload to the minimum size.
     *
     * Mirrors the Rust helper, which pads `body` up to `MIN_FRAME_LEN - 3` so the
     * resulting frame is at least `MIN_FRAME_LEN`. Without the padding a short
     * payload produces a frame the codec rejects, which is what an earlier version
     * of this helper got wrong.
     *
     * The length byte is `payload + 6`: the six bytes the envelope adds are counted
     * in the header, matching the Rust builder.
     */
    private fun plainFrame(payload: ByteArray): ByteArray {
        val body = payload.copyOf(maxOf(payload.size, NinebotCryptoCipher.MIN_FRAME_LEN - 3))
        val out = ByteArray(3 + body.size)
        out[0] = NinebotCryptoCipher.SYNC[0]
        out[1] = NinebotCryptoCipher.SYNC[1]
        out[2] = (body.size + 6).toByte()
        body.copyInto(out, 3)
        return out
    }

    // --------------------------------------------------- RUST VECTOR: SHA-1

    @Test
    fun `sha1 first sixteen matches a published digest`() {
        // RUST VECTOR: sha1_first16(b"abc") == SHA1("abc")[0..16].
        // SHA1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d
        val expected = byteArrayOf(
            0xA9.toByte(), 0x99.toByte(), 0x3E, 0x36, 0x47, 0x06, 0x81.toByte(), 0x6A,
            0xBA.toByte(), 0x3E, 0x25, 0x71, 0x78, 0x50, 0xC2.toByte(), 0x6C,
        )

        assertArrayEquals(expected, NinebotCryptoCipher.sha1First16("abc".toByteArray()))
    }

    // -------------------------------------------- RUST VECTOR: key derivation

    @Test
    fun `key derivation is deterministic`() {
        assertArrayEquals(
            NinebotCryptoCipher.deriveSha1Key(name, bleData),
            NinebotCryptoCipher.deriveSha1Key(name, bleData),
        )
    }

    @Test
    fun `the name affects the key`() {
        // RUST VECTOR: a different name must not produce the same key.
        val other = "MIScooter9999\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

        assertFalse(
            NinebotCryptoCipher.deriveSha1Key(name, bleData)
                .contentEquals(NinebotCryptoCipher.deriveSha1Key(other, bleData))
        )
    }

    @Test
    fun `a name longer than sixteen bytes is truncated not rejected`() {
        // RUST VECTOR: the reference passes fixed 16-byte buffers, so a long name
        // must behave as its first 16 bytes rather than throwing.
        val long = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray()
        val truncated = "ABCDEFGHIJKLMNOP".toByteArray()

        assertArrayEquals(
            NinebotCryptoCipher.deriveSha1Key(truncated, bleData),
            NinebotCryptoCipher.deriveSha1Key(long, bleData),
        )
    }

    @Test
    fun `a short name is zero padded`() {
        // RUST VECTOR: derive_sha1_key(b"abc") == derive_sha1_key(b"abc\0…").
        val padded = ByteArray(16).also { "abc".toByteArray().copyInto(it) }

        assertArrayEquals(
            NinebotCryptoCipher.deriveSha1Key(padded, bleData),
            NinebotCryptoCipher.deriveSha1Key("abc".toByteArray(), bleData),
        )
    }

    @Test
    fun `key derivation rejects a ble_data of the wrong length`() {
        // Silently accepting 8 bytes would derive a key from a truncated nonce and
        // produce frames the scooter rejects with no clue why.
        assertThrows(IllegalArgumentException::class.java) {
            NinebotCryptoCipher.deriveSha1Key(name, ByteArray(8))
        }
    }

    // ----------------------------------------------------- RUST VECTOR: AES

    @Test
    fun `aes ecb matches the published FIPS-197 vector`() {
        // RUST VECTOR: FIPS-197 Appendix B / C.1 example.
        val key = ByteArray(16) { it.toByte() }
        val plain = byteArrayOf(
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(),
            0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        val expected = byteArrayOf(
            0x69, 0xC4.toByte(), 0xE0.toByte(), 0xD8.toByte(), 0x6A, 0x7B, 0x04, 0x30,
            0xD8.toByte(), 0xCD.toByte(), 0xB7.toByte(), 0x80.toByte(),
            0x70, 0xB4.toByte(), 0xC5.toByte(), 0x5A,
        )

        assertArrayEquals(expected, NinebotCryptoCipher.aesEcbEncryptBlock(plain, key))
    }

    @Test
    fun `aes ecb does not pad`() {
        // "AES/ECB/NoPadding" is required: the default "AES" transformation adds
        // PKCS#5 padding and would return 32 bytes, corrupting every frame.
        val out = NinebotCryptoCipher.aesEcbEncryptBlock(ByteArray(16), ByteArray(16))
        assertEquals(16, out.size)
    }

    // ------------------------------------------------ RUST VECTOR: checksum

    @Test
    fun `first frame checksum inverts the sum little endian`() {
        // RUST VECTOR: ~(1+2+3) & 0xFFFF = 0xFFF9, little-endian => F9 FF
        assertArrayEquals(
            byteArrayOf(0xF9.toByte(), 0xFF.toByte()),
            NinebotCryptoCipher.firstFrameChecksum(byteArrayOf(1, 2, 3)),
        )
    }

    @Test
    fun `an empty payload checksums to all ones`() {
        // RUST VECTOR
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
            NinebotCryptoCipher.firstFrameChecksum(ByteArray(0)),
        )
    }

    @Test
    fun `first frame checksum wraps at sixteen bits`() {
        // RUST VECTOR: 300 x 0xFF sums to 76500; 76500 mod 65536 = 10964 = 0x2AD4;
        // inverted = 0xD52B, little-endian => 2B D5.
        //
        // This is THE test that distinguishes this checksum from
        // FrameCodec.checksum, which accumulates in a wide Int and never wraps.
        // Using the non-wrapping version here would yield a different value and
        // every crypto frame would be rejected.
        val payload = ByteArray(300) { 0xFF.toByte() }

        assertArrayEquals(
            byteArrayOf(0x2B, 0xD5.toByte()),
            NinebotCryptoCipher.firstFrameChecksum(payload),
        )
    }

    @Test
    fun `the wrapping checksum differs from the non wrapping one on a long frame`() {
        // Guards the distinction directly: if FrameCodec.checksum is ever swapped
        // in here, this fails.
        val payload = ByteArray(300) { 0xFF.toByte() }

        val wrapping = NinebotCryptoCipher.firstFrameChecksum(payload)
        val nonWrapping = FrameCodec.checksum(payload, skipLeading = 0)

        assertNotEquals(
            (nonWrapping and 0xFFFF),
            ((wrapping[0].toInt() and 0xFF) or ((wrapping[1].toInt() and 0xFF) shl 8)),
        )
    }

    // --------------------------------------------------------------- nonce

    @Test
    fun `nonce tags are distinct`() {
        // Domain separation: reusing one tag for both purposes would make a
        // checksum block equal a payload block and leak the keystream relation.
        val payload = NinebotCryptoCipher.nonce(0x01, 1, bleData, byteArrayOf(0, 0, 0))
        val checksum = NinebotCryptoCipher.nonce(0x59, 1, bleData, byteArrayOf(0, 0, 0))

        assertNotEquals(payload[0], checksum[0])
        assertEquals(0x01.toByte(), payload[0])
        assertEquals(0x59.toByte(), checksum[0])
    }

    @Test
    fun `the nonce carries the counter big endian`() {
        // RUST VECTOR: nonce(tag, 0x01020304, ..)[1..5] == 01 02 03 04.
        // Big-endian is unusual for this protocol, which is why it is asserted.
        val n = NinebotCryptoCipher.nonce(0x01, 0x01020304, bleData, byteArrayOf(0, 0, 0))

        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04),
            n.copyOfRange(1, 5),
        )
    }

    @Test
    fun `the nonce embeds eight bytes of ble data and a three byte tail`() {
        val tail = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val n = NinebotCryptoCipher.nonce(0x01, 0, bleData, tail)

        assertArrayEquals(bleData.copyOfRange(0, 8), n.copyOfRange(5, 13))
        assertArrayEquals(tail, n.copyOfRange(13, 16))
    }

    // ------------------------------------------------------------ encrypt

    @Test
    fun `encrypt passes the sync bytes through in the clear`() {
        val wire = cipher().encrypt(plainFrame(byteArrayOf(1, 2, 3, 4, 5, 6)))

        assertEquals(NinebotCryptoCipher.SYNC[0], wire[0])
        assertEquals(NinebotCryptoCipher.SYNC[1], wire[1])
    }

    @Test
    fun `encrypt adds six bytes of overhead over the plaintext`() {
        val plain = plainFrame(ByteArray(10))
        val wire = cipher().encrypt(plain)

        assertEquals(plain.size + 6, wire.size)
    }

    @Test
    fun `the first frame ends with a four byte zero pad plus the checksum`() {
        val payload = byteArrayOf(9, 9, 9, 9)
        val wire = cipher().encrypt(plainFrame(payload))
        val crc = NinebotCryptoCipher.firstFrameChecksum(payload)

        // Layout: [3 header][payload][00 00 00 00][crc lo][crc hi]
        val tail = wire.copyOfRange(wire.size - 6, wire.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, crc[0], crc[1]), tail)
    }

    @Test
    fun `the first frame consumes the first-frame flag and advances the counter`() {
        val c = cipher()
        assertTrue(c.isFirstFrame)
        assertEquals(0, c.currentMsgIt)

        c.encrypt(plainFrame(ByteArray(4)))

        assertFalse(c.isFirstFrame)
        assertEquals(1, c.currentMsgIt)
    }

    @Test
    fun `subsequent frames carry the counter in the trailer`() {
        val c = cipher()
        c.encrypt(plainFrame(ByteArray(4))) // first frame
        val second = c.encrypt(plainFrame(ByteArray(4)))

        // Counter 2, big-endian in the final four bytes' first two positions.
        val end = second.size
        assertEquals(0x00.toByte(), second[end - 4])
        assertEquals(0x02.toByte(), second[end - 3])
        assertEquals(0x00.toByte(), second[end - 2])
        assertEquals(0x00.toByte(), second[end - 1])
    }

    @Test
    fun `encrypt rejects a short frame`() {
        assertThrows(IllegalArgumentException::class.java) {
            cipher().encrypt(ByteArray(NinebotCryptoCipher.MIN_FRAME_LEN - 1))
        }
    }

    @Test
    fun `encrypt rejects bad sync bytes`() {
        // Encrypting a frame the scooter will discard wastes a counter value.
        val bad = byteArrayOf(0x00, 0x00, 0x00) + ByteArray(6)
        assertThrows(IllegalArgumentException::class.java) {
            cipher().encrypt(bad)
        }
    }

    // ------------------------------------------------------------ decrypt

    @Test
    fun `a first frame round trips`() {
        val plain = plainFrame(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val wire = cipher().encrypt(plain)
        val back = cipher().decrypt(wire)

        assertArrayEquals(plain, back)
    }

    @Test
    fun `subsequent frames round trip across several counters`() {
        // A single-frame test would not exercise the counter-derived keystream or
        // the rolling checksum at all.
        val sender = cipher()
        val receiver = cipher()

        repeat(6) { index ->
            val plain = plainFrame(byteArrayOf(index.toByte(), 2, 3, 4, 5, 6))
            val wire = sender.encrypt(plain)
            val back = receiver.decrypt(wire)
            assertArrayEquals("frame $index", plain, back)
        }
    }

    @Test
    fun `decrypt rejects a short frame`() {
        assertThrows(IllegalArgumentException::class.java) {
            cipher().decrypt(ByteArray(NinebotCryptoCipher.MIN_FRAME_LEN - 1))
        }
    }

    @Test
    fun `decrypt rejects bad sync bytes`() {
        val bad = byteArrayOf(0x11, 0x22, 0x00) + ByteArray(6)
        assertThrows(IllegalArgumentException::class.java) {
            cipher().decrypt(bad)
        }
    }

    @Test
    fun `the counter never moves backwards on a replayed frame`() {
        // Reusing a keystream position would let an observer recover plaintext, so
        // a replayed frame must not rewind the counter.
        val sender = cipher()
        val receiver = cipher()

        val first = sender.encrypt(plainFrame(ByteArray(4)))
        val second = sender.encrypt(plainFrame(ByteArray(4)))
        receiver.decrypt(first)
        receiver.decrypt(second)
        val afterBoth = receiver.currentMsgIt

        receiver.decrypt(first) // replay of an older frame

        assertEquals(
            "the counter must not rewind",
            afterBoth,
            receiver.currentMsgIt,
        )
    }

    // ------------------------------------------------------------- pre-phase

    @Test
    fun `pre handshake starts from the firmware constant`() {
        val c = NinebotCryptoCipher.preHandshake(name)

        assertTrue(c.isFirstFrame)
        assertEquals(0, c.currentMsgIt)
    }

    @Test
    fun `pre handshake and post handshake derive different keys`() {
        // They must differ, or the first 0x5B request would be encrypted with the
        // key that only becomes valid after its reply.
        val pre = NinebotCryptoCipher.preHandshake(name).encrypt(plainFrame(ByteArray(4)))
        val post = cipher().encrypt(plainFrame(ByteArray(4)))

        assertFalse(pre.contentEquals(post))
    }

    @Test
    fun `a pre handshake frame round trips`() {
        val plain = plainFrame(byteArrayOf(1, 2, 3, 4))
        val wire = NinebotCryptoCipher.preHandshake(name).encrypt(plain)
        val back = NinebotCryptoCipher.preHandshake(name).decrypt(wire)

        assertArrayEquals(plain, back)
    }
}
