package io.github.zero2005x.pev.core.gateway

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.Reading
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot
import org.junit.Assert.*
import org.junit.Test

class GatewayCodecTest {

    @Test
    fun `encodeV1 succeeds for Xiaomi M365 with valid fields`() {
        val snapshot = TelemetrySnapshot(
            mapOf(
                FieldId.SPEED_KMH to Reading.valid(20.5, 100L, Evidence.WIRE_CAPTURED),
                FieldId.SOC_PERCENT to Reading.valid(75.0, 100L, Evidence.WIRE_CAPTURED),
                FieldId.TEMP_FRAME to Reading.valid(32.0, 100L, Evidence.WIRE_CAPTURED),
                FieldId.TOTAL_DISTANCE_M to Reading.valid(54321.0, 100L, Evidence.WIRE_CAPTURED),
            )
        )

        val result = GatewayCodec.encodeV1(
            snapshot = snapshot,
            vehicleType = GatewayProtocol.VEHICLE_XIAOMI_M365,
            connectionState = GatewayProtocol.STATE_READY,
            tripMeters = 1200,
            tripSeconds = 300,
        )

        assertTrue(result is V1EncodingResult.Success)
        val frame = (result as V1EncodingResult.Success).frame
        assertEquals(20.5f, frame.speedKmh, 0.01f)
        assertEquals(75, frame.batteryPercent)
        assertEquals(32.0f, frame.temperatureC, 0.1f)
        assertEquals(54321L, frame.totalDistanceMeters)
        assertEquals(1200, frame.tripMeters)
        assertEquals(300, frame.tripSeconds)
        assertEquals(GatewayProtocol.STATE_READY, frame.connectionState)
    }

    @Test
    fun `encodeV1 falls back gracefully when fields are absent or use alternative sensors`() {
        // Empty snapshot -> 0 fallbacks
        val emptyResult = GatewayCodec.encodeV1(TelemetrySnapshot())
        assertTrue(emptyResult is V1EncodingResult.Success)
        val emptyFrame = (emptyResult as V1EncodingResult.Success).frame
        assertEquals(0f, emptyFrame.speedKmh, 0.01f)
        assertEquals(0, emptyFrame.batteryPercent)
        assertEquals(0f, emptyFrame.temperatureC, 0.01f)
        assertEquals(0L, emptyFrame.totalDistanceMeters)

        // Alternative temperature sensor: MOSFET
        val mosfetResult = GatewayCodec.encodeV1(
            TelemetrySnapshot(mapOf(FieldId.TEMP_MOSFET to Reading.valid(45.0, 100L, Evidence.WIRE_CAPTURED)))
        )
        assertEquals(45f, (mosfetResult as V1EncodingResult.Success).frame.temperatureC, 0.01f)

        // Alternative temperature sensor: Battery
        val battTempResult = GatewayCodec.encodeV1(
            TelemetrySnapshot(mapOf(FieldId.TEMP_BATTERY to Reading.valid(36.0, 100L, Evidence.WIRE_CAPTURED)))
        )
        assertEquals(36f, (battTempResult as V1EncodingResult.Success).frame.temperatureC, 0.01f)
    }

    @Test
    fun `encodeV1 refuses non-M365 vehicle to avoid disguised zero-filling`() {
        val snapshot = TelemetrySnapshot(
            mapOf(
                FieldId.SPEED_KMH to Reading.valid(35.0, 100L, Evidence.WIRE_CAPTURED),
            )
        )

        val result = GatewayCodec.encodeV1(
            snapshot = snapshot,
            vehicleType = GatewayProtocol.VEHICLE_BEGODE_EUC,
        )

        assertTrue(result is V1EncodingResult.IncompatibleVehicle)
        val failure = result as V1EncodingResult.IncompatibleVehicle
        assertEquals(GatewayProtocol.VEHICLE_BEGODE_EUC, failure.vehicleType)
        assertTrue(failure.reason.contains("upgrade required"))
    }

    @Test
    fun `encodeV2 correctly maps field validity states and alerts`() {
        val snapshot = TelemetrySnapshot(
            mapOf(
                FieldId.SPEED_KMH to Reading.valid(18.2, 100L, Evidence.WIRE_CAPTURED),
                FieldId.SOC_PERCENT to Reading.valid(12.0, 100L, Evidence.WIRE_CAPTURED),
                FieldId.TEMP_FRAME to Reading.valid(29.0, 100L, Evidence.WIRE_CAPTURED),
                FieldId.PACK_VOLTAGE to Reading.valid(38.4, 100L, Evidence.WIRE_CAPTURED),
                FieldId.BATTERY_CURRENT to Reading.valid(-4.5, 100L, Evidence.WIRE_CAPTURED),
                // Stale total distance should NOT be flagged as valid!
                FieldId.TOTAL_DISTANCE_M to Reading(12345.0, FieldState.STALE, 100L, Evidence.WIRE_CAPTURED),
            )
        )

        val frame = GatewayCodec.encodeV2(
            snapshot = snapshot,
            vehicleType = GatewayProtocol.VEHICLE_XIAOMI_M365,
            connectionState = GatewayProtocol.STATE_READY,
            sequence = 77L,
            tripMeters = 4500L,
            tripSeconds = 800L,
            avgSpeedKmh = 16.5f,
            remainingRangeKm = 8.0f,
            alerts = GatewayProtocol.ALERT_BATTERY_LOW,
        )

        assertEquals(GatewayProtocol.VEHICLE_XIAOMI_M365, frame.vehicleType)
        assertEquals(GatewayProtocol.STATE_READY, frame.connectionState)
        assertEquals(77L, frame.sequence)

        assertTrue(frame.isFieldValid(GatewayProtocol.FIELD_SPEED))
        assertTrue(frame.isFieldValid(GatewayProtocol.FIELD_BATTERY))
        assertTrue(frame.isFieldValid(GatewayProtocol.FIELD_TEMPERATURE))
        assertTrue(frame.isFieldValid(GatewayProtocol.FIELD_VOLTAGE))
        assertTrue(frame.isFieldValid(GatewayProtocol.FIELD_CURRENT))
        assertTrue(frame.isFieldValid(GatewayProtocol.FIELD_TRIP_DISTANCE))
        assertTrue(frame.isFieldValid(GatewayProtocol.FIELD_TRIP_DURATION))
        assertTrue(frame.isFieldValid(GatewayProtocol.FIELD_AVG_SPEED))
        assertTrue(frame.isFieldValid(GatewayProtocol.FIELD_REMAINING_RANGE))

        // Total distance was STALE, so validity bit must NOT be set
        assertFalse(frame.isFieldValid(GatewayProtocol.FIELD_TOTAL_DISTANCE))
        assertNull(frame.totalDistanceMeters)

        assertEquals(18.2f, frame.speedKmh!!, 0.01f)
        assertEquals(12, frame.batteryPercent)
        assertEquals(29.0f, frame.temperatureC!!, 0.1f)
        assertEquals(38.4f, frame.voltageVolts!!, 0.01f)
        assertEquals(-4.5f, frame.currentAmperes!!, 0.01f)
        assertEquals(4500L, frame.tripMeters)
        assertEquals(800L, frame.tripSeconds)
        assertEquals(GatewayProtocol.ALERT_BATTERY_LOW, frame.alertFlags)
    }

