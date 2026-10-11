package com.m365bleapp.gateway

import com.m365bleapp.repository.MotorInfo
import io.github.zero2005x.pev.core.gateway.GatewayCodec
import io.github.zero2005x.pev.core.gateway.GatewayProtocol
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/**
 * Typed V2 packet for a negotiated glasses link. Missing fields stay absent.
 * This does not replace the deployed 20-byte M365 packet.
 */
object GatewayTypedTelemetry {

    fun m365OrNull(info: MotorInfo?, connectionState: Int, sequence: Long, nowMs: Long): ByteArray? {
        if (sequence !in 0..0xFFFFFFFFL) return null
        val fields = info?.typedFields.orEmpty()
        return runCatching {
            GatewayCodec.encodeV2(
                TelemetrySnapshot(fields),
                GatewayProtocol.VEHICLE_XIAOMI_M365,
                connectionState,
                sequence,
                nowMs,
            ).toBytes()
        }.getOrNull()
    }
}
