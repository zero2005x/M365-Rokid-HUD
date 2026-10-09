package com.m365bleapp.gateway

import com.m365bleapp.repository.ConnectionState
import com.m365bleapp.repository.MotorInfo
import io.github.zero2005x.pev.core.gateway.GatewayV1Frame

internal fun ConnectionState?.toGatewayState(): Int = when (this) {
    is ConnectionState.Connecting, is ConnectionState.Handshaking -> M365HudGattProfile.STATE_CONNECTING
    is ConnectionState.Ready -> M365HudGattProfile.STATE_READY
    else -> M365HudGattProfile.STATE_DISCONNECTED
}

/** Both gateway transports use the same units and legacy wire fields. */
internal fun MotorInfo?.toGatewayFrame(connectionState: Int): GatewayV1Frame = GatewayV1Frame(
    speedKmh = this?.speed ?: 0.0,
    batteryPercent = this?.battery ?: 0,
    temperatureC = this?.temp ?: 0.0,
    totalDistanceMeters = ((this?.mileage ?: 0.0) * 1000).toLong(),
    avgSpeedKmh = this?.avgSpeed ?: 0.0,
    remainingRangeKm = this?.remainingKm ?: 0.0,
    connectionState = connectionState,
    tripMeters = this?.tripMeters ?: 0,
    tripSeconds = this?.tripSeconds ?: 0,
)
