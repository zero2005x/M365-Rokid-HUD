package com.m365bleapp.protocol

/**
 * Plaintext (unencrypted) Ninebot/Xiaomi register session.
 *
 * ## Why this exists — and why [FrameCodec] was dead code
 *
 * [FrameCodec] implements the **outer** link-layer envelope
 * (`55 AA` / `5A A5` + length + body + inverted-sum checksum). For encrypted
 * scooters that envelope is built and verified inside the Rust `ninebot-ffi`
 * crypto layer (`encrypt_uart` / `decrypt_uart`), so nothing in Kotlin ever
 * needed [FrameCodec] — it sat fully implemented and fully tested with **zero
 * call sites**.
 *
 * It is needed for the other half of the fleet: **plaintext** scooters, which
 * never run the crypto handshake. Scootbatt's own dispatch confirms both
 * framings are unencrypted variants (`55 AA` for Xiaomi, `5A A5` for Ninebot).
 * This class is that consumer, so [FrameCodec] is now on a live path.
 *
 * ## Scope
 *
 * This is a transport, not a parser. It frames a register read, sends it via an
 * injected [Write] function, reassembles the reply through [FrameCodec.decode]
 * and hands the decoded [FrameCodec.Frame] to a caller-supplied decoder. Keeping
 * the BLE write injectable is what makes the whole thing unit-testable without a
 * scooter attached.
 *
 * ## ⚠️ Not verified against real hardware
 *
 * No scooter has ever been attached to this project. The framing itself is
 * unit-tested against [FrameCodec]'s round-trip tests, but whether a real
 * plaintext scooter accepts `source = 0x3E` and which protocol generation each
 * model speaks is **static analysis only**, taken from Scootbatt and the
 * community register map. See `doc/IMPROVEMENT_PLAN.md`.
 */
class PlaintextRegisterSession(
    /** Which framing generation the connected model speaks. */
    val protocol: FrameCodec.Protocol,
    /** Addressing byte used as the frame source. `0x3E` in both vendor layouts. */
    private val source: Byte = DEFAULT_SOURCE,
    /** Addressing byte for the target device. `0x20` is the ESC. */
    private val destination: Byte = ADDRESS_ESC,
    /**
     * Sends one complete, already-framed request.
     *
     * Injected so tests can drive the session with canned replies. The real
     * implementation writes to the NUS RX characteristic.
     */
    private val write: Write,
) {

    /** Sends a fully framed request byte array. */
    fun interface Write {
        suspend fun send(frame: ByteArray)
    }

    companion object {
        /** Frame source used by both vendor layouts. */
        const val DEFAULT_SOURCE: Byte = 0x3E

        /** Addressing byte for the ESC (motor controller). */
        const val ADDRESS_ESC: Byte = 0x20

        /** Addressing byte for the internal BMS. */
        const val ADDRESS_BMS: Byte = 0x22

        /** Addressing byte for an external / secondary BMS. */
        const val ADDRESS_EXT_BMS: Byte = 0x23
    }

    init {
        require(source != destination) {
            "frame source and destination must differ, both were 0x%02x".format(source)
        }
    }

    /**
     * Builds the request frame for [register].
     *
     * The 2-byte read length travels as the frame's argument, which is what the
     * existing encrypted loop does (`0xB0` with argument `0x20`).
     */
    fun buildRead(
        register: Byte,
        argument: Byte,
        destination: Byte = this.destination
    ): ByteArray = FrameCodec.encodeRequest(
        protocol = protocol,
        source = source,
        destination = destination,
        register = register,
        argument = argument
    )

    /**
     * Parses one inbound frame and returns the decoded [FrameCodec.Frame].
     *
     * @throws FrameCodec.FrameException when the sync word is unknown, the length
     *   byte disagrees with the actual size, or the checksum fails. Propagating
     *   rather than guessing is deliberate: a corrupt frame injected into the
     *   HUD could show a wrong speed, which is worse than showing nothing.
     */
    fun accept(raw: ByteArray): FrameCodec.Frame = FrameCodec.decode(raw)

    /**
     * True when [frame] is a reply to a read of [register] from any vendor.
     *
     * Delegates to [FrameCodec.isReplyFor] so the vendor's reply-argument
     * convention (`0x01` Xiaomi / `0x04` Ninebot) lives in exactly one place.
     */
    fun isReplyFor(frame: FrameCodec.Frame, register: Byte): Boolean =
        FrameCodec.isReplyFor(frame, register)

    /**
     * Performs one register read: frame it, send it, decode what comes back.
     *
     * @param destination which device to address; defaults to the ESC. Pass
     *   [ADDRESS_BMS] or [ADDRESS_EXT_BMS] for battery registers.
     * @param reply the raw inbound bytes for this request, already reassembled
     *   from any BLE fragments.
     * @return the decoded frame, or `null` when [reply] is not a reply to this
     *   register — which includes unsolicited frames and the common case of a
     *   notification belonging to a previous request.
     * @throws FrameCodec.FrameException when the reply is malformed. Callers that
     *   would rather drop a bad frame than abort a loop should catch it.
     */
    suspend fun readRegister(
        register: Byte,
        argument: Byte,
        destination: Byte = this.destination,
        reply: () -> ByteArray
    ): FrameCodec.Frame? {
        write.send(buildRead(register, argument, destination))
        val frame = accept(reply())
        return if (isReplyFor(frame, register)) frame else null
    }

    /**
     * Reassembles [chunks] into one frame payload.
     *
     * A plaintext reply longer than the BLE payload size arrives as several
     * notifications. [FrameCodec.decode] needs the whole frame, so the chunks are
     * concatenated first. No length arithmetic is done here on purpose: the
     * frame's own length byte is authoritative and `decode` validates it against
     * the real size, so a premature "have I got everything?" guess would only add
     * a second, weaker source of truth.
     */
    fun reassemble(chunks: List<ByteArray>): ByteArray {
        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var cursor = 0
        for (chunk in chunks) {
            chunk.copyInto(out, cursor)
            cursor += chunk.size
        }
        return out
    }

    /**
     * Length in bytes the frame at [raw] claims to be, or `null` when [raw] is
     * too short to tell.
     *
     * Used to decide whether more BLE fragments are still needed. This reads the
     * same length byte [FrameCodec.decode] validates, so the two cannot drift.
     */
    fun declaredTotalLength(raw: ByteArray): Int? {
        val extra = if (protocol == FrameCodec.Protocol.P2) 1 else 0
        val lenIndex = 2 + extra
        if (raw.size <= lenIndex) return null
        val length = raw[lenIndex].toInt() and 0xFF
        // 2 sync + (BT_ID) + 1 len + length counted bytes + 2 checksum
        return lenIndex + 1 + length + 2
    }

    /**
     * True when [raw] already contains a complete frame.
     *
     * @return `null` when the length cannot be read yet, so callers can keep
     *   buffering instead of mistaking "unknown" for "complete".
     */
    fun isComplete(raw: ByteArray): Boolean? =
        declaredTotalLength(raw)?.let { raw.size >= it }
}
