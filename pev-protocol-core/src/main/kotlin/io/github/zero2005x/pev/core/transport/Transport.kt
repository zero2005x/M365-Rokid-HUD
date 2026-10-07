package io.github.zero2005x.pev.core.transport

/** One notify event, tagged by the connection and monotonically increasing receive cursor. */
class TransportNotification(val sequence: Long, val connectionId: String, bytes: ByteArray) {
    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

/** App-layer transport; every reconnect must allocate a new opaque connectionId. */
interface PevTransport {
    val mtu: Int
    val connected: Boolean
    val deviceId: String
    val connectionId: String
    val notificationSequence: Long

    /** False means submission failed; it is not a vehicle acknowledgment. */
    fun write(bytes: ByteArray): Boolean

    /**
     * Atomically bind the current GATT/session before encryption and submission.
     * Refuse if its connectionId differs; never re-resolve another GATT after this check.
     */
    fun writeForConnection(bytes: ByteArray, expectedConnectionId: String): Boolean

    /** Raw consumer API; this must not be used as command confirmation. */
    fun awaitNotify(timeoutMs: Long): ByteArray?

    /** Return only notifications received after the cursor, never buffered earlier replies. */
    fun awaitNotifyAfter(timeoutMs: Long, afterSequence: Long): TransportNotification?
}
