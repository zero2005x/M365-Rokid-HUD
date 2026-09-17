package com.m365bleapp.protocol

/**
 * Decoders for the ESC (motor controller) registers that Scootbatt reads.
 *
 * ## Provenance and its limits
 *
 * Offsets and scales are transcribed from the decompiled Scootbatt 1.9.2 response
 * parsers (`C1138os` cases, keyed by register in `ao3.java:186-228`). Where a
 * value is signed or carries a bias the test names say so, because those are the
 * two places an implementation silently goes wrong.
 *
 * ## ⚠️ Not verified against real hardware
 *
 * No scooter has been attached to this project. Two specific caveats:
 *
 * - **`0xB5` speed units are unresolved.** Scootbatt scales it by `0.001` for
 *   Xiaomi models and `0.1` for everything else, which is an inference from code,
 *   not a capture. Both paths are exposed as constants so a real measurement can
 *   settle it in one place.
 * - **`0x25` is documented elsewhere as "remaining mileage" but Scootbatt treats
 *   it like a speed** (`u16 / 100` with a km/mi unit string). Both readings fit the
 *   arithmetic, so the accessor is named neutrally and the ambiguity is recorded
 *   rather than guessed away.
 */
object EscTelemetryParser {

    /** Applied to `0xB5` on Xiaomi models. */
    const val XIAOMI_SPEED_SCALE = 0.001

    /** Applied to `0xB5` on every other model. */
    const val DEFAULT_SPEED_SCALE = 0.1

    /** Applied to `0x25`, `0x2F` and `0xB9` (hundredths). */
    private const val HUNDREDTHS = 100.0

    /** Applied to `0x29` (odometer, thousandths of a km). */
    private const val THOUSANDTHS = 1000.0

    /** Applied to `0x3E` (tenths of a degree). */
    private const val TENTHS = 10.0

    /** Applied to `0x53` (hundredths of an amp). */
    private const val HUNDREDTHS_AMP = 100.0

    // ------------------------------------------------------------------ reads

    /** Reads a little-endian unsigned 16-bit value, or `null` when out of range. */
    fun u16(data: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 2 > data.size) return null
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    /** Reads a little-endian signed 16-bit value, or `null` when out of range. */
    fun i16(data: ByteArray, offset: Int): Int? {
        val raw = u16(data, offset) ?: return null
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    /**
     * Reads a little-endian signed 32-bit value, or `null` when out of range.
     *
     * Signed because Scootbatt reads these as signed ints: an odometer read as
     * unsigned would turn a small negative into ~4.29 billion km.
     */
    fun i32(data: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > data.size) return null
        var value = 0
        for (i in 3 downTo 0) {
            value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return value
    }

    /** Reads an unsigned byte, or `null` when out of range. */
    fun u8(data: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset >= data.size) return null
        return data[offset].toInt() and 0xFF
    }

    // -------------------------------------------------------------- registers

    /**
     * `0xB5` — instantaneous speed.
     *
     * @param xiaomi whether the connected model is a Xiaomi one, which changes the
     *   scale. Defaults to the non-Xiaomi path.
     */
    fun speedKmh(data: ByteArray, xiaomi: Boolean = false): Double? {
        val raw = i16(data, 0) ?: return null
        val scale = if (xiaomi) XIAOMI_SPEED_SCALE else DEFAULT_SPEED_SCALE
        return raw * scale
    }

    /**
     * `0x25` — a `u16 / 100` value with a km/mi unit string in Scootbatt.
     *
     * See the class note: documented elsewhere as remaining mileage, but the
     * arithmetic matches a speed too. Returned as a neutral scaled number.
     */
    fun scaled100(data: ByteArray): Double? = u16(data, 0)?.let { it / HUNDREDTHS }

    /** `0x2F` — the other `u16 / 100` value (speed-like). */
    fun scaled100Alt(data: ByteArray): Double? = u16(data, 0)?.let { it / HUNDREDTHS }

