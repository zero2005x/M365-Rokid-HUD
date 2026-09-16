package com.m365bleapp.protocol

import com.m365bleapp.repository.MotorInfo
import kotlin.random.Random

/**
 * Synthetic ride telemetry for testing without a scooter.
 *
 * ## Why this exists
 *
 * The BLE peripheral on a phone cannot impersonate a scooter, so with no scooter
 * present the HUD has nothing to render and none of the display path can be
 * exercised on a real device. This class produces a plausible [MotorInfo] stream
 * instead, which [com.m365bleapp.repository.ScooterRepository] can push into the
 * same `_motorInfo` flow the real parser writes to. Because that flow is the
 * single source for the phone UI, the BLE gateway **and** the WiFi gateway, a
 * demo run exercises the whole chain including the glasses HUD.
 *
 * ## Design constraints
 *
 * - **Pure and deterministic.** No clock, no coroutine, no Android types. The
 *   caller supplies the elapsed milliseconds and a seeded [Random], so a test can
 *   replay an identical ride and assert on it. Randomness without a seed would
 *   make every assertion here flaky.
 * - **No I/O.** Advancing the ride is [step]; nothing sends anything.
 * - **Physically sane bounds.** Speed never goes negative and never exceeds
 *   [MAX_SPEED_KMH]; battery only ever falls; mileage only ever rises. A demo
 *   that shows a 400 km/h scooter regenerating charge would hide exactly the
 *   parsing bugs this mode is meant to surface.
 *
 * ## ⚠️ Not a protocol test
 *
 * This bypasses [FrameCodec], the crypto layer and the parsers entirely. It
 * proves the *display* path, not the *protocol* path. Passing a demo run says
 * nothing about whether real frames decode correctly.
 */
