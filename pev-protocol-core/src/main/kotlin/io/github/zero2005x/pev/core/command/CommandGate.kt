package io.github.zero2005x.pev.core.command

import io.github.zero2005x.pev.core.identity.DeviceIdentity
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/** Per-device, per-session write consent. Bound to one profile key; any identity change revokes it. */
class WriteSession(val deviceId: String, val authenticated: Boolean) {
    var experimentalProfileKey: String? = null
        private set

    /** Owner explicitly opts in for exactly this profile in this session. */
    fun enableExperimental(identity: DeviceIdentity) {
        experimentalProfileKey = identity.profileKey
    }

    fun revokeExperimental() {
        experimentalProfileKey = null
    }

    fun experimentalFor(identity: DeviceIdentity): Boolean = experimentalProfileKey == identity.profileKey
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
    fun evaluate(
        spec: CommandSpec,
        identity: DeviceIdentity,
        session: WriteSession,
        params: Map<String, Double>,
        telemetry: TelemetrySnapshot,
        nowMs: Long,
    ): GateDecision = firstDenial(spec, identity, session, params, telemetry, nowMs)
        ?.let { GateDecision.Denied(it) } ?: GateDecision.Allowed

    private fun firstDenial(
        spec: CommandSpec,
        identity: DeviceIdentity,
        session: WriteSession,
        params: Map<String, Double>,
        telemetry: TelemetrySnapshot,
        nowMs: Long,
    ): String? = when {
        !session.authenticated -> "session not authenticated"
        !identity.isExact -> "profile not exactly identified"
        !inScope(spec, identity) -> "model/firmware outside command scope"
        spec.evidence != Evidence.VEHICLE_VERIFIED && !session.experimentalFor(identity) ->
            "unverified command requires per-session experimental opt-in"
        else -> paramDenial(spec, params) ?: standstillDenial(spec, telemetry, nowMs)
    }

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
        val speed = t.aged(nowMs, maxSpeedAgeMs).usableValue(FieldId.SPEED_KMH)
            ?: return "speed unknown or stale; riding-affecting command refused"
        return if (kotlin.math.abs(speed) > standstillKmh) "vehicle not at standstill" else null
    }
}
