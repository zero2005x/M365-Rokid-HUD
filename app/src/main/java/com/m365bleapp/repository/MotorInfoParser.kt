package com.m365bleapp.repository

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the payload supplied to the existing 0xB0 handler.
 *
 * ## The payload is the ESC's `B0..BB` register block
 *
 * One 32-byte read of register `0xB0` returns the whole consecutive block: two
 * bytes per register from B0 (error code) at offset 0 through BB (frame
 * temperature) at offset 22, with B7 (odometer) occupying four.
 *
 * | offset | reg | meaning            | scale         |
 * |-------:|-----|--------------------|---------------|
 * |      8 | B4  | battery            | percent       |
 * |     10 | B5  | speed              | m/h (÷1000 → km/h) |
 * |     12 | B6  | average speed      | m/h (÷1000 → km/h) |
 * |     14 | B7  | total mileage, m   | ÷1000 → km    |
 * |     22 | BB  | frame temperature  | ÷10 → °C      |
 *
 * ## Verified on hardware — 2026-09-20
 *
 * Captured from a Xiaomi M365 (`MIScooter8964`) with the rear wheel spun off the
 * ground. The offsets above are confirmed: reading the block this way yields a
 * battery, a temperature and an odometer that are all simultaneously plausible,
 * which a shifted slice could not.
 *
 * The scales were checked against an independent reference rather than assumed:
 * over 43 two-second windows the decoded speed matched the odometer's rate of
 * change (`3.6 × Δmetres / Δseconds`) with a median ratio of **1.023**, i.e.
 * inside the odometer's own 1 m quantisation. See `artifacts/SPEED-FIELD-ANALYSIS.md`.
 */
internal object MotorInfoParser {

    /**
     * B5 values at or above this are a *negative* signed speed (wheel creeping
     * backwards, or the ESC's "no estimate" value just below `0xFFFF`), not a
     * speed.
     *
     * Hardware captures show the register is really an i16 in m/h whose
     * small negative values (down to about -5 km/h: `0xEA76`, and 2026-10-07
     * `0xF5xx`..`0xFEBx` = 62.9..65.2 read unsigned, with the odometer barely
     * moving) are far more common than a genuine reading above 49 km/h, which no
     * M365 reaches. Cutting at `0xC000` (49.152 km/h unsigned / -16.384 signed)
     * leaves every real forward speed untouched.
     */
    private const val NEGATIVE_SPEED_RAW = 0xC000

    fun parse(data: ByteArray, existing: MotorInfo? = null): MotorInfo? {
        if (data.size < 22) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val battery = bb.getShort(8).toInt() and 0xFFFF
        val alternateBattery = data[7].toUByte().toInt()
        // Temperature stays signed: a frame reading below zero is meaningful in
        // cold weather, so it must not be masked the way speed is.
        val temperature = if (data.size >= 24) bb.getShort(22).toInt() else 0
        return MotorInfo(
            speed = decodeSpeed(bb),
            avgSpeed = ((bb.getShort(12).toInt() and 0xFFFF).toFloat() / 1000.0f).toDouble(),
            mileage = bb.getInt(14) / 1000.0,
            battery = if (battery in 1..100) battery else alternateBattery,
            temp = (temperature.toFloat() / 10.0f).toDouble(),
            tripSeconds = existing?.tripSeconds ?: 0,
            tripMeters = existing?.tripMeters ?: 0,
            remainingKm = existing?.remainingKm ?: 0.0
        )
    }

    /**
     * Decodes B5, which is an **unsigned** speed in m/h.
     *
     * It has to be read unsigned. As a `Short`, every genuine reading above
     * 32.767 km/h comes back negative — which is how a downhill or an over-speed
     * moment ends up rendering as "-5 km/h" on the HUD. Note that `avgSpeed` a
     * few lines up always masked this; speed did not.
     *
     * Masking alone would then turn the ESC's no-estimate sentinel into
     * "65 km/h", so the sentinel is folded to zero here as well.
     *
     * Known limit: an isolated mid-range spike is *not* filtered. One was
     * observed at `0xEA76` (60.022 km/h) while the wheel was barely turning;
     * nothing in the register distinguishes it from a genuine reading on a
     * modified scooter, so clamping it away would be a guess. Only the
     * unambiguous sentinel is rejected.
     */
    private fun decodeSpeed(bb: ByteBuffer): Double {
        val raw = bb.getShort(10).toInt() and 0xFFFF
        return if (raw >= NEGATIVE_SPEED_RAW) 0.0 else raw / 1000.0
    }
}
