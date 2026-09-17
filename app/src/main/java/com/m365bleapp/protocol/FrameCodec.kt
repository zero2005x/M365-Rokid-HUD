package com.m365bleapp.protocol

/**
 * Frame codec for the legacy Ninebot/Xiaomi ESC wire formats.
 *
 * ## Wire layout
 *
 * ```
 * [sync hi][sync lo][len][src][dst][cmd][arg][payload…][checksum lo][checksum hi]
 *                    └───────────────── body ─────────────────┘
 * ```
 *
 * `len` counts the body, i.e. `4 + payload.size`.
 *
 * | Element | Meaning |
 * |---------|---------|
 * | `src`   | sender address — `0x20` ESC, `0x21` BLE, `0x22` BMS, `0x23` ext BMS |
 * | `dst`   | recipient address |
 * | `cmd`   | operation, **or** the register on a reply (see below) |
 * | `arg`   | argument to `cmd` |
 *
 * ## Two generations
 *
 * | Generation | Sync    | Extra byte | Checksum covers |
 * |------------|---------|------------|-----------------|
 * | Protocol 1 | `55 AA` | —          | body            |
 * | Protocol 2 | `5A A5` | `BT_ID` at index 3 | `BT_ID` + body |
 *
 * Protocol 2 inserts a `BT_ID` byte (`0x3E`) between `len` and `src`, and the
 * checksum reaches one byte further back to include it. Body indexing below is
 * therefore relative to the start of the body, not to a fixed frame offset.
 *
 * ## Checksum — not a CRC
 *
 * A 15-bit inverted sum, transmitted little-endian:
 *
 * ```
 * sum = Σ bytes
 * sum = (sum & 0x7FFF) + (sum >> 15)   // fold the overflow bit back in
 * checksum = ~sum & 0xFFFF
 * ```
 *
 * Several community implementations call this "CRC16", which is why frames
 * produced by independent implementations sometimes fail to verify against each
 * other.
 *
 * ## The vendor trap
 *
 * On a **reply**, `cmd` is the register that was read and `arg` is the status:
 * Xiaomi answers `arg = 0x01`, Ninebot answers `arg = 0x04`. A parser that
 * compares `cmd` against a single reply constant — as the existing telemetry
 * path effectively does — reads one vendor's data and silently discards the
 * other's. [ReplyKind] handles both and lets a caller learn which vendor it is
 * talking to.
 */
object FrameCodec {

    const val SYNC_1_HI: Byte = 0x55
    const val SYNC_1_LO: Byte = 0xAA.toByte()
    const val SYNC_2_HI: Byte = 0x5A
    const val SYNC_2_LO: Byte = 0xA5.toByte()

    /** Protocol 2's routing byte, between `len` and `src`. */
    const val BT_ID: Byte = 0x3E

    /** Reply argument used by Xiaomi devices. */
    const val REPLY_ARG_XIAOMI: Byte = 0x01.toByte()

    /** Reply argument used by Ninebot devices for the same read. */
    const val REPLY_ARG_NINEBOT: Byte = 0x04.toByte()

    /**
     * Body layout, all offsets relative to the start of the body.
     *
     * `body[0]` is the length byte, and **its value is the number of bytes that
     * follow it** — `src`, `dst`, `cmd`, `arg`, then payload. So a request with
     * no payload has `len = 4` and a body of 5 bytes. [BODY_FIXED] is that
     * smallest body.
     *
     * Getting this off by one is the classic framing bug: the encoder and
     * decoder agree with each other and the device answers nothing.
     */
    private const val BODY_FIXED = 5
    private const val LEN_SRC = 1

    /** Value the length byte carries for a payload-free request. */
    private const val LEN_NO_PAYLOAD = BODY_FIXED - 1

    /** Smallest body the decoder will accept. */
    private const val MIN_BODY = BODY_FIXED

    /** Which wire generation a frame belongs to. */
    enum class Protocol(val label: String) {
        /** `55 AA` — oldest Xiaomi/Ninebot boards. */
        P1("Protocol 1 (55AA)"),

        /** `5A A5` — adds the `BT_ID` routing byte. */
        P2("Protocol 2 (5AA5)")
    }

    /** Vendor convention inferred from a reply's argument byte. */
    enum class ReplyKind {
        /** `arg == 0x01`. */
        XIAOMI,

        /** `arg == 0x04`. */
        NINEBOT,

        /** Anything else — treat as an unsolicited or unknown frame. */
        OTHER
    }

    class FrameException(message: String) : IllegalArgumentException(message)

