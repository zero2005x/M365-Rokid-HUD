package io.github.zero2005x.pev.core.gateway

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Per-connection version choice. A missing hello means the peer is a legacy client.
 * Choosing V1 never invents fields the legacy packet cannot represent.
 */
enum class GatewayTransportChoice {
    LEGACY_V1,
    NEGOTIATED_V2,
    INCOMPATIBLE,
}

data class GatewayHello(
    val maxVersion: Int,
    val acceptsTypedTelemetry: Boolean,
    val preferredAttMtu: Int,
)

object GatewayNegotiation {
    const val HELLO_MAGIC_0 = 0x50
    const val HELLO_MAGIC_1 = 0x45
    const val HELLO_MAGIC_2 = 0x56
    const val HELLO_MAGIC_3 = 0x48
    const val HELLO_SIZE = 8
    const val FLAG_TYPED_TELEMETRY = 0x01
    const val PHONE_MAX_VERSION = GatewayProtocol.VERSION_2_EXTENDED

    fun encodeHello(maxVersion: Int, acceptsTypedTelemetry: Boolean, preferredAttMtu: Int): ByteArray {
        require(maxVersion == 1 || maxVersion == 2) { "unsupported offered version" }
        require(preferredAttMtu in 23..517) { "ATT MTU outside negotiation range" }
        val flags = if (acceptsTypedTelemetry) FLAG_TYPED_TELEMETRY else 0
        return ByteBuffer.allocate(HELLO_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put(HELLO_MAGIC_0.toByte()).put(HELLO_MAGIC_1.toByte())
            .put(HELLO_MAGIC_2.toByte()).put(HELLO_MAGIC_3.toByte())
            .put(maxVersion.toByte()).put(flags.toByte()).putShort(preferredAttMtu.toShort())
            .array()
    }

    fun decodeHello(bytes: ByteArray): GatewayHello? {
        if (bytes.size != HELLO_SIZE) return null
        if ((bytes[0].toInt() and 0xFF) != HELLO_MAGIC_0 || (bytes[1].toInt() and 0xFF) != HELLO_MAGIC_1 ||
            (bytes[2].toInt() and 0xFF) != HELLO_MAGIC_2 || (bytes[3].toInt() and 0xFF) != HELLO_MAGIC_3
        ) return null
        val version = bytes[4].toInt() and 0xFF
        if (version != 1 && version != 2) return null
        val mtu = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(6).toInt() and 0xFFFF
        if (mtu !in 23..517) return null
        return GatewayHello(version, bytes[5].toInt() and FLAG_TYPED_TELEMETRY != 0, mtu)
    }

    /**
     * Four deployment pairs:
     * old/old and new/old stay on V1 when the vehicle fits; otherwise incompatible.
     * old/new stays on V1 because this phone cannot speak V2.
     * new/new uses V2 only after a typed hello. No pair fills missing fields with zero.
     */
    fun choose(phoneMaxVersion: Int, glasses: GatewayHello?, vehicleSupportsLegacyV1: Boolean): GatewayTransportChoice {
        require(phoneMaxVersion == 1 || phoneMaxVersion == 2) { "unsupported phone version" }
        val glassesVersion = glasses?.maxVersion ?: 1
        if (phoneMaxVersion >= 2 && glassesVersion >= 2 && glasses?.acceptsTypedTelemetry == true) {
            return GatewayTransportChoice.NEGOTIATED_V2
        }
        return if (vehicleSupportsLegacyV1) GatewayTransportChoice.LEGACY_V1 else GatewayTransportChoice.INCOMPATIBLE
    }
}

/** Drops duplicate and older sequences. A new session generation starts a new sequence space. */
class GatewayPacketGate {
    private var generation = Long.MIN_VALUE
    private var lastSequence = -1L

    fun reset(sessionGeneration: Long) {
        generation = sessionGeneration
        lastSequence = -1L
    }

    fun accept(sessionGeneration: Long, sequence: Long): Boolean {
        require(sequence >= 0) { "sequence must be nonnegative" }
        if (sessionGeneration != generation) {
            generation = sessionGeneration
            lastSequence = sequence
            return true
        }
        if (sequence <= lastSequence) return false
        lastSequence = sequence
        return true
    }
}
