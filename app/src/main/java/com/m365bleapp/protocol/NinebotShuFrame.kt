package com.m365bleapp.protocol

/**
 * Classic Ninebot SHU register frames inside the encrypted 5A A5 envelope.
 * Unlike FrameCodec.P2 these frames have no extra BT_ID or plaintext CRC.
 * Experimental: no on-scooter G30 verification yet.
 */
internal object NinebotShuFrame {
    private const val FIXED_LENGTH = 7
    private const val TRAILER_LENGTH = 6

    fun read(register: Int, count: Int): ByteArray {
        require(register in 0..255 && count in 1..255)
        return byteArrayOf(
            0x5A, 0xA5.toByte(), 1, 0x3E, 0x20, 1,
            register.toByte(), count.toByte()
        )
    }

    fun encryptedLength(header: ByteArray): Int? {
        if (header.size < 3) return null
        require(header[0] == 0x5A.toByte() && header[1] == 0xA5.toByte())
        return FIXED_LENGTH + TRAILER_LENGTH + (header[2].toInt() and 255)
    }

    fun replyPayload(plain: ByteArray, register: Int): ByteArray? {
        if (plain.size < FIXED_LENGTH ||
            plain[0] != 0x5A.toByte() || plain[1] != 0xA5.toByte()) return null
        val length = plain[2].toInt() and 255
        if (plain.size != FIXED_LENGTH + length ||
            plain[3] != 0x20.toByte() || plain[4] != 0x3E.toByte() ||
            plain[5] != 1.toByte() || (plain[6].toInt() and 255) != register) return null
        return plain.copyOfRange(FIXED_LENGTH, plain.size)
    }
}

/** One instance per BLE connection; do not call concurrently. */
internal class NinebotEncryptedReadSession(private val cipher: NinebotCryptoCipher) {
    fun buildRead(register: Int, count: Int): ByteArray =
        cipher.encrypt(NinebotShuFrame.read(register, count))

    fun accept(register: Int, ciphertext: ByteArray): ByteArray? {
        val expected = NinebotShuFrame.encryptedLength(ciphertext) ?: return null
        if (ciphertext.size != expected) return null
        return NinebotShuFrame.replyPayload(cipher.decrypt(ciphertext), register)
    }
}

/**
 * Reassemble one encrypted SHU frame from arbitrary BLE notification fragments.
 * Header bytes can arrive separately; a truncated or concatenated frame must
 * never be passed to the stateful decryptor.
 */
internal class NinebotEncryptedFrameBuffer {
    private var pending = ByteArray(0)

    fun push(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) return null
        if (pending.size + chunk.size > 268) {
            pending = ByteArray(0)
            throw IllegalArgumentException("Ninebot encrypted frame exceeds 268 bytes")
        }
        pending += chunk
        if (pending[0] != 0x5A.toByte() ||
            (pending.size >= 2 && pending[1] != 0xA5.toByte())) {
            pending = ByteArray(0)
            throw IllegalArgumentException("Invalid Ninebot SHU sync")
        }
        val expected = NinebotShuFrame.encryptedLength(pending) ?: return null
        if (pending.size > expected) {
            pending = ByteArray(0)
            throw IllegalArgumentException("Concatenated or overflowed Ninebot frame")
        }
        if (pending.size < expected) return null
        return pending.also { pending = ByteArray(0) }
    }
}
