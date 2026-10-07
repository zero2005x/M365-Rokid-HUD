package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.Reading
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot

/**
 * M365 BMS register payloads, independently implemented from documented offsets/scales.
 * Static vendor analysis supports these layouts; no BMS hardware capture validates them.
 * Callers must select the M365 profile: this is not evidence for every Xiaomi model.
 * Payloads exclude the logical reply header and padding. Additional bytes are ignored.
 */
object XiaomiBmsDecoder {
    const val STATUS_LENGTH = 12
    const val STATUS_MIN_LENGTH = 8
    const val TEMPERATURE_LENGTH = 2
    const val CELL_COUNT = 10
    const val CELL_VOLTAGE_LENGTH = CELL_COUNT * 2

    data class Status(
        val telemetry: TelemetrySnapshot,
        /** Remaining charge in milliampere-hours (mAh), not percent or energy. */
        val remainingMah: Reading,
        /** Unvalidated u16 wire diagnostic; only telemetry.SOC_PERCENT is suitable for validity gates. */
        val rawSocPercent: Int?,
    ) {
        fun aged(nowMs: Long, maxAgeMs: Long) =
            Status(telemetry.aged(nowMs, maxAgeMs), remainingMah.aged(nowMs, maxAgeMs), rawSocPercent)
    }

    /** Two distinct wire sensors in degrees Celsius; their physical locations are unresolved. */
    data class Temperatures(val firstCelsius: Reading, val secondCelsius: Reading) {
        fun aged(nowMs: Long, maxAgeMs: Long) =
            Temperatures(firstCelsius.aged(nowMs, maxAgeMs), secondCelsius.aged(nowMs, maxAgeMs))
    }

    /** Exactly ten zero-based wire slots in volts. Absent slots retain their original indices. */
    data class Cells(val volts: List<Reading>) {
        fun aged(nowMs: Long, maxAgeMs: Long) = Cells(volts.map { it.aged(nowMs, maxAgeMs) })
    }

    /** Dimensionless unsigned charge counters, including legitimate zero counts. */
    data class ChargeCounts(val full: Reading, val partial: Reading) {
        fun aged(nowMs: Long, maxAgeMs: Long) =
            ChargeCounts(full.aged(nowMs, maxAgeMs), partial.aged(nowMs, maxAgeMs))
    }

    /** Typed charging observation: an invalid payload never becomes a false flag. */
    data class Charging(
        val value: Boolean?,
        val state: FieldState,
        val observedAtMs: Long,
        val evidence: Evidence,
        val source: String,
    ) {
        val usable: Boolean get() = state == FieldState.VALID && value != null

        fun aged(nowMs: Long, maxAgeMs: Long): Charging =
            if (state == FieldState.VALID && nowMs - observedAtMs > maxAgeMs) {
                copy(state = FieldState.STALE)
            } else {
                this
            }
    }

    /** 0x31: u16 mAh@0, u16 percent@2, signed i16 /100 A@4, u16 /100 V@6. */
    fun decodeStatus(payload: ByteArray, nowMs: Long): Status {
        val complete = payload.size >= STATUS_MIN_LENGTH
        val remaining = if (complete) Le.u16(payload, 0) else null
        val soc = if (complete) Le.u16(payload, 2) else null
        val current = if (complete) Le.i16(payload, 4) else null
        val voltage = if (complete) Le.u16(payload, 6) else null
        return Status(
            TelemetrySnapshot(
                mapOf(
                    FieldId.SOC_PERCENT to percent(soc, nowMs, "31"),
                    FieldId.BATTERY_CURRENT to number(current?.div(100.0), nowMs, "31"),
                    FieldId.PACK_VOLTAGE to number(voltage?.div(100.0), nowMs, "31"),
                ),
            ),
            number(remaining?.toDouble(), nowMs, "31"),
            soc,
        )
    }

