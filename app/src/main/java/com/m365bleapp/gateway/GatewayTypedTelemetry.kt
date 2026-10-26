package com.m365bleapp.gateway

import com.m365bleapp.repository.MotorInfo
import io.github.zero2005x.pev.core.gateway.GatewayCodec
import io.github.zero2005x.pev.core.gateway.GatewayProtocol
import io.github.zero2005x.pev.core.gateway.GatewayV2Supplement
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/**
 * Typed V2 packet for a negotiated glasses link. Missing fields stay absent.
 * This does not replace the deployed 20-byte M365 packet.
 */
object GatewayTypedTelemetry {

    fun forVehicle(
        snapshot: TelemetrySnapshot,
        vehicleType: Int,
        connectionState: Int,
        sequence: Long,
        nowMs: Long,
        supplement: GatewayV2Supplement = GatewayV2Supplement(),
        maxAgeMs: Long = GatewayProtocol.DEFAULT_MAX_AGE_MS,
    ): ByteArray? {
        if (sequence !in 0..0xFFFFFFFFL) return null
        return runCatching {
            GatewayCodec.encodeV2(
                snapshot = snapshot,
                vehicleType = vehicleType,
                connectionState = connectionState,
                sequence = sequence,
                nowMs = nowMs,
                supplement = supplement,
                maxAgeMs = maxAgeMs,
            ).toBytes()
        }.getOrNull()
    }

    fun fromMotorInfoOrNull(
        info: MotorInfo?,
        vehicleType: Int,
        connectionState: Int,
        sequence: Long,
        nowMs: Long,
        maxAgeMs: Long = GatewayProtocol.DEFAULT_MAX_AGE_MS,
    ): ByteArray? = forVehicle(
        snapshot = TelemetrySnapshot(info?.typedFields.orEmpty()),
        vehicleType = vehicleType,
        connectionState = connectionState,
        sequence = sequence,
        nowMs = nowMs,
        maxAgeMs = maxAgeMs,
    )

    fun m365OrNull(info: MotorInfo?, connectionState: Int, sequence: Long, nowMs: Long): ByteArray? =
        fromMotorInfoOrNull(
            info = info,
            vehicleType = GatewayProtocol.VEHICLE_XIAOMI_M365,
            connectionState = connectionState,
            sequence = sequence,
            nowMs = nowMs,
        )
}
