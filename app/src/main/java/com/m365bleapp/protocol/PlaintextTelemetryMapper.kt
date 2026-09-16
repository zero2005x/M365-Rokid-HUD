package com.m365bleapp.protocol

/**
 * Maps a decoded plaintext reply onto telemetry fields.
 *
 * ## Why this is separate from the repository
 *
 * [PlaintextRegisterSession] proves a frame *arrives* correctly. Whether the right
 * register updates the right field is a different question, and in the repository
 * it was untestable because that code sits behind Android types and a live GATT
 * connection. This object holds only the mapping, so every branch is unit-tested
 * and the repository just applies the result.
 *
 * ## Design rules
 *
 * - **A value that fails to decode changes nothing.** Returning an empty update
 *   for a short or unparseable payload means the previous reading stays on screen.
 *   Writing a zero instead would show "0 km/h" on a moving scooter, and the rider
 *   has no way to tell that from a real stop.
 * - **Only the addressed register is touched.** Each update is a single named
 *   change rather than a whole new sample, so adding a register cannot silently
 *   reset an unrelated field.
 *
 * ## ⚠️ Not verified against real hardware
 *
 * Offsets come from [EscTelemetryParser] and [BmsTelemetryParser], which are
 * transcriptions of Scootbatt's parsers. No scooter has been attached.
 */
object PlaintextTelemetryMapper {

    /** Register byte for instantaneous speed. */
    const val REG_SPEED = 0xB5

    /** Register byte for the ESC error / warning code. */
    const val REG_ERROR = 0x1B

    /** Register byte for frame temperature. */
    const val REG_TEMPERATURE = 0x3E

    /** Register byte for battery state of health. */
    const val REG_HEALTH = 0x3B

    /**
     * One field change decoded from a reply.
     *
     * A sealed hierarchy rather than a mutable holder so a caller cannot apply a
     * half-populated update: every variant carries exactly the value it sets.
     */
    sealed class Update {
        /** Decoded a new speed, km/h. */
        data class Speed(val kmh: Double) : Update()

        /** Decoded an ESC error code, with its description for display. */
        data class Error(val code: Int, val description: String, val fault: Boolean) : Update()

        /** Decoded a frame temperature, °C. */
        data class Temperature(val celsius: Double) : Update()

        /** Decoded battery state of health, percent. */
        data class Health(val percent: Int) : Update()

        /** Nothing usable in this frame; apply nothing. */
        object Ignored : Update()
    }

    /**
     * Decodes [frame] into an [Update].
     *
     * @param xiaomi whether the model uses the Xiaomi speed scale. Threaded through
     *   rather than assumed, because the two scales differ by 100x on `0xB5`.
     * @return [Update.Ignored] for an unknown register or a payload that does not
     *   decode, so the caller never has to distinguish "no change" from "no data".
     */
    fun decode(frame: FrameCodec.Frame, xiaomi: Boolean = false): Update =
        decode(frame.command.toInt() and 0xFF, frame.payload, xiaomi)

    /**
     * Decodes by register and payload.
     *
     * Split out so it can be tested without constructing a [FrameCodec.Frame].
     */
    fun decode(register: Int, payload: ByteArray, xiaomi: Boolean = false): Update {
        // A reply with no payload cannot carry a value, whatever the register.
        if (payload.isEmpty()) return Update.Ignored

        return when (register) {
            REG_SPEED -> EscTelemetryParser.speedKmh(payload, xiaomi)
                ?.let { Update.Speed(it) }
                ?: Update.Ignored

            REG_ERROR -> EscTelemetryParser.error(payload)
                ?.let { Update.Error(it.code, it.description, !it.isHealthy) }
                ?: Update.Ignored

            REG_TEMPERATURE -> EscTelemetryParser.escTemperatureC(payload)
                ?.let { Update.Temperature(it) }
                ?: Update.Ignored

            REG_HEALTH -> BmsTelemetryParser.parseHealthPercent(payload)
                ?.let { Update.Health(it) }
                ?: Update.Ignored

            else -> Update.Ignored
        }
    }

    /**
     * Applies [update] to [current], returning the new sample.
     *
     * Pure: the same inputs always produce the same output, which is what makes
     * the mapping testable. A `null` [current] seeds a zero-valued sample, because
     * [com.m365bleapp.repository.MotorInfo] has four non-nullable legacy fields.
     *
     * Note that the speed update also leaves the legacy `temp` field alone and the
     * temperature update writes both the new `escTemperatureC` and the legacy
     * `temp`, so an older consumer that only reads `temp` keeps working.
     */
    fun apply(
        current: com.m365bleapp.repository.MotorInfo?,
        update: Update,
    ): com.m365bleapp.repository.MotorInfo? {
        if (update is Update.Ignored) return current
        val base = current ?: com.m365bleapp.repository.MotorInfo(
            speed = 0.0,
            battery = 0,
            temp = 0.0,
            mileage = 0.0,
        )

        return when (update) {
            is Update.Speed -> base.copy(speed = update.kmh)
            is Update.Error -> base.copy(
                errorCode = update.code,
                errorDescription = update.description,
            )
            is Update.Temperature -> base.copy(
                escTemperatureC = update.celsius,
                temp = update.celsius,
            )
            is Update.Health -> base.copy(batteryHealthPercent = update.percent)
            Update.Ignored -> current
        }
    }
}