    /** 0x35: two unsigned bytes, each biased by +20 degrees Celsius. */
    fun decodeTemperatures(payload: ByteArray, nowMs: Long): Temperatures {
        if (payload.size < TEMPERATURE_LENGTH) {
            return Temperatures(number(null, nowMs, "35"), number(null, nowMs, "35"))
        }
        return Temperatures(
            number(((payload[0].toInt() and 0xFF) - 20).toDouble(), nowMs, "35"),
            number(((payload[1].toInt() and 0xFF) - 20).toDouble(), nowMs, "35"),
        )
    }

    /**
     * 0x40: ten little-endian u16 millivolt slots. A zero slot is represented as
     * NOT_PROVIDED by decoder policy; it does not prove the physical cell is absent or healthy.
     * A truncated block invalidates all slots rather than suggesting a smaller battery pack.
     */
    fun decodeCells(payload: ByteArray, nowMs: Long): Cells = Cells(
        List(CELL_COUNT) { slot ->
            val raw = if (payload.size >= CELL_VOLTAGE_LENGTH) Le.u16(payload, slot * 2) else null
            if (raw == 0) {
                Reading(null, FieldState.NOT_PROVIDED, nowMs, Evidence.VENDOR_STATIC, source("40"))
            } else {
                number(raw?.div(1000.0), nowMs, "40")
            }
        },
    )

    /** 0x30: unsigned status byte, charging indicated by bit 6. Other bits are preserved only on wire. */
    fun decodeCharging(payload: ByteArray, nowMs: Long): Charging {
        val raw = Le.u8(payload, 0)
        return Charging(
            raw?.let { it and 0x40 != 0 },
            if (raw == null) FieldState.INVALID else FieldState.VALID,
            nowMs,
            Evidence.VENDOR_STATIC,
            source("30"),
        )
    }

    /** 0x18: little-endian u16 design capacity in mAh. No unverified zero sentinel is assumed. */
    fun decodeDesignCapacity(payload: ByteArray, nowMs: Long): Reading =
        number(Le.u16(payload, 0)?.toDouble(), nowMs, "18")

    /** 0x1B: u16 full-cycle counter@0, u16 partial-cycle counter@2, little-endian. */
    fun decodeChargeCounts(payload: ByteArray, nowMs: Long): ChargeCounts {
        val complete = payload.size >= 4
        val full = if (complete) Le.u16(payload, 0) else null
        val partial = if (complete) Le.u16(payload, 2) else null
        return ChargeCounts(
            number(full?.toDouble(), nowMs, "1B"),
            number(partial?.toDouble(), nowMs, "1B"),
        )
    }

    /** 0x3B: unsigned state-of-health percent. Outside 0..100 is INVALID, never clamped. */
    fun decodeHealth(payload: ByteArray, nowMs: Long): Reading = percent(rawHealthPercent(payload), nowMs, "3B")

    /** Unvalidated 0x3B wire diagnostic. Use [decodeHealth] for telemetry and validity gates. */
    fun rawHealthPercent(payload: ByteArray): Int? = Le.u8(payload, 0)

    /** Bounded raw word readers for legacy adapters; these do not validate physical measurements. */
    fun readU16(payload: ByteArray, offset: Int): Int? =
        if (offset >= 0 && offset <= payload.size - 2) Le.u16(payload, offset) else null

    fun readI16(payload: ByteArray, offset: Int): Int? =
        if (offset >= 0 && offset <= payload.size - 2) Le.i16(payload, offset) else null

    private fun percent(raw: Int?, nowMs: Long, register: String): Reading =
        // Both callers supply unsigned wire values, so the lower bound is already guaranteed.
        number(raw?.takeIf { it <= 100 }?.toDouble(), nowMs, register)

    private fun number(value: Double?, nowMs: Long, register: String): Reading = Reading(
        value,
        if (value == null) FieldState.INVALID else FieldState.VALID,
        nowMs,
        Evidence.VENDOR_STATIC,
        source(register),
    )

    private fun source(register: String) = "xiaomi.M365.BMS.$register"
}
