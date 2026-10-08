package io.github.zero2005x.pev.core.gateway

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.Reading

/** Stable V2 field ids. Units belong to the field, never to a guessed alternate sensor. */
enum class GatewayField(val wireId: Int, val coreId: FieldId?, val unit: String) {
    SPEED_KMH(1, FieldId.SPEED_KMH, "km/h"),
    SOC_PERCENT(2, FieldId.SOC_PERCENT, "%"),
    TEMP_FRAME(3, FieldId.TEMP_FRAME, "C"),
    TOTAL_DISTANCE_M(4, FieldId.TOTAL_DISTANCE_M, "m"),
    AVG_SPEED_KMH(5, null, "km/h"),
    REMAINING_RANGE_KM(6, null, "km"),
    TRIP_DISTANCE_M(7, FieldId.TRIP_DISTANCE_M, "m"),
    TRIP_TIME_S(8, FieldId.TRIP_TIME_S, "s"),
    PACK_VOLTAGE(9, FieldId.PACK_VOLTAGE, "V"),
    BATTERY_CURRENT(10, FieldId.BATTERY_CURRENT, "A"),
    PHASE_CURRENT(11, FieldId.PHASE_CURRENT, "A"),
    TEMP_IMU(12, FieldId.TEMP_IMU, "C"),
    TEMP_MOSFET(13, FieldId.TEMP_MOSFET, "C"),
    TEMP_MOTOR(14, FieldId.TEMP_MOTOR, "C"),
    TEMP_BATTERY(15, FieldId.TEMP_BATTERY, "C");

    internal fun accepts(value: Double): Boolean {
        if (!value.isFinite()) return false
        return when (this) {
            SPEED_KMH -> value in -1000.0..1000.0
            AVG_SPEED_KMH -> value in 0.0..1000.0
            SOC_PERCENT -> value in 0.0..100.0
            TOTAL_DISTANCE_M, TRIP_DISTANCE_M, TRIP_TIME_S ->
                value in 0.0..GatewayProtocol.MAX_EXACT_COUNTER && value % 1.0 == 0.0
            REMAINING_RANGE_KM -> value in 0.0..1_000_000.0
            PACK_VOLTAGE -> value in 0.0..10_000.0
            BATTERY_CURRENT, PHASE_CURRENT -> value in -100_000.0..100_000.0
            else -> value in -273.15..10_000.0
        }
    }
}

enum class GatewayAlertKind(val wireId: Int) {
    SPEED_WARNING(1), BATTERY_LOW(2), TEMPERATURE_HIGH(3), BMS_FAULT(4), TILTBACK_ENGAGED(5), ESC_FAULT(6),
}

internal object GatewayReadingRules {
    fun numeric(state: FieldState): Boolean = state == FieldState.VALID || state == FieldState.STALE

    fun sourceValid(source: String?): Boolean = source == null ||
        (source.isNotBlank() && source.none { it.isISOControl() } &&
            Charsets.UTF_8.newEncoder().canEncode(source) &&
            source.toByteArray(Charsets.UTF_8).size <= GatewayProtocol.MAX_SOURCE_BYTES)

    fun validate(reading: Reading, generatedAtMs: Long, accepts: (Double) -> Boolean) {
        require(sourceValid(reading.source)) { "source label invalid or too long" }
        require(reading.observedAtMs == null || reading.observedAtMs >= 0) { "negative observation time" }
        if (numeric(reading.state)) {
            require(reading.value != null && accepts(reading.value)) { "invalid numeric field" }
            require(reading.observedAtMs != null && reading.observedAtMs <= generatedAtMs) { "numeric field requires a nonfuture observation" }
            require(reading.evidence != null && !reading.source.isNullOrBlank()) { "numeric field requires provenance" }
        } else {
            require(reading.value == null) { "unavailable field cannot carry a numeric value" }
        }
    }

    /** Normalize malformed values to INVALID, preserving metadata only when it is representable. */
    fun normalize(reading: Reading, nowMs: Long, maxAgeMs: Long, accepts: (Double) -> Boolean): Reading {
        val source = reading.source.takeIf { sourceValid(it) }
        val at = reading.observedAtMs?.takeIf { it >= 0 }
        if (!numeric(reading.state)) return reading.copy(value = null, observedAtMs = at, source = source)
        val value = reading.value
        if (value == null || !accepts(value) || at == null || at > nowMs || reading.evidence == null || source.isNullOrBlank()) {
            return Reading(null, FieldState.INVALID, at, reading.evidence, source)
        }
        val state = if (reading.state == FieldState.STALE || nowMs - at > maxAgeMs) FieldState.STALE else FieldState.VALID
        return reading.copy(state = state)
    }

    fun stateCode(state: FieldState): Int = when (state) {
        FieldState.NOT_PROVIDED -> 0
        FieldState.VALID -> 1
        FieldState.STALE -> 2
        FieldState.INVALID -> 3
        FieldState.UNSUPPORTED -> 4
    }

    fun state(code: Int): FieldState? = when (code) {
        0 -> FieldState.NOT_PROVIDED
        1 -> FieldState.VALID
        2 -> FieldState.STALE
        3 -> FieldState.INVALID
        4 -> FieldState.UNSUPPORTED
        else -> null
    }

    fun evidenceCode(evidence: Evidence?): Int = when (evidence) {
        null -> 0
        Evidence.SYNTHETIC -> 1
        Evidence.VENDOR_STATIC -> 2
        Evidence.WIRE_CAPTURED -> 3
        Evidence.VEHICLE_VERIFIED -> 4
    }

    fun evidence(code: Int): Evidence? = when (code) {
        1 -> Evidence.SYNTHETIC
        2 -> Evidence.VENDOR_STATIC
        3 -> Evidence.WIRE_CAPTURED
        4 -> Evidence.VEHICLE_VERIFIED
        else -> null
    }
}
