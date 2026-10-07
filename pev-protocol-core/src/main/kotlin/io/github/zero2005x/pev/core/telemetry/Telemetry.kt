package io.github.zero2005x.pev.core.telemetry

/** Physical unit of a telemetry value. Never inferred; each [FieldId] declares its own. */
enum class PhysUnit { KMH, VOLT, AMP, PERCENT, CELSIUS, METER, SECOND, RPM }

/** Why a reading can or cannot be trusted. UNKNOWN is never rendered as zero. */
enum class FieldState { VALID, STALE, INVALID, NOT_PROVIDED, UNSUPPORTED }

/** Strongest evidence behind the decoding of a field. Synthetic vectors never imply vehicle truth. */
enum class Evidence { SYNTHETIC, VENDOR_STATIC, WIRE_CAPTURED, VEHICLE_VERIFIED }

/**
 * Semantically distinct measurements. Pack voltage, phase/battery current and the
 * different temperatures are separate ids on purpose: merging them is a known source of
 * wrong-but-plausible telemetry.
 */
enum class FieldId(val unit: PhysUnit) {
    SPEED_KMH(PhysUnit.KMH),
    PACK_VOLTAGE(PhysUnit.VOLT),
    PHASE_CURRENT(PhysUnit.AMP),
    BATTERY_CURRENT(PhysUnit.AMP),
    SOC_PERCENT(PhysUnit.PERCENT),
    TEMP_IMU(PhysUnit.CELSIUS),
    TEMP_MOSFET(PhysUnit.CELSIUS),
    TEMP_MOTOR(PhysUnit.CELSIUS),
    TEMP_BATTERY(PhysUnit.CELSIUS),
    /** Frame/board temperature as labelled by the vendor register (e.g. Xiaomi BB); not an IMU/MOS reading. */
    TEMP_FRAME(PhysUnit.CELSIUS),
    TRIP_DISTANCE_M(PhysUnit.METER),
    TOTAL_DISTANCE_M(PhysUnit.METER),
    TRIP_TIME_S(PhysUnit.SECOND),
}

/** One field observation with provenance. [value] is null unless [state] is VALID or STALE. */
data class Reading(
    val value: Double?,
    val state: FieldState,
    val observedAtMs: Long? = null,
    val evidence: Evidence? = null,
    val source: String? = null,
) {
    /** True only for a fresh, valid value that UI/gates may act on. */
    val usable: Boolean get() = state == FieldState.VALID && value != null

    /** Age-based downgrade: VALID readings older than [maxAgeMs] become STALE; others unchanged. */
    fun aged(nowMs: Long, maxAgeMs: Long): Reading {
        val at = observedAtMs ?: return this
        return if (state == FieldState.VALID && nowMs - at > maxAgeMs) copy(state = FieldState.STALE) else this
    }

    companion object {
        val NOT_PROVIDED = Reading(null, FieldState.NOT_PROVIDED)
        val UNSUPPORTED = Reading(null, FieldState.UNSUPPORTED)

        fun valid(value: Double, observedAtMs: Long, evidence: Evidence, source: String? = null) =
            Reading(value, FieldState.VALID, observedAtMs, evidence, source)

        fun invalid(observedAtMs: Long, source: String? = null) =
            Reading(null, FieldState.INVALID, observedAtMs, null, source)
    }
}

/**
 * Immutable snapshot. Missing ids read as NOT_PROVIDED (never 0). [merge] applies a delta
 * without letting a newer-but-absent field silently keep an expired previous value: callers
 * should run [aged] with their freshness window before display or gating.
 */
data class TelemetrySnapshot(val fields: Map<FieldId, Reading> = emptyMap()) {
    operator fun get(id: FieldId): Reading = fields[id] ?: Reading.NOT_PROVIDED

    fun merge(delta: TelemetrySnapshot): TelemetrySnapshot = TelemetrySnapshot(fields + delta.fields)

    fun aged(nowMs: Long, maxAgeMs: Long): TelemetrySnapshot =
        TelemetrySnapshot(fields.mapValues { it.value.aged(nowMs, maxAgeMs) })

    /** Usable value or null: the only accessor safe for safety gates. */
    fun usableValue(id: FieldId): Double? = this[id].takeIf { it.usable }?.value
}
