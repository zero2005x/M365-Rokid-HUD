package io.github.zero2005x.pev.core.gateway

import org.junit.Assert.*
import org.junit.Test

class GatewayProtocolTest {

    @Test
    fun `calculateCrc16 matches MODBUS standard vector`() {
        // "123456789" -> 0x4B37 (LE in bytes: 0x37, 0x4B)
        val testData = "123456789".toByteArray()
        val crc = GatewayProtocol.calculateCrc16(testData)
        assertEquals(0x4B37, crc)
    }

    @Test
    fun `calculateCrc16 handles empty and offset subranges`() {
        assertEquals(0xFFFF, GatewayProtocol.calculateCrc16(ByteArray(0)))
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5)
        val full = GatewayProtocol.calculateCrc16(byteArrayOf(1, 2, 3))
        val sub = GatewayProtocol.calculateCrc16(bytes, 1, 3)
        assertEquals(full, sub)
    }

    @Test
    fun `V1 frame round-trips correctly`() {
        val frame = GatewayV1Frame(
            speedKmh = 23.45f,
            batteryPercent = 88,
            temperatureC = 34.5f,
            totalDistanceMeters = 123456L,
            avgSpeedKmh = 18.20f,
            remainingRangeKm = 25.5f,
            connectionState = GatewayProtocol.STATE_READY,
            tripMeters = 3400,
            tripSeconds = 950,
        )

        val bytes = frame.toBytes()
        assertEquals(GatewayProtocol.V1_FRAME_SIZE, bytes.size)

        val decoded = GatewayV1Frame.fromBytes(bytes)
        assertNotNull(decoded)
        assertEquals(23.45f, decoded!!.speedKmh, 0.01f)
        assertEquals(88, decoded.batteryPercent)
        assertEquals(34.5f, decoded.temperatureC, 0.1f)
        assertEquals(123456L, decoded.totalDistanceMeters)
        assertEquals(18.20f, decoded.avgSpeedKmh, 0.01f)
        assertEquals(25.5f, decoded.remainingRangeKm, 0.1f)
        assertEquals(GatewayProtocol.STATE_READY, decoded.connectionState)
        assertEquals(3400, decoded.tripMeters)
        assertEquals(950, decoded.tripSeconds)
        assertTrue(decoded.isValid)
    }

    @Test
    fun `V1 frame rejects truncated bytes and corrupt CRC`() {
        assertNull(GatewayV1Frame.fromBytes(ByteArray(19)))

        val frame = GatewayV1Frame(
            speedKmh = 15f,
            batteryPercent = 50,
            temperatureC = 25f,
            totalDistanceMeters = 1000L,
            avgSpeedKmh = 10f,
            remainingRangeKm = 10f,
            connectionState = GatewayProtocol.STATE_READY,
            tripMeters = 500,
            tripSeconds = 120,
        )
        val bytes = frame.toBytes()
        bytes[0] = (bytes[0] + 1).toByte() // Corrupt first byte
        assertNull(GatewayV1Frame.fromBytes(bytes))
    }

    @Test
    fun `V2 frame round-trips all valid fields`() {
        val frame = GatewayV2Frame(
            vehicleType = GatewayProtocol.VEHICLE_BEGODE_EUC,
            connectionState = GatewayProtocol.STATE_READY,
            sequence = 42L,
            validityMask = GatewayProtocol.FIELD_SPEED or
                GatewayProtocol.FIELD_BATTERY or
                GatewayProtocol.FIELD_TEMPERATURE or
                GatewayProtocol.FIELD_TOTAL_DISTANCE or
                GatewayProtocol.FIELD_AVG_SPEED or
                GatewayProtocol.FIELD_REMAINING_RANGE or
                GatewayProtocol.FIELD_TRIP_DISTANCE or
                GatewayProtocol.FIELD_TRIP_DURATION or
                GatewayProtocol.FIELD_VOLTAGE or
                GatewayProtocol.FIELD_CURRENT,
            speedKmh = 35.6f,
            batteryPercent = 95,
            temperatureC = 42.1f,
            totalDistanceMeters = 789123L,
            avgSpeedKmh = 28.4f,
            remainingRangeKm = 60.5f,
            tripMeters = 15000L,
            tripSeconds = 2400L,
            voltageVolts = 82.5f,
            currentAmperes = 12.3f,
            alertFlags = GatewayProtocol.ALERT_SPEED_WARNING or GatewayProtocol.ALERT_TILTBACK_ENGAGED,
        )

        val bytes = frame.toBytes()
        assertTrue(bytes.size >= GatewayProtocol.V2_MIN_FRAME_SIZE)

        val decoded = GatewayV2Frame.fromBytes(bytes)
        assertNotNull(decoded)
        assertEquals(GatewayProtocol.VEHICLE_BEGODE_EUC, decoded!!.vehicleType)
        assertEquals(GatewayProtocol.STATE_READY, decoded.connectionState)
        assertEquals(42L, decoded.sequence)
        assertTrue(decoded.isFieldValid(GatewayProtocol.FIELD_SPEED))
        assertEquals(35.6f, decoded.speedKmh!!, 0.01f)
        assertEquals(95, decoded.batteryPercent)
        assertEquals(42.1f, decoded.temperatureC!!, 0.1f)
        assertEquals(789123L, decoded.totalDistanceMeters)
        assertEquals(28.4f, decoded.avgSpeedKmh!!, 0.01f)
        assertEquals(60.5f, decoded.remainingRangeKm!!, 0.1f)
        assertEquals(15000L, decoded.tripMeters)
        assertEquals(2400L, decoded.tripSeconds)
        assertEquals(82.5f, decoded.voltageVolts!!, 0.01f)
        assertEquals(12.3f, decoded.currentAmperes!!, 0.01f)
        assertEquals(
            GatewayProtocol.ALERT_SPEED_WARNING or GatewayProtocol.ALERT_TILTBACK_ENGAGED,
            decoded.alertFlags
        )
    }

    @Test
    fun `V2 frame respects validity mask and leaves unprovided fields null`() {
        val frame = GatewayV2Frame(
            vehicleType = GatewayProtocol.VEHICLE_XIAOMI_M365,
            connectionState = GatewayProtocol.STATE_CONNECTING,
            sequence = 1L,
            validityMask = GatewayProtocol.FIELD_SPEED,
            speedKmh = 12.5f,
            batteryPercent = null,
            temperatureC = null,
        )

        val bytes = frame.toBytes()
        val decoded = GatewayV2Frame.fromBytes(bytes)
        assertNotNull(decoded)
        assertTrue(decoded!!.isFieldValid(GatewayProtocol.FIELD_SPEED))
        assertFalse(decoded.isFieldValid(GatewayProtocol.FIELD_BATTERY))
        assertFalse(decoded.isFieldValid(GatewayProtocol.FIELD_TEMPERATURE))
        assertEquals(12.5f, decoded.speedKmh!!, 0.01f)
        assertNull(decoded.batteryPercent)
        assertNull(decoded.temperatureC)
        assertNull(decoded.totalDistanceMeters)
        assertNull(decoded.voltageVolts)
    }

    @Test
    fun `V2 frame rejects short bytes wrong magic wrong version and bad CRC`() {
        assertNull(GatewayV2Frame.fromBytes(ByteArray(30)))

        val frame = GatewayV2Frame(
            vehicleType = GatewayProtocol.VEHICLE_XIAOMI_M365,
            connectionState = GatewayProtocol.STATE_READY,
            sequence = 1L,
            validityMask = 0,
        )
        val bytes = frame.toBytes()

        // Wrong magic
        val wrongMagic = bytes.copyOf()
        wrongMagic[0] = 0
        assertNull(GatewayV2Frame.fromBytes(wrongMagic))

        // Wrong version
        val wrongVersion = bytes.copyOf()
        wrongVersion[4] = 99
        assertNull(GatewayV2Frame.fromBytes(wrongVersion))

        // Corrupt CRC
        val badCrc = bytes.copyOf()
        badCrc[bytes.size - 1] = (badCrc[bytes.size - 1] + 1).toByte()
        assertNull(GatewayV2Frame.fromBytes(badCrc))
    }
}