    /**
     * A decoded frame.
     *
     * @param protocol generation indicated by the sync word
     * @param source sender address
     * @param destination recipient address
     * @param command operation code, or the register on a reply
     * @param argument argument to [command], or the status on a reply
     * @param payload everything after [argument], excluding the checksum
     */
    data class Frame(
        val protocol: Protocol,
        val source: Byte,
        val destination: Byte,
        val command: Byte,
        val argument: Byte,
        val payload: ByteArray
    ) {
        /** Vendor convention this frame follows, from its argument byte. */
        val replyKind: ReplyKind
            get() = when (argument) {
                REPLY_ARG_XIAOMI -> ReplyKind.XIAOMI
                REPLY_ARG_NINEBOT -> ReplyKind.NINEBOT
                else -> ReplyKind.OTHER
            }

        // ByteArray yields identity equals/hashCode, which would make the
        // generated data-class equality silently wrong. Compare contents.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return protocol == other.protocol &&
                source == other.source &&
                destination == other.destination &&
                command == other.command &&
                argument == other.argument &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = protocol.hashCode()
            result = 31 * result + source
            result = 31 * result + destination
            result = 31 * result + command
            result = 31 * result + argument
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * 15-bit inverted sum over the body, optionally skipping leading bytes.
     *
     * @param skipLeading how many bytes at the START of [data] are excluded.
     *   Protocol 1 passes 1 to skip the length byte; Protocol 2 passes 0 so
     *   that `BT_ID` is included.
     *
     * The parameter direction matters and was originally backwards. It now
     * means "ignore the first N", which is what both the encoder (which holds
     * the whole body including its length byte) and the decoder (which holds
     * the counted bytes with `BT_ID` prepended) actually need.
     */
    fun checksum(data: ByteArray, skipLeading: Int = 0): Int {
        require(skipLeading in 0..data.size) {
            "skipLeading $skipLeading out of range for ${data.size}-byte body"
        }
        var sum = 0
        for (i in skipLeading until data.size) {
            sum += data[i].toInt() and 0xFF
        }
        sum = (sum and 0x7FFF) + (sum shr 15)
        return sum.inv() and 0xFFFF
    }

    /**
     * Leading bytes the checksum ignores.
     *
     * Protocol 1 does not check its own length byte, so it is skipped.
     * Protocol 2's `BT_ID` sits before the counted bytes and **is** covered, so
     * nothing is skipped.
     */
    private fun checksumSkip(protocol: Protocol): Int = when (protocol) {
        Protocol.P1 -> 1
        Protocol.P2 -> 0
    }

    private fun syncBytes(protocol: Protocol): Pair<Byte, Byte> = when (protocol) {
        Protocol.P1 -> SYNC_1_HI to SYNC_1_LO
        Protocol.P2 -> SYNC_2_HI to SYNC_2_LO
    }

    /**
     * Builds a frame from a body that **starts with its own length byte**.
     *
     * Output layout:
     * ```
     * P1: [55][AA][len][counted…][ck lo][ck hi]
     * P2: [5A][A5][BT_ID][len][counted…][ck lo][ck hi]
     * ```
     *
     * For Protocol 2 the `BT_ID` byte is inserted by this function, immediately
     * after the sync bytes — so it sits *before* the length byte and is
     * therefore not counted by it, while still being covered by the checksum.
     * Whether a device expects `BT_ID` at all is a property of that device, not
     * of the payload, hence [btId] rather than requiring the caller to splice it
     * into [body].
     */
    fun encode(
        protocol: Protocol,
        body: ByteArray,
        btId: Byte = BT_ID
    ): ByteArray {
        if (body.size < MIN_BODY) {
            throw FrameException("body must be at least $MIN_BODY bytes, got ${body.size}")
        }

        // The checksum is computed over the same buffer `decode` will rebuild,
        // so the two can never disagree:
        //   P1 -> [len][counted…]            skip 1 (the length byte)
        //   P2 -> [BT_ID][len][counted…]     skip 0 (BT_ID is covered)
        val checksumBuffer = if (protocol == Protocol.P2) {
            byteArrayOf(btId) + body
        } else {
            body
        }
        val checksum = checksum(checksumBuffer, checksumSkip(protocol))
        val (hi, lo) = syncBytes(protocol)

        val extra = if (protocol == Protocol.P2) 1 else 0
        val out = ByteArray(2 + extra + body.size + 2)
        out[0] = hi
        out[1] = lo
        var cursor = 2
        if (protocol == Protocol.P2) {
            out[cursor++] = btId
        }
        body.copyInto(out, cursor)
        cursor += body.size
        out[cursor] = (checksum and 0xFF).toByte()
        out[cursor + 1] = ((checksum shr 8) and 0xFF).toByte()
        return out
    }

    /**
     * Builds a read request: `[len][src][dst][cmd][arg][payload…]`.
     *
     * `len` counts `src + dst + cmd + arg + payload`, hence `4 + payload.size`.
     * A plain register read passes an empty [payload] and puts the register's
     * sub-argument in [argument] — which is what the existing telemetry reads
     * do (`0xB0` with argument `0x20`).
     */
    fun encodeRequest(
        protocol: Protocol,
        source: Byte,
        destination: Byte,
        register: Byte,
        argument: Byte,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val body = ByteArray(BODY_FIXED + payload.size)
        // The length byte counts everything after itself.
        body[0] = (LEN_NO_PAYLOAD + payload.size).toByte()
        body[LEN_SRC] = source
        body[LEN_SRC + 1] = destination
        body[LEN_SRC + 2] = register
        body[LEN_SRC + 3] = argument
        payload.copyInto(body, BODY_FIXED)
        return encode(protocol, body)
    }

    /**
     * Parses a received frame.
     *
     * Throws rather than guessing: an unknown sync word, a truncated frame, a
     * length byte that disagrees with the actual size, or a bad checksum all
     * fail. Accepting any of those would inject corrupt telemetry, and a wrong
     * speed on a HUD is worse than a blank one.
     */
    fun decode(raw: ByteArray): Frame {
        // Smallest legal frame: 2 sync + 1 len + 4 counted bytes + 2 checksum.
        val minTotal = 2 + 1 + LEN_NO_PAYLOAD + 2
        if (raw.size < minTotal) {
            throw FrameException("frame too short: ${raw.size} bytes (minimum $minTotal)")
        }

        val protocol = when {
            raw[0] == SYNC_1_HI && raw[1] == SYNC_1_LO -> Protocol.P1
            raw[0] == SYNC_2_HI && raw[1] == SYNC_2_LO -> Protocol.P2
            else -> throw FrameException("unknown sync word: %02x %02x".format(raw[0], raw[1]))
        }

        // Layout: P1 [sync][sync][len] [counted…] [ck lo][ck hi]
        //         P2 [sync][sync][BT_ID][len] [counted…] [ck lo][ck hi]
        //
        // The length byte counts only the bytes AFTER itself, so Protocol 2's
        // BT_ID is outside its count while still being inside the checksummed
        // range. `lenIndex` is therefore 2 for P1 and 3 for P2.
        val extra = if (protocol == Protocol.P2) 1 else 0
        val lenIndex = 2 + extra
        val length = raw[lenIndex].toInt() and 0xFF
        val expectedTotal = lenIndex + 1 + length + 2
        if (raw.size != expectedTotal) {
            throw FrameException(
                "length byte says $length (=> $expectedTotal bytes) but frame is ${raw.size} bytes"
            )
        }
        if (length < LEN_NO_PAYLOAD) {
            throw FrameException("length byte $length is below the $LEN_NO_PAYLOAD-byte minimum")
        }

        // The buffer handed to `checksum` is exactly:
        //   P1 -> [len][counted…]           skip 1 (the length byte)
        //   P2 -> [BT_ID][len][counted…]    skip 0 (BT_ID is covered)
        //
        // Building it as a plain slice from index 2 gives precisely that for
        // both generations, because P2's BT_ID already occupies raw[2] and the
        // length byte follows it. Prepending BT_ID again — as an earlier
        // revision did — doubles it and the checksum can never match.
        val body = raw.copyOfRange(2, lenIndex + 1 + length)

        val expectedChecksum = checksum(body, checksumSkip(protocol))
        val actualChecksum = (raw[raw.size - 2].toInt() and 0xFF) or
            ((raw[raw.size - 1].toInt() and 0xFF) shl 8)
        if (expectedChecksum != actualChecksum) {
            throw FrameException(
                "checksum mismatch: frame says 0x%04x, computed 0x%04x"
                    .format(actualChecksum, expectedChecksum)
            )
        }

        // Counted bytes are [src][dst][cmd][arg][payload…]. For P2 the leading
        // BT_ID shifts everything by one, hence `contentBase`.
        val contentBase = if (protocol == Protocol.P2) LEN_SRC + 1 else LEN_SRC
        val payloadStart = contentBase + 4
        val payloadBytes = length - LEN_NO_PAYLOAD
        val payload = if (payloadBytes > 0) {
            body.copyOfRange(payloadStart, payloadStart + payloadBytes)
        } else {
            ByteArray(0)
        }

        return Frame(
            protocol = protocol,
            source = body[contentBase],
            destination = body[contentBase + 1],
            command = body[contentBase + 2],
            argument = body[contentBase + 3],
            payload = payload
        )
    }

    /**
     * True when [frame] is a reply carrying [register], from either vendor.
     *
     * Use this instead of comparing `command` to a single reply constant:
     * hard-coding one vendor's reply byte is exactly what makes a parser blind
     * to the other.
     */
    fun isReplyFor(frame: Frame, register: Byte): Boolean =
        frame.command == register && frame.replyKind != ReplyKind.OTHER
}
