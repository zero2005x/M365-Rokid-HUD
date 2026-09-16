package com.m365hud.glass

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Which telemetry fields the HUD renders.
 *
 * This is a MIRROR of `DisplayField` in the phone app
 * (`app/.../gateway/M365HudGattProfile.kt`). The bit assignments are a wire
 * contract shared between the two APKs — **never renumber an existing bit**.
 * A field that is retired keeps its bit reserved so an older glasses build and
 * a newer phone can never disagree about what a bit means.
 *
 * Bits this build does not recognise are ignored when rendering. That is what
 * makes a future phone-side addition backward compatible: the phone sets a bit
 * we do not know, and we simply do not draw it.
 */
object DisplayField {
    /** Current speed. The hero element; almost always on. */
    const val SPEED = 1 shl 0

    /** Scooter battery percentage. */
    const val SCOOTER_BATTERY = 1 shl 1

    /** Phone battery percentage. */
    const val PHONE_BATTERY = 1 shl 2

    /** Our own battery percentage. */
    const val GLASSES_BATTERY = 1 shl 3

    /** Clock (HH:mm). */
    const val TIME = 1 shl 4

    /** BLE link quality indicator (the signal / stale icon). */
    const val SIGNAL_QUALITY = 1 shl 5

    /** Controller / frame temperature. */
    const val TEMPERATURE = 1 shl 6

    /** Total odometer. */
    const val TOTAL_MILEAGE = 1 shl 7

    /** Remaining range estimate. */
    const val REMAINING_RANGE = 1 shl 8

    /** Average speed. */
    const val AVG_SPEED = 1 shl 9

    /** Trip distance. */
    const val TRIP_DISTANCE = 1 shl 10

    /** Trip time. */
    const val TRIP_TIME = 1 shl 11

    /**
     * The layout used when no preference has ever been received.
     *
     * Matches the hard-coded layout this feature replaced (time, phone battery,
     * glasses battery, speed, scooter battery, signal) so that pairing an
     * updated glasses with an older phone — or simply connecting before the
     * first preference arrives — produces no visible change.
     */
    const val DEFAULT_MASK = SPEED or SCOOTER_BATTERY or PHONE_BATTERY or
        GLASSES_BATTERY or TIME or SIGNAL_QUALITY
}

/**
 * Parsed display preferences pushed by the phone.
 *
 * Wire format (7 bytes, little-endian) — must match
 * `M365HudGattProfile.DISPLAY_PREFS_*` on the phone:
 *
 *   Byte 0:    Version (u8)
 *   Byte 1-4:  Field bitmask (u32 LE)
 *   Byte 5:    Text scale percent (u8), 100 = normal
 *   Byte 6:    Reserved (u8), must be 0
 */
