package com.m365hud.glass

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
            
            // Validate CRC
            val calculatedCrc = calculateCrc16(bytes.copyOfRange(0, 18))
            val isValid = calculatedCrc == receivedCrc
            
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
                isValid = isValid
            )
        }
        
        /**
         * CRC-16-CCITT calculation
         */
        private fun calculateCrc16(data: ByteArray): Int {
            var crc = 0xFFFF
            for (byte in data) {
                crc = crc xor ((byte.toInt() and 0xFF) shl 8)
                for (i in 0 until 8) {
                    crc = if ((crc and 0x8000) != 0) {
                        (crc shl 1) xor 0x1021
                    } else {
                        crc shl 1
                    }
                    crc = crc and 0xFFFF
                }
            }
            return crc
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
