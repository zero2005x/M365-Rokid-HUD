package io.github.zero2005x.pev.core.gateway

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.Reading
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

class GatewayProtocolTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun value(v: Double = 0.0, at: Long = 100, source: String = "wheel:test") =
        Reading.valid(v, at, Evidence.WIRE_CAPTURED, source)
    private fun v2(fields: Map<GatewayField, Reading> = mapOf(GatewayField.SPEED_KMH to value()),
        alerts: Map<GatewayAlertKind, Reading> = emptyMap(), seq: Long = 1, at: Long = 100) =
        GatewayV2Frame(2, 2, seq, at, fields, alerts)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("invalid wire input accepted") } catch (_: IllegalArgumentException) { /* refused */ }
    }
    private fun crc(bytes: ByteArray): ByteArray = bytes.also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(it.size - 2, GatewayProtocol.calculateCrc16(it, 0, it.size - 2).toShort())
    }
    private fun changed(bytes: ByteArray, offset: Int, value: Int) = crc(bytes.copyOf().also { it[offset] = value.toByte() })

    @Test fun modbusStandardVectorAndCheckedSubrange() {
        assertEquals(0x4B37, GatewayProtocol.calculateCrc16("123456789".toByteArray()))
        assertEquals(0xFFFF, GatewayProtocol.calculateCrc16(byteArrayOf()))
        assertEquals(0x4B37, GatewayProtocol.calculateCrc16(byteArrayOf(0) + "123456789".toByteArray(), 1))
        assertEquals(GatewayProtocol.calculateCrc16(byteArrayOf(1, 2)), GatewayProtocol.calculateCrc16(byteArrayOf(0, 1, 2, 3), 1, 2))
        listOf(-1 to 1, 0 to -1, 4 to 0, 1 to Int.MAX_VALUE).forEach { (o, n) ->
            rejected { GatewayProtocol.calculateCrc16(byteArrayOf(1, 2, 3), o, n) }
        }
    }

    @Test fun v1MatchesFrozenProducerVectorsIncludingDoubleTruncationAndWrap() {
        // Frozen synthetic vectors derived from both production server byte layouts, not a round-trip oracle.
        val common = GatewayV1Frame(23.45, 88, 34.5, 123456, 18.20, 25.5, 2, 3400, 950)
        assertArrayEquals(hex("290958590140e201001c07ff0002480db603430d"), common.toBytes())
        val wraps = GatewayV1Frame(1.15, 101, -12.34, 0x1FFFFFFFFL, 655.36999, 6553.6, 258, 70000, -1)
        assertArrayEquals(hex("72006485ffffffffff00000000027011ffff6d4a"), wraps.toBytes())
        // Float multiplication would produce 115; the actual Double producer truncates this to 114.
        assertEquals(114, ByteBuffer.wrap(wraps.toBytes()).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        val negative = GatewayV1Frame(-327.689, -1, -3276.89, -1, 0.0, 0.0, 0, -1, 65536)
        assertArrayEquals(hex("0080000080ffffffff0000000000ffff00009467"), negative.toBytes())
    }

    @Test fun v1ReadsExactlyLikeLegacyGlassesIncludingUnsignedCounters() {
        val raw = hex("72006485ffffffffff00000000027011ffff6d4a")
        val frame = requireNotNull(GatewayV1Frame.fromBytes(raw + byteArrayOf(9, 8)))
        assertEquals(1.14f.toDouble(), frame.speedKmh, 0.0)
        assertEquals((-12.3f).toDouble(), frame.temperatureC, 0.0)
        assertEquals(0xFFFFFFFFL, frame.totalDistanceMeters)
        assertEquals(100, frame.batteryPercent)
        assertEquals(4464, frame.tripMeters)
        assertEquals(65535, frame.tripSeconds)
        assertTrue(frame.isValid)
    }

    @Test fun v1RejectsAllPrefixesAndEverySingleByteCorruption() {
        val raw = hex("290958590140e201001c07ff0002480db603430d")
        for (length in 0 until 20) assertNull(GatewayV1Frame.fromBytes(raw.copyOf(length)))
        raw.indices.forEach { i ->
            assertNull(GatewayV1Frame.fromBytes(raw.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }))
        }
    }

    @Test fun v2HasLiteralMagicExactHeaderAndDistinctSemanticFields() {
        val fields = mapOf(GatewayField.BATTERY_CURRENT to value(-123.456), GatewayField.PHASE_CURRENT to value(987.654),
            GatewayField.TEMP_FRAME to value(12.0), GatewayField.TEMP_MOSFET to value(34.0),
            GatewayField.TRIP_DISTANCE_M to value(4_294_967_296.0), GatewayField.TRIP_TIME_S to value(100_000.0))
        val raw = v2(fields, seq = 0xFFFFFFFFL).toBytes()
        assertArrayEquals("PEVG".toByteArray(), raw.copyOf(4))
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(raw.size, b.getShort(8).toInt() and 0xFFFF)
        assertEquals(0xFFFFFFFFL, b.getInt(10).toLong() and 0xFFFFFFFFL)
        assertEquals(100L, b.getLong(14))
        assertEquals(fields.size, raw[22].toInt())
        val decoded = requireNotNull(GatewayV2Frame.fromBytes(raw, 101))
        assertEquals(fields, decoded.fields)
        assertEquals("A", GatewayField.PHASE_CURRENT.unit)
        assertEquals("s", GatewayField.TRIP_TIME_S.unit)
        assertEquals(FieldState.NOT_PROVIDED, decoded[GatewayField.TEMP_BATTERY].state)
    }

    @Test fun v2PreservesUnavailableStatesMetadataAndAgesEveryProvidedValue() {
        val fields = mapOf(GatewayField.SPEED_KMH to value(0.0),
            GatewayField.SOC_PERCENT to value(50.0).copy(state = FieldState.STALE),
            GatewayField.TEMP_FRAME to Reading.invalid(100, "bad-sensor"),
            GatewayField.TOTAL_DISTANCE_M to Reading.NOT_PROVIDED, GatewayField.PHASE_CURRENT to Reading.UNSUPPORTED)
        val raw = v2(fields).toBytes()
        val fresh = requireNotNull(GatewayV2Frame.fromBytes(raw, 101, 10))
        assertEquals(fields, fresh.fields)
        val aged = requireNotNull(GatewayV2Frame.fromBytes(raw, 111, 10))
        assertEquals(FieldState.STALE, aged[GatewayField.SPEED_KMH].state)
        assertEquals(0.0, aged[GatewayField.SPEED_KMH].value)
        assertFalse(aged[GatewayField.SPEED_KMH].usable)
        assertEquals("wheel:test", aged[GatewayField.SPEED_KMH].source)
        assertNull(aged[GatewayField.TEMP_FRAME].value)
        assertNull(aged[GatewayField.PHASE_CURRENT].value)
    }

    @Test fun v2TruncationsWithCorrectedLengthAndCrcNeverReadOutsideBuffer() {
        val raw = v2().toBytes()
        for (length in 0 until raw.size) {
            val prefix = raw.copyOf(length)
            if (length >= 26) {
                ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).putShort(8, length.toShort())
                crc(prefix)
            }
            assertNull("length=$length", GatewayV2Frame.fromBytes(prefix, 100))
        }
        assertNull(GatewayV2Frame.fromBytes(raw + byteArrayOf(0), 100))
        assertNull(GatewayV2Frame.fromBytes(ByteArray(GatewayProtocol.V2_MAX_FRAME_SIZE + 1), 100))
    }

    @Test fun v2RejectsHeaderVersionReservedLengthCountsAndCrc() {
        val raw = v2().toBytes()
        for ((offset, value) in listOf(0 to 0, 4 to 1, 5 to 255, 6 to 9, 7 to 1, 8 to 1,
            22 to 16, 23 to 7)) assertNull("offset=$offset", GatewayV2Frame.fromBytes(changed(raw, offset, value), 100))
        raw.indices.forEach { i ->
            assertNull(GatewayV2Frame.fromBytes(raw.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }, 100))
        }
        val trailing = raw.copyOf(raw.size + 1)
        ByteBuffer.wrap(trailing).order(ByteOrder.LITTLE_ENDIAN).putShort(8, trailing.size.toShort())
        assertNull(GatewayV2Frame.fromBytes(crc(trailing), 100))
    }

    @Test fun v2RejectsInvalidEntryIdsEnumsMetadataAndUtf8EvenWithCorrectCrc() {
        val raw = v2().toBytes()
        for ((offset, v) in listOf(24 to 255, 25 to 255, 26 to 255, 27 to 1, 44 to 97, 44 to 96)) {
            assertNull("offset=$offset", GatewayV2Frame.fromBytes(changed(raw, offset, v), 100))
        }
        val negativeAt = raw.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(28, -2) }
        assertNull(GatewayV2Frame.fromBytes(crc(negativeAt), 100))
        val futureAt = raw.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(28, 101) }
        assertNull(GatewayV2Frame.fromBytes(crc(futureAt), 100))
        val noTimestamp = raw.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(28, -1) }
        assertNull(GatewayV2Frame.fromBytes(crc(noTimestamp), 100))
        assertNull(GatewayV2Frame.fromBytes(changed(raw, 26, 0), 100))
        assertNull(GatewayV2Frame.fromBytes(changed(raw, 45, 0xFF), 100))
        assertNull(GatewayV2Frame.fromBytes(changed(raw, 45, 0x0A), 100))
        val nan = raw.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putDouble(36, Double.NaN) }
        assertNull(GatewayV2Frame.fromBytes(crc(nan), 100))
        val unavailableWithValue = raw.copyOf().also {
            it[25] = 0; ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putDouble(36, 1.0)
        }
        assertNull(GatewayV2Frame.fromBytes(crc(unavailableWithValue), 100))
    }

    @Test fun v2RejectsDuplicateFieldsAlertsAndUnknownAlertCode() {
        val raw = v2(mapOf(GatewayField.SPEED_KMH to value(), GatewayField.SOC_PERCENT to value())).toBytes()
        val secondOffset = 24 + 21 + "wheel:test".length
        assertNull(GatewayV2Frame.fromBytes(changed(raw, secondOffset, GatewayField.SPEED_KMH.wireId), 100))
        val alerts = v2(emptyMap(), mapOf(GatewayAlertKind.BATTERY_LOW to value(1.0), GatewayAlertKind.BMS_FAULT to value(1.0))).toBytes()
        assertNull(GatewayV2Frame.fromBytes(changed(alerts, secondOffset, GatewayAlertKind.BATTERY_LOW.wireId), 100))
        assertNull(GatewayV2Frame.fromBytes(changed(alerts, 24, 255), 100))
    }

    @Test fun v2SupportsMaximumExactCountersAndBoundedMultibyteSource() {
        val fields = mapOf(GatewayField.TRIP_DISTANCE_M to value(GatewayProtocol.MAX_EXACT_COUNTER, source = "漢".repeat(32)))
        assertEquals(fields, requireNotNull(GatewayV2Frame.fromBytes(v2(fields).toBytes(), 100)).fields)
        rejected { v2(mapOf(GatewayField.SPEED_KMH to value(source = "漢".repeat(33)))) }
        rejected { v2(mapOf(GatewayField.SPEED_KMH to value(source = "\uD800"))) }
        val many = GatewayField.entries.associateWith { value(0.0, source = "x".repeat(96)) }
        val alarms = GatewayAlertKind.entries.associateWith { value(1.0, source = "x".repeat(96)) }
        val raw = v2(many, alarms).toBytes()
        assertEquals(2483, raw.size)
        assertEquals(many, requireNotNull(GatewayV2Frame.fromBytes(raw, 100)).fields)
    }

    @Test fun v2ConstructionRejectsInvalidRangesAndUnprovenNumericalValues() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1001.0, 1001.0).forEach { v ->
            rejected { v2(mapOf(GatewayField.SPEED_KMH to value(v))) }
        }
        for (r in listOf(value().copy(observedAtMs = null), value().copy(evidence = null), value().copy(source = null),
            value().copy(source = " "), value().copy(source = "a\nb"), value().copy(observedAtMs = -1),
            Reading(0.0, FieldState.NOT_PROVIDED))) rejected { v2(mapOf(GatewayField.SPEED_KMH to r)) }
        rejected { v2(mapOf(GatewayField.TRIP_DISTANCE_M to value(-1.0))) }
        rejected { v2(mapOf(GatewayField.TRIP_TIME_S to value(0.5))) }
        rejected { v2(mapOf(GatewayField.TRIP_TIME_S to value(9_007_199_254_740_992.0))) }
        rejected { GatewayV2Frame(255, 2, 1, 100) }
        rejected { GatewayV2Frame(1, 255, 1, 100) }
        rejected { v2(seq = -1) }; rejected { v2(seq = 0x1FFFFFFFFL) }; rejected { v2(at = -1) }
        rejected { v2(emptyMap(), mapOf(GatewayAlertKind.BMS_FAULT to value(2.0))) }
    }

    @Test fun v2RequiresCoherentClockAndCallerFreshnessPolicy() {
        val raw = v2().toBytes()
        assertNull(GatewayV2Frame.fromBytes(raw, 99))
        val negative = raw.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(14, -1) }
        assertNull(GatewayV2Frame.fromBytes(crc(negative), 100))
        rejected { GatewayV2Frame.fromBytes(raw, -1) }
        rejected { GatewayV2Frame.fromBytes(raw, 100, -1) }
        rejected { v2().activeAlerts(-1) }; rejected { v2().activeAlerts(100, -1) }
    }

    @Test fun v2SnapshotsCannotBeMutatedThroughInputOrPublicCollections() {
        val input = mutableMapOf(GatewayField.SPEED_KMH to value(), GatewayField.SOC_PERCENT to value())
        val alerts = mutableMapOf(GatewayAlertKind.BMS_FAULT to value(1.0), GatewayAlertKind.BATTERY_LOW to value(1.0))
        val f = v2(input, alerts)
        input.clear(); alerts.clear()
        (f.fields as MutableMap<GatewayField, Reading>).clear()
        (f.alerts as MutableMap<GatewayAlertKind, Reading>).clear()
        assertEquals(2, f.fields.size); assertEquals(2, f.alerts.size)
    }

    @Test fun v2RandomMalformedInputsNeverThrow() {
        val random = Random(77)
        repeat(500) {
            val bytes = ByteArray(random.nextInt(200))
            random.nextBytes(bytes)
            assertNull(GatewayV2Frame.fromBytes(bytes, 100))
        }
    }

    @Test fun alertTrackerDeduplicatesFreshEdgesExpiresAndHandlesSequenceWrap() {
        val tracker = GatewayAlertTracker()
        fun f(seq: Long, at: Long, active: Boolean) = v2(emptyMap(),
            mapOf(GatewayAlertKind.BMS_FAULT to value(if (active) 1.0 else 0.0, at)), seq, at)
        assertEquals(setOf(GatewayAlertKind.BMS_FAULT), tracker.update(f(0xFFFFFFFFL, 100, true), 100))
        assertTrue(tracker.update(f(0xFFFFFFFFL, 100, true), 100).isEmpty())
        assertTrue(tracker.update(f(0, 101, true), 101).isEmpty())
        assertTrue(tracker.update(f(0xFFFFFFFFL, 100, false), 101).isEmpty())
        assertTrue(tracker.update(f(1, 102, false), 102).isEmpty())
        assertEquals(setOf(GatewayAlertKind.BMS_FAULT), tracker.update(f(2, 103, true), 103))
        assertTrue(f(2, 103, true).activeAlerts(104, 0).isEmpty())
        assertEquals(setOf(GatewayAlertKind.BMS_FAULT), tracker.update(f(3, 106, true), 106, 0))
        tracker.reset()
        assertEquals(setOf(GatewayAlertKind.BMS_FAULT), tracker.update(f(0, 100, true), 100))
        rejected { tracker.update(f(1, 100, true), -1) }
        rejected { tracker.update(f(1, 100, true), 100, -1) }
    }
}
