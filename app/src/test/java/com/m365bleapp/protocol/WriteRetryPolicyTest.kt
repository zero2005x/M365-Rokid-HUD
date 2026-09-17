package com.m365bleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the write retry policy.
 *
 * The important property is the distinction between a *rejected* write and one
 * the peer *silently discarded*. Retrying the first is correct; retrying the
 * second burns three times the latency and then reports the wrong cause,
 * because an oversized frame is discarded identically on every attempt.
 */
class WriteRetryPolicyTest {

    @Test
    fun `a rejected write is retried with increasing backoff`() {
        val first = WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.REJECTED, attemptsMade = 1)
        val second = WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.REJECTED, attemptsMade = 2)

        assertTrue("first failure should retry", first is WriteRetryPolicy.Decision.Retry)
        assertTrue("second failure should retry", second is WriteRetryPolicy.Decision.Retry)

        val d1 = (first as WriteRetryPolicy.Decision.Retry).delayMs
        val d2 = (second as WriteRetryPolicy.Decision.Retry).delayMs
        assertTrue("backoff must not shrink: $d1 then $d2", d2 >= d1)
    }

    @Test
    fun `the retry budget is bounded and then gives up`() {
        val budget = WriteRetryPolicy.maxAttempts()

        // Attempts 1..budget-1 retry; the final one gives up.
        for (made in 1 until budget) {
            assertTrue(
                "attempt $made of $budget should still retry",
                WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.REJECTED, made) is
                    WriteRetryPolicy.Decision.Retry
            )
        }
        val last = WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.REJECTED, budget)
        assertTrue("final attempt must give up", last is WriteRetryPolicy.Decision.GiveUp)
        assertTrue(
            "the give-up reason should name the attempt count",
            (last as WriteRetryPolicy.Decision.GiveUp).reason.contains("$budget")
        )
    }

    @Test
    fun `exhausting the budget never retries again`() {
        // Belt and braces: no attempt count beyond the budget may produce a retry.
        for (made in WriteRetryPolicy.maxAttempts()..(WriteRetryPolicy.maxAttempts() + 5)) {
            assertTrue(
                "attempt $made must not retry",
                WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.REJECTED, made) is
                    WriteRetryPolicy.Decision.GiveUp
            )
        }
    }

    @Test
    fun `a peer-discarded write is never retried`() {
        // This is the whole point of the third outcome: the frame was too large
        // for the link, so every retry would be discarded identically.
        for (made in 1..WriteRetryPolicy.maxAttempts()) {
            val decision =
                WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.DISCARDED_BY_PEER, made)
            assertTrue(
                "attempt $made must not retry a discarded frame",
                decision is WriteRetryPolicy.Decision.GiveUp
            )
        }
    }

    @Test
    fun `the discarded-by-peer reason explains the MTU cause`() {
        val decision =
            WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.DISCARDED_BY_PEER, 1)
        val reason = (decision as WriteRetryPolicy.Decision.GiveUp).reason
        assertTrue(
            "the message should point at the MTU, otherwise it is unactionable: $reason",
            reason.contains("MTU")
        )
    }

    @Test
    fun `an accepted write is never retried`() {
        val decision = WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.ACCEPTED, 1)
        assertTrue(decision is WriteRetryPolicy.Decision.GiveUp)
    }

    @Test
    fun `backoff is exponential then clamped`() {
        // Base 40ms doubling: 40, 80, 160, then clamped at 250.
        assertEquals(40L, WriteRetryPolicy.backoffMs(1))
        assertEquals(80L, WriteRetryPolicy.backoffMs(2))
        assertEquals(160L, WriteRetryPolicy.backoffMs(3))
        assertEquals(WriteRetryPolicy.MAX_BACKOFF_MS, WriteRetryPolicy.backoffMs(4))
        assertEquals(WriteRetryPolicy.MAX_BACKOFF_MS, WriteRetryPolicy.backoffMs(5))
    }

    @Test
    fun `backoff is always positive even for a miscounted attempt`() {
        // A caller passing 0 or a negative would otherwise produce a zero or
        // negative delay, i.e. a hot retry loop.
        for (bad in intArrayOf(-100, -1, 0)) {
            val delay = WriteRetryPolicy.backoffMs(bad)
            assertTrue("backoff for attempt $bad must be positive, was $delay", delay > 0)
        }
    }

    @Test
    fun `backoff never overflows into a negative delay`() {
        // A large attempt count shifts a Long; if it were unbounded the value
        // could wrap and a negative delay would retry immediately.
        for (big in intArrayOf(20, 63, 64, 1000, Int.MAX_VALUE)) {
            val delay = WriteRetryPolicy.backoffMs(big)
            assertTrue("backoff for attempt $big must be positive, was $delay", delay > 0)
            assertTrue(
                "backoff for attempt $big must stay within the ceiling",
                delay <= WriteRetryPolicy.MAX_BACKOFF_MS
            )
        }
    }

    @Test
    fun `retry decisions stay within the declared latency budget`() {
        // Every delay before the final attempt must be short enough that the
        // scooter's reply can still arrive inside the caller's read timeout.
        val readTimeoutMs = 5_000L
        var total = 0L
        for (made in 1 until WriteRetryPolicy.maxAttempts()) {
            val d = WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.REJECTED, made)
            total += (d as WriteRetryPolicy.Decision.Retry).delayMs
        }
        assertTrue(
            "total retry delay ${total}ms should leave room inside a ${readTimeoutMs}ms read",
            total < readTimeoutMs / 2
        )
    }

    @Test
    fun `the attempt number reported to the caller increments`() {
        val first = WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.REJECTED, 1)
            as WriteRetryPolicy.Decision.Retry
        val second = WriteRetryPolicy.decide(WriteRetryPolicy.Outcome.REJECTED, 2)
            as WriteRetryPolicy.Decision.Retry
        assertEquals(2, first.attempt)
        assertEquals(3, second.attempt)
    }
}
