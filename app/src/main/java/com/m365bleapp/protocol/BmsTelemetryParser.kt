package com.m365bleapp.protocol

import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiBmsDecoder
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Legacy HUD DTO adapter for [XiaomiBmsDecoder], the authoritative M365 BMS decoder.
 * Wire offsets, scaling and validity live in the MIT core. Percentage clamping,
 * compact cell lists and zero-capacity suppression below retain existing HUD API behavior;
 * they are compatibility policies and do not establish measurement validity.
 *
 * ## Provenance — where these offsets come from
 *
 * The core's independently implemented offsets and scales come from the Scootbatt
 * 1.9.2 (`com.basse.scootbatt`) parser dispatch, specifically the BMS handler
 * registered for direction `0x22` (internal BMS) and `0x23` (external / eBMS).
 * Scootbatt's own field names are R8-obfuscated, so the *offsets and scales*
 * are evidence-backed while the *field names* here are our vocabulary.
 *
 * ## ⚠️ Not verified against real hardware
 *
 * These BMS layouts have not been checked against hardware captures. They are static
 * analysis of a third-party app, cross-checked against the community register
 * map in `doc/PROTOCOL_FAMILIES.md`. Treat every value as unconfirmed until a
 * capture from a real BMS exists.
 *
 * ## Register summary
 *
 * | Register | Length | Contents |
 * |---|---|---|
 * | `0x31` | 12 | remaining mAh, percent, current, voltage (power is derived) |
 * | `0x40` | 20 | 10 cell voltages, each `u16 / 1000` V |
 * | `0x35` | 2 | two temperatures, each `(u8 - 20)` °C |
 * | `0x30` | 1+ | status bits; bit 6 = charging |
 * | `0x18` | 2 | design capacity, mAh |
 * | `0x1B` | 4 | full charge count, partial charge count |
 * | `0x3B` | 1 | state of health, percent |
 */
object BmsTelemetryParser {

    /** Full payload length of the `0x31` "battery status" register. */
    const val STATUS_LENGTH = XiaomiBmsDecoder.STATUS_LENGTH

    /**
     * Bytes of the `0x31` register this parser actually reads.
     *
     * The register is 12 bytes, but the four fields decoded here occupy only the
     * first 8: remaining mAh, percent, current and voltage. The trailing four
     * bytes are not decoded (Scootbatt stores them but never surfaces them).
     *
     * Requiring only [STATUS_MIN_LENGTH] rather than the full [STATUS_LENGTH]
     * is deliberate: a scooter that answers with the 8 bytes we understand is
     * useful, and rejecting it would throw away a perfectly good reading. The
     * full length is still exported so callers can document or log it.
     */
    const val STATUS_MIN_LENGTH = XiaomiBmsDecoder.STATUS_MIN_LENGTH

    /** Number of cells reported by the `0x40` register. */
    const val CELL_COUNT = XiaomiBmsDecoder.CELL_COUNT

    /** Payload length of the `0x40` cell-voltage register. */
    const val CELL_VOLTAGE_LENGTH = XiaomiBmsDecoder.CELL_VOLTAGE_LENGTH

    /**
     * Parsed `0x31` battery status.
     *
     * [powerWatts] is derived (`volts * amps`) rather than transmitted, matching
     * what Scootbatt does.
     */
    data class Status(
        /** Charge remaining, in mAh. */
        val remainingMah: Int,
        /** State of charge, 0..100. */
        val percent: Int,
        /** Instantaneous current in A. Negative while discharging. */
        val currentAmps: Double,
        /** Pack voltage in V. */
        val voltageVolts: Double,
    ) {
        /** Derived power draw in W (`volts * amps`). */
        val powerWatts: Double get() = voltageVolts * currentAmps
    }

    /**
     * Parsed `0x40` cell voltages.
     *
     * [spreadVolts] is the useful HUD number: a pack with a wide spread is
     * unbalanced, which is what a rider actually wants to see.
     */
    data class Cells(
        /** Per-cell voltages in V, in the order transmitted. */
        val volts: List<Double>,
    ) {
        val highestVolts: Double? get() = volts.maxOrNull()
        val lowestVolts: Double? get() = volts.minOrNull()

        /** Highest minus lowest cell, or `null` when no cells were parsed. */
        val spreadVolts: Double?
            get() {
                val hi = highestVolts ?: return null
                val lo = lowestVolts ?: return null
                return hi - lo
            }
    }

    /** Parsed `0x35` temperatures. */
    data class Temperatures(
        /** First sensor, °C. */
        val firstCelsius: Double,
        /** Second sensor, °C. */
        val secondCelsius: Double,
    )

