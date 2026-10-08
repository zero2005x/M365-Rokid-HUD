package io.github.zero2005x.pev.core.gateway

import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.Reading
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

sealed interface V1EncodingResult {
    data class Success(val frame: GatewayV1Frame) : V1EncodingResult
    data class IncompatibleVehicle(val vehicleType: Int, val reason: String) : V1EncodingResult
}

/** Glasses may return health feedback; preferences flow from phone to glasses only. */
object GatewayCommandGuard {
    const val MSG_TYPE_TELEMETRY: Byte = 0x01
    const val MSG_TYPE_TIME: Byte = 0x02
    const val MSG_TYPE_COMMAND: Byte = 0x03
    const val MSG_TYPE_HEARTBEAT: Byte = 0x04
    const val MSG_TYPE_GLASSES_BATTERY: Byte = 0x05
    const val MSG_TYPE_DISPLAY_PREFS: Byte = 0x06

    sealed interface InspectionResult {
        data class Allowed(val type: Byte, val description: String) : InspectionResult
        data class Rejected(val type: Byte, val reason: String) : InspectionResult
    }

    fun inspect(type: Byte, payload: ByteArray): InspectionResult = when {
        type == MSG_TYPE_HEARTBEAT && payload.size in setOf(0, 8) -> InspectionResult.Allowed(type, "Heartbeat")
        type == MSG_TYPE_GLASSES_BATTERY && payload.size == 1 && (payload[0].toInt() and 0xFF) <= 100 ->
            InspectionResult.Allowed(type, "Glasses battery update")
        type == MSG_TYPE_COMMAND -> InspectionResult.Rejected(type, "Vehicle settings cannot originate from glasses")
        else -> InspectionResult.Rejected(type, "Unsupported client message or invalid feedback payload")
    }
}

object GatewayCodec {
    /** V1 has no per-field unknown/stale representation, so an unsafe downgrade requires upgraded glasses. */
    fun encodeV1(snapshot: TelemetrySnapshot, vehicleType: Int, connectionState: Int, nowMs: Long,
        avgSpeed: Reading = Reading.NOT_PROVIDED, remainingRange: Reading = Reading.NOT_PROVIDED,
        maxAgeMs: Long = GatewayProtocol.DEFAULT_MAX_AGE_MS): V1EncodingResult {
        require(nowMs >= 0 && maxAgeMs >= 0) { "invalid freshness window" }
        if (vehicleType != GatewayProtocol.VEHICLE_XIAOMI_M365 || !GatewayProtocol.validState(connectionState)) {
            return incompatible(vehicleType, "vehicle/state cannot be represented")
        }
        val values = mapOf(
            GatewayField.SPEED_KMH to snapshot[FieldId.SPEED_KMH], GatewayField.SOC_PERCENT to snapshot[FieldId.SOC_PERCENT],
            GatewayField.TEMP_FRAME to snapshot[FieldId.TEMP_FRAME], GatewayField.TOTAL_DISTANCE_M to snapshot[FieldId.TOTAL_DISTANCE_M],
            GatewayField.TRIP_DISTANCE_M to snapshot[FieldId.TRIP_DISTANCE_M], GatewayField.TRIP_TIME_S to snapshot[FieldId.TRIP_TIME_S],
            GatewayField.AVG_SPEED_KMH to avgSpeed, GatewayField.REMAINING_RANGE_KM to remainingRange,
        ).mapValues { (id, r) -> GatewayReadingRules.normalize(r, nowMs, maxAgeMs, id::accepts) }
        if (values.values.any { it.state != FieldState.VALID }) return incompatible(vehicleType, "unknown, invalid or stale telemetry")
        val v = values.mapValues { requireNotNull(it.value.value) }
        if (!fitsV1(v)) return incompatible(vehicleType, "metric exceeds legacy representation")
        return V1EncodingResult.Success(GatewayV1Frame(v.getValue(GatewayField.SPEED_KMH), v.getValue(GatewayField.SOC_PERCENT).toInt(),
            v.getValue(GatewayField.TEMP_FRAME), v.getValue(GatewayField.TOTAL_DISTANCE_M).toLong(),
            v.getValue(GatewayField.AVG_SPEED_KMH), v.getValue(GatewayField.REMAINING_RANGE_KM), connectionState,
            v.getValue(GatewayField.TRIP_DISTANCE_M).toInt(), v.getValue(GatewayField.TRIP_TIME_S).toInt()))
    }

    private fun fitsV1(v: Map<GatewayField, Double>): Boolean =
        v.getValue(GatewayField.SPEED_KMH) in -327.68..327.67 && v.getValue(GatewayField.TEMP_FRAME) in -3276.8..3276.7 &&
            v.getValue(GatewayField.TOTAL_DISTANCE_M) <= 0xFFFFFFFFL && v.getValue(GatewayField.TRIP_DISTANCE_M) <= 65535 &&
            v.getValue(GatewayField.TRIP_TIME_S) <= 65535 && v.getValue(GatewayField.AVG_SPEED_KMH) <= 655.35 &&
            v.getValue(GatewayField.REMAINING_RANGE_KM) <= 6553.5

    private fun incompatible(vehicle: Int, reason: String) = V1EncodingResult.IncompatibleVehicle(vehicle, "$reason; upgrade required")

    /** Complete snapshot. Extras are explicitly typed average/range only, not alternate sensor substitutions. */
    fun encodeV2(snapshot: TelemetrySnapshot, vehicleType: Int, connectionState: Int, sequence: Long, nowMs: Long,
        extras: Map<GatewayField, Reading> = emptyMap(), alerts: Map<GatewayAlertKind, Reading> = emptyMap(),
        maxAgeMs: Long = GatewayProtocol.DEFAULT_MAX_AGE_MS): GatewayV2Frame {
        require(nowMs >= 0 && maxAgeMs >= 0) { "invalid freshness window" }
        require(extras.keys.all { it.coreId == null }) { "extras cannot override core measurements" }
        val fields = GatewayField.entries.associateWith { id ->
            val reading = id.coreId?.let { snapshot[it] } ?: (extras[id] ?: Reading.NOT_PROVIDED)
            GatewayReadingRules.normalize(reading, nowMs, maxAgeMs, id::accepts)
        }
        return GatewayV2Frame(vehicleType, connectionState, sequence, nowMs, fields,
            alerts.mapValues { (_, r) -> GatewayReadingRules.normalize(r, nowMs, maxAgeMs) { it == 0.0 || it == 1.0 } })
    }
}
