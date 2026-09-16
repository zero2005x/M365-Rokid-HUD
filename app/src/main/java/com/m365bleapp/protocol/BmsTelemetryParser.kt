package com.m365bleapp.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoded battery-management-system telemetry.
 *
 * ## Provenance — where these offsets come from
 *
 * Every offset and scale in this file was read out of the decompiled Scootbatt
 * 1.9.2 (`com.basse.scootbatt`) parser dispatch, specifically the BMS handler
 * registered for direction `0x22` (internal BMS) and `0x23` (external / eBMS).
 * Scootbatt's own field names are R8-obfuscated, so the *offsets and scales*
 * are evidence-backed while the *field names* here are our vocabulary.
 *
 * ## ⚠️ Not verified against real hardware
 *
 * No scooter has ever been attached to this project. These layouts are static
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
    const val STATUS_LENGTH = 12

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
    const val STATUS_MIN_LENGTH = 8

    /** Number of cells reported by the `0x40` register. */
    const val CELL_COUNT = 10

    /** Payload length of the `0x40` cell-voltage register. */
    const val CELL_VOLTAGE_LENGTH = CELL_COUNT * 2

    /** Temperature offset applied by the `0x35` register, in °C. */
    private const val TEMPERATURE_OFFSET = 20.0

    /** Cell voltages are transmitted in millivolts. */
    private const val CELL_VOLTAGE_SCALE = 1000.0

    /** Current is transmitted in hundredths of an amp. */
    private const val CURRENT_SCALE = 100.0

    /** Voltage is transmitted in hundredths of a volt. */
    private const val VOLTAGE_SCALE = 100.0

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
     * Reads a little-endian unsigned 16-bit value at [offset].
     *
     * Returns `null` when the payload is too short, so callers never index past
     * the end. Scootbatt instead lets an out-of-range read throw and reports it
     * to Crashlytics; a HUD that drops a frame is better than one that crashes.
     */
    private fun u16(data: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 2 > data.size) return null
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    /** Reads a little-endian signed 16-bit value at [offset], or `null`. */
    private fun i16(data: ByteArray, offset: Int): Int? {
        val raw = u16(data, offset) ?: return null
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    /** Reads an unsigned byte at [offset], or `null` when out of range. */
    private fun u8(data: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset >= data.size) return null
        return data[offset].toInt() and 0xFF
    }

    /**
     * Parses the `0x31` battery-status register.
     *
     * @return `null` when the payload is shorter than [STATUS_MIN_LENGTH]. A
     *   short payload is a protocol error, not a zero reading: reporting 0 %
     *   would be worse than reporting nothing.
     */
    fun parseStatus(data: ByteArray): Status? {
        if (data.size < STATUS_MIN_LENGTH) return null

        val remainingMah = u16(data, 0) ?: return null
        val percent = u16(data, 2) ?: return null
        val currentRaw = i16(data, 4) ?: return null
        val voltageRaw = u16(data, 6) ?: return null

        return Status(
            remainingMah = remainingMah,
            // Clamp: a BMS that reports >100 % is a known quirk on some packs,
            // and letting it through would draw a 130 %-full battery.
            percent = percent.coerceIn(0, 100),
            currentAmps = currentRaw / CURRENT_SCALE,
            voltageVolts = voltageRaw / VOLTAGE_SCALE,
        )
    }

    /**
     * Parses the `0x40` cell-voltage register.
     *
     * @return `null` when the payload is shorter than [CELL_VOLTAGE_LENGTH].
     *   Trailing zero cells are dropped, because some packs pad the block.
     */
    fun parseCells(data: ByteArray): Cells? {
        if (data.size < CELL_VOLTAGE_LENGTH) return null

        val volts = ArrayList<Double>(CELL_COUNT)
        for (i in 0 until CELL_COUNT) {
            val raw = u16(data, i * 2) ?: return null
            val v = raw / CELL_VOLTAGE_SCALE
            // A cell reading of exactly 0 V means "not populated" rather than a
            // genuinely dead cell, which cannot happen on a pack that is awake.
            if (v > 0.0) volts.add(v)
        }
        return Cells(volts)
    }

    /**
     * Parses the `0x35` temperature register.
     *
     * Both bytes carry a `+20` bias, so the wire value is `(celsius + 20)`.
     *
     * @return `null` when fewer than two bytes are present.
     */
    fun parseTemperatures(data: ByteArray): Temperatures? {
        val first = u8(data, 0) ?: return null
        val second = u8(data, 1) ?: return null
        return Temperatures(
            firstCelsius = first - TEMPERATURE_OFFSET,
            secondCelsius = second - TEMPERATURE_OFFSET,
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
    fun isCharging(data: ByteArray): Boolean? {
        val status = u8(data, 0) ?: return null
        return (status and 0x40) != 0
    }

    /**
     * Parses the `0x18` design-capacity register, in mAh.
     *
     * @return `null` when absent or zero (a pack never reports 0 mAh design
     *   capacity, so zero means "not populated").
     */
    fun parseDesignCapacityMah(data: ByteArray): Int? =
        u16(data, 0)?.takeIf { it > 0 }

    /**
     * Parses the `0x1B` charge-cycle counters.
     *
     * @return `null` when absent; otherwise a pair of counter values.
     */
    fun parseChargeCounts(data: ByteArray): Pair<Int, Int>? {
        val full = u16(data, 0) ?: return null
        val partial = u16(data, 2) ?: return null
        return full to partial
    }

    /**
     * Parses the `0x3B` state-of-health register, in percent.
     *
     * Clamped to 0..100 because the byte is used as a percentage directly and a
     * bad read should not render as a 255 %-healthy battery.
     */
    fun parseHealthPercent(data: ByteArray): Int? =
        u8(data, 0)?.coerceIn(0, 100)

    /**
     * Convenience: reads a little-endian `u16` from [data] at [offset].
     *
     * Exposed so tests and other parsers share one implementation instead of
     * each hand-rolling the shift/or dance.
     */
    fun readU16(data: ByteArray, offset: Int): Int? = u16(data, offset)

    /** Convenience wrapper used by tests to validate endianness assumptions. */
    internal fun readI16(data: ByteArray, offset: Int): Int? = i16(data, offset)

    /** Little-endian buffer helper, mirroring how the rest of the app reads. */
    internal fun buffer(data: ByteArray): ByteBuffer =
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
}
