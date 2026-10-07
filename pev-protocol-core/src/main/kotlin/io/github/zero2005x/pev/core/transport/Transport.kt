package io.github.zero2005x.pev.core.transport

/**
 * Minimal BLE-agnostic transport. Implemented in the app layer (GATT); the core only
 * sees bytes. Blocking by design so the single write coordinator stays trivially ordered.
 */
interface PevTransport {
    val mtu: Int

    /** Writes one packet; false on a write failure or disconnect. */
    fun write(bytes: ByteArray): Boolean

    /** Waits for the next notification or returns null on timeout/disconnect. */
    fun awaitNotify(timeoutMs: Long): ByteArray?

    val connected: Boolean
}
