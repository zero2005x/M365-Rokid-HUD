package io.github.zero2005x.pev.core.gateway

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.Reading
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot
import org.junit.Assert.*
import org.junit.Test

class GatewayCodecTest {
    private fun r(value: Double = 0.0, at: Long = 100) = Reading.valid(value, at, Evidence.WIRE_CAPTURED, "m365:wire")
    private fun full() = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to r(1.15), FieldId.SOC_PERCENT to r(50.0),
        FieldId.TEMP_FRAME to r(30.0), FieldId.TOTAL_DISTANCE_M to r(123456.0),
        FieldId.TRIP_DISTANCE_M to r(500.0), FieldId.TRIP_TIME_S to r(300.0)))
    private fun v1(snapshot: TelemetrySnapshot = full(), type: Int = 1, state: Int = 2, now: Long = 100,
        average: Reading = r(12.0), range: Reading = r(20.0), age: Long = 2_000) =
        GatewayCodec.encodeV1(snapshot, type, state, now, average, range, age)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("invalid configuration accepted") } catch (_: IllegalArgumentException) { /* refused */ }
    }

    @Test fun v1SafeDowngradePreservesAllActualM365FieldsAndDoubleQuantization() {
        val frame = (v1() as V1EncodingResult.Success).frame
        assertEquals(1.15, frame.speedKmh, 0.0)
        assertEquals(12.0, frame.avgSpeedKmh, 0.0)
        assertEquals(20.0, frame.remainingRangeKm, 0.0)
        assertEquals(500, frame.tripMeters); assertEquals(300, frame.tripSeconds)
        assertEquals(114.toByte(), frame.toBytes()[0])
    }

    @Test fun v1CannotTurnAbsentStaleInvalidOrAlternateSensorsIntoValidZero() {
        assertTrue(v1(TelemetrySnapshot()) is V1EncodingResult.IncompatibleVehicle)
        assertTrue(v1(type = 2) is V1EncodingResult.IncompatibleVehicle)
        assertTrue(v1(state = 99) is V1EncodingResult.IncompatibleVehicle)
        assertTrue(v1(average = Reading.NOT_PROVIDED) is V1EncodingResult.IncompatibleVehicle)
        assertTrue(v1(range = Reading.UNSUPPORTED) is V1EncodingResult.IncompatibleVehicle)
        for (bad in listOf(Reading.NOT_PROVIDED, r().copy(state = FieldState.STALE), r(Double.NaN), r().copy(source = null))) {
            assertTrue(v1(full().merge(TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to bad)))) is V1EncodingResult.IncompatibleVehicle)
        }
        val alternate = full().fields - FieldId.TEMP_FRAME + mapOf(FieldId.TEMP_MOSFET to r(40.0), FieldId.TEMP_BATTERY to r(50.0))
        assertTrue(v1(TelemetrySnapshot(alternate)) is V1EncodingResult.IncompatibleVehicle)
        assertTrue((v1(TelemetrySnapshot(alternate)) as V1EncodingResult.IncompatibleVehicle).reason.contains("upgrade required"))
        assertTrue(v1(now = 2101) is V1EncodingResult.IncompatibleVehicle)
    }

    @Test fun v1SafeDowngradeRefusesEveryOverflowInsteadOfWrapping() {
        val outside = listOf(FieldId.SPEED_KMH to 327.68, FieldId.TEMP_FRAME to 3276.8,
            FieldId.TOTAL_DISTANCE_M to 4_294_967_296.0, FieldId.TRIP_DISTANCE_M to 65_536.0, FieldId.TRIP_TIME_S to 65_536.0)
        outside.forEach { (id, value) ->
            assertTrue("$id", v1(full().merge(TelemetrySnapshot(mapOf(id to r(value))))) is V1EncodingResult.IncompatibleVehicle)
        }
        assertTrue(v1(average = r(655.36)) is V1EncodingResult.IncompatibleVehicle)
        assertTrue(v1(range = r(6553.6)) is V1EncodingResult.IncompatibleVehicle)
        rejected { v1(now = -1) }; rejected { v1(age = -1) }
    }

    @Test fun v2NeverSubstitutesCurrentOrTemperatureSensors() {
        val snapshot = TelemetrySnapshot(mapOf(FieldId.PHASE_CURRENT to r(15.2), FieldId.TEMP_MOSFET to r(52.0), FieldId.TEMP_BATTERY to r(33.0)))
        val frame = GatewayCodec.encodeV2(snapshot, 2, 2, 88, 100)
        assertEquals(15.2, frame[GatewayField.PHASE_CURRENT].value)
        assertEquals(52.0, frame[GatewayField.TEMP_MOSFET].value)
        assertEquals(33.0, frame[GatewayField.TEMP_BATTERY].value)
        assertEquals(FieldState.NOT_PROVIDED, frame[GatewayField.BATTERY_CURRENT].state)
        assertEquals(FieldState.NOT_PROVIDED, frame[GatewayField.TEMP_FRAME].state)
        assertNull(frame[GatewayField.BATTERY_CURRENT].value)
        assertNull(frame[GatewayField.TEMP_FRAME].value)
        assertEquals(frame.fields, requireNotNull(GatewayV2Frame.fromBytes(frame.toBytes(), 100)).fields)
    }

    @Test fun v2PreservesEvidenceFreshnessSourceAndExplicitlyTypedExtras() {
        val stale = full().merge(TelemetrySnapshot(mapOf(FieldId.SOC_PERCENT to r(20.0).copy(state = FieldState.STALE))))
        val frame = GatewayCodec.encodeV2(stale, 1, 2, 77, 100,
            extras = mapOf(GatewayField.AVG_SPEED_KMH to r(18.0), GatewayField.REMAINING_RANGE_KM to r(25.0)),
            alerts = mapOf(GatewayAlertKind.BATTERY_LOW to r(1.0)))
        assertEquals(FieldState.STALE, frame[GatewayField.SOC_PERCENT].state)
        assertEquals(20.0, frame[GatewayField.SOC_PERCENT].value)
        assertEquals(Evidence.WIRE_CAPTURED, frame[GatewayField.SOC_PERCENT].evidence)
        assertEquals("m365:wire", frame[GatewayField.SOC_PERCENT].source)
        assertEquals(100L, frame[GatewayField.SOC_PERCENT].observedAtMs)
        assertEquals(18.0, frame[GatewayField.AVG_SPEED_KMH].value)
        assertEquals(25.0, frame[GatewayField.REMAINING_RANGE_KM].value)
        assertEquals(setOf(GatewayAlertKind.BATTERY_LOW), frame.activeAlerts(100))
        assertTrue(frame.activeAlerts(2101).isEmpty())
        val expired = GatewayCodec.encodeV2(full(), 1, 2, 78, 2101)
        assertEquals(FieldState.STALE, expired[GatewayField.SPEED_KMH].state)
    }

    @Test fun v2InvalidNumbersTimesOrSourcesStayInvalidAndNeverBecomeValidZero() {
        val readings = listOf(r(Double.NaN), r(Double.POSITIVE_INFINITY), r(-1.0), r(101.0),
            r().copy(observedAtMs = null), r().copy(observedAtMs = -1), r(at = 101),
            r().copy(source = null), r().copy(source = "x".repeat(97)), r().copy(evidence = null),
            r().copy(source = "\uD800"), r().copy(value = null))
        readings.forEach { bad ->
            val frame = GatewayCodec.encodeV2(TelemetrySnapshot(mapOf(FieldId.SOC_PERCENT to bad)), 1, 2, 1, 100)
            assertEquals(FieldState.INVALID, frame[GatewayField.SOC_PERCENT].state)
            assertNull(frame[GatewayField.SOC_PERCENT].value)
            assertEquals(FieldState.INVALID, requireNotNull(GatewayV2Frame.fromBytes(frame.toBytes(), 100))[GatewayField.SOC_PERCENT].state)
        }
        val noValue = Reading(123.0, FieldState.UNSUPPORTED, -1, source = "bad\nsource")
        val frame = GatewayCodec.encodeV2(TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to noValue)), 1, 2, 1, 100)
        assertEquals(Reading.UNSUPPORTED, frame[GatewayField.SPEED_KMH])
        rejected { GatewayCodec.encodeV2(full(), 1, 2, 1, -1) }
        rejected { GatewayCodec.encodeV2(full(), 1, 2, 1, 100, maxAgeMs = -1) }
        rejected { GatewayCodec.encodeV2(full(), 1, 2, 1, 100, extras = mapOf(GatewayField.PACK_VOLTAGE to r(1.0))) }
    }

    @Test fun v2CarriesEveryEvidenceLevelWithoutPromotingIt() {
        Evidence.entries.forEach { evidence ->
            val frame = GatewayCodec.encodeV2(TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to r(1.0).copy(evidence = evidence))), 1, 2, 1, 100)
            assertEquals(evidence, requireNotNull(GatewayV2Frame.fromBytes(frame.toBytes(), 100))[GatewayField.SPEED_KMH].evidence)
        }
    }

    @Test fun guardAcceptsOnlyActualHealthFeedbackShapes() {
        assertTrue(GatewayCommandGuard.inspect(4, byteArrayOf()) is GatewayCommandGuard.InspectionResult.Allowed)
        assertTrue(GatewayCommandGuard.inspect(4, ByteArray(8)) is GatewayCommandGuard.InspectionResult.Allowed)
        assertTrue(GatewayCommandGuard.inspect(5, byteArrayOf(0)) is GatewayCommandGuard.InspectionResult.Allowed)
        assertTrue(GatewayCommandGuard.inspect(5, byteArrayOf(100)) is GatewayCommandGuard.InspectionResult.Allowed)
        for ((type, data) in listOf(4 to ByteArray(1), 4 to ByteArray(9), 5 to byteArrayOf(), 5 to ByteArray(2),
            5 to byteArrayOf(101), 5 to byteArrayOf(-1))) {
            assertTrue(GatewayCommandGuard.inspect(type.toByte(), data) is GatewayCommandGuard.InspectionResult.Rejected)
        }
        for (type in 0..255) {
            if (type != 4 && type != 5) assertTrue(GatewayCommandGuard.inspect(type.toByte(), ByteArray(8)) is GatewayCommandGuard.InspectionResult.Rejected)
        }
        assertTrue((GatewayCommandGuard.inspect(3, byteArrayOf(0x7B)) as GatewayCommandGuard.InspectionResult.Rejected).reason.contains("Vehicle settings"))
    }
}