    /**
     * Parses the `0x31` battery-status register.
     *
     * @return `null` when the payload is shorter than [STATUS_MIN_LENGTH]. A
     *   short payload is a protocol error, not a zero reading: reporting 0 %
     *   would be worse than reporting nothing.
     */
    fun parseStatus(data: ByteArray): Status? {
        val decoded = XiaomiBmsDecoder.decodeStatus(data, 0)
        val remainingMah = decoded.remainingMah.value?.toInt() ?: return null
        val percent = decoded.rawSocPercent ?: return null
        val currentAmps = decoded.telemetry[FieldId.BATTERY_CURRENT].value ?: return null
        val voltageVolts = decoded.telemetry[FieldId.PACK_VOLTAGE].value ?: return null

        return Status(
            remainingMah = remainingMah,
            // Legacy display compatibility only: the core SOC Reading is INVALID above 100.
            percent = percent.coerceIn(0, 100),
            currentAmps = currentAmps,
            voltageVolts = voltageVolts,
        )
    }

    /**
     * Parses the `0x40` cell-voltage register.
     *
     * @return `null` when the payload is shorter than [CELL_VOLTAGE_LENGTH].
     *   Zero slots are omitted by legacy display policy. The core preserves every
     *   wire slot and does not infer that a zero proves a physical cell is absent.
     */
    fun parseCells(data: ByteArray): Cells? {
        val decoded = XiaomiBmsDecoder.decodeCells(data, 0)
        if (decoded.volts.any { it.state == FieldState.INVALID }) return null
        return Cells(decoded.volts.mapNotNull { it.value })
    }

    /**
     * Parses the `0x35` temperature register.
     *
     * Both bytes carry a `+20` bias, so the wire value is `(celsius + 20)`.
     *
     * @return `null` when fewer than two bytes are present.
     */
    fun parseTemperatures(data: ByteArray): Temperatures? {
        val decoded = XiaomiBmsDecoder.decodeTemperatures(data, 0)
        val first = decoded.firstCelsius.value ?: return null
        val second = decoded.secondCelsius.value ?: return null
        return Temperatures(
            firstCelsius = first,
            secondCelsius = second,
        )
    }

    /**
     * True when the `0x30` status register reports active charging.
     *
     * Bit 6 is the charging flag. Scootbatt reads the same bit.
     *
     * @return `null` when the payload is empty, so "unknown" is distinguishable
     *   from "not charging".
     */
    fun isCharging(data: ByteArray): Boolean? = XiaomiBmsDecoder.decodeCharging(data, 0).value

    /**
     * Parses the `0x18` design-capacity register, in mAh.
     *
     * @return `null` when absent or zero, retaining the legacy display policy.
     *   The core does not assert a zero-capacity sentinel from unverified semantics.
     */
    fun parseDesignCapacityMah(data: ByteArray): Int? =
        XiaomiBmsDecoder.decodeDesignCapacity(data, 0).value?.toInt()?.takeIf { it > 0 }

    /**
     * Parses the `0x1B` charge-cycle counters.
     *
     * @return `null` when absent; otherwise a pair of counter values.
     */
    fun parseChargeCounts(data: ByteArray): Pair<Int, Int>? {
        val decoded = XiaomiBmsDecoder.decodeChargeCounts(data, 0)
        val full = decoded.full.value?.toInt() ?: return null
        val partial = decoded.partial.value?.toInt() ?: return null
        return full to partial
    }

    /**
     * Parses the `0x3B` state-of-health register, in percent.
     *
     * Clamping is a legacy display policy. Core [XiaomiBmsDecoder.decodeHealth]
     * marks out-of-range values INVALID; consumers needing validity must use it.
     */
    fun parseHealthPercent(data: ByteArray): Int? =
        XiaomiBmsDecoder.rawHealthPercent(data)?.coerceIn(0, 100)

    /**
     * Convenience: reads a little-endian `u16` from [data] at [offset].
     *
     * Exposed so tests and other parsers share one implementation instead of
     * each hand-rolling the shift/or dance.
     */
    fun readU16(data: ByteArray, offset: Int): Int? = XiaomiBmsDecoder.readU16(data, offset)

    /** Convenience wrapper used by tests to validate endianness assumptions. */
    internal fun readI16(data: ByteArray, offset: Int): Int? = XiaomiBmsDecoder.readI16(data, offset)

    /** Little-endian buffer helper, mirroring how the rest of the app reads. */
    internal fun buffer(data: ByteArray): ByteBuffer =
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
}
