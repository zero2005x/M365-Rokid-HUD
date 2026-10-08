package io.github.zero2005x.pev.core.gateway

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire constants used by both BLE and Wi-Fi; transport framing is outside this module. */
object GatewayProtocol {
    const val V1_FRAME_SIZE = 20
    const val V1_CRC_COVERED_BYTES = 18
    const val VERSION_1_LEGACY = 1
    const val VERSION_2_EXTENDED = 2
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_READY = 2
    const val STATE_ERROR = 3
    const val STATE_UPGRADE_REQUIRED = 0xFE
    const val VEHICLE_UNKNOWN = 0
    const val VEHICLE_XIAOMI_M365 = 1
    const val VEHICLE_BEGODE_EUC = 2
    const val VEHICLE_NINEBOT_G30_ESX = 3
    const val VEHICLE_ZYDTECH = 4
    const val VEHICLE_INMOTION = 5
    const val VEHICLE_KINGSONG = 6
    const val VEHICLE_VETERAN = 7
    const val V2_HEADER_SIZE = 24
    const val V2_MIN_FRAME_SIZE = V2_HEADER_SIZE + 2
    const val V2_MAX_FRAME_SIZE = 4096
    const val MAX_SOURCE_BYTES = 96
    const val DEFAULT_MAX_AGE_MS = 2_000L
    const val MAX_EXACT_COUNTER = 9_007_199_254_740_991.0

    /** CRC-16/MODBUS, init FFFF, reflected polynomial A001, no final xor. */
    fun calculateCrc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset in 0..data.size && length >= 0 && length <= data.size - offset) { "CRC range outside payload" }
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    internal fun validState(state: Int): Boolean = state in STATE_DISCONNECTED..STATE_ERROR || state == STATE_UPGRADE_REQUIRED
}

/**
 * Exact legacy producer contract. Double multiplication precedes truncation to Int;
 * narrowing wraps as the existing BLE and Wi-Fi producers do, including trip u16.
 * This raw compatibility type cannot express unknown fields. Use encodeV1 for safe downgrade.
 * Decoding preserves the legacy glasses Float view. Do not forward/re-encode that view:
 * narrowing and floating-point truncation mean decode-to-encode is not a byte round-trip.
 */
data class GatewayV1Frame(
    val speedKmh: Double,
    val batteryPercent: Int,
    val temperatureC: Double,
    val totalDistanceMeters: Long,
    val avgSpeedKmh: Double,
    val remainingRangeKm: Double,
    val connectionState: Int,
    val tripMeters: Int,
    val tripSeconds: Int,
    val isValid: Boolean = true,
) {
    fun toBytes(): ByteArray {
        val b = ByteBuffer.allocate(GatewayProtocol.V1_FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort((speedKmh * 100).toInt().toShort())
        b.put(batteryPercent.coerceIn(0, 100).toByte())
        b.putShort((temperatureC * 10).toInt().toShort())
        b.putInt(totalDistanceMeters.toInt())
        b.putShort((avgSpeedKmh * 100).toInt().toShort())
        b.putShort((remainingRangeKm * 10).toInt().toShort())
        b.put(connectionState.toByte())
        b.putShort(tripMeters.toShort())
        b.putShort(tripSeconds.toShort())
        b.putShort(GatewayProtocol.calculateCrc16(b.array(), 0, 18).toShort())
        return b.array()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): GatewayV1Frame? {
            // Older glasses intentionally consume the first 20 bytes of a larger buffer.
            if (bytes.size < GatewayProtocol.V1_FRAME_SIZE) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if ((b.getShort(18).toInt() and 0xFFFF) != GatewayProtocol.calculateCrc16(bytes, 0, 18)) return null
            return GatewayV1Frame(
                (b.getShort(0).toInt() / 100f).toDouble(), bytes[2].toInt() and 0xFF,
                (b.getShort(3).toInt() / 10f).toDouble(), b.getInt(5).toLong() and 0xFFFFFFFFL,
                ((b.getShort(9).toInt() and 0xFFFF) / 100f).toDouble(),
                ((b.getShort(11).toInt() and 0xFFFF) / 10f).toDouble(), bytes[13].toInt() and 0xFF,
                b.getShort(14).toInt() and 0xFFFF, b.getShort(16).toInt() and 0xFFFF,
            )
        }
    }
}
