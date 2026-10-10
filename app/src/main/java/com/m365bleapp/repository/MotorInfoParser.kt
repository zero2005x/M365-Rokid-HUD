package com.m365bleapp.repository

import io.github.zero2005x.pev.core.codec.xiaomi.XiaomiMotorInfoDecoder
import io.github.zero2005x.pev.core.telemetry.FieldId
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the payload supplied to the existing 0xB0 handler.
 *
 * Structural and physical telemetry decoding is delegated to [XiaomiMotorInfoDecoder]
 * from `:pev-protocol-core`. Signed speed is retained for reverse movement on
 * both the phone and glasses; raw negative readings are also kept in diagnostics.
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

    fun parse(data: ByteArray, existing: MotorInfo? = null): MotorInfo? {
        if (data.size < 22) return null

        val snapshot = XiaomiMotorInfoDecoder.decode(data, System.currentTimeMillis())
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // B5 has no separate direction or unavailable flag in this block.
        // Do not discard all negative values: that hides genuine reverse movement.
        val decodedSpeed = snapshot[FieldId.SPEED_KMH].value ?: 0.0

        val totalDistM = snapshot[FieldId.TOTAL_DISTANCE_M].value ?: 0.0
        val mileageKm = totalDistM / 1000.0

        val soc = snapshot[FieldId.SOC_PERCENT].value?.toInt()
        val alternateBattery = data[7].toUByte().toInt()
        val battery = if (soc != null && soc in 1..100) soc else alternateBattery

        // Frame temperature from core; default to 0.0 if not provided or size < 24.
        val temp = snapshot[FieldId.TEMP_FRAME].value ?: 0.0

        // Average speed is not modelled as a physical core field; read unsigned from offset 12.
        val avgSpeed = ((bb.getShort(12).toInt() and 0xFFFF).toFloat() / 1000.0f).toDouble()
        val errorCode = bb.getShort(0).toInt() and 0xFFFF

        return (existing ?: MotorInfo(0.0, 0, 0.0, 0.0)).copy(
            speed = decodedSpeed,
            avgSpeed = avgSpeed,
            mileage = mileageKm,
            battery = battery,
            temp = temp,
            tripSeconds = existing?.tripSeconds ?: 0,
            tripMeters = existing?.tripMeters ?: 0,
            remainingKm = existing?.remainingKm ?: 0.0,
            errorCode = errorCode,
            errorDescription = com.m365bleapp.protocol.ScooterErrorCodes.describe(errorCode),
        )
    }
}
