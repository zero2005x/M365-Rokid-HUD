package com.m365bleapp.protocol

/**
 * Validated view of one decrypted scooter reply.
 *
 * ## The problem this solves
 *
 * `ScooterRepository.parseTelemetry` used to guard inbound data with a single
 * `packet.size < 7` check and then slice by arithmetic. That lets a **truncated**
 * frame through: a reply claiming a 32-byte motor-info payload but carrying only
 * five bytes produced an empty data array, and the downstream parser then read
 * past the end of it. Scootbatt has exactly this bug and reports the resulting
 * `ArrayIndexOutOfBoundsException` to Crashlytics instead of dropping the frame.
 *
 * This class makes the declared length the **first** thing checked, so a frame
 * whose length byte disagrees with its real size is rejected before any field
 * offset is computed. A dropped frame shows a stale value; a mis-parsed one shows
 * a wrong value, and on a speed readout wrong is worse than stale.
 *
 * ## Layout
 *
 * After decryption the plaintext is the *inner* frame — no sync word and no
 * checksum, both of which the crypto layer already handled:
 *
 * ```
 * [0]      size byte (total length of everything that follows, including it)
 * [1]      direction / source (0x23 ESC, 0x25 BMS, …)
 * [2]      type (0x01 = read reply)
 * [3]      attribute / register (0xB0, 0x3A, 0x25, …)
 * [4..n-4] data
 * [n-4..n] four bytes of random padding added by `encrypt_uart`
 * ```
 *
 * The padding is not payload: `encrypt_uart` appends a random 4-byte tail to the
 * plaintext, so the decryptor cannot tell it apart from data and the length byte
 * is the only reliable way to know where the data ends.
 *
 * ## ⚠️ Not verified against real hardware
 *
 * The size-byte convention is inferred from `ninebot-ble/src/session/commands.rs`
 * (`ScooterCommand::as_bytes`) and the existing working loop, not from a capture.
 * No scooter has been attached to this project.
 */
data class ScooterReply(
    /** Direction / source byte, e.g. `0x23` ESC or `0x25` BMS. */
    val direction: Int,
    /** Type byte; `0x01` is a read reply. */
    val type: Int,
    /** Attribute / register this reply answers, e.g. `0xB0`. */
    val attribute: Int,
    /** The reply's data with header and padding removed. */
    val data: ByteArray,
) {

    /** Register this reply answers, as an unsigned int. */
    val register: Int get() = attribute

    companion object {
        /** Bytes of header before the data: size, direction, type, attribute. */
        const val HEADER_LEN = 4

        /**
         * Random tail `encrypt_uart` appends to every plaintext.
         *
         * Sized from `mi_crypto.rs`: the encryptor draws a `[u8; 4]`. Note that
         * this is *not* AES block padding — AES-CCM is a stream cipher and adds no
         * block padding of its own.
         */
        const val PADDING_LEN = 4

        /** Smallest legal size byte: header plus at least one data byte. */
        const val MIN_SIZE_BYTE = HEADER_LEN + 1

        /**
         * Validates [raw] and extracts the reply.
         *
         * Rejects, in order: an empty frame, a frame shorter than
         * [MIN_SIZE_BYTE] + [PADDING_LEN], a size byte below [MIN_SIZE_BYTE], a
         * size byte that disagrees with the actual frame length, and a frame with
         * no data bytes. Every one of those would otherwise reach a field offset
         * calculation.
         */
        fun parse(raw: ByteArray): ScooterReplyValidation {
            if (raw.isEmpty()) return ScooterReplyValidation.Rejected("empty frame")

            // Check the header fields in the order that yields the most specific
            // reason. Reading the size byte first is safe once we know at least
            // one byte exists, and it keeps a genuinely bad size byte from being
            // reported as the vaguer "frame too short".
            val size = raw[0].toInt() and 0xFF
            if (size < MIN_SIZE_BYTE) {
                return ScooterReplyValidation.Rejected(
                    "size byte $size below the $MIN_SIZE_BYTE-byte minimum"
                )
            }

            // Smallest conceivable frame: size + direction + type + attribute + at
            // least one data byte, plus the encryptor's random tail.
            val minimum = MIN_SIZE_BYTE + PADDING_LEN
            if (raw.size < minimum) {
                return ScooterReplyValidation.Rejected("frame too short: ${raw.size} bytes (minimum $minimum)")
            }

            // The size byte counts the original message, which does NOT include
            // the 4-byte random tail `encrypt_uart` appends after encryption. So
            // the frame on the wire may legitimately be longer than `size`, and an
            // equality check would reject every valid reply.
            //
            // What can still be checked — and is the truncation guard that
            // matters — is that the frame is long enough to actually contain the
            // data the header claims. This is exactly the case that used to slip
            // through: a 0xB0 reply announcing 32 bytes with one byte present.
            if (raw.size < size) {
                return ScooterReplyValidation.Rejected(
                    "size byte says $size bytes but the frame is only ${raw.size}"
                )
            }

            val dataLen = size - HEADER_LEN
            if (dataLen <= 0) {
                return ScooterReplyValidation.Rejected("no data bytes after the header")
            }

            val data = raw.copyOfRange(HEADER_LEN, HEADER_LEN + dataLen)
            return ScooterReplyValidation.Valid(
                ScooterReply(
                    direction = raw[1].toInt() and 0xFF,
                    type = raw[2].toInt() and 0xFF,
                    attribute = raw[3].toInt() and 0xFF,
                    data = data,
                )
            )
        }

        /**
         * Convenience wrapper returning the reply or `null`.
         *
         * Use [parse] when the rejection reason matters for diagnostics.
         */
        fun parseOrNull(raw: ByteArray): ScooterReply? =
            (parse(raw) as? ScooterReplyValidation.Valid)?.reply

        /**
         * Builds a synthetic frame for tests and for the demo mode.
         *
         * Kept here rather than duplicated in test code so the encoder and the
         * validator cannot drift apart: a test that builds frames differently from
         * the parser's expectation would pass while the real path was broken.
         */
        fun build(
            direction: Int,
            type: Int,
            attribute: Int,
            data: ByteArray,
            padding: ByteArray = ByteArray(PADDING_LEN),
        ): ByteArray {
            // `size` counts the original message only; the random tail the
            // encryptor appends is deliberately excluded, mirroring the wire.
            val size = HEADER_LEN + data.size
            val out = ByteArray(size + padding.size)
            out[0] = size.toByte()
            out[1] = direction.toByte()
            out[2] = type.toByte()
            out[3] = attribute.toByte()
            data.copyInto(out, HEADER_LEN)
            padding.copyInto(out, HEADER_LEN + data.size)
            return out
        }
    }
}

/**
 * Outcome of validating a raw decrypted reply.
 *
 * A sealed result rather than a nullable return so the caller can log *why* a
 * frame was dropped: "dropped for a bad length" and "dropped because it was
 * empty" are very different field reports.
 *
 * Deliberately **top level**, not nested in [ScooterReply]. A class nested inside
 * a `companion object` is addressed through the companion and did not resolve as
 * `ScooterReply.Validation` from the test source set, and naming it `Result`
 * collided with `kotlin.Result`. A top-level name avoids both problems.
 */
sealed class ScooterReplyValidation {
    /** The frame was well formed. */
    data class Valid(val reply: ScooterReply) : ScooterReplyValidation()
    /** The frame was rejected; [reason] is suitable for a log line. */
    data class Rejected(val reason: String) : ScooterReplyValidation()
}
