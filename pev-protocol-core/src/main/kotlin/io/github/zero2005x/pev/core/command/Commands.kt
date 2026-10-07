package io.github.zero2005x.pev.core.command

import io.github.zero2005x.pev.core.identity.Family
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.PhysUnit

/** How the vehicle confirms a write. Presented to the user separately, never conflated. */
enum class ConfirmKind { NONE, ACK_ONLY, READBACK }

/** Whether a failed/unconfirmed command may be re-sent. Non-idempotent commands never auto-retry. */
enum class RetryPolicy { NEVER, IDEMPOTENT_ONCE }

/** Where a command is applicable. null model/firmware means "not established", never "any". */
data class CommandScope(val family: Family, val models: Set<String>, val firmwares: Set<String> = emptySet())

/** Allowed parameter range with an explicit unit; commands with unknown units expose no parameter. */
data class ParamSpec(val name: String, val unit: PhysUnit?, val min: Double, val max: Double) {
    fun accepts(v: Double): Boolean = v in min..max
}

/** Static description of a command: evidence, scope and risk. Holds no wire bytes. */
data class CommandSpec(
    val id: String,
    val label: String,
    val scope: CommandScope,
    val params: List<ParamSpec>,
    val evidence: Evidence,
    val confirm: ConfirmKind,
    val retry: RetryPolicy,
    val affectsRiding: Boolean,
    val riskNote: String,
    val sourceRefs: List<String>,
)

/** A write step; [delayAfterMs] spaces multi-step sequences (e.g. ASCII letters). */
class WriteStep(val bytes: ByteArray, val delayAfterMs: Long = 0)

/** Read-back expectation: after the plan, query [request] and compare reply with [expect]. */
class ReadbackSpec(val request: ByteArray, val expect: (ByteArray) -> Boolean)

/** Complete, ordered, atomic unit the coordinator executes. */
class CommandPlan(
    val spec: CommandSpec,
    val steps: List<WriteStep>,
    val readback: ReadbackSpec? = null,
) {
    init {
        require(steps.isNotEmpty()) { "plan has no steps" }
        require((spec.confirm == ConfirmKind.READBACK) == (readback != null)) { "readback spec must match confirm kind" }
    }
}

/** Outcome reported to the UI; UNCONFIRMED is the honest "sent, not verified" state. */
enum class CommandOutcome { READBACK_CONFIRMED, ACK_CONFIRMED, SENT_UNCONFIRMED, TIMEOUT, CANCELLED, DISCONNECTED, REJECTED }

data class CommandResult(val outcome: CommandOutcome, val detail: String? = null)
