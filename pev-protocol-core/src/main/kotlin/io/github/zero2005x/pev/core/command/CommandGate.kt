package io.github.zero2005x.pev.core.command

import io.github.zero2005x.pev.core.identity.DeviceIdentity
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/** Per-device, per-session write consent. Bound to one profile key; any identity change revokes it. */
class WriteSession(val deviceId: String, val connectionId: String, val authenticated: Boolean) {
    var experimentalProfileKey: String? = null
        private set
    private var observedProfileKey: String? = null

    /** Called on every identity observation; returning to an old identity never restores consent. */
    @Synchronized
    fun observeIdentity(identity: DeviceIdentity) {
        val key = identity.profileKey
        if (observedProfileKey != null && observedProfileKey != key) experimentalProfileKey = null
        observedProfileKey = key
    }

    @Synchronized
    fun enableExperimental(identity: DeviceIdentity) {
        observeIdentity(identity)
        experimentalProfileKey = identity.profileKey
    }

    @Synchronized
    fun revokeExperimental() {
        experimentalProfileKey = null
    }

    @Synchronized
    fun experimentalFor(identity: DeviceIdentity): Boolean {
        observeIdentity(identity)
        return experimentalProfileKey == identity.profileKey
    }
}

sealed interface GateDecision {
    data object Allowed : GateDecision
    data class Denied(val reason: String) : GateDecision
}

/**
 * Decides whether a spec may be sent now. Default-deny: unknown profile, unauthenticated
 * session, out-of-scope model/firmware, out-of-range parameter, missing evidence or stale
 * speed on riding-affecting commands all refuse. Experimental opt-in only lifts the evidence
 * requirement, nothing else.
 */
class CommandGate(private val maxSpeedAgeMs: Long = 2_000, private val standstillKmh: Double = 0.5) {
    init {
        require(maxSpeedAgeMs >= 0) { "speed age must be nonnegative" }
        require(standstillKmh.isFinite() && standstillKmh >= 0) { "standstill threshold must be finite and nonnegative" }
    }
    fun evaluate(
        spec: CommandSpec,
        identity: DeviceIdentity,
        session: WriteSession,
        params: Map<String, Double>,
        telemetry: TelemetrySnapshot,
        nowMs: Long,
    ): GateDecision {
        session.observeIdentity(identity)
        return firstDenial(spec, identity, session, params, telemetry, nowMs)
            ?.let { GateDecision.Denied(it) } ?: GateDecision.Allowed
    }

    private fun firstDenial(
        spec: CommandSpec,
        identity: DeviceIdentity,
        session: WriteSession,
        params: Map<String, Double>,
        telemetry: TelemetrySnapshot,
        nowMs: Long,
    ): String? = when {
        !session.authenticated -> "session not authenticated"
        sourceEvidenceIncomplete(spec) -> "command source evidence incomplete"
        !identity.isExact -> "profile not exactly identified"
        !inScope(spec, identity) -> "model/firmware outside command scope"
        spec.evidence != Evidence.VEHICLE_VERIFIED && !session.experimentalFor(identity) ->
            "unverified command requires per-session experimental opt-in"
        else -> paramDenial(spec, params) ?: standstillDenial(spec, telemetry, nowMs)
    }

    private fun sourceEvidenceIncomplete(spec: CommandSpec): Boolean =
        spec.evidence == Evidence.SYNTHETIC || spec.sourceRefs.isEmpty() || spec.sourceRefs.any { it.isBlank() }

    private fun inScope(spec: CommandSpec, id: DeviceIdentity): Boolean {
        val s = spec.scope
        val firmwareOk = s.firmwares.isEmpty() || (id.firmware != null && id.firmware in s.firmwares)
        return id.family == s.family && id.model in s.models && firmwareOk
    }

    private fun paramDenial(spec: CommandSpec, params: Map<String, Double>): String? {
        if (params.keys != spec.params.map { it.name }.toSet()) return "parameter set mismatch"
        return spec.params.firstOrNull { !it.accepts(params.getValue(it.name)) }?.let { "parameter ${it.name} out of range" }
    }

    private fun standstillDenial(spec: CommandSpec, t: TelemetrySnapshot, nowMs: Long): String? {
        if (!spec.affectsRiding) return null
        val reading = t[FieldId.SPEED_KMH]
        val at = reading.observedAtMs ?: return "speed unknown or stale; timestamp missing"
        val speed = reading.value
        if (!reading.usable || speed == null || !speed.isFinite() || at < 0 || nowMs < at ||
            nowMs - at > maxSpeedAgeMs || reading.evidence !in setOf(Evidence.WIRE_CAPTURED, Evidence.VEHICLE_VERIFIED) || reading.source.isNullOrBlank()
        ) return "speed unknown or stale; trusted stationary evidence required"
        return if (kotlin.math.abs(speed) > standstillKmh) "vehicle not at standstill" else null
    }
}
