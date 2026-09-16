package com.m365bleapp.protocol

import kotlin.math.min

/**
 * Decides what to do when an ATT write does not land.
 *
 * ## What "dropped" means here
 *
 * A BLE write can fail in two very different ways, and they need different
 * responses:
 *
 *  - **Reported failure.** `writeCharacteristic()` returns `false`, or
 *    `onCharacteristicWrite` arrives with a non-zero status. The stack noticed.
 *    Retrying the same bytes is worthwhile and usually works.
 *  - **Silent loss.** A write larger than `ATT_MTU - 3` is discarded by the peer
 *    with no callback at all, so the stack reports success and the scooter
 *    simply never sees the frame. This is the failure the MTU rule exists to
 *    prevent; if it still happens the correct response is *not* to retry the
 *    same oversized chunk but to give up loudly, because retrying will be
 *    discarded identically every time.
 *
 * The second case is why this policy is expressed in terms of *attempts on a
 * chunk that the stack accepted or rejected*, and why exceeding the retry
 * budget surfaces as an error rather than being absorbed. An earlier version of
 * the telemetry loop counted failures and silently retried, which turned a
 * persistent protocol fault into an apparently idle scooter.
 *
 * Kept free of Android types so the arithmetic is unit-testable on the host.
 */
object WriteRetryPolicy {

    /**
     * Attempts per individual ATT chunk.
     *
     * Three: the first try plus two retries. A transient failure (the peer's
     * ATT queue full, a connection-interval collision) clears within one or two
     * attempts; a fourth would only add latency to a link that is failing for a
     * structural reason.
     */
    const val MAX_ATTEMPTS_PER_CHUNK = 3

    /** Base backoff before the first retry, in milliseconds. */
    const val BASE_BACKOFF_MS = 40L

    /**
     * Ceiling for the exponential backoff.
     *
     * Bounded because the scooter's UART bridge needs the whole command within a
     * few hundred milliseconds; a longer pause makes the reply arrive after the
     * caller has already timed out reading it, which looks like a different bug.
     */
    const val MAX_BACKOFF_MS = 250L

    /** Outcome of one write attempt. */
    enum class Outcome {
        /** The stack accepted the write. */
        ACCEPTED,

        /**
         * The stack rejected the write, or reported a non-zero status.
         *
         * Retrying is sensible; the bytes never reached the peer.
         */
        REJECTED,

        /**
         * The peer accepted the write and did not act on it.
         *
         * Distinct from [REJECTED] because retrying an oversized frame is
         * futile — see the class docs. Callers should stop and report.
         */
        DISCARDED_BY_PEER,
    }

    /** What the caller should do next. */
    sealed class Decision {
        /** Re-send the same chunk after [delayMs]. */
        data class Retry(val delayMs: Long, val attempt: Int) : Decision()

        /** Stop; this chunk cannot be delivered. */
        data class GiveUp(val reason: String) : Decision()
    }

    /**
     * Backoff before retry number [attempt] (1-based: the delay after the first
     * failure).
     *
     * Exponential, then clamped. `attempt` below 1 is treated as 1 so a
     * miscounted caller cannot produce a zero or negative delay.
     */
    fun backoffMs(attempt: Int): Long {
        val n = attempt.coerceAtLeast(1)
        // Shift is bounded so a large attempt cannot overflow into a negative.
        val shift = min(n - 1, 16)
        val raw = BASE_BACKOFF_MS shl shift
        return min(raw, MAX_BACKOFF_MS)
    }

    /**
     * Whether to retry, given how many attempts have already been made.
     *
     * @param attemptsMade attempts already performed for this chunk, including
     *   the one that just failed. 1 means the first try just failed.
     */
    fun decide(outcome: Outcome, attemptsMade: Int): Decision {
        val made = attemptsMade.coerceAtLeast(1)

        return when (outcome) {
            Outcome.ACCEPTED ->
                // Nothing to decide; the caller moves on. Expressed as GiveUp so
                // that a caller which mistakenly asks still does not retry.
                Decision.GiveUp("write already accepted")

            Outcome.DISCARDED_BY_PEER ->
                Decision.GiveUp(
                    "the scooter accepted the write but did not act on it; this is the " +
                        "signature of a frame larger than ATT_MTU - 3, which the peer " +
                        "discards silently. Retrying would be discarded identically. " +
                        "Check the negotiated MTU (currently reported in the BLE log)."
                )

            Outcome.REJECTED ->
                if (made >= MAX_ATTEMPTS_PER_CHUNK) {
                    Decision.GiveUp(
                        "write rejected by the BLE stack $made times; giving up after " +
                            "$MAX_ATTEMPTS_PER_CHUNK attempts"
                    )
                } else {
                    Decision.Retry(backoffMs(made), made + 1)
                }
        }
    }

    /**
     * Total attempts a caller will make for one chunk.
     *
     * Exposed so tests and logs agree with [decide] without re-deriving it.
     */
    fun maxAttempts(): Int = MAX_ATTEMPTS_PER_CHUNK
}
