package com.m365bleapp.protocol

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * NinebotCrypto (`5A A5` + chained AES) frame codec.
 *
 * ## Why this exists in Kotlin
 *
 * `ninebot-ble/src/ninebot_legacy.rs` already implements this cipher, but
 * `ninebot-ffi` exports only the seven `mi_crypto` entry points, so the Rust
 * implementation is unreachable from the app — it is dead code, and the
 * NinebotCrypto dialect has therefore never worked. Porting it here keeps the
 * whole path unit-testable on the JVM with no native build step, which matters
 * because no scooter is available to test against.
 *
 * The Rust source is the specification; every constant and layout below is copied
 * from it, and the tests reuse its published vectors (including the FIPS-197 AES
 * example) so the port can be checked against the original.
 *
 * ## ⚠️ One deliberate difference from [FrameCodec]
 *
 * [FrameCodec.checksum] accumulates into a wide `Int` and then folds once, so it
 * effectively never wraps. The first-frame checksum here must wrap at 16 bits the
 * way the Rust `wrapping_add` does: **300 bytes of `0xFF` give `0x2AD4`, not
 * `0x84FF`**. Using the wrong one produces frames a crypto scooter rejects, which
 * is a failure that looks like "the scooter ignores me" rather than a checksum
 * error. [firstFrameChecksum] is the wrapping one.
 *
 * ## ⚠️ Not verified against real hardware
 *
 * No scooter has been attached to this project. The algorithm matches the Rust
 * reference and its vectors; whether a given physical scooter accepts these
 * frames is unknown.
 */
