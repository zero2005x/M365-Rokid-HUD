package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.Reading
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/** Non-physical register observation. [raw] survives unknown enumerations for diagnostics. */
data class XiaomiRegisterValue<T>(
    val value: T?,
    val raw: Int?,
    val state: FieldState,
    val observedAtMs: Long,
    val source: String,
    val evidence: Evidence = Evidence.VENDOR_STATIC,
) {
    fun aged(nowMs: Long, maxAgeMs: Long): XiaomiRegisterValue<T> =
        if (state == FieldState.VALID && nowMs - observedAtMs > maxAgeMs) copy(state = FieldState.STALE) else this
}

/**
 * M365 register layouts; callers must select the profile before dispatching here.
 * These reads do not infer a model, authorize writes, or settle firmware compatibility.
 * Unknown 0x25 scale and firmware word roles remain raw diagnostics.
 */
object XiaomiEscDecoder {
    const val TAIL_LIGHT_BIT = 0x02
    const val MPH_BIT = 0x10

    data class StatusWord(val raw: Int) {
        val tailLightAlwaysOn: Boolean get() = raw and TAIL_LIGHT_BIT != 0
        val milesPerHour: Boolean get() = raw and MPH_BIT != 0
    }

    /** Three u16 words from 0x66; no inferred board roles or plausibility heuristic. */
    data class FirmwareWords(val first: Int, val second: Int, val third: Int)

    /** 0x39 version components, not a serial number, MAC, or exact vehicle model. */
    data class VersionComponents(val major: Int, val minor: Int, val patch: Int, val variant: Int)

    fun trip(payload: ByteArray, nowMs: Long): TelemetrySnapshot {
        val fields = listOf(FieldId.TRIP_TIME_S, FieldId.TRIP_DISTANCE_M)
        val source = source(0x3A)
        if (payload.size < 4) return TelemetrySnapshot(fields.associateWith { Reading.invalid(nowMs, source) })
        return TelemetrySnapshot(fields.mapIndexed { index, field ->
            field to Reading.valid(requireNotNull(Le.u16(payload, index * 2)).toDouble(), nowMs, Evidence.VENDOR_STATIC, source)
        }.toMap())
    }

    /** Raw 0x25 u16. Conflicting /10 and /100 sources prevent a physical range field. */
    fun rangeDiagnostic(payload: ByteArray, nowMs: Long): XiaomiRegisterValue<Int> = word(payload, 0x25, nowMs)

    /** Numeric ESC error code only; descriptions/severity remain app policy. */
    fun errorCode(payload: ByteArray, nowMs: Long): XiaomiRegisterValue<Int> = word(payload, 0x1B, nowMs)

    fun kers(payload: ByteArray, nowMs: Long): XiaomiRegisterValue<Int> {
        val raw = Le.u8(payload, 0)
        return observation(raw?.takeIf { it in 0..2 }, raw, 0x7B, nowMs)
    }

    /** Unknown encodings are INVALID, never silently converted to false. */
    fun cruise(payload: ByteArray, nowMs: Long): XiaomiRegisterValue<Boolean> {
        val raw = Le.u8(payload, 0)
        val value = when (raw) { 0 -> false; 1 -> true; else -> null }
        return observation(value, raw, 0x7C, nowMs)
    }

    fun statusWord(payload: ByteArray, nowMs: Long): XiaomiRegisterValue<StatusWord> {
        val raw = Le.u16(payload, 0)
        return observation(raw?.let(::StatusWord), raw, 0x7D, nowMs)
    }

    fun firmwareWords(payload: ByteArray, nowMs: Long): XiaomiRegisterValue<FirmwareWords> {
        val value = if (payload.size >= 6) FirmwareWords(
            requireNotNull(Le.u16(payload, 0)), requireNotNull(Le.u16(payload, 2)), requireNotNull(Le.u16(payload, 4)),
        ) else null
        return observation(value, null, 0x66, nowMs)
    }

    /** Static source requires five bytes; the unassigned suffix is not interpreted. */
    fun versionComponents(payload: ByteArray, nowMs: Long): XiaomiRegisterValue<VersionComponents> {
        val value = if (payload.size >= 5) VersionComponents(
            payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF,
            payload[2].toInt() and 0xFF, payload[3].toInt() and 0xFF,
        ) else null
        return observation(value, null, 0x39, nowMs)
    }

    private fun word(payload: ByteArray, register: Int, nowMs: Long): XiaomiRegisterValue<Int> {
        val raw = Le.u16(payload, 0)
        return observation(raw, raw, register, nowMs)
    }

    private fun source(register: Int) = "xiaomi.M365.ESC.${register.toString(16)}"

    private fun <T> observation(value: T?, raw: Int?, register: Int, nowMs: Long) = XiaomiRegisterValue(
        value, raw, if (value == null) FieldState.INVALID else FieldState.VALID, nowMs, source(register),
    )
}
