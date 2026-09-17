package com.m365bleapp.repository

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the payload supplied to the existing 0xB0 handler.
 *
 * These offsets and fallback rules preserve shipped behavior, not a verified
 * register map. The four-byte discrepancy needs a hardware capture before any
 * correction; see doc/MODEL_SUPPORT.md.
 */
internal object MotorInfoParser {
    fun parse(data: ByteArray, existing: MotorInfo? = null): MotorInfo? {
        if (data.size < 22) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val battery = bb.getShort(8).toInt() and 0xFFFF
        val alternateBattery = data[7].toUByte().toInt()
        val temperature = if (data.size >= 24) bb.getShort(22).toInt() else 0
        return MotorInfo(
            speed = (bb.getShort(10).toFloat() / 1000.0f).toDouble(),
            avgSpeed = ((bb.getShort(12).toInt() and 0xFFFF).toFloat() / 1000.0f).toDouble(),
            mileage = bb.getInt(14) / 1000.0,
            battery = if (battery in 1..100) battery else alternateBattery,
            temp = (temperature.toFloat() / 10.0f).toDouble(),
            tripSeconds = existing?.tripSeconds ?: 0,
            tripMeters = existing?.tripMeters ?: 0,
            remainingKm = existing?.remainingKm ?: 0.0
        )
    }
}
