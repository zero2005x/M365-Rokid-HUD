package io.github.zero2005x.pev.core.gateway

import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/** Result of encoding for legacy V1 glasses client. */
sealed interface V1EncodingResult {
    data class Success(val frame: GatewayV1Frame) : V1EncodingResult
    data class IncompatibleVehicle(val vehicleType: Int, val reason: String) : V1EncodingResult
}

/** Gatekeeper for remote/glasses inbound messages. */
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

    /**
     * Inspect any incoming payload from a glasses client.
     * Vehicle settings writes are strictly forbidden from glasses clients.
     */
    fun inspect(type: Byte, payload: ByteArray): InspectionResult {
        return when (type) {
            MSG_TYPE_HEARTBEAT -> InspectionResult.Allowed(type, "Heartbeat")
            MSG_TYPE_GLASSES_BATTERY -> {
                if (payload.isEmpty()) {
                    InspectionResult.Rejected(type, "Empty battery payload")
                } else {
                    InspectionResult.Allowed(type, "Glasses battery update")
                }
            }
            MSG_TYPE_COMMAND -> {
                InspectionResult.Rejected(type, "Vehicle setting or control commands cannot originate from glasses")
            }
            MSG_TYPE_DISPLAY_PREFS -> {
                // Glasses display preference is UI-only, not vehicle setting
                InspectionResult.Allowed(type, "Display preference")
            }
            else -> InspectionResult.Rejected(type, "Unknown or unsupported client message type: $type")
        }
    }
}

/** Translates core telemetry snapshots into versioned gateway frames. */
object GatewayCodec {

    private fun validDouble(snapshot: TelemetrySnapshot, vararg ids: FieldId): Double? {
        for (id in ids) {
            val r = snapshot[id]
            if (r.usable) return r.value
        }
        return null
    }

    /**
     * Translates to legacy V1 (20 bytes).
     * If vehicle is not Xiaomi M365, fails with IncompatibleVehicle to prevent
     * zero-filling and misleading the rider.
     */
    fun encodeV1(
        snapshot: TelemetrySnapshot,
        vehicleType: Int = GatewayProtocol.VEHICLE_XIAOMI_M365,
        connectionState: Int = GatewayProtocol.STATE_READY,
        tripMeters: Int = 0,
        tripSeconds: Int = 0,
    ): V1EncodingResult {
        if (vehicleType != GatewayProtocol.VEHICLE_XIAOMI_M365) {
            return V1EncodingResult.IncompatibleVehicle(
                vehicleType,
                "Vehicle family $vehicleType cannot be represented on legacy V1 glasses without zero-filling; upgrade required."
            )
        }

        val speed = (validDouble(snapshot, FieldId.SPEED_KMH) ?: 0.0).toFloat()
        val battery = (validDouble(snapshot, FieldId.SOC_PERCENT) ?: 0.0).toInt()
        val temp = (validDouble(snapshot, FieldId.TEMP_FRAME, FieldId.TEMP_MOSFET, FieldId.TEMP_BATTERY) ?: 0.0).toFloat()
        val totalDist = (validDouble(snapshot, FieldId.TOTAL_DISTANCE_M) ?: 0.0).toLong()

        val frame = GatewayV1Frame(
            speedKmh = speed,
            batteryPercent = battery,
            temperatureC = temp,
            totalDistanceMeters = totalDist,
            avgSpeedKmh = 0f,
            remainingRangeKm = 0f,
            connectionState = connectionState,
            tripMeters = tripMeters,
            tripSeconds = tripSeconds,
            isValid = true,
        )
        return V1EncodingResult.Success(frame)
    }

    /**
     * Translates to extended V2 with field validity flags.
     * Invalid/unknown fields are masked out in validityMask and will never be parsed as valid zeros.
     */
    fun encodeV2(
        snapshot: TelemetrySnapshot,
        vehicleType: Int,
        connectionState: Int,
        sequence: Long,
        tripMeters: Long? = null,
        tripSeconds: Long? = null,
        avgSpeedKmh: Float? = null,
        remainingRangeKm: Float? = null,
        alerts: Int = GatewayProtocol.ALERT_NONE,
    ): GatewayV2Frame {
        var mask = 0

        val speedVal = validDouble(snapshot, FieldId.SPEED_KMH)
        val speed = if (speedVal != null) {
            mask = mask or GatewayProtocol.FIELD_SPEED
            speedVal.toFloat()
        } else null

        val socVal = validDouble(snapshot, FieldId.SOC_PERCENT)
        val battery = if (socVal != null) {
            mask = mask or GatewayProtocol.FIELD_BATTERY
            socVal.toInt()
        } else null

        val tempVal = validDouble(snapshot, FieldId.TEMP_FRAME, FieldId.TEMP_MOSFET, FieldId.TEMP_BATTERY)
        val temp = if (tempVal != null) {
            mask = mask or GatewayProtocol.FIELD_TEMPERATURE
            tempVal.toFloat()
        } else null

        val distVal = validDouble(snapshot, FieldId.TOTAL_DISTANCE_M)
        val totalDist = if (distVal != null) {
            mask = mask or GatewayProtocol.FIELD_TOTAL_DISTANCE
            distVal.toLong()
        } else null

        val voltVal = validDouble(snapshot, FieldId.PACK_VOLTAGE)
        val voltage = if (voltVal != null) {
            mask = mask or GatewayProtocol.FIELD_VOLTAGE
            voltVal.toFloat()
        } else null

        val currVal = validDouble(snapshot, FieldId.BATTERY_CURRENT, FieldId.PHASE_CURRENT)
        val current = if (currVal != null) {
            mask = mask or GatewayProtocol.FIELD_CURRENT
            currVal.toFloat()
        } else null

        if (tripMeters != null) {
            mask = mask or GatewayProtocol.FIELD_TRIP_DISTANCE
        }
        if (tripSeconds != null) {
            mask = mask or GatewayProtocol.FIELD_TRIP_DURATION
        }
        if (avgSpeedKmh != null) {
            mask = mask or GatewayProtocol.FIELD_AVG_SPEED
        }
        if (remainingRangeKm != null) {
            mask = mask or GatewayProtocol.FIELD_REMAINING_RANGE
        }

        return GatewayV2Frame(
            vehicleType = vehicleType,
            connectionState = connectionState,
            sequence = sequence,
            validityMask = mask,
            speedKmh = speed,
            batteryPercent = battery,
            temperatureC = temp,
            totalDistanceMeters = totalDist,
            avgSpeedKmh = avgSpeedKmh,
            remainingRangeKm = remainingRangeKm,
            tripMeters = tripMeters,
            tripSeconds = tripSeconds,
            voltageVolts = voltage,
            currentAmperes = current,
            alertFlags = alerts,
        )
    }
}