class NinebotCryptoCipher private constructor(
    private val sha1Key: ByteArray,
    private val bleData: ByteArray,
    private var msgIt: Int,
    private var firstFrame: Boolean,
) {

    companion object {
        /** The `5A A5` sync bytes, passed through in the clear. */
        val SYNC = byteArrayOf(0x5A, 0xA5.toByte())

        /** AES block size. */
        const val BLOCK = 16

        /**
         * Firmware constant used as the nonce's second component *before* the
         * scooter has answered, and as the seed for the pre-handshake key.
         */
        val FW_DATA = byteArrayOf(
            0x97.toByte(), 0xCF.toByte(), 0xB8.toByte(), 0x02, 0x84.toByte(), 0x41, 0x43,
            0xDE.toByte(), 0x56, 0x00, 0x2B, 0x3B, 0x34, 0x78, 0x0A, 0x5D,
        )

        /** Bytes a frame gains over its plaintext (`3 + payload + 6`). */
        const val FRAME_OVERHEAD = 9

        /** Smallest legal frame. */
        const val MIN_FRAME_LEN = FRAME_OVERHEAD

        /** Nonce domain-separation byte for the payload keystream. */
        private const val NONCE_TAG_PAYLOAD: Byte = 0x01

        /** Nonce domain-separation byte for the checksum block. */
        private const val NONCE_TAG_CHECKSUM: Byte = 0x59.toByte()

        /**
         * Builds the cipher for the pre-handshake phase.
         *
         * Before the scooter has answered, the nonce's second component is
         * [FW_DATA] and there is no captured `ble_data` yet. This is the state the
         * first `0x5B` request is encrypted in.
         *
         * @param name the BLE advertised name, padded or truncated to 16 bytes.
         */
        fun preHandshake(name: ByteArray): NinebotCryptoCipher {
            val ble = FW_DATA.copyOf()
            return NinebotCryptoCipher(
                sha1Key = deriveSha1Key(name, ble),
                bleData = ble,
                msgIt = 0,
                firstFrame = true,
            )
        }

        /**
         * Builds the cipher from the captured pairing state.
         *
         * @param name the BLE advertised name, padded or truncated to 16 bytes.
         * @param bleData the 16-byte value from the scooter's first `0x5B` reply.
         */
        fun afterHandshake(name: ByteArray, bleData: ByteArray): NinebotCryptoCipher {
            require(bleData.size == BLOCK) {
                "ble_data must be $BLOCK bytes, got ${bleData.size}"
            }
            return NinebotCryptoCipher(
                sha1Key = deriveSha1Key(name, bleData),
                bleData = bleData.copyOf(),
                msgIt = 0,
                firstFrame = true,
            )
        }

        /**
         * `SHA1(name ‖ bleData)[0..16]`, both inputs padded or truncated to 16.
         *
         * A short name is **zero padded** and a long one truncated, matching the
         * reference, which passes fixed 16-byte buffers. Both behaviours have their
         * own test because a length check here would reject real scooters.
         */
        fun deriveSha1Key(name: ByteArray, bleData: ByteArray): ByteArray {
            require(bleData.size == BLOCK) {
                "ble_data must be $BLOCK bytes, got ${bleData.size}"
            }
            val joined = ByteArray(BLOCK * 2)
            val n = minOf(name.size, BLOCK)
            name.copyInto(joined, 0, 0, n)
            bleData.copyInto(joined, BLOCK)
            return sha1First16(joined)
        }

        /** `SHA1(data)` truncated to its first 16 bytes. */
        fun sha1First16(data: ByteArray): ByteArray {
            // NOSONAR kotlin:S4790 — SHA-1 is fixed by the NinebotCrypto key-derivation
            // spec; it is interop-mandated, not a security choice we can change.
            val digest = MessageDigest.getInstance("SHA-1").digest(data) // NOSONAR
            return digest.copyOf(BLOCK)
        }

        /**
         * One AES-128 ECB block encryption.
         *
         * Uses the platform JCE provider. `AES/ECB/NoPadding` is required rather
         * than `"AES"`, because the default transformation adds PKCS#5 padding and
         * would turn a 16-byte block into 32.
         */
        fun aesEcbEncryptBlock(input: ByteArray, key: ByteArray): ByteArray {
            require(input.size == BLOCK) { "input must be $BLOCK bytes" }
            require(key.size == BLOCK) { "key must be $BLOCK bytes" }
            // NOSONAR kotlin:S5542 — AES-128-ECB single-block is the NinebotCrypto wire
            // format; the scooter firmware requires exactly this, so it is interop-fixed.
            val cipher = Cipher.getInstance("AES/ECB/NoPadding") // NOSONAR
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            return cipher.doFinal(input)
        }

        /**
         * The first frame's 2-byte checksum: `~sum(bytes)`, little-endian.
         *
         * **Accumulates in 16 bits so the sum wraps**, unlike
         * [FrameCodec.checksum]. The Rust reference uses `wrapping_add`; see the
         * class note.
         *
         * @return two bytes, low first.
         */
        fun firstFrameChecksum(payload: ByteArray): ByteArray {
            var sum = 0
            for (b in payload) {
                // Mask to 16 bits after every addition so the sum wraps exactly as
                // the reference does.
                sum = (sum + (b.toInt() and 0xFF)) and 0xFFFF
            }
            val inverted = sum.inv() and 0xFFFF
            return byteArrayOf(
                (inverted and 0xFF).toByte(),
                ((inverted shr 8) and 0xFF).toByte(),
            )
        }

        /** Builds the 16-byte nonce block. */
        internal fun nonce(tag: Byte, msgIt: Int, ble: ByteArray, tail: ByteArray): ByteArray {
            val b = ByteArray(BLOCK)
            b[0] = tag
            // Counter is BIG-endian here, unlike almost every other field in this
            // protocol. The Rust reference and its test both say so.
            b[1] = ((msgIt ushr 24) and 0xFF).toByte()
            b[2] = ((msgIt ushr 16) and 0xFF).toByte()
            b[3] = ((msgIt ushr 8) and 0xFF).toByte()
            b[4] = (msgIt and 0xFF).toByte()
            ble.copyInto(b, 5, 0, 8)
            tail.copyInto(b, 13, 0, 3)
            return b
        }
    }

    /** Current frame counter. */
    val currentMsgIt: Int get() = msgIt

    /** True while no frame has been sent or received yet. */
    val isFirstFrame: Boolean get() = firstFrame

    /**
     * Encrypts a complete plaintext frame into a wire frame.
     *
     * @param src plaintext beginning with the `5A A5` sync bytes, which pass
     *   through in the clear as the protocol requires.
     * @throws IllegalArgumentException when the frame is too short or has bad sync.
     */
    fun encrypt(src: ByteArray): ByteArray {
        require(src.size >= MIN_FRAME_LEN) {
            "frame too short: ${src.size} bytes (minimum $MIN_FRAME_LEN)"
        }
        require(src[0] == SYNC[0] && src[1] == SYNC[1]) {
            "bad sync: %02x %02x".format(src[0], src[1])
        }

        val payload = src.copyOfRange(3, src.size)
        val out = ByteArray(src.size + 6)
        src.copyInto(out, 0, 0, 3)

        if (firstFrame) {
            val crc = firstFrameChecksum(payload)
            val keystream = keystreamFirst()
            for (i in payload.indices) {
                out[3 + i] = (payload[i].toInt() xor keystream[i % BLOCK].toInt()).toByte()
            }
            // 00 00 00 00 then the checksum.
            out[3 + payload.size + 4] = crc[0]
            out[3 + payload.size + 5] = crc[1]
            msgIt += 1
            firstFrame = false
        } else {
            msgIt += 1
            val crc = checksumNext(src, msgIt)
            val keystream = keystreamNext(msgIt)
            for (i in payload.indices) {
                out[3 + i] = (payload[i].toInt() xor keystream[i % BLOCK].toInt()).toByte()
            }
            crc.copyInto(out, 3 + payload.size)
            val low = msgIt and 0xFFFF
            val end = out.size
            out[end - 4] = ((low ushr 8) and 0xFF).toByte()
            out[end - 3] = (low and 0xFF).toByte()
            out[end - 2] = 0x00
            out[end - 1] = 0x00
        }

        return out
    }

    /**
     * Decrypts a wire frame back to plaintext.
     *
     * The counter never moves backwards: a replayed or reordered frame would
     * otherwise reuse a keystream position.
     */
    fun decrypt(src: ByteArray): ByteArray {
        require(src.size >= MIN_FRAME_LEN) {
            "frame too short: ${src.size} bytes (minimum $MIN_FRAME_LEN)"
        }
        require(src[0] == SYNC[0] && src[1] == SYNC[1]) {
            "bad sync: %02x %02x".format(src[0], src[1])
        }

        val out = ByteArray(src.size - 6)
        src.copyInto(out, 0, 0, 3)
        val payloadLen = out.size - 3
        val cipherText = src.copyOfRange(3, 3 + payloadLen)

        if (firstFrame) {
            val keystream = keystreamFirst()
            for (i in cipherText.indices) {
                out[3 + i] = (cipherText[i].toInt() xor keystream[i % BLOCK].toInt()).toByte()
            }
            firstFrame = false
        } else {
            // Recover the peer's counter from the trailer before deriving the
            // keystream, and never let it go backwards.
            val end = src.size
            val low = ((src[end - 4].toInt() and 0xFF) shl 8) or (src[end - 3].toInt() and 0xFF)
            val peerIt = low
            if (peerIt > (msgIt and 0xFFFF)) {
                msgIt = (msgIt and 0xFFFF.inv()) or peerIt
            }
            val keystream = keystreamNext(msgIt)
            for (i in cipherText.indices) {
                out[3 + i] = (cipherText[i].toInt() xor keystream[i % BLOCK].toInt()).toByte()
            }
        }

        return out
    }

    // ------------------------------------------------------------ internals

    /**
     * The counter-0 keystream.
     *
     * Counter 0 has no nonce-derived stream at all: the key itself is encrypted
     * and the resulting block repeated. The Rust reference does the same.
     */
    private fun keystreamFirst(): ByteArray = aesEcbEncryptBlock(FW_DATA, sha1Key)

    /** The keystream block for [it] and index 0. */
    private fun keystreamNext(it: Int): ByteArray {
        val block = nonce(NONCE_TAG_PAYLOAD, it, bleData, byteArrayOf(0, 0, 0))
        block[15] = 0
        return aesEcbEncryptBlock(block, sha1Key)
    }

    /**
     * The 4-byte checksum carried in a subsequent frame's trailer.
     *
     * Not a plain sum: the reference encrypts a nonce whose last three bytes encode
     * `src.len() - 3`, folds the first three source bytes in, then runs successive
     * AES rounds over the rest.
     */
    private fun checksumNext(src: ByteArray, it: Int): ByteArray {
        val encodedLen = (src.size - 3) and 0xFFFFFF
        val tail = byteArrayOf(
            ((encodedLen ushr 16) and 0xFF).toByte(),
            ((encodedLen ushr 8) and 0xFF).toByte(),
            (encodedLen and 0xFF).toByte(),
        )
        val nonce = nonce(NONCE_TAG_CHECKSUM, it, bleData, tail)
        val key = aesEcbEncryptBlock(nonce, sha1Key)

        val state = ByteArray(BLOCK)
        src.copyInto(state, 0, 0, 3)
        for (i in 0 until BLOCK) {
            state[i] = (state[i].toInt() xor key[i].toInt()).toByte()
        }

        var offset = 3
        while (offset < src.size) {
            val round = aesEcbEncryptBlock(state, sha1Key)
            val take = minOf(BLOCK, src.size - offset)
            for (i in 0 until take) {
                state[i] = (round[i].toInt() xor src[offset + i].toInt()).toByte()
            }
            offset += take
        }

        return state.copyOfRange(0, 4)
    }
}
