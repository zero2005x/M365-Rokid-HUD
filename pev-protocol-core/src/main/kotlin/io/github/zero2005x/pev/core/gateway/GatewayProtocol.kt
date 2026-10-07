package io.github.zero2005x.pev.core.gateway

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Common gateway communication protocol shared across BLE GATT and Wi-Fi TCP.
 * Glasses display telemetry and alerts only; vehicle settings are never sent to or from glasses.
 */
object GatewayProtocol {
    const val V1_FRAME_SIZE = 20
    const val V1_CRC_COVERED_BYTES = 18

    /** V2 extended header magic "PEVG" (0x50, 0x45, 0x56, 0x47). */
    const val V2_MAGIC = 0x50455647
    const val V2_HEADER_SIZE = 12 // Magic(4) + Version(1) + VehicleType(1) + ConnState(1) + Reserved(1) + Sequence(4)
    const val V2_MIN_FRAME_SIZE = 36 // Header(12) + ValidityMask(4) + Metrics(16) + Alerts(2) + CRC(2)

    const val VERSION_1_LEGACY = 1
    const val VERSION_2_EXTENDED = 2

    // Connection states
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_READY = 2
    const val STATE_ERROR = 3
    const val STATE_UPGRADE_REQUIRED = 0xFE // Sent to clients when vehicle/protocol cannot be represented safely

    // Vehicle families
    const val VEHICLE_UNKNOWN = 0
    const val VEHICLE_XIAOMI_M365 = 1
    const val VEHICLE_BEGODE_EUC = 2
    const val VEHICLE_NINEBOT_G30_ESX = 3
    const val VEHICLE_ZYDTECH = 4
    const val VEHICLE_INMOTION = 5
    const val VEHICLE_KINGSONG = 6
    const val VEHICLE_VETERAN = 7

    // V2 Validity mask bitflags (1 = valid physical measurement, 0 = invalid/unknown/stale)
    const val FIELD_SPEED = 1 shl 0
    const val FIELD_BATTERY = 1 shl 1
    const val FIELD_TEMPERATURE = 1 shl 2
    const val FIELD_TOTAL_DISTANCE = 1 shl 3
    const val FIELD_AVG_SPEED = 1 shl 4
    const val FIELD_REMAINING_RANGE = 1 shl 5
    const val FIELD_TRIP_DISTANCE = 1 shl 6
    const val FIELD_TRIP_DURATION = 1 shl 7
    const val FIELD_VOLTAGE = 1 shl 8
    const val FIELD_CURRENT = 1 shl 9

    // V2 Alert flags
    const val ALERT_NONE = 0
    const val ALERT_SPEED_WARNING = 1 shl 0
    const val ALERT_BATTERY_LOW = 1 shl 1
    const val ALERT_TEMPERATURE_HIGH = 1 shl 2
    const val ALERT_BMS_FAULT = 1 shl 3
    const val ALERT_TILTBACK_ENGAGED = 1 shl 4
    const val ALERT_ESC_FAULT = 1 shl 5