class DemoRideSource(
    seed: Int = DEFAULT_SEED,
    initialMileageKm: Double = 120.0,
    initialBatteryPercent: Int = 92,
) {

    companion object {
        /** Seed used when the caller does not care about reproducibility. */
        const val DEFAULT_SEED = 0x5C007

        /** Hard ceiling on simulated speed, km/h. */
        const val MAX_SPEED_KMH = 25.0

        /** Simulated pack capacity used for the remaining-range estimate. */
        const val PACK_RANGE_AT_FULL_KM = 30.0

        /** Nominal pack capacity in mAh. A 10S pack of ~7.8 Ah is typical. */
        const val PACK_CAPACITY_MAH = 7800

        /** Nominal pack voltage. Used for `0x31` voltage and the `0x40` cell split. */
        const val PACK_VOLTAGE_V = 37.0

        /** Cells in the simulated pack, matching the `0x40` register's ten slots. */
        const val CELL_COUNT = BmsTelemetryParser.CELL_COUNT

        /**
         * Distinguishable from a real scooter in logs and on screen, so a demo
         * run is never mistaken for live telemetry.
         */
        const val DEMO_MARKER = "DEMO"

        /** Battery percent lost per simulated hour at cruising speed. */
        private const val DRAIN_PERCENT_PER_HOUR = 12.0
    }

    private val seed = seed

    /**
     * Seeded generator. Re-created by [reset], because [Random] is a *consumable*
     * sequence: keeping the seed alone is not enough to replay a ride, since the
     * generator's position has already advanced.
     */
    private var random = Random(seed)

    /** Starting values, retained so [reset] restores the constructor's state. */
    private val initialMileage = initialMileageKm
    private val initialBattery = initialBatteryPercent.toDouble()

    // ---- drive cycle -----------------------------------------------------
    // Ramp up, hold, coast down, stop, repeat. A flat constant speed would let
    // several off-by-one bugs hide, because every tick would look the same.
    private enum class Phase { RAMP_UP, CRUISE, COAST, STOPPED }

    private var phase = Phase.RAMP_UP

    /**
     * Milliseconds spent in [phase].
     *
     * A plain `Long`. This was written as `0.0L`, which is not a valid Long
     * literal — a decimal point and an `L` suffix cannot be combined — and it
     * failed to compile.
     */
    private var phaseElapsedMs = 0L

    private var speedKmh = 0.0
    private var batteryPercent = initialBatteryPercent.toDouble()
    private var mileageKm = initialMileageKm
    private var tripMeters = 0.0
    private var tripSeconds = 0.0

    /** Latest generated sample. `null` until the first [step]. */
    var current: MotorInfo? = null
        private set

    /** True once the simulated battery has run flat. */
    val isDepleted: Boolean get() = batteryPercent <= 0.0

    /**
     * Advances the simulation by [elapsedMs] and returns the new sample.
     *
     * @param elapsedMs wall-clock milliseconds since the previous call. Values
     *   `<= 0` still produce a sample (so a caller can read the initial state)
     *   but do not advance distance or battery.
     */
    fun step(elapsedMs: Long): MotorInfo {
        val dtMs = elapsedMs.coerceAtLeast(0L)
        advancePhase(dtMs)

        val target = targetSpeedForPhase()
        // Approach the target rather than jumping to it, so the rendered speed
        // moves smoothly instead of stepping between discrete values.
        val responsePerSecond = 3.5
        val blend = (dtMs / 1000.0 * responsePerSecond).coerceIn(0.0, 1.0)
        speedKmh += (target - speedKmh) * blend
        speedKmh = speedKmh.coerceIn(0.0, MAX_SPEED_KMH)
        if (isDepleted) speedKmh = 0.0

        val hours = dtMs / 3_600_000.0
        val distanceKm = speedKmh * hours
        mileageKm += distanceKm
        tripMeters += distanceKm * 1000.0
        tripSeconds += dtMs / 1000.0

        if (distanceKm > 0.0) {
            batteryPercent -= DRAIN_PERCENT_PER_HOUR * hours
            batteryPercent = batteryPercent.coerceAtLeast(0.0)
        }

        val info = MotorInfo(
            // A little jitter so the display is visibly live, bounded so speed
            // stays inside the physical limits asserted by the tests.
            speed = (speedKmh + jitter(0.15)).coerceIn(0.0, MAX_SPEED_KMH),
            battery = batteryPercent.toInt().coerceIn(0, 100),
            temp = 28.0 + jitter(1.5),
            mileage = mileageKm,
            avgSpeed = averageSpeedKmh(),
            tripSeconds = tripSeconds.toInt(),
            tripMeters = tripMeters.toInt(),
            remainingKm = remainingRangeKm(),
            // The extended fields are produced by *encoding synthetic register
            // payloads and decoding them through the real parsers*, not by
            // computing the values directly. That makes demo mode an integration
            // test of EscTelemetryParser and BmsTelemetryParser: an offset or scale
            // mistake shows up on screen without a scooter.
            errorCode = decodedError.code,
            errorDescription = decodedError.description,
            rideMode = decodedRideMode,
            kersLevel = decodedKers,
            escTemperatureC = decodedEscTemp,
            batteryTemperatureC = bmsTemps?.firstCelsius,
            phaseCurrentA = decodedPhaseCurrentA,
            batteryCurrentA = bmsStatus?.currentAmps,
            packVoltageV = bmsStatus?.voltageVolts,
            packPowerW = bmsStatus?.powerWatts,
            remainingMah = bmsStatus?.remainingMah,
            highestCellV = bmsCells?.highestVolts,
            lowestCellV = bmsCells?.lowestVolts,
            cellSpreadV = bmsCells?.spreadVolts,
            batteryHealthPercent = decodedHealth,
            isCharging = decodedCharging,
        )
        current = info
        return info
    }

    // ---- synthetic registers, decoded through the production parsers ---------

    /** ESC `0x75` ride mode, cycling Normal → ECO → Sport. */
    private val decodedRideMode: EscTelemetryParser.RideMode
        get() = EscTelemetryParser.rideMode(
            byteArrayOf(((phaseOrdinal) % 3).toByte())
        ) ?: EscTelemetryParser.RideMode.NORMAL

    /** ESC `0x7B` KERS level, reusing the ride-mode cycle shape. */
    private val decodedKers: EscTelemetryParser.KersLevel
        get() = EscTelemetryParser.kersLevel(
            byteArrayOf(((phaseOrdinal + 1) % 3).toByte())
        ) ?: EscTelemetryParser.KersLevel.WEAK

    /** ESC `0x1B` error code. Healthy until the pack runs flat, then a real fault. */
    private val decodedError: EscTelemetryParser.ScooterError
        get() {
            // 45 = battery cell deep discharge, a genuinely plausible code for a
            // flat pack. Reporting 0 while depleted would make the error path
            // untestable off-hardware.
            val code = if (isDepleted) 45 else 0
            return EscTelemetryParser.error(le16(code))
                ?: EscTelemetryParser.ScooterError(0, "None - all OK", ScooterErrorCodes.Severity.OK)
        }

    /** ESC `0x3E` frame temperature, tenths of a degree on the wire. */
    private val decodedEscTemp: Double?
        get() = EscTelemetryParser.escTemperatureC(le16(((tempC * 10).toInt())))

    /** ESC `0x53` phase current, hundredths of an amp, negative under braking. */
    private val decodedPhaseCurrentA: Double?
        get() {
            val amps = if (phase == Phase.COAST) -1.5 else speedKmh * 0.35
            return EscTelemetryParser.phaseCurrentA(le16((amps * 100).toInt()))
        }

    /** BMS `0x31` pack status: mAh, percent, current, voltage. */
    private val bmsStatus: BmsTelemetryParser.Status?
        get() {
            val remainingMah = (PACK_CAPACITY_MAH * batteryPercent / 100.0).toInt()
            val amps = if (phase == Phase.COAST) -1.5 else speedKmh * 0.35
            return BmsTelemetryParser.parseStatus(
                le16(
                    remainingMah and 0xFFFF,
                    batteryPercent.toInt().coerceIn(0, 100),
                    (amps * 100).toInt() and 0xFFFF,
                    (PACK_VOLTAGE_V * 100).toInt(),
                )
            )
        }

    /**
     * BMS `0x40` cell voltages, millivolts on the wire.
     *
     * A deliberate imbalance of up to ~40 mV is simulated so the spread field shows
     * a realistic non-zero number instead of a suspiciously perfect pack.
     */
    private val bmsCells: BmsTelemetryParser.Cells?
        get() {
            val baseMv = (PACK_VOLTAGE_V / CELL_COUNT * 1000).toInt()
            val spreadMv = ((batteryPercent / 100.0) * 40).toInt()
            val values = IntArray(CELL_COUNT) { index ->
                if (index == 0) baseMv + spreadMv else baseMv
            }
            val payload = ByteArray(CELL_COUNT * 2)
            values.forEachIndexed { i, mv ->
                payload[i * 2] = (mv and 0xFF).toByte()
                payload[i * 2 + 1] = ((mv shr 8) and 0xFF).toByte()
            }
            return BmsTelemetryParser.parseCells(payload)
        }

    /** BMS `0x35` two temperatures, each biased by +20 °C. */
    private val bmsTemps: BmsTelemetryParser.Temperatures?
        get() = BmsTelemetryParser.parseTemperatures(
            byteArrayOf(
                (tempC + 20.0).toInt().toByte(),
                (tempC + 22.0).toInt().toByte(),
            )
        )

    /** BMS `0x3B` state of health: degrades very slowly with charge cycles. */
    private val decodedHealth: Int
        get() = (99 - (batteryPercent / 20.0)).toInt().coerceIn(0, 100)

    /** BMS `0x30` charging flag: charging whenever the pack is not being ridden. */
    private val decodedCharging: Boolean?
        get() = BmsTelemetryParser.isCharging(
            byteArrayOf(if (speedKmh < 0.5 && !isDepleted) 0x40 else 0x00)
        )

    /** Ordinal of the current drive phase, used to rotate mode and KERS. */
    private val phaseOrdinal: Int get() = phase.ordinal

    /** Simulated pack temperature, tracked so `0x3E` and `0x35` agree. */
    private val tempC: Double get() = 28.0 + (speedKmh / MAX_SPEED_KMH) * 6.0

    private fun le16(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Average speed over the whole simulated trip, km/h. */
    private fun averageSpeedKmh(): Double {
        val hours = tripSeconds / 3600.0
        if (hours <= 0.0) return 0.0
        return (tripMeters / 1000.0) / hours
    }

    /**
     * Remaining range from the state of charge.
     *
     * Linear on purpose: a real BMS is not linear, but a demo that invented a
     * discharge curve would make the HUD look more accurate than the protocol
     * actually is.
     */
    private fun remainingRangeKm(): Double =
        PACK_RANGE_AT_FULL_KM * (batteryPercent / 100.0)

    private fun jitter(amplitude: Double): Double =
        (random.nextDouble() * 2.0 - 1.0) * amplitude

    private fun advancePhase(dtMs: Long) {
        phaseElapsedMs += dtMs
        val duration = when (phase) {
            Phase.RAMP_UP -> 6_000L
            Phase.CRUISE -> 18_000L
            Phase.COAST -> 7_000L
            Phase.STOPPED -> 4_000L
        }
        if (phaseElapsedMs >= duration) {
            phaseElapsedMs = 0L
            phase = when (phase) {
                Phase.RAMP_UP -> Phase.CRUISE
                Phase.CRUISE -> Phase.COAST
                Phase.COAST -> Phase.STOPPED
                Phase.STOPPED -> Phase.RAMP_UP
            }
        }
    }

    private fun targetSpeedForPhase(): Double = when (phase) {
        Phase.RAMP_UP -> 18.0
        Phase.CRUISE -> 22.0
        Phase.COAST -> 8.0
        Phase.STOPPED -> 0.0
    }

    /**
     * Resets the ride to its starting state.
     *
     * Restores the constructor's battery and mileage rather than hard-coded
     * defaults, and keeps the seed, so restarting a demo replays the same ride —
     * which is what makes a reported demo bug reproducible.
     */
    fun reset() {
        phase = Phase.RAMP_UP
        phaseElapsedMs = 0L
        speedKmh = 0.0
        batteryPercent = initialBattery
        mileageKm = initialMileage
        tripMeters = 0.0
        tripSeconds = 0.0
        current = null
        // Rewind the jitter sequence too, otherwise the replay diverges from the
        // first run even though the seed is unchanged.
        random = Random(seed)
    }

    /** Snapshot for assertions and for the diagnostics screen. */
    data class Snapshot(
        val speedKmh: Double,
        val batteryPercent: Int,
        val mileageKm: Double,
    )

    /** Current internal state, for tests and diagnostics. */
    fun snapshot(): Snapshot = Snapshot(
        speedKmh = speedKmh,
        batteryPercent = batteryPercent.toInt(),
        mileageKm = mileageKm,
    )

    /** True when the source has produced at least one sample. */
    fun hasProduced(): Boolean = current != null
}