    /** `0xB9` — `u16 / 100` distance-like value. */
    fun scaled100Distance(data: ByteArray): Double? = u16(data, 0)?.let { it / HUNDREDTHS }

    /** `0x29` — total odometer in km (`i32 / 1000`). */
    fun odometerKm(data: ByteArray): Double? = i32(data, 0)?.let { it / THOUSANDTHS }

    /** `0x3E` — ESC / frame temperature in °C (`i16 / 10`). */
    fun escTemperatureC(data: ByteArray): Double? = i16(data, 0)?.let { it / TENTHS }

    /** `0x53` — motor phase current in A (`i16 / 100`). */
    fun phaseCurrentA(data: ByteArray): Double? = i16(data, 0)?.let { it / HUNDREDTHS_AMP }

    /** `0x47` — system (ADC-derived) voltage in V (`u16 / 100`). */
    fun systemVoltageV(data: ByteArray): Double? = u16(data, 0)?.let { it / HUNDREDTHS }

    /** `0x1B` — error / warning code. */
    fun errorCode(data: ByteArray): Int? = u16(data, 0)

    /**
     * `0x1B` — the code plus its description and severity.
     *
     * Combining them here keeps the table lookup and the numeric value in step:
     * a caller cannot report a description for a different code than it shows.
     */
    fun error(data: ByteArray): ScooterError? {
        val code = errorCode(data) ?: return null
        return ScooterError(
            code = code,
            description = ScooterErrorCodes.describe(code),
            severity = ScooterErrorCodes.severityOf(code),
        )
    }

    /** A decoded `0x1B` reading. */
    data class ScooterError(
        val code: Int,
        val description: String,
        val severity: ScooterErrorCodes.Severity,
    ) {
        /** True when this reading means "no fault". */
        val isHealthy: Boolean get() = ScooterErrorCodes.isHealthy(code)
    }

    /**
     * `0x75` — ride mode.
     *
     * Scootbatt maps `0 = Normal`, `1 = ECO`, `2 = Sport`, anything else to "N/D".
     */
    fun rideMode(data: ByteArray): RideMode? =
        u8(data, 0)?.let { RideMode.from(it) }

    /** Ride mode as reported by `0x75`. */
    enum class RideMode(val code: Int, val label: String) {
        NORMAL(0, "Normal"),
        ECO(1, "ECO"),
        SPORT(2, "Sport"),

        /** Reported for any code outside 0..2. */
        NOT_DEFINED(-1, "N/D");

        companion object {
            /** Maps a raw byte to a mode, never throwing. */
            fun from(raw: Int): RideMode = entries.firstOrNull { it.code == raw } ?: NOT_DEFINED
        }
    }

    /**
     * `0x7B` — KERS (regenerative braking) level.
     *
     * Three levels only; Scootbatt has no "off" value for the read path.
     */
    fun kersLevel(data: ByteArray): KersLevel? =
        u8(data, 0)?.let { KersLevel.from(it) }

    /** KERS level as reported by `0x7B`. */
    enum class KersLevel(val code: Int, val label: String) {
        WEAK(0, "Weak"),
        MEDIUM(1, "Medium"),
        STRONG(2, "Strong"),

        /** Reported for any code outside 0..2. */
        UNKNOWN(-1, "Unknown");

        companion object {
            /** Maps a raw byte to a level, never throwing. */
            fun from(raw: Int): KersLevel = entries.firstOrNull { it.code == raw } ?: UNKNOWN
        }
    }

    /** `0x7C` — cruise control engaged (`byte == 1`). */
    fun cruiseEngaged(data: ByteArray): Boolean? = u8(data, 0)?.let { it == 1 }

    /**
     * `0x7D` — status bitfield.
     *
     * Bit 1 is "tail light always on", bit 4 selects mph. Scootbatt reads this
     * register **little-endian** while it *writes* it big-endian, which is the
     * single most likely source of a wrong value in the whole register set; see
     * `doc/IMPROVEMENT_PLAN.md` stage D.
     */
    data class StatusBits(
        /** True when the tail light is configured to stay on. */
        val tailLightAlwaysOn: Boolean,
        /** True when the display unit is miles per hour. */
        val milesPerHour: Boolean,
        /** The raw 16-bit word, retained for diagnostics. */
        val raw: Int,
    )

