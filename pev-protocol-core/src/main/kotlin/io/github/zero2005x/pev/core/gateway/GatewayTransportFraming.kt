package io.github.zero2005x.pev.core.gateway

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BLE notifications cannot carry a 4096-byte V2 packet. Fragments are bound to a
 * session generation so a late chunk cannot complete the next vehicle's frame.
 */
object GatewayBleFraming {
    const val MAGIC: Int = 0xB7
    const val HEADER_SIZE = 5
    const val MAX_FRAGMENTS = 64

    fun chunkSize(attMtu: Int): Int {
        val usable = attMtu - 3
        require(usable >= HEADER_SIZE + 1) { "ATT MTU cannot carry a fragment" }
        return usable
    }

    fun fragment(packet: ByteArray, attMtu: Int, sessionGeneration: Int): List<ByteArray> {
        require(packet.size in 1..GatewayProtocol.V2_MAX_FRAME_SIZE) { "packet outside V2 bound" }
        require(sessionGeneration in 0..0xFFFF) { "session generation outside fragment header" }
        val payloadBudget = chunkSize(attMtu) - HEADER_SIZE
        val count = (packet.size + payloadBudget - 1) / payloadBudget
        require(count in 1..MAX_FRAGMENTS) { "packet needs more fragments than the bound allows" }
        return List(count) { index ->
            val start = index * payloadBudget
            val slice = packet.copyOfRange(start, minOf(start + payloadBudget, packet.size))
            ByteBuffer.allocate(HEADER_SIZE + slice.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(MAGIC.toByte()).putShort(sessionGeneration.toShort())
                .put(index.toByte()).put(count.toByte()).put(slice).array()
        }
    }
}

class GatewayBleReassembler(private val maxBytes: Int = GatewayProtocol.V2_MAX_FRAME_SIZE) {
    private var session = -1
    private var count = 0
    private var parts = arrayOfNulls<ByteArray>(0)

    fun reset() {
        session = -1
        count = 0
        parts = arrayOfNulls(0)
    }

    fun accept(chunk: ByteArray, sessionGeneration: Int): ByteArray? {
        if (chunk.size <= GatewayBleFraming.HEADER_SIZE) return null
        if ((chunk[0].toInt() and 0xFF) != GatewayBleFraming.MAGIC) return null
        val header = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        val claimed = header.getShort(1).toInt() and 0xFFFF
        if (claimed != sessionGeneration) return null
        val index = chunk[3].toInt() and 0xFF
        val total = chunk[4].toInt() and 0xFF
        if (total !in 1..GatewayBleFraming.MAX_FRAGMENTS || index >= total) return resetAndDrop()
        if (session != sessionGeneration || count != total) {
            session = sessionGeneration
            count = total
            parts = arrayOfNulls(total)
        }
        val payload = chunk.copyOfRange(GatewayBleFraming.HEADER_SIZE, chunk.size)
        val existing = parts[index]
        if (existing != null && !existing.contentEquals(payload)) return resetAndDrop()
        parts[index] = payload
        val received = parts.filterNotNull()
        if (received.size != count) return null
        val size = received.sumOf { it.size }
        if (size !in 1..maxBytes) return resetAndDrop()
        val joined = ByteArray(size)
        var cursor = 0
        for (part in parts) {
            val bytes = part ?: return null
            bytes.copyInto(joined, cursor)
            cursor += bytes.size
        }
        reset()
        return joined
    }

    private fun resetAndDrop(): ByteArray? {
        reset()
        return null
    }
}

object GatewayWifiFraming {
    const val TYPE_TELEMETRY_V2: Int = 0x10
    const val TYPE_HELLO: Int = 0x07

    fun maxDeclaredLength(): Int = 1 + GatewayProtocol.V2_MAX_FRAME_SIZE

    fun validDeclaredLength(length: Int): Boolean = length in 1..maxDeclaredLength()
}
