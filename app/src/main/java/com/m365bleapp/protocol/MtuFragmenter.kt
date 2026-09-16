package com.m365bleapp.protocol

/**
 * Splits an outgoing message into ATT-writable chunks.
 *
 * ## Why this exists
 *
 * A BLE write carrying more than `ATT_MTU − 3` bytes is **silently discarded**
 * by the peer. There is no error, no callback, no disconnect — the frame simply
 * never arrives, and the symptom is indistinguishable from the scooter
 * refusing the command or the pairing being rejected. A community project
 * misdiagnosed exactly this as an "account lock" across several releases before
 * finding it.
 *
 * The three bytes are ATT overhead: one for the opcode and two for the
 * attribute handle. The usable payload is therefore `ATT_MTU − 3`, a rule fixed
 * by the Bluetooth Core Specification rather than a choice.
 *
 * ## What was wrong before
 *
 * `ScooterRepository.writeUartEncrypted()` hard-coded a 20-byte chunk while
 * separately calling `gatt.requestMtu(512)` — and nothing anywhere recorded
 * what MTU was actually granted, because `onMtuChanged` was never overridden.
 * So the code could neither use a larger MTU nor detect a smaller one.
 *
 * See [com.m365bleapp.ble.BleManager] for where the negotiated value is
 * captured, and [MtuFragmenter.DEFAULT_ATT_MTU] for the fallback used before
 * negotiation completes.
 */
object MtuFragmenter {

    /** ATT overhead per write: 1 opcode byte + 2 handle bytes. */
    const val ATT_OVERHEAD = 3

    /**
     * The ATT_MTU every Bluetooth LE stack must support, and therefore the only
     * value that is safe to assume before negotiation finishes.
     *
     * Using anything larger here would be a guess that fails silently on a peer
     * that never negotiated up.
     */
    const val DEFAULT_ATT_MTU = 23

    /** Usable payload with an un-negotiated link: 23 − 3 = 20 bytes. */
    const val DEFAULT_CHUNK_SIZE = DEFAULT_ATT_MTU - ATT_OVERHEAD

    /**
     * Largest payload we will ever emit in one write.
     *
     * The protocol's length field is a single byte and Mi frames carry a 2-byte
     * chunk header, so a payload above this could not be framed anyway.
     */
    const val MAX_CHUNK_SIZE = 0xFF

    /**
     * Usable payload size for a negotiated MTU.
     *
     * A [negotiatedMtu] that cannot carry even one byte (some stacks report 0
     * before negotiation) falls back to [DEFAULT_CHUNK_SIZE] rather than
     * producing a zero or negative chunk size, which would loop forever or
     * throw.
     */
    fun chunkSizeFor(negotiatedMtu: Int): Int {
        if (negotiatedMtu <= ATT_OVERHEAD) return DEFAULT_CHUNK_SIZE
        return (negotiatedMtu - ATT_OVERHEAD).coerceAtMost(MAX_CHUNK_SIZE)
    }

    /**
     * Splits [payload] for writing over a link with [negotiatedMtu].
     *
     * Returns an empty list for an empty payload — a caller iterating the result
     * then performs no write at all, which is correct: there is nothing to send.
     */
    fun fragment(payload: ByteArray, negotiatedMtu: Int = DEFAULT_ATT_MTU): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()

        val size = chunkSizeFor(negotiatedMtu)
        val chunks = ArrayList<ByteArray>((payload.size + size - 1) / size)
        var offset = 0
        while (offset < payload.size) {
            val end = (offset + size).coerceAtMost(payload.size)
            chunks.add(payload.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    /**
     * Number of writes [fragment] would produce.
     *
     * Separate from [fragment] so callers can size a buffer or log the expected
     * count without allocating the chunks.
     */
    fun chunkCount(payloadSize: Int, negotiatedMtu: Int = DEFAULT_ATT_MTU): Int {
        if (payloadSize <= 0) return 0
        val size = chunkSizeFor(negotiatedMtu)
        return (payloadSize + size - 1) / size
    }
}