    /** CRC-16/MODBUS (initial 0xFFFF, polynomial 0xA001). */
    fun calculateCrc16(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        val end = (offset + length).coerceAtMost(data.size)
        for (i in offset until end) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}

/** Legacy 20-byte telemetry frame compatible with original M365 HUD glasses. */
data class GatewayV1Frame(
    val speedKmh: Float,
    val batteryPercent: Int,
    val temperatureC: Float,
    val totalDistanceMeters: Long,
    val avgSpeedKmh: Float,
    val remainingRangeKm: Float,
    val connectionState: Int,
    val tripMeters: Int,
    val tripSeconds: Int,
    val isValid: Boolean = true,
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(GatewayProtocol.V1_FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort((speedKmh * 100f).toInt().toShort())
        buffer.put(batteryPercent.coerceIn(0, 100).toByte())
        buffer.putShort((temperatureC * 10f).toInt().toShort())
        buffer.putInt(totalDistanceMeters.toInt())
        buffer.putShort((avgSpeedKmh * 100f).toInt().toShort())
        buffer.putShort((remainingRangeKm * 10f).toInt().toShort())
        buffer.put(connectionState.toByte())
        buffer.putShort(tripMeters.coerceIn(0, 0xFFFF).toShort())
        buffer.putShort(tripSeconds.coerceIn(0, 0xFFFF).toShort())
        val crc = GatewayProtocol.calculateCrc16(buffer.array(), 0, GatewayProtocol.V1_CRC_COVERED_BYTES)
        buffer.putShort(crc.toShort())
        return buffer.array()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): GatewayV1Frame? {
            if (bytes.size < GatewayProtocol.V1_FRAME_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val expectedCrc = buffer.getShort(18).toInt() and 0xFFFF
            val actualCrc = GatewayProtocol.calculateCrc16(bytes, 0, GatewayProtocol.V1_CRC_COVERED_BYTES)
            if (expectedCrc != actualCrc) return null

            val speedRaw = buffer.getShort(0).toInt()
            val batteryRaw = bytes[2].toInt() and 0xFF
            val tempRaw = buffer.getShort(3).toInt()
            val totalDistRaw = buffer.getInt(5).toLong() and 0xFFFFFFFFL
            val avgSpeedRaw = buffer.getShort(9).toInt() and 0xFFFF
            val rangeRaw = buffer.getShort(11).toInt() and 0xFFFF
            val stateRaw = bytes[13].toInt() and 0xFF
            val tripDistRaw = buffer.getShort(14).toInt() and 0xFFFF
            val tripSecRaw = buffer.getShort(16).toInt() and 0xFFFF

            return GatewayV1Frame(
                speedKmh = speedRaw / 100f,
                batteryPercent = batteryRaw,
                temperatureC = tempRaw / 10f,
                totalDistanceMeters = totalDistRaw,
                avgSpeedKmh = avgSpeedRaw / 100f,
                remainingRangeKm = rangeRaw / 10f,
                connectionState = stateRaw,
                tripMeters = tripDistRaw,
                tripSeconds = tripSecRaw,
                isValid = true,
            )
        }
    }
}

/**
 * Extended V2 telemetry frame supporting multi-vehicle architectures,
 * explicit field validity, wide trip counters, and alert codes.
 */
data class GatewayV2Frame(
    val vehicleType: Int,
    val connectionState: Int,
    val sequence: Long,
    val validityMask: Int,
    val speedKmh: Float? = null,
    val batteryPercent: Int? = null,
    val temperatureC: Float? = null,
    val totalDistanceMeters: Long? = null,
    val avgSpeedKmh: Float? = null,
    val remainingRangeKm: Float? = null,
    val tripMeters: Long? = null,
    val tripSeconds: Long? = null,
    val voltageVolts: Float? = null,
    val currentAmperes: Float? = null,
    val alertFlags: Int = GatewayProtocol.ALERT_NONE,
) {
    fun isFieldValid(fieldFlag: Int): Boolean = (validityMask and fieldFlag) != 0

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(GatewayProtocol.V2_MAGIC)
        buffer.put(GatewayProtocol.VERSION_2_EXTENDED.toByte())
        buffer.put(vehicleType.toByte())
        buffer.put(connectionState.toByte())
        buffer.put(0.toByte()) // Reserved
        buffer.putInt((sequence and 0xFFFFFFFFL).toInt())
        buffer.putInt(validityMask)

        // Metrics (using sentinels or 0 when invalid, guarded by validityMask)
        buffer.putShort(((speedKmh ?: 0f) * 100f).toInt().toShort())
        buffer.put((batteryPercent ?: 0).coerceIn(0, 100).toByte())
        buffer.putShort(((temperatureC ?: 0f) * 10f).toInt().toShort())
        buffer.putInt(((totalDistanceMeters ?: 0L) and 0xFFFFFFFFL).toInt())
        buffer.putShort(((avgSpeedKmh ?: 0f) * 100f).toInt().toShort())
        buffer.putShort(((remainingRangeKm ?: 0f) * 10f).toInt().toShort())
        buffer.putInt(((tripMeters ?: 0L) and 0xFFFFFFFFL).toInt())
        buffer.putInt(((tripSeconds ?: 0L) and 0xFFFFFFFFL).toInt())
        buffer.putShort(((voltageVolts ?: 0f) * 100f).toInt().toShort())
        buffer.putShort(((currentAmperes ?: 0f) * 100f).toInt().toShort())
        buffer.putShort(alertFlags.toShort())

        val covered = buffer.position()
        val crc = GatewayProtocol.calculateCrc16(buffer.array(), 0, covered)
        val out = ByteArray(covered + 2)
        System.arraycopy(buffer.array(), 0, out, 0, covered)
        out[covered] = (crc and 0xFF).toByte()
        out[covered + 1] = ((crc ushr 8) and 0xFF).toByte()
        return out
    }