data class DisplayPrefs(
    val mask: Int = DisplayField.DEFAULT_MASK,
    val textScalePercent: Int = 100
) {
    /** True when the rider wants this field drawn. */
    fun shows(field: Int): Boolean = (mask and field) != 0

    companion object {
        /** Version this build understands. */
        const val VERSION = 1

        /** Payload size this build understands. */
        const val SIZE = 7

        /** Accepted text-scale range; values outside are clamped, not rejected. */
        const val MIN_SCALE = 80
        const val MAX_SCALE = 140

        /**
         * Defaults, used when the phone app predates this feature.
         *
         * Note the deliberate ordering: an unrecognised version or a short
         * frame returns defaults rather than throwing. The HUD must never go
         * blank because of a protocol mismatch — a blank windshield is worse
         * than a wrong one.
         */
        fun fromBytes(bytes: ByteArray): DisplayPrefs {
            if (bytes.size < SIZE) {
                return DisplayPrefs()
            }
            if ((bytes[0].toInt() and 0xFF) != VERSION) {
                return DisplayPrefs()
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val rawMask = buffer.getInt(1)

            // A zero mask is indistinguishable from "no preference known" (an
            // all-zero flash page, a truncated write). Showing nothing is never
            // what a rider wants, so fall back to the default layout.
            val mask = if (rawMask == 0) DisplayField.DEFAULT_MASK else rawMask

            val rawScale = bytes[5].toInt() and 0xFF
            val scale = rawScale.coerceIn(MIN_SCALE, MAX_SCALE)

            return DisplayPrefs(mask = mask, textScalePercent = scale)
        }
    }
}

/**
 * Data class holding parsed telemetry from the phone Gateway
 */
data class TelemetryData(
    val speedKmh: Float = 0f,           // Current speed in km/h
    val scooterBattery: Int = 0,        // Scooter battery percentage (0-100)
    val temperatureC: Float = 0f,        // Temperature in Celsius
    val totalMileageM: Long = 0,         // Total mileage in meters
    val avgSpeedKmh: Float = 0f,         // Average speed in km/h
    val remainingRangeKm: Float = 0f,    // Remaining range in km
    val connectionState: Int = 0,        // 0=Disconnected, 1=Connecting, 2=Ready
    val tripMeters: Int = 0,             // Trip distance in meters
    val tripSeconds: Int = 0,            // Trip time in seconds
    val isValid: Boolean = false         // CRC validation result
) {
    companion object {
        /**
         * Parse telemetry bytes from the Gateway characteristic
         * 
         * Format (20 bytes):
         * Byte 0-1:   Speed (i16 LE, ×100)
         * Byte 2:     Scooter Battery (u8)
         * Byte 3-4:   Temperature (i16 LE, ×10)
         * Byte 5-8:   Total Mileage (u32 LE, meters)
         * Byte 9-10:  Avg Speed (u16 LE, ×100)
         * Byte 11-12: Remaining Range (u16 LE, ×10 km)
         * Byte 13:    Connection State
         * Byte 14-15: Trip Meters (u16 LE)
         * Byte 16-17: Trip Seconds (u16 LE)
         * Byte 18-19: CRC16
         */
        fun fromBytes(bytes: ByteArray): TelemetryData {
            if (bytes.size < 20) {
                return TelemetryData(isValid = false)
            }
            
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            
            // Parse fields
            val speedRaw = buffer.getShort(0).toInt()
            val batteryRaw = bytes[2].toInt() and 0xFF
            val tempRaw = buffer.getShort(3).toInt()
            val mileageRaw = buffer.getInt(5).toLong() and 0xFFFFFFFFL
            val avgSpeedRaw = buffer.getShort(9).toInt() and 0xFFFF
            val rangeRaw = buffer.getShort(11).toInt() and 0xFFFF
            val connectionState = bytes[13].toInt() and 0xFF
            val tripMeters = buffer.getShort(14).toInt() and 0xFFFF
            val tripSeconds = buffer.getShort(16).toInt() and 0xFFFF
            val receivedCrc = buffer.getShort(18).toInt() and 0xFFFF
            
            // Validate CRC over bytes 0..17 (no copy: this runs on the hot BLE
            // notification path, and copyOfRange allocated on every frame).
            val calculatedCrc = calculateCrc16(bytes, 18)
            if (calculatedCrc != receivedCrc) {
                // Return a zeroed record so a corrupt frame cannot leak bogus
                // speed/battery values to a caller that forgets to check isValid.
                return TelemetryData(isValid = false)
            }

            return TelemetryData(
                speedKmh = speedRaw / 100f,
                scooterBattery = batteryRaw,
                temperatureC = tempRaw / 10f,
                totalMileageM = mileageRaw,
                avgSpeedKmh = avgSpeedRaw / 100f,
                remainingRangeKm = rangeRaw / 10f,
                connectionState = connectionState,
                tripMeters = tripMeters,
                tripSeconds = tripSeconds,
                isValid = true
            )
        }
        
        /**
         * CRC-16/MODBUS, matching the gateway's `M365HudGattProfile.CRC16_SPEC`.
         *
         * Parameters: width 16, polynomial 0xA001 (reflected 0x8005),
         * init 0xFFFF, input and output reflected, no final xor. The result is
         * carried in the frame as a little-endian u16.
         *
         * This used to be CRC-16/CCITT-FALSE (poly 0x1021, non-reflected),
         * which never matched the gateway's checksum, so `isValid` was false
         * for every packet and all telemetry was silently discarded.
         *
         * @param length number of leading bytes covered (bytes 0..length-1)
         */
        private fun calculateCrc16(data: ByteArray, length: Int = data.size): Int {
            var crc = 0xFFFF
            for (i in 0 until length) {
                crc = crc xor (data[i].toInt() and 0xFF)
                for (j in 0 until 8) {
                    crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
                }
            }
            return crc and 0xFFFF
        }
    }
    
    /**
     * Format trip time as MM:SS or HH:MM:SS
     */
    fun formatTripTime(): String {
        val hours = tripSeconds / 3600
        val minutes = (tripSeconds % 3600) / 60
        val seconds = tripSeconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
    
    /**
     * Format mileage as km with 1 decimal
     */
    fun formatTripDistance(): String {
        val km = tripMeters / 1000f
        return String.format("%.1f km", km)
    }
    
    /**
     * Format total mileage
     */
    fun formatTotalMileage(): String {
        val km = totalMileageM / 1000f
        return String.format("%.1f km", km)
    }
}

/**
 * Data class holding parsed time data from the phone Gateway
 */
data class TimeData(
    val hour: Int = 0,
    val minute: Int = 0,
    val second: Int = 0,
    val phoneBattery: Int = 0
) {
    companion object {
        /**
         * Parse time bytes from the Gateway characteristic
         * 
         * Format (8 bytes):
         * Byte 0: Hour (0-23)
         * Byte 1: Minute (0-59)
         * Byte 2: Second (0-59)
         * Byte 3: Phone Battery (0-100)
         * Byte 4-7: Reserved
         */
        fun fromBytes(bytes: ByteArray): TimeData {
            if (bytes.size < 4) {
                return TimeData()
            }
            
            return TimeData(
                hour = bytes[0].toInt() and 0xFF,
                minute = bytes[1].toInt() and 0xFF,
                second = bytes[2].toInt() and 0xFF,
                phoneBattery = bytes[3].toInt() and 0xFF
            )
        }
    }
    
    /**
     * Format time as HH:MM
     */
    fun formatTime(): String {
        return String.format("%02d:%02d", hour, minute)
    }
    
    /**
     * Format time as HH:MM:SS
     */
    fun formatTimeWithSeconds(): String {
        return String.format("%02d:%02d:%02d", hour, minute, second)
    }
}
