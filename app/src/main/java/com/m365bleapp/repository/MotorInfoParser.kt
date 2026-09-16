package com.m365bleapp.repository

/** Maps the profile decoder result into UI state, preserving separately polled trip data. */
internal object MotorInfoParser {
    fun parse(data: ByteArray, existing: MotorInfo? = null, decode: (ByteArray) -> DoubleArray): MotorInfo? {
        val values = decode(data)
        if (values.size != 7) return null
        return MotorInfo(
            speed = values[1], avgSpeed = values[2], mileage = values[3] / 1000.0,
            battery = values[0].toInt(), temp = values[6],
            tripSeconds = existing?.tripSeconds ?: 0,
            tripMeters = existing?.tripMeters ?: 0,
            remainingKm = existing?.remainingKm ?: 0.0
        )
    }
}
