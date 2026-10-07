package io.github.zero2005x.pev.core.command

import io.github.zero2005x.pev.core.identity.Family
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.transport.PevTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CommandCoordinatorTest {
    private class FakeTransport(var replies: ArrayDeque<ByteArray?> = ArrayDeque()) : PevTransport {
        override val mtu = 20
        override var connected = true
        var failWrite = false
        val written = mutableListOf<List<Byte>>()
        var onWrite: () -> Unit = {}
        override fun write(bytes: ByteArray): Boolean {
            onWrite()
            if (failWrite) return false
            written += bytes.toList()
            return true
        }
        override fun awaitNotify(timeoutMs: Long): ByteArray? = replies.removeFirstOrNull()
    }

    private fun spec(confirm: ConfirmKind) = CommandSpec(
        "c", "c", CommandScope(Family.BEGODE, setOf("A2")), emptyList(), Evidence.SYNTHETIC,
        confirm, RetryPolicy.NEVER, false, "r", emptyList(),
    )

    private fun plan(confirm: ConfirmKind, readback: ReadbackSpec? = null) = CommandPlan(
        spec(confirm), listOf(WriteStep(byteArrayOf(1), 5), WriteStep(byteArrayOf(2))), readback,
    )

    private val slept = mutableListOf<Long>()
    private fun coordinator(t: FakeTransport) = CommandCoordinator(t, { slept += it }, 10)

    @Test
    fun stepsAreWrittenInOrderWithDelays() {
        val t = FakeTransport()
        val r = coordinator(t).execute(plan(ConfirmKind.NONE))
        assertEquals(CommandOutcome.SENT_UNCONFIRMED, r.outcome)
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), t.written)
        assertEquals(listOf(5L), slept)
    }

    @Test
    fun ackIsReportedAsAckNotReadback() {
        val t = FakeTransport(ArrayDeque(listOf(byteArrayOf(0))))
        assertEquals(CommandOutcome.ACK_CONFIRMED, coordinator(t).execute(plan(ConfirmKind.ACK_ONLY)).outcome)
    }

    @Test
    fun ackTimeoutIsNotRetried() {
        val t = FakeTransport()
        assertEquals(CommandOutcome.TIMEOUT, coordinator(t).execute(plan(ConfirmKind.ACK_ONLY)).outcome)
        assertEquals(2, t.written.size)
    }

    @Test
    fun readbackMatchConfirmsAndMismatchDoesNot() {
        val ok = ReadbackSpec(byteArrayOf(9)) { it[0] == 7.toByte() }
        val t1 = FakeTransport(ArrayDeque(listOf(byteArrayOf(7))))
        assertEquals(CommandOutcome.READBACK_CONFIRMED, coordinator(t1).execute(plan(ConfirmKind.READBACK, ok)).outcome)
        val t2 = FakeTransport(ArrayDeque(listOf(byteArrayOf(8))))
        val r = coordinator(t2).execute(plan(ConfirmKind.READBACK, ok))
        assertEquals(CommandOutcome.SENT_UNCONFIRMED, r.outcome)
        assertEquals("readback mismatch", r.detail)
    }

    @Test
    fun readbackTimeoutAndWriteFailure() {
        val spec = ReadbackSpec(byteArrayOf(9)) { true }
        val timeout = coordinator(FakeTransport()).execute(plan(ConfirmKind.READBACK, spec))
        assertEquals(CommandOutcome.TIMEOUT, timeout.outcome)
        val t = FakeTransport(ArrayDeque(listOf(byteArrayOf(1))))
        t.onWrite = { if (t.written.size == 2) t.failWrite = true }
        assertEquals(CommandOutcome.DISCONNECTED, coordinator(t).execute(plan(ConfirmKind.READBACK, spec)).outcome)
    }

    @Test
    fun disconnectAndWriteFailureAbort() {
        val t = FakeTransport().also { it.connected = false }
        assertEquals(CommandOutcome.DISCONNECTED, coordinator(t).execute(plan(ConfirmKind.NONE)).outcome)
        val f = FakeTransport().also { it.failWrite = true }
        assertEquals(CommandOutcome.DISCONNECTED, coordinator(f).execute(plan(ConfirmKind.NONE)).outcome)
    }

    @Test
    fun cancelBetweenStepsStopsRemainingSteps() {
        val t = FakeTransport()
        val c = coordinator(t)
        t.onWrite = { c.cancel() }
        assertEquals(CommandOutcome.CANCELLED, c.execute(plan(ConfirmKind.NONE)).outcome)
        assertEquals(1, t.written.size)
    }

    @Test
    fun concurrentPlanIsRejectedNotInterleaved() {
        val t = FakeTransport()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        t.onWrite = { inside.countDown(); release.await(2, TimeUnit.SECONDS) }
        val c = coordinator(t)
        var first: CommandResult? = null
        val th = thread { first = c.execute(plan(ConfirmKind.NONE)) }
        assertTrue(inside.await(2, TimeUnit.SECONDS))
        assertEquals(CommandOutcome.REJECTED, c.execute(plan(ConfirmKind.NONE)).outcome)
        release.countDown()
        th.join(2_000)
        assertEquals(CommandOutcome.SENT_UNCONFIRMED, first?.outcome)
    }

    @Test(expected = IllegalArgumentException::class)
    fun readbackKindRequiresSpec() {
        plan(ConfirmKind.READBACK, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPlanRejected() {
        CommandPlan(spec(ConfirmKind.NONE), emptyList())
    }
}