    /** Decodes `0x7D`. */
    fun statusBits(data: ByteArray): StatusBits? {
        val raw = u16(data, 0) ?: return null
        return StatusBits(
            tailLightAlwaysOn = (raw and TAIL_LIGHT_BIT) != 0,
            milesPerHour = (raw and MPH_BIT) != 0,
            raw = raw,
        )
    }

    /** Bit 1 of `0x7D`: tail light always on. */
    const val TAIL_LIGHT_BIT = 1 shl 1

    /** Bit 4 of `0x7D`: display unit is mph. */
    const val MPH_BIT = 1 shl 4

    /**
     * `0x66` — firmware version triple plus a "version looks wrong" flag.
     *
     * Scootbatt builds a BCD-ish value from `b[4]`/`b[5]` and flags anything
     * outside 72..200 as suspicious. The exact meaning of the 72..200 window is
     * not documented anywhere; it is reproduced verbatim and flagged as inferred.
     */
    data class FirmwareVersions(
        val first: Int,
        val second: Int,
        val third: Int,
        /** True when the derived value falls outside the plausible 72..200 window. */
        val implausible: Boolean,
    )

    /** Lowest BCD-derived value Scootbatt accepts. */
    const val MIN_PLAUSIBLE_VERSION = 72

    /** Highest BCD-derived value Scootbatt accepts. */
    const val MAX_PLAUSIBLE_VERSION = 200

    /** Decodes `0x66`. Requires at least 6 bytes. */
    fun firmwareVersions(data: ByteArray): FirmwareVersions? {
        if (data.size < 6) return null
        val first = u16(data, 0) ?: return null
        val second = u16(data, 2) ?: return null
        val third = u16(data, 4) ?: return null

        val highNibble = ((data[4].toInt() and 0xF0) shr 4) * 10
        val lowNibble = data[4].toInt() and 0x0F
        val middle = (data[5].toInt() and 0xFF) * 100
        val derived = highNibble + middle + lowNibble

        return FirmwareVersions(
            first = first,
            second = second,
            third = third,
            implausible = derived < MIN_PLAUSIBLE_VERSION || derived > MAX_PLAUSIBLE_VERSION,
        )
    }

    /**
     * `0x32` / `0x34` — a duration in seconds, rendered as an uptime string.
     *
     * Scootbatt formats these as days/hours; only the numeric value is exposed
     * here so the display layer owns formatting.
     */
    fun uptimeSeconds(data: ByteArray): Int? = i32(data, 0)

    /**
     * `0x39` — a serial / MAC-like identity string.
     *
     * Scootbatt concatenates the first three bytes as decimals, then repeats them
     * dotted. Both forms are returned because the dotted one is what appears in
     * the UI while the plain one is what is parsed back to an integer.
     */
    data class Identity(val plain: String, val dotted: String, val numeric: Int?)

    /** Decodes `0x39`. Requires at least 4 bytes, matching Scootbatt. */
    fun identity(data: ByteArray): Identity? {
        if (data.size < 4) return null
        val a = data[0].toInt() and 0xFF
        val b = data[1].toInt() and 0xFF
        val c = data[2].toInt() and 0xFF

        val plain = "$a$b$c"
        return Identity(
            plain = plain,
            dotted = "$a.$b.$c",
            numeric = plain.toIntOrNull(),
        )
    }

    /**
     * `0xFF` — a 16-bit value that Scootbatt treats as unusable at 0 or 255.
     *
     * @return `null` when the value is 0 or 255, because both mean "not set"
     *   rather than a genuine reading of zero or 255.
     */
    fun u16ExcludingSentinels(data: ByteArray): Int? {
        val value = u16(data, 0) ?: return null
        return if (value == 0 || value == 255) null else value
    }
}
