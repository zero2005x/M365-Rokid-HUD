package com.m365hud.glass.wifi

import com.m365hud.glass.TelemetryData
import io.github.zero2005x.pev.core.gateway.*
import io.github.zero2005x.pev.core.telemetry.FieldState
import java.nio.ByteBuffer

/** Wi-Fi V2 envelope: phone session generation (i64 BE), then unchanged core packet. */
internal class TypedTelemetryReceiver {
    private val gate = GatewayPacketGate()
    private var latestGeneration = Long.MIN_VALUE
    fun reset() { latestGeneration = Long.MIN_VALUE; gate.reset(Long.MIN_VALUE) }
    fun applyTyped(payload: ByteArray): TelemetryData? {
        if (payload.size < 8) return null
        val generation = ByteBuffer.wrap(payload).long
        val frame = GatewayV2Frame.fromBytesForRemoteDisplay(payload.copyOfRange(8, payload.size)) ?: return null
        if (generation < latestGeneration || !gate.accept(generation, frame.sequence)) return null
        latestGeneration = generation
        fun value(field: GatewayField): Double? = frame[field].takeIf { it.state == FieldState.VALID }?.value
        return TelemetryData(
            speedKmh = value(GatewayField.SPEED_KMH)?.toFloat() ?: Float.NaN,
            scooterBattery = value(GatewayField.SOC_PERCENT)?.toInt() ?: -1,
            temperatureC = value(GatewayField.TEMP_FRAME)?.toFloat() ?: Float.NaN,
            totalMileageM = value(GatewayField.TOTAL_DISTANCE_M)?.toLong() ?: -1,
            avgSpeedKmh = Float.NaN,
            remainingRangeKm = Float.NaN,
            tripMeters = value(GatewayField.TRIP_DISTANCE_M)?.toInt() ?: -1,
            tripSeconds = value(GatewayField.TRIP_TIME_S)?.toInt() ?: -1,
            connectionState = frame.connectionState,
            isValid = true,
            typedStates = GatewayField.entries.associateWith { frame[it].state },
        )
    }
}