    @Test
    fun `encodeV2 handles alternative sensor fallbacks and null trip counters`() {
        val snapshot = TelemetrySnapshot(
            mapOf(
                FieldId.TEMP_MOSFET to Reading.valid(52.0, 100L, Evidence.WIRE_CAPTURED),
                FieldId.PHASE_CURRENT to Reading.valid(15.2, 100L, Evidence.WIRE_CAPTURED),
            )
        )

        val frame = GatewayCodec.encodeV2(
            snapshot = snapshot,
            vehicleType = GatewayProtocol.VEHICLE_BEGODE_EUC,
            connectionState = GatewayProtocol.STATE_READY,
            sequence = 88L,
            tripMeters = null,
            tripSeconds = null,
            avgSpeedKmh = null,
            remainingRangeKm = null,
        )

        assertEquals(52.0f, frame.temperatureC!!, 0.01f)
        assertEquals(15.2f, frame.currentAmperes!!, 0.01f)
        assertFalse(frame.isFieldValid(GatewayProtocol.FIELD_TRIP_DISTANCE))
        assertFalse(frame.isFieldValid(GatewayProtocol.FIELD_TRIP_DURATION))
        assertFalse(frame.isFieldValid(GatewayProtocol.FIELD_AVG_SPEED))
        assertFalse(frame.isFieldValid(GatewayProtocol.FIELD_REMAINING_RANGE))
        assertNull(frame.tripMeters)
        assertNull(frame.tripSeconds)
        assertNull(frame.avgSpeedKmh)
        assertNull(frame.remainingRangeKm)

        // Battery temperature fallback
        val battSnap = TelemetrySnapshot(
            mapOf(FieldId.TEMP_BATTERY to Reading.valid(33.0, 100L, Evidence.WIRE_CAPTURED))
        )
        val battFrame = GatewayCodec.encodeV2(battSnap, GatewayProtocol.VEHICLE_XIAOMI_M365, 0, 1)
        assertEquals(33.0f, battFrame.temperatureC!!, 0.01f)
    }

    @Test
    fun `GatewayCommandGuard permits telemetry feedback and strictly rejects vehicle settings`() {
        // Allowed: Heartbeat
        val heartbeatRes = GatewayCommandGuard.inspect(GatewayCommandGuard.MSG_TYPE_HEARTBEAT, ByteArray(0))
        assertTrue(heartbeatRes is GatewayCommandGuard.InspectionResult.Allowed)

        // Allowed: Glasses battery
        val batteryRes = GatewayCommandGuard.inspect(GatewayCommandGuard.MSG_TYPE_GLASSES_BATTERY, byteArrayOf(85))
        assertTrue(batteryRes is GatewayCommandGuard.InspectionResult.Allowed)

        // Rejected: Empty battery payload
        val emptyBatteryRes = GatewayCommandGuard.inspect(GatewayCommandGuard.MSG_TYPE_GLASSES_BATTERY, ByteArray(0))
        assertTrue(emptyBatteryRes is GatewayCommandGuard.InspectionResult.Rejected)

        // Allowed: Display preference
        val prefsRes = GatewayCommandGuard.inspect(GatewayCommandGuard.MSG_TYPE_DISPLAY_PREFS, byteArrayOf(1, 0, 0, 0, 0, 100, 0))
        assertTrue(prefsRes is GatewayCommandGuard.InspectionResult.Allowed)

        // REJECTED: MSG_TYPE_COMMAND (Vehicle settings write attempt from glasses)
        val commandRes = GatewayCommandGuard.inspect(GatewayCommandGuard.MSG_TYPE_COMMAND, byteArrayOf(0x01, 0x02))
        assertTrue(commandRes is GatewayCommandGuard.InspectionResult.Rejected)
        assertTrue((commandRes as GatewayCommandGuard.InspectionResult.Rejected).reason.contains("Vehicle setting"))

        // REJECTED: Unknown message type
        val unknownRes = GatewayCommandGuard.inspect(0x7F.toByte(), ByteArray(0))
        assertTrue(unknownRes is GatewayCommandGuard.InspectionResult.Rejected)
    }
}
