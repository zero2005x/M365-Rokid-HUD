package io.github.zero2005x.pev.core.codec.xiaomi

/**
 * Xiaomi/M365 register messages at the *logical* level: the bytes the app's crypto
 * adapter encrypts and frames. Sync words, AES and the outer checksum stay in the
 * app/Rust layer; this core never sees key material.
 *
 * Outbound layout (as seen in a real M365 capture, e.g. `03 20 01 B0 20`):
 * `[len = payload + 2, to, cmd, register, payload...]`.
 * Inbound (decrypted) layout: `[direction, type, register, data..., 4 padding bytes]`.
 */
object XiaomiPdu {
    const val ADDR_ESC = 0x20
    const val ADDR_BMS = 0x22
    const val CMD_READ = 0x01
    const val CMD_WRITE = 0x02

    const val REG_MOTOR_INFO = 0xB0
    const val REG_KERS = 0x7B
    const val REG_CRUISE = 0x7C
    const val REG_STATUS_WORD = 0x7D

    fun read(register: Int, length: Int, to: Int = ADDR_ESC): ByteArray =
        message(to, CMD_READ, register, byteArrayOf(length.toByte()))

    fun write(register: Int, payload: ByteArray, to: Int = ADDR_ESC): ByteArray =
        message(to, CMD_WRITE, register, payload)

    private fun message(to: Int, cmd: Int, register: Int, payload: ByteArray): ByteArray {
        require(payload.size + 2 <= 0xFF) { "payload too long" }
        return byteArrayOf((payload.size + 2).toByte(), to.toByte(), cmd.toByte(), register.toByte()) + payload
    }
}

/** A decrypted inbound reply. [data] excludes header and the 4 trailing padding bytes. */
class XiaomiReply(val direction: Int, val type: Int, val register: Int, val data: ByteArray) {
    companion object {
        const val HEADER_LEN = 3
        const val PADDING_LEN = 4
        const val MIN_LEN = HEADER_LEN + 1 + PADDING_LEN

        /** Null for anything too short to carry a header, one data byte and the padding. */
        fun parse(raw: ByteArray): XiaomiReply? {
            if (raw.size < MIN_LEN) return null
            return XiaomiReply(
                raw[0].toInt() and 0xFF,
                raw[1].toInt() and 0xFF,
                raw[2].toInt() and 0xFF,
                raw.copyOfRange(HEADER_LEN, raw.size - PADDING_LEN),
            )
        }
    }
}

/** Little-endian readers with explicit bounds; null instead of an exception. */
internal object Le {
    fun u8(d: ByteArray, o: Int): Int? = if (o in d.indices) d[o].toInt() and 0xFF else null

    fun u16(d: ByteArray, o: Int): Int? =
        if (o >= 0 && o + 2 <= d.size) (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8) else null

    fun i16(d: ByteArray, o: Int): Int? = u16(d, o)?.let { if (it >= 0x8000) it - 0x10000 else it }

    fun u32(d: ByteArray, o: Int): Long? {
        val lo = u16(d, o) ?: return null
        val hi = u16(d, o + 2) ?: return null
        return (hi.toLong() shl 16) or lo.toLong()
    }
}
