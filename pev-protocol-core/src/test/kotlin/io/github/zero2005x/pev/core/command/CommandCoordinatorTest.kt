package io.github.zero2005x.pev.core.command

import io.github.zero2005x.pev.core.identity.DeviceIdentity
import io.github.zero2005x.pev.core.identity.Family
import io.github.zero2005x.pev.core.identity.IdentitySource
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot
import io.github.zero2005x.pev.core.transport.PevTransport
import io.github.zero2005x.pev.core.transport.TransportNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CommandCoordinatorTest {
    private class FakeTransport : PevTransport {
        override val mtu = 20
        override var connected = true
        override var deviceId = "device1"
        override var connectionId = "connection1"
        override var notificationSequence = 10L
        var failWrite = false
        var onWrite: () -> Unit = {}
        var onWait: () -> Unit = {}
        var beforeBoundWrite: () -> Unit = {}
        val written = mutableListOf<List<Byte>>()
        val replies = ArrayDeque<TransportNotification>()
        override fun write(bytes: ByteArray): Boolean {
            onWrite()
            if (failWrite) return false
            written += bytes.toList()
            return true
        }
        override fun writeForConnection(bytes: ByteArray, expectedConnectionId: String): Boolean {
            beforeBoundWrite()
            return connected && connectionId == expectedConnectionId && write(bytes)
        }
        override fun awaitNotify(timeoutMs: Long): ByteArray? = error("uncorrelated API must not be called")
        override fun awaitNotifyAfter(timeoutMs: Long, afterSequence: Long): TransportNotification? {
            onWait()
            return replies.removeFirstOrNull()
        }
        fun reply(value: Int, seq: Long = 11, connection: String = connectionId) {
            replies += TransportNotification(seq, connection, byteArrayOf(value.toByte()))
        }
    }

    private val identity = DeviceIdentity(Family.BEGODE, "A2", "fw", source = IdentitySource.USER_SELECTED)
    private fun state(t: FakeTransport) = CurrentWriteState(t.deviceId, identity,
        WriteSession(t.deviceId, t.connectionId, true).also { it.enableExperimental(identity) },
        emptyMap(), TelemetrySnapshot(), 1_000)
    private fun context(t: FakeTransport): CommandExecutionContext {
        val initial = state(t)
        return CommandExecutionContext(initial) { initial }
    }
    private fun spec(confirm: ConfirmKind = ConfirmKind.NONE) = CommandSpec(
        "light", "light", CommandScope(Family.BEGODE, setOf("A2")), emptyList(), Evidence.VENDOR_STATIC,
        confirm, RetryPolicy.NEVER, false, "unverified", listOf("vendor-static:test-contract"),
    )
    private fun plan(confirm: ConfirmKind = ConfirmKind.NONE) = CommandPlan(spec(confirm),
        listOf(WriteStep(byteArrayOf(1), 5), WriteStep(byteArrayOf(2))),
        readback = if (confirm == ConfirmKind.READBACK) ReadbackSpec(byteArrayOf(9)) { it.contentEquals(byteArrayOf(7)) } else null,
        ack = if (confirm == ConfirmKind.ACK_ONLY) AckSpec { it.contentEquals(byteArrayOf(7)) } else null)
    private fun coordinator(t: FakeTransport, sleep: (Long) -> Unit = {}) = CommandCoordinator(t, sleep, 100)

    @Test fun stepsAreWrittenInOrderWithDelays() {
        val t = FakeTransport()
        val delays = mutableListOf<Long>()
        assertEquals(CommandOutcome.SENT_UNCONFIRMED, coordinator(t) { delays += it }.execute(plan(), context(t)).outcome)
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), t.written)
        assertEquals(listOf(5L), delays)
    }

    @Test fun unrelatedTelemetryCannotConfirmAckAndMatchingAckCan() {
        val t = FakeTransport().also { it.reply(0); it.reply(7, 12) }
        assertEquals(CommandOutcome.ACK_CONFIRMED, coordinator(t).execute(plan(ConfirmKind.ACK_ONLY), context(t)).outcome)
        val onlyTelemetry = FakeTransport().also { it.reply(0) }
        assertEquals(CommandOutcome.TIMEOUT, coordinator(onlyTelemetry).execute(plan(ConfirmKind.ACK_ONLY), context(onlyTelemetry)).outcome)
        assertEquals(2, onlyTelemetry.written.size)
    }

    @Test fun preWriteReplyAndReplyFromAnotherConnectionCannotConfirm() {
        for (reply in listOf(TransportNotification(10, "connection1", byteArrayOf(7)),
            TransportNotification(11, "old-connection", byteArrayOf(7)))) {
            val t = FakeTransport().also { it.replies += reply }
            assertEquals(CommandOutcome.SENT_UNCONFIRMED, coordinator(t).execute(plan(ConfirmKind.ACK_ONLY), context(t)).outcome)
        }
    }

    @Test fun readbackRequiresMatchingFreshReplyAndIsNotAck() {
        val t = FakeTransport().also { it.reply(0); it.reply(7, 12) }
        assertEquals(CommandOutcome.READBACK_CONFIRMED, coordinator(t).execute(plan(ConfirmKind.READBACK), context(t)).outcome)
        assertEquals(listOf<Byte>(9), t.written.last())
        val mismatch = FakeTransport().also { it.reply(8) }
        assertEquals(CommandOutcome.TIMEOUT, coordinator(mismatch).execute(plan(ConfirmKind.READBACK), context(mismatch)).outcome)
    }

    @Test fun writeFailureAndReadbackFailureAbort() {
        val t = FakeTransport().also { it.failWrite = true }
        assertEquals(CommandOutcome.DISCONNECTED, coordinator(t).execute(plan(), context(t)).outcome)
        val readback = FakeTransport()
        readback.onWrite = { if (readback.written.size == 2) readback.failWrite = true }
        assertEquals(CommandOutcome.DISCONNECTED, coordinator(readback).execute(plan(ConfirmKind.READBACK), context(readback)).outcome)
    }

    @Test fun disconnectAndReconnectDuringDelayStopContinuation() {
        for (reconnect in listOf(false, true)) {
            val t = FakeTransport()
            val ctx = context(t)
            val c = coordinator(t) { if (reconnect) t.connectionId = "connection2" else t.connected = false }
            assertEquals(CommandOutcome.DISCONNECTED, c.execute(plan(), ctx).outcome)
            assertEquals(1, t.written.size)
            assertEquals(null, ctx.initial.session.experimentalProfileKey)
        }
    }

    @Test fun cancelDuringFinalDelayPreventsReadback() {
        val t = FakeTransport()
        lateinit var c: CommandCoordinator
        c = coordinator(t) { c.cancel() }
        val p = CommandPlan(spec(ConfirmKind.READBACK), listOf(WriteStep(byteArrayOf(1), 5)), ReadbackSpec(byteArrayOf(9)) { true })
        assertEquals(CommandOutcome.CANCELLED, c.execute(p, context(t)).outcome)
        assertEquals(1, t.written.size)
    }

    @Test fun cancelOrReconnectDuringConfirmationNeverReportsSuccess() {
        for (cancel in listOf(false, true)) {
            val t = FakeTransport().also { it.reply(7) }
            val c = coordinator(t)
            t.onWait = { if (cancel) c.cancel() else t.connectionId = "connection2" }
            assertEquals(if (cancel) CommandOutcome.CANCELLED else CommandOutcome.DISCONNECTED,
                c.execute(plan(ConfirmKind.ACK_ONLY), context(t)).outcome)
        }
    }

    @Test fun noOptInUnauthenticatedAndWrongDeviceWriteNothing() {
        val t = FakeTransport()
        val ctx = context(t)
        ctx.initial.session.revokeExperimental()
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(plan(), ctx).outcome)
        val unauth = ctx.initial.copy(session = WriteSession(t.deviceId, t.connectionId, false))
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(plan(), CommandExecutionContext(unauth) { unauth }).outcome)
        val wrong = ctx.initial.copy(deviceId = "another-device")
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(plan(), CommandExecutionContext(wrong) { wrong }).outcome)
        assertTrue(t.written.isEmpty())
    }

    @Test fun identityChangeRevokesConsentAndStopsRemainingSteps() {
        val t = FakeTransport()
        val initial = state(t)
        var current = initial
        t.onWrite = { current = current.copy(identity = identity.copy(firmware = "changed")) }
        val ctx = CommandExecutionContext(initial) { current }
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(plan(), ctx).outcome)
        assertEquals(1, t.written.size)
        current = initial
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(plan(), ctx).outcome)
    }

    @Test fun parameterMetadataCannotBeSubstitutedAtExecution() {
        val t = FakeTransport()
        val s = spec().copy(params = listOf(ParamSpec("mode", null, 0.0, 2.0)))
        val p = CommandPlan(s, listOf(WriteStep(byteArrayOf(2))), parameterValues = mapOf("mode" to 2.0))
        val initial = state(t).copy(params = mapOf("mode" to 0.0))
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(p, CommandExecutionContext(initial) { initial }).outcome)
        assertTrue(t.written.isEmpty())
    }

    @Test fun concurrentAndRecursivePlansAreRejected() {
        val t = FakeTransport()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val c = coordinator(t)
        val ctx = context(t)
        t.onWrite = {
            assertEquals(CommandOutcome.REJECTED, c.execute(plan(), ctx).outcome)
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
        }
        var result: CommandResult? = null
        val worker = thread { result = c.execute(plan(), ctx) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertEquals(CommandOutcome.REJECTED, c.execute(plan(), ctx).outcome)
        release.countDown()
        worker.join(2_000)
        assertEquals(CommandOutcome.SENT_UNCONFIRMED, result?.outcome)
    }

    @Test fun mutableInputBytesCannotChangePlan() {
        val raw = byteArrayOf(1)
        val request = byteArrayOf(9)
        val steps = mutableListOf(WriteStep(raw))
        val p = CommandPlan(spec(ConfirmKind.READBACK), steps, ReadbackSpec(request) { true })
        raw[0] = 8; request[0] = 8; steps.clear()
        p.steps[0].bytes[0] = 8; requireNotNull(p.readback).request[0] = 8
        assertEquals(1.toByte(), p.steps[0].bytes[0])
        assertEquals(9.toByte(), requireNotNull(p.readback).request[0])
    }

    @Test(expected = IllegalArgumentException::class) fun ackKindRequiresPredicate() {
        CommandPlan(spec(ConfirmKind.ACK_ONLY), listOf(WriteStep(byteArrayOf(1))))
    }
    @Test(expected = IllegalArgumentException::class) fun readbackKindRequiresSpec() {
        CommandPlan(spec(ConfirmKind.READBACK), listOf(WriteStep(byteArrayOf(1))))
    }
    @Test(expected = IllegalArgumentException::class) fun emptyPlanRejected() { CommandPlan(spec(), emptyList()) }
    @Test fun expiredPlanStopsBeforeWriteAndBeforeReadback() {
        val t = FakeTransport()
        val initial = state(t)
        var current = initial.copy(nowMs = 1_001)
        val p = CommandPlan(spec(ConfirmKind.READBACK), listOf(WriteStep(byteArrayOf(1))),
            ReadbackSpec(byteArrayOf(9)) { true }, validUntilMs = 1_000)
        val ctx = CommandExecutionContext(initial) { current }
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(p, ctx).outcome)
        assertTrue(t.written.isEmpty())
        current = initial
        t.onWrite = { current = current.copy(nowMs = 1_001) }
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(p, ctx).outcome)
        assertEquals(1, t.written.size)
    }

    @Test fun reconnectBetweenGateAndSubmissionIsRejectedByBoundTransport() {
        val t = FakeTransport()
        val ctx = context(t)
        t.beforeBoundWrite = { t.connectionId = "connection2" }
        assertEquals(CommandOutcome.DISCONNECTED, coordinator(t).execute(plan(), ctx).outcome)
        assertTrue(t.written.isEmpty())
    }

    @Test fun bindingRequiresTheExactDeviceConnectionAndProfile() {
        val t = FakeTransport()
        val ctx = context(t)
        val valid = CommandBinding(t.deviceId, t.connectionId, identity.profileKey)
        for (bad in listOf(valid.copy(deviceId = "other"), valid.copy(connectionId = "old"), valid.copy(profileKey = "other"))) {
            val p = CommandPlan(spec(), listOf(WriteStep(byteArrayOf(1))), binding = bad)
            assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(p, ctx).outcome)
        }
        assertTrue(t.written.isEmpty())
        assertEquals(CommandOutcome.SENT_UNCONFIRMED, coordinator(t).execute(
            CommandPlan(spec(), listOf(WriteStep(byteArrayOf(1))), binding = valid), ctx).outcome)
    }

    @Test fun nestedPublicSpecCollectionsCannotChangeStoredAuthorizationScope() {
        val s = spec().copy(scope = CommandScope(Family.BEGODE, linkedSetOf("A2", "A3")),
            sourceRefs = mutableListOf("source1", "source2"))
        val p = CommandPlan(s, listOf(WriteStep(byteArrayOf(1))))
        (p.spec.scope.models as MutableSet<String>).clear()
        (p.spec.sourceRefs as MutableList<String>).clear()
        assertEquals(setOf("A2", "A3"), p.spec.scope.models)
        assertEquals(listOf("source1", "source2"), p.spec.sourceRefs)
        val t = FakeTransport()
        assertEquals(CommandOutcome.SENT_UNCONFIRMED, coordinator(t).execute(p, context(t)).outcome)
    }

    @Test fun liveDeviceSessionAndPlanMetadataChangesAreRejected() {
        val t = FakeTransport()
        val initial = state(t)
        val variants = listOf(initial.copy(deviceId = "other"), initial.copy(session = state(t).session),
            initial.copy(session = WriteSession("wrong-device", t.connectionId, true)))
        for (current in variants) {
            val ctx = CommandExecutionContext(initial) { current }
            assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(plan(), ctx).outcome)
        }
        assertTrue(t.written.isEmpty())
        val p = CommandPlan(spec().copy(params = listOf(ParamSpec("mode", null, 0.0, 2.0))),
            listOf(WriteStep(byteArrayOf(2))), parameterValues = mapOf("mode" to 2.0))
        val badInitial = initial.copy(params = mapOf("mode" to 1.0))
        val current = initial.copy(params = p.parameterValues)
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(p, CommandExecutionContext(badInitial) { current }).outcome)
    }

    @Test fun readbackCannotStartWhenConsentChangesAfterFinalStep() {
        val t = FakeTransport()
        val ctx = context(t)
        var checks = 0
        val live = CommandExecutionContext(ctx.initial) {
            if (++checks == 4) ctx.initial.session.revokeExperimental()
            ctx.initial
        }
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(plan(ConfirmKind.READBACK), live).outcome)
        assertEquals(2, t.written.size)
    }

    @Test fun readbackSubmissionRaceAbortsAndRevokesConsent() {
        val t = FakeTransport()
        val ctx = context(t)
        var submissions = 0
        t.beforeBoundWrite = { if (++submissions == 3) t.connectionId = "new-connection" }
        assertEquals(CommandOutcome.DISCONNECTED, coordinator(t).execute(plan(ConfirmKind.READBACK), ctx).outcome)
        assertEquals(2, t.written.size)
        assertEquals(null, ctx.initial.session.experimentalProfileKey)
    }

    @Test fun confirmationDeadlineStopsEvenWhenTransportHasNotReturnedAReply() {
        val t = FakeTransport().also { it.reply(7) }
        var clock = -200_000_000L
        val c = CommandCoordinator(t, {}, 100, nanoTime = { clock += 200_000_000; clock })
        assertEquals(CommandOutcome.TIMEOUT, c.execute(plan(ConfirmKind.ACK_ONLY), context(t)).outcome)
        assertEquals(2, t.written.size)
    }

    @Test fun invalidPlanParameterSetsRangesAndStepsAreRejected() {
        val s = spec().copy(params = listOf(ParamSpec("mode", null, 0.0, 2.0)))
        val invalid = listOf<() -> Unit>(
            { CommandPlan(s, listOf(WriteStep(byteArrayOf(1)))) },
            { CommandPlan(s, listOf(WriteStep(byteArrayOf(1))), parameterValues = mapOf("mode" to 3.0)) },
            { CommandPlan(spec(), listOf(WriteStep(byteArrayOf()))) },
            { CommandPlan(spec(), listOf(WriteStep(byteArrayOf(1), -1))) },
            { CommandCoordinator(FakeTransport(), ackTimeoutMs = 0) },
        )
        for (make in invalid) {
            try { make(); throw AssertionError("invalid command accepted") }
            catch (_: IllegalArgumentException) { /* constructor refused it */ }
        }
    }

    @Test fun notificationPayloadCannotChangeWhenAdapterReusesItsBuffer() {
        val buffer = byteArrayOf(0)
        val reply = TransportNotification(11, "connection1", buffer)
        buffer[0] = 7
        reply.bytes[0] = 7
        val t = FakeTransport().also { it.replies += reply }
        assertEquals(CommandOutcome.TIMEOUT, coordinator(t).execute(plan(ConfirmKind.ACK_ONLY), context(t)).outcome)
    }

    @Test fun matchingAckAfterDeadlineCannotConfirm() {
        val t = FakeTransport().also { it.reply(7) }
        var clock = 0L
        t.onWait = { clock = 100_000_000 }
        val c = CommandCoordinator(t, {}, 100, nanoTime = { clock })
        assertEquals(CommandOutcome.TIMEOUT, c.execute(plan(ConfirmKind.ACK_ONLY), context(t)).outcome)
    }

    @Test fun clockBeforeObservationRefusesThePlanWithoutWriting() {
        val t = FakeTransport()
        val initial = state(t)
        val p = CommandPlan(spec(), listOf(WriteStep(byteArrayOf(1))), validFromMs = 1_001, validUntilMs = 3_001)
        assertEquals(CommandOutcome.REJECTED, coordinator(t).execute(p, CommandExecutionContext(initial) { initial }).outcome)
        assertTrue(t.written.isEmpty())
        val started = initial.copy(nowMs = 1_001)
        assertEquals(CommandOutcome.SENT_UNCONFIRMED, coordinator(t).execute(p, CommandExecutionContext(started) { started }).outcome)
    }

    @Test fun invalidPlanValidityWindowIsRejected() {
        val invalid = listOf<() -> Unit>(
            { CommandPlan(spec(), listOf(WriteStep(byteArrayOf(1))), validFromMs = -1) },
            { CommandPlan(spec(), listOf(WriteStep(byteArrayOf(1))), validUntilMs = -1) },
            { CommandPlan(spec(), listOf(WriteStep(byteArrayOf(1))), validFromMs = 2, validUntilMs = 1) },
        )
        for (make in invalid) {
            try { make(); throw AssertionError("invalid command validity window accepted") }
            catch (_: IllegalArgumentException) { /* constructor refused it */ }
        }
    }

}
