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
class WriteStep(bytes: ByteArray, val delayAfterMs: Long = 0) {
    private val payload = bytes.copyOf()
    val bytes: ByteArray get() = payload.copyOf()
}

/** Read-back expectation: after the plan, query [request] and compare reply with [expect]. */
class ReadbackSpec(request: ByteArray, val expect: (ByteArray) -> Boolean) {
    private val payload = request.copyOf()
    val request: ByteArray get() = payload.copyOf()
}

/** A protocol-specific ACK predicate; ordinary telemetry must never satisfy it. */
class AckSpec(val expect: (ByteArray) -> Boolean)

/** A plan derived from observations is usable only on the same device, connection and profile. */
data class CommandBinding(val deviceId: String, val connectionId: String, val profileKey: String)

private fun copySpec(spec: CommandSpec): CommandSpec = spec.copy(
    scope = spec.scope.copy(models = spec.scope.models.toSet(), firmwares = spec.scope.firmwares.toSet()),
    params = spec.params.toList(), sourceRefs = spec.sourceRefs.toList(),
)

/** Complete, ordered, atomic unit the coordinator executes. */
class CommandPlan(
    spec: CommandSpec,
    steps: List<WriteStep>,
    val readback: ReadbackSpec? = null,
    val ack: AckSpec? = null,
    parameterValues: Map<String, Double> = emptyMap(),
    val validUntilMs: Long? = null,
    val binding: CommandBinding? = null,
    val validFromMs: Long? = null,
) {
    /** Trusted typed builders must encode precisely these values; arbitrary raw plans are not UI commands. */
    private val description = copySpec(spec)
    val spec: CommandSpec get() = copySpec(description)
    private val orderedSteps = steps.map { WriteStep(it.bytes, it.delayAfterMs) }
    val steps: List<WriteStep> get() = orderedSteps.toList()
    private val values = parameterValues.toMap()
    val parameterValues: Map<String, Double> get() = values.toMap()

    init {
        require(validFromMs == null || validFromMs >= 0) { "plan validity start must be nonnegative" }
        require(validUntilMs == null || validUntilMs >= 0) { "plan validity end must be nonnegative" }
        require(validFromMs == null || validUntilMs == null || validFromMs <= validUntilMs) { "plan validity window reversed" }
        require(this.parameterValues.keys == spec.params.map { it.name }.toSet()) { "plan parameter set mismatch" }
        require(spec.params.all { it.accepts(this.parameterValues.getValue(it.name)) }) { "plan parameter out of range" }
        require(steps.all { it.bytes.isNotEmpty() && it.delayAfterMs >= 0 }) { "invalid write step" }
        require((spec.confirm == ConfirmKind.ACK_ONLY) == (ack != null)) { "ACK spec must match confirm kind" }
        require(steps.isNotEmpty()) { "plan has no steps" }
        require((spec.confirm == ConfirmKind.READBACK) == (readback != null)) { "readback spec must match confirm kind" }
    }
}

/** Outcome reported to the UI; UNCONFIRMED is the honest "sent, not verified" state. */
enum class CommandOutcome { READBACK_CONFIRMED, ACK_CONFIRMED, SENT_UNCONFIRMED, TIMEOUT, CANCELLED, DISCONNECTED, REJECTED }

data class CommandResult(val outcome: CommandOutcome, val detail: String? = null)
