package io.github.zero2005x.pev.core.gateway

import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.Reading
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Unreleased V2 complete snapshot, including distinct current and temperature sources.
 * Header: PEVG, version, vehicle, connection, zero, u16 length, u32 sequence,
 * i64 sender epoch-ms, u8 field count, u8 alert count. Entries are 21+source bytes;
 * each stores id/state/evidence/zero, i64 observation (-1 absent), f64 value, u8 source length.
 * Unavailable values have canonical zero storage but decode to null, never to valid zero.
 * CRC16/MODBUS follows all entries. BLE must reassemble this bounded packet before decoding.
 */
class GatewayV2Frame(
    val vehicleType: Int,
    val connectionState: Int,
    val sequence: Long,
    val generatedAtMs: Long,
    fields: Map<GatewayField, Reading> = emptyMap(),
    alerts: Map<GatewayAlertKind, Reading> = emptyMap(),
) {
    private val fieldSnapshot = fields.toMap()
    private val alertSnapshot = alerts.toMap()
    val fields: Map<GatewayField, Reading> get() = fieldSnapshot.toMap()
    val alerts: Map<GatewayAlertKind, Reading> get() = alertSnapshot.toMap()

    init {
        require(vehicleType in GatewayProtocol.VEHICLE_UNKNOWN..GatewayProtocol.VEHICLE_VETERAN) { "unknown vehicle code" }
        require(GatewayProtocol.validState(connectionState)) { "unknown connection state" }
        require(sequence in 0..0xFFFFFFFFL && generatedAtMs >= 0) { "sequence or generation time outside wire range" }
        fieldSnapshot.forEach { (id, r) -> GatewayReadingRules.validate(r, generatedAtMs, id::accepts) }
        alertSnapshot.values.forEach { GatewayReadingRules.validate(it, generatedAtMs) { v -> v == 0.0 || v == 1.0 } }
    }

    operator fun get(field: GatewayField): Reading = fieldSnapshot[field] ?: Reading.NOT_PROVIDED

    fun activeAlerts(nowMs: Long, maxAgeMs: Long = GatewayProtocol.DEFAULT_MAX_AGE_MS): Set<GatewayAlertKind> {
        require(nowMs >= 0 && maxAgeMs >= 0) { "invalid freshness window" }
        return alertSnapshot.filterValues {
            GatewayReadingRules.normalize(it, nowMs, maxAgeMs) { v -> v == 0.0 || v == 1.0 }
                .let { r -> r.state == FieldState.VALID && r.value == 1.0 }
        }.keys.toSet()
    }

    fun toBytes(): ByteArray {
        val b = ByteBuffer.allocate(GatewayProtocol.V2_MAX_FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x50, 0x45, 0x56, 0x47))
        b.put(GatewayProtocol.VERSION_2_EXTENDED.toByte()).put(vehicleType.toByte()).put(connectionState.toByte()).put(0)
        b.putShort(0).putInt(sequence.toInt()).putLong(generatedAtMs)
        b.put(fieldSnapshot.size.toByte()).put(alertSnapshot.size.toByte())
        fieldSnapshot.toSortedMap(compareBy { it.wireId }).forEach { (id, r) -> writeEntry(b, id.wireId, r) }
        alertSnapshot.toSortedMap(compareBy { it.wireId }).forEach { (id, r) -> writeEntry(b, id.wireId, r) }
        val length = b.position() + 2
        b.putShort(8, length.toShort())
        b.putShort(GatewayProtocol.calculateCrc16(b.array(), 0, length - 2).toShort())
        return b.array().copyOf(length)
    }

    companion object {
        /** Clocks must share the phone's epoch basis; caller supplies age, never receipt-as-observation. */
        fun fromBytes(bytes: ByteArray, nowMs: Long, maxAgeMs: Long = GatewayProtocol.DEFAULT_MAX_AGE_MS): GatewayV2Frame? {
            require(nowMs >= 0 && maxAgeMs >= 0) { "invalid freshness window" }
            if (!validEnvelope(bytes)) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val generatedAt = b.getLong(14)
            if (generatedAt < 0 || generatedAt > nowMs) return null
            val fieldCount = bytes[22].toInt() and 0xFF
            val alertCount = bytes[23].toInt() and 0xFF
            if (fieldCount > GatewayField.entries.size || alertCount > GatewayAlertKind.entries.size) return null
            b.position(GatewayProtocol.V2_HEADER_SIZE)
            b.limit(bytes.size - 2)
            val fields = readFields(b, fieldCount) ?: return null
            val alerts = readAlerts(b, alertCount) ?: return null
            if (b.hasRemaining()) return null
            return validatedFrame(bytes, generatedAt, fields, alerts, nowMs, maxAgeMs)
        }

        private fun validEnvelope(bytes: ByteArray): Boolean {
            if (bytes.size !in GatewayProtocol.V2_MIN_FRAME_SIZE..GatewayProtocol.V2_MAX_FRAME_SIZE) return false
            if (!bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x50, 0x45, 0x56, 0x47))) return false
            if ((bytes[4].toInt() and 0xFF) != 2 || bytes[7] != 0.toByte()) return false
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if ((b.getShort(8).toInt() and 0xFFFF) != bytes.size) return false
            return (b.getShort(bytes.size - 2).toInt() and 0xFFFF) == GatewayProtocol.calculateCrc16(bytes, 0, bytes.size - 2)
        }

        private fun validatedFrame(bytes: ByteArray, at: Long, fields: Map<GatewayField, Reading>,
            alerts: Map<GatewayAlertKind, Reading>, nowMs: Long, maxAgeMs: Long): GatewayV2Frame? {
            return try {
                val seq = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(10).toLong() and 0xFFFFFFFFL
                // Validate raw records before normalization; malformed wire provenance is rejected.
                GatewayV2Frame(bytes[5].toInt() and 0xFF, bytes[6].toInt() and 0xFF, seq, at, fields, alerts)
                GatewayV2Frame(bytes[5].toInt() and 0xFF, bytes[6].toInt() and 0xFF, seq, at,
                    fields.mapValues { (id, r) -> GatewayReadingRules.normalize(r, nowMs, maxAgeMs, id::accepts) },
                    alerts.mapValues { (_, r) -> GatewayReadingRules.normalize(r, nowMs, maxAgeMs) { v -> v == 0.0 || v == 1.0 } })
            } catch (_: IllegalArgumentException) { null }
        }

        private fun readFields(b: ByteBuffer, count: Int): Map<GatewayField, Reading>? {
            val result = mutableMapOf<GatewayField, Reading>()
            repeat(count) {
                val entry = readEntry(b) ?: return null
                val id = GatewayField.entries.firstOrNull { it.wireId == entry.first } ?: return null
                if (result.put(id, entry.second) != null) return null
            }
            return result
        }

        private fun readAlerts(b: ByteBuffer, count: Int): Map<GatewayAlertKind, Reading>? {
            val result = mutableMapOf<GatewayAlertKind, Reading>()
            repeat(count) {
                val entry = readEntry(b) ?: return null
                val id = GatewayAlertKind.entries.firstOrNull { it.wireId == entry.first } ?: return null
                if (result.put(id, entry.second) != null) return null
            }
            return result
        }

        private fun writeEntry(b: ByteBuffer, id: Int, r: Reading) {
            val source = r.source?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
            b.put(id.toByte()).put(GatewayReadingRules.stateCode(r.state).toByte())
            b.put(GatewayReadingRules.evidenceCode(r.evidence).toByte()).put(0)
            b.putLong(r.observedAtMs ?: -1).putDouble(r.value ?: 0.0)
            b.put(source.size.toByte()).put(source)
        }

        private fun readEntry(b: ByteBuffer): Pair<Int, Reading>? {
            if (b.remaining() < 21) return null
            val id = b.get().toInt() and 0xFF
            val state = GatewayReadingRules.state(b.get().toInt() and 0xFF) ?: return null
            val evidenceCode = b.get().toInt() and 0xFF
            if (evidenceCode !in 0..4 || b.get() != 0.toByte()) return null
            val at = b.long
            if (at < -1) return null
            val value = b.double
            if (!GatewayReadingRules.numeric(state) && value.toBits() != 0L) return null
            val sourceLength = b.get().toInt() and 0xFF
            if (sourceLength > GatewayProtocol.MAX_SOURCE_BYTES || b.remaining() < sourceLength) return null
            val source = readSource(b, sourceLength) ?: return null
            return id to Reading(if (GatewayReadingRules.numeric(state)) value else null, state,
                if (at == -1L) null else at, GatewayReadingRules.evidence(evidenceCode), source.ifEmpty { null })
        }

        private fun readSource(b: ByteBuffer, length: Int): String? {
            val raw = ByteArray(length)
            b.get(raw)
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
            } catch (_: CharacterCodingException) { null }
        }
    }
}

/** Edge deduplication per gateway connection. Reset on reconnect; missing alerts are cleared. */
class GatewayAlertTracker {
    private var lastSequence: Long? = null
    private var active = emptyMap<GatewayAlertKind, Reading>()

    fun reset() { lastSequence = null; active = emptyMap() }

    fun update(frame: GatewayV2Frame, nowMs: Long, maxAgeMs: Long = GatewayProtocol.DEFAULT_MAX_AGE_MS): Set<GatewayAlertKind> {
        require(nowMs >= 0 && maxAgeMs >= 0) { "invalid freshness window" }
        active = active.filterValues { r ->
            GatewayReadingRules.normalize(r, nowMs, maxAgeMs) { it == 0.0 || it == 1.0 }.state == FieldState.VALID
        }
        val previous = lastSequence
        val distance = if (previous == null) 1 else (frame.sequence - previous) and 0xFFFFFFFFL
        if (distance == 0L || distance > 0x7FFFFFFF) return emptySet()
        lastSequence = frame.sequence
        val current = frame.activeAlerts(nowMs, maxAgeMs)
        val newlyActive = current - active.keys
        active = frame.alerts.filterKeys { it in current }
        return newlyActive
    }
}
