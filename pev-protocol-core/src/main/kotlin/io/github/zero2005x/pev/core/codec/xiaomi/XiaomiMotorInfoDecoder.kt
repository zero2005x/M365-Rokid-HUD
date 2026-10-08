package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.Reading
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/**
 * Decoder for the Xiaomi `0xB0` motor-info block (32 payload bytes), little-endian.
 *
 * | offset | field | encoding |
 * |---:|---|---|
 * | 8 | state of charge | u16, percent |
 * | 10 | speed | u16; 0xC000..0xFFFF subtract 0x10000, then ÷1000 km/h |
 * | 14 | total distance | u32, metres |
 * | 22 | frame temperature | signed i16, ÷10 °C |
 *
 * Historical M365 payloads pin the layout and provide regression values, not independent
 * physical-scale acceptance. The available 152-payload replay does not reproduce the previously
 * claimed moving-window odometer/speed ratio. The threshold above preserves HUD compatibility;
 * its forward/wrap interpretation still needs separate evidence across the full raw-word range.
 * Negative readings are retained; clamping or magnitude is an App display policy.
 * Offsets 12 (average speed) and 18/20 are intentionally not decoded: their meaning is unresolved.
 */
object XiaomiMotorInfoDecoder {
    const val MIN_LENGTH = 22
    private const val SOURCE = "xiaomi.B0"

    fun decode(payload: ByteArray, nowMs: Long): TelemetrySnapshot {
        if (payload.size < MIN_LENGTH) return TelemetrySnapshot(allInvalid(nowMs))
        val tempReading = if (payload.size >= 24) {
            scaled(Le.i16(payload, 22), 0.1, nowMs)
        } else {
            Reading.NOT_PROVIDED
        }
        return TelemetrySnapshot(
            mapOf(
                FieldId.SOC_PERCENT to soc(payload, nowMs),
                FieldId.SPEED_KMH to decodeSpeed(payload, nowMs),
                FieldId.TOTAL_DISTANCE_M to scaled(Le.u32(payload, 14)?.toDouble(), 1.0, nowMs),
                FieldId.TEMP_FRAME to tempReading,
            ),
        )
    }

    private val FIELDS = listOf(FieldId.SOC_PERCENT, FieldId.SPEED_KMH, FieldId.TOTAL_DISTANCE_M, FieldId.TEMP_FRAME)

    private fun allInvalid(nowMs: Long) = FIELDS.associateWith { Reading.invalid(nowMs, SOURCE) }

    private fun soc(p: ByteArray, nowMs: Long): Reading {
        val raw = Le.u16(p, 8) ?: return Reading.invalid(nowMs, SOURCE)
        return if (raw in 0..100) Reading.valid(raw.toDouble(), nowMs, Evidence.WIRE_CAPTURED, SOURCE) else Reading.invalid(nowMs, SOURCE)
    }

    private fun decodeSpeed(p: ByteArray, nowMs: Long): Reading {
        val raw = Le.u16(p, 10) ?: return Reading.invalid(nowMs, SOURCE)
        val signedMh = if (raw >= 0xC000) raw - 0x10000 else raw
        return Reading.valid(signedMh * 0.001, nowMs, Evidence.WIRE_CAPTURED, SOURCE)
    }

    private fun scaled(raw: Number?, scale: Double, nowMs: Long): Reading =
        raw?.let { Reading.valid(it.toDouble() * scale, nowMs, Evidence.WIRE_CAPTURED, SOURCE) } ?: Reading.invalid(nowMs, SOURCE)
}