    companion object {
        fun fromBytes(bytes: ByteArray): GatewayV2Frame? {
            if (bytes.size < GatewayProtocol.V2_MIN_FRAME_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.getInt(0)
            if (magic != GatewayProtocol.V2_MAGIC) return null
            val version = bytes[4].toInt() and 0xFF
            if (version != GatewayProtocol.VERSION_2_EXTENDED) return null

            val crcOffset = bytes.size - 2
            val expectedCrc = ((bytes[crcOffset].toInt() and 0xFF) or
                ((bytes[crcOffset + 1].toInt() and 0xFF) shl 8))
            val actualCrc = GatewayProtocol.calculateCrc16(bytes, 0, crcOffset)
            if (expectedCrc != actualCrc) return null

            val vehicleType = bytes[5].toInt() and 0xFF
            val connectionState = bytes[6].toInt() and 0xFF
            val sequence = buffer.getInt(8).toLong() and 0xFFFFFFFFL
            val validityMask = buffer.getInt(12)

            val speedRaw = buffer.getShort(16).toInt()
            val batteryRaw = bytes[18].toInt() and 0xFF
            val tempRaw = buffer.getShort(19).toInt()
            val totalDistRaw = buffer.getInt(21).toLong() and 0xFFFFFFFFL
            val avgSpeedRaw = buffer.getShort(25).toInt() and 0xFFFF
            val rangeRaw = buffer.getShort(27).toInt() and 0xFFFF
            val tripDistRaw = buffer.getInt(29).toLong() and 0xFFFFFFFFL
            val tripSecRaw = buffer.getInt(33).toLong() and 0xFFFFFFFFL
            val voltageRaw = buffer.getShort(37).toInt()
            val currentRaw = buffer.getShort(39).toInt()
            val alertsRaw = buffer.getShort(41).toInt() and 0xFFFF

            val speed = if ((validityMask and GatewayProtocol.FIELD_SPEED) != 0) speedRaw / 100f else null
            val battery = if ((validityMask and GatewayProtocol.FIELD_BATTERY) != 0) batteryRaw else null
            val temp = if ((validityMask and GatewayProtocol.FIELD_TEMPERATURE) != 0) tempRaw / 10f else null
            val totalDist = if ((validityMask and GatewayProtocol.FIELD_TOTAL_DISTANCE) != 0) totalDistRaw else null
            val avgSpeed = if ((validityMask and GatewayProtocol.FIELD_AVG_SPEED) != 0) avgSpeedRaw / 100f else null
            val range = if ((validityMask and GatewayProtocol.FIELD_REMAINING_RANGE) != 0) rangeRaw / 10f else null
            val tripDist = if ((validityMask and GatewayProtocol.FIELD_TRIP_DISTANCE) != 0) tripDistRaw else null
            val tripSec = if ((validityMask and GatewayProtocol.FIELD_TRIP_DURATION) != 0) tripSecRaw else null
            val voltage = if ((validityMask and GatewayProtocol.FIELD_VOLTAGE) != 0) voltageRaw / 100f else null
            val current = if ((validityMask and GatewayProtocol.FIELD_CURRENT) != 0) currentRaw / 100f else null

            return GatewayV2Frame(
                vehicleType = vehicleType,
                connectionState = connectionState,
                sequence = sequence,
                validityMask = validityMask,
                speedKmh = speed,
                batteryPercent = battery,
                temperatureC = temp,
                totalDistanceMeters = totalDist,
                avgSpeedKmh = avgSpeed,
                remainingRangeKm = range,
                tripMeters = tripDist,
                tripSeconds = tripSec,
                voltageVolts = voltage,
                currentAmperes = current,
                alertFlags = alertsRaw,
            )
        }
    }
}
