package io.github.zero2005x.pev.core.command

import io.github.zero2005x.pev.core.transport.PevTransport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The single writer. Plans run strictly one at a time (a second caller is REJECTED, not
 * queued, so multi-step sequences never interleave), are cancellable between steps, never
 * retry on timeout, and report ACK vs read-back vs "sent, unconfirmed" distinctly.
 */
class CommandCoordinator(
    private val transport: PevTransport,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val ackTimeoutMs: Long = 1_000,
) {
    private val lock = ReentrantLock()

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun execute(plan: CommandPlan): CommandResult {
        if (!lock.tryLock()) return CommandResult(CommandOutcome.REJECTED, "another command is in flight")
        try {
            cancelled = false
            return run(plan)
        } finally {
            lock.unlock()
        }
    }

    private fun run(plan: CommandPlan): CommandResult {
        for (step in plan.steps) {
            if (cancelled) return CommandResult(CommandOutcome.CANCELLED)
            if (!transport.connected || !transport.write(step.bytes)) {
                return CommandResult(CommandOutcome.DISCONNECTED, "write failed")
            }
            if (step.delayAfterMs > 0) sleep(step.delayAfterMs)
        }
        return confirm(plan)
    }

    private fun confirm(plan: CommandPlan): CommandResult = when (plan.spec.confirm) {
        ConfirmKind.NONE -> CommandResult(CommandOutcome.SENT_UNCONFIRMED)
        ConfirmKind.ACK_ONLY -> transport.awaitNotify(ackTimeoutMs)
            ?.let { CommandResult(CommandOutcome.ACK_CONFIRMED) }
            ?: CommandResult(CommandOutcome.TIMEOUT, "no ACK; not retried")
        ConfirmKind.READBACK -> readback(plan.readback!!)
    }

    private fun readback(spec: ReadbackSpec): CommandResult {
        if (!transport.write(spec.request)) return CommandResult(CommandOutcome.DISCONNECTED, "readback request failed")
        val reply = transport.awaitNotify(ackTimeoutMs)
            ?: return CommandResult(CommandOutcome.TIMEOUT, "no readback reply; not retried")
        return if (spec.expect(reply)) {
            CommandResult(CommandOutcome.READBACK_CONFIRMED)
        } else {
            CommandResult(CommandOutcome.SENT_UNCONFIRMED, "readback mismatch")
        }
    }
}
