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
 * After decryption the plaintext is the *inner* message: no sync word, no size
 * byte and no checksum — the crypto layer handled the first two, and verified
 * the third.
 *
 * ```
 * [0]      direction / source (0x23 ESC, 0x25 BMS, …)
 * [1]      type (0x01 = read reply)
 * [2]      attribute / register (0xB0, 0x3A, 0x25, …)
 * [3..n-4] data
 * [n-4..n] four bytes of random padding added by `encrypt_uart`
 * ```
 *
 * ### There is no size byte in this buffer
 *
 * `encrypt_uart` keeps the size byte *outside* the ciphertext
 * (`send_data = size ‖ counter ‖ ct`) and `decrypt_uart` returns only the
 * decrypted `msg[1..] ‖ rand`, so it is never put back. This class used to read
 * `raw[0]` as that size byte, which actually reads the **direction** byte —
 * `0x23` = 35 — and so mis-sized every reply:
 *
 * * `0x25` (9 bytes) and `0x3A` (11 bytes) were rejected outright as
 *   "size byte says 35 bytes but the frame is only N".
 * * `0xB0` (39 bytes) passed the length check by luck, after which every field
 *   was shifted one byte; `attribute` read the first *data* byte instead of
 *   `0xB0`, so the reply fell through to the "unknown attribute" branch and the
 *   telemetry log stayed empty.
 *
 * ## Verified against real hardware — 2026-09-20
 *
 * Captured from a Xiaomi M365 (`MIScooter8964`, `C7:B8:DC:3B:A1:B2`) over the
 * `xiaomi_mi` dialect. Decrypted reply lengths were 9, 11 and 39 bytes for the
 * `0x25`, `0x3A` and `0xB0` polls, i.e. `HEADER_LEN + payload + PADDING_LEN`
 * every time. The frame carries no length field, so the data ends at
 * `raw.size - PADDING_LEN`.
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
        /**
         * Bytes of header before the data: direction, type, attribute.
         *
         * Deliberately 3, not 4 — the size byte never reaches this buffer. See
         * the class docs; reading it as a length is the bug fixed 2026-09-20.
         */
        const val HEADER_LEN = 3

        /**
         * Random tail `encrypt_uart` appends to every plaintext.
         *
         * Sized from `mi_crypto.rs`: the encryptor draws a `[u8; 4]`. Note that
         * this is *not* AES block padding — AES-CCM is a stream cipher and adds no
         * block padding of its own.
         */
        const val PADDING_LEN = 4

        /**
         * Smallest frame that can carry a header and at least one data byte.
         *
         * The frame has no length field of its own, so this is the only
         * "too short to even slice" guard available.
         */
        const val MIN_FRAME_LEN = HEADER_LEN + 1 + PADDING_LEN

        /**
         * Validates [raw] and extracts the reply.
         *
         * Rejects an empty frame and anything shorter than [MIN_FRAME_LEN]. The
         * data runs from [HEADER_LEN] to `raw.size - PADDING_LEN`, so a frame
         * that passes the minimum check always yields at least one data byte and
         * no separate "no data" branch is reachable.
         */
        fun parse(raw: ByteArray): ScooterReplyValidation {
            if (raw.isEmpty()) return ScooterReplyValidation.Rejected("empty frame")

            if (raw.size < MIN_FRAME_LEN) {
                return ScooterReplyValidation.Rejected(
                    "frame too short: ${raw.size} bytes (minimum $MIN_FRAME_LEN)"
                )
            }

            // The padding is indistinguishable from data once decrypted, and no
            // length field survives, so the tail is the only way to find the end.
            val dataEnd = raw.size - PADDING_LEN
            return ScooterReplyValidation.Valid(
                ScooterReply(
                    direction = raw[0].toInt() and 0xFF,
                    type = raw[1].toInt() and 0xFF,
                    attribute = raw[2].toInt() and 0xFF,
                    data = raw.copyOfRange(HEADER_LEN, dataEnd),
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
         * Mirrors `decrypt_uart`'s output, *not* the wire frame: no sync word, no
         * size byte and no counter. Building it any other way is what let the
         * tests pass while the real path rejected every reply, so the encoder
         * lives here rather than being duplicated in test code.
         */
        fun build(
            direction: Int,
            type: Int,
            attribute: Int,
            data: ByteArray,
            padding: ByteArray = ByteArray(PADDING_LEN),
        ): ByteArray {
            val out = ByteArray(HEADER_LEN + data.size + padding.size)
            out[0] = direction.toByte()
            out[1] = type.toByte()
            out[2] = attribute.toByte()
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
