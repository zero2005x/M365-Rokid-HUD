package io.github.zero2005x.pev.core.command

import io.github.zero2005x.pev.core.identity.DeviceIdentity
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot
import io.github.zero2005x.pev.core.transport.PevTransport
import java.util.concurrent.locks.ReentrantLock

/** Live authorization inputs from the phone's current device/session state. */
data class CurrentWriteState(
    val deviceId: String,
    val identity: DeviceIdentity,
    val session: WriteSession,
    val params: Map<String, Double>,
    val telemetry: TelemetrySnapshot,
    val nowMs: Long,
)

/** The app must supply fresh state on every invocation; a captured stale snapshot is not a provider. */
class CommandExecutionContext(val initial: CurrentWriteState, val currentState: () -> CurrentWriteState) {
    val initialProfileKey: String = initial.identity.profileKey
}

/** Single writer: authorization is checked at each boundary and no plan survives reconnect. */
class CommandCoordinator(
    private val transport: PevTransport,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val ackTimeoutMs: Long = 1_000,
    private val gate: CommandGate = CommandGate(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = ReentrantLock()
    @Volatile private var cancelled = false

    init { require(ackTimeoutMs > 0) { "confirmation timeout must be positive" } }

    fun cancel() { cancelled = true }

    fun execute(plan: CommandPlan, context: CommandExecutionContext): CommandResult {
        // ReentrantLock.tryLock alone permits recursive execute on the same thread.
        if (lock.isHeldByCurrentThread || !lock.tryLock()) return result(CommandOutcome.REJECTED, "another command is in flight")
        try {
            cancelled = false
            return run(plan, context)
        } finally {
            lock.unlock()
        }
    }

    private fun check(plan: CommandPlan, context: CommandExecutionContext): CommandResult? {
        if (cancelled) return result(CommandOutcome.CANCELLED)
        val initial = context.initial
        val current = context.currentState()
        current.session.observeIdentity(current.identity)
        if (!transport.connected || transport.connectionId != initial.session.connectionId) {
            initial.session.revokeExperimental()
            return result(CommandOutcome.DISCONNECTED, "connection changed or disconnected")
        }
        if (transport.deviceId != initial.deviceId || current.deviceId != initial.deviceId ||
            current.session !== initial.session || current.session.deviceId != initial.deviceId ||
            current.identity.profileKey != context.initialProfileKey || current.params != plan.parameterValues ||
            initial.params != plan.parameterValues
        ) {
            initial.session.revokeExperimental()
            return result(CommandOutcome.REJECTED, "authorization context changed or does not match plan")
        }
        val binding = plan.binding
        if (binding != null && binding != CommandBinding(transport.deviceId, transport.connectionId, current.identity.profileKey)) {
            return result(CommandOutcome.REJECTED, "plan observation belongs to another device, connection or profile")
        }
        if (plan.validFromMs != null && current.nowMs < plan.validFromMs) {
            return result(CommandOutcome.REJECTED, "plan observation is from the future")
        }
        if (plan.validUntilMs != null && current.nowMs > plan.validUntilMs) {
            return result(CommandOutcome.REJECTED, "plan evidence expired")
        }
        return when (val decision = gate.evaluate(plan.spec, current.identity, current.session,
            current.params, current.telemetry, current.nowMs)) {
            GateDecision.Allowed -> null
            is GateDecision.Denied -> result(CommandOutcome.REJECTED, decision.reason)
        }
    }

    private fun run(plan: CommandPlan, context: CommandExecutionContext): CommandResult {
        var finalWriteCursor = transport.notificationSequence
        for (step in plan.steps) {
            check(plan, context)?.let { return it }
            finalWriteCursor = transport.notificationSequence
            if (!transport.writeForConnection(step.bytes, context.initial.session.connectionId)) {
                check(plan, context)?.let { return it }
                return result(CommandOutcome.DISCONNECTED, "write failed")
            }
            if (step.delayAfterMs > 0) sleep(step.delayAfterMs)
        }
        check(plan, context)?.let { return it }
        return when (plan.spec.confirm) {
            ConfirmKind.NONE -> result(CommandOutcome.SENT_UNCONFIRMED)
            ConfirmKind.ACK_ONLY -> awaitMatch(plan, context, finalWriteCursor, requireNotNull(plan.ack).expect, CommandOutcome.ACK_CONFIRMED)
            ConfirmKind.READBACK -> readback(plan, context)
        }
    }

    private fun readback(plan: CommandPlan, context: CommandExecutionContext): CommandResult {
        check(plan, context)?.let { return it }
        val spec = requireNotNull(plan.readback)
        val cursor = transport.notificationSequence
        if (!transport.writeForConnection(spec.request, context.initial.session.connectionId)) {
            check(plan, context)?.let { return it }
            return result(CommandOutcome.DISCONNECTED, "readback request failed")
        }
        return awaitMatch(plan, context, cursor, spec.expect, CommandOutcome.READBACK_CONFIRMED)
    }

    private fun awaitMatch(
        plan: CommandPlan,
        context: CommandExecutionContext,
        afterSequence: Long,
        expect: (ByteArray) -> Boolean,
        confirmed: CommandOutcome,
    ): CommandResult {
        val start = nanoTime()
        var cursor = afterSequence
        while (true) {
            check(plan, context)?.let { return it }
            val remaining = ackTimeoutMs - (nanoTime() - start) / 1_000_000
            if (remaining <= 0) return result(CommandOutcome.TIMEOUT, "no matching confirmation; not retried")
            val reply = transport.awaitNotifyAfter(remaining, cursor)
            check(plan, context)?.let { return it }
            if ((nanoTime() - start) / 1_000_000 >= ackTimeoutMs) {
                return result(CommandOutcome.TIMEOUT, "confirmation arrived after deadline; not retried")
            }
            if (reply == null) return result(CommandOutcome.TIMEOUT, "no matching confirmation; not retried")
            if (reply.connectionId != context.initial.session.connectionId || reply.sequence <= cursor) {
                return result(CommandOutcome.SENT_UNCONFIRMED, "uncorrelated notification")
            }
            cursor = reply.sequence
            if (expect(reply.bytes)) return result(confirmed)
        }
    }

    private fun result(outcome: CommandOutcome, detail: String? = null) = CommandResult(outcome, detail)
}
