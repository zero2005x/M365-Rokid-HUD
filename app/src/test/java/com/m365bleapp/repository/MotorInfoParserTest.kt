package com.m365bleapp.repository

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for MotorInfo parsing logic
 * 
 * Tests the conversion of raw BLE bytes to MotorInfo data class
 */
class MotorInfoParserTest {

    /**
     * Helper function to parse motor info from bytes
     * This mirrors the logic in ScooterRepository.parseMotorInfoFromData()
     */
    private fun parseMotorInfo(data: ByteArray): MotorInfo? {
        if (data.size < 32) return null
        
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        
        // Skip first 4 bytes (header)
        buffer.position(4)
        
        // Parse fields according to protocol
        val speedRaw = buffer.short.toInt()  // 4-5: speed in m/h (× 1000)
        val avgSpeedRaw = buffer.short.toInt()  // 6-7: avg speed
        val mileageRaw = buffer.int  // 8-11: mileage in meters
        
        buffer.position(14)
        val tripMetersRaw = buffer.short.toInt() and 0xFFFF  // 14-15
        val tripSecondsRaw = buffer.short.toInt() and 0xFFFF  // 16-17
        
        buffer.position(22)
        val tempRaw = buffer.short.toInt()  // 22-23: temp × 10
        
        buffer.position(26)
        val batteryRaw = buffer.short.toInt() and 0xFFFF  // 26-27: battery %
        
        return MotorInfo(
            speed = speedRaw / 1000.0,
            avgSpeed = avgSpeedRaw / 1000.0,
            mileage = mileageRaw / 1000.0,
            tripMeters = tripMetersRaw,
            tripSeconds = tripSecondsRaw,
            temp = tempRaw / 10.0,
            battery = batteryRaw.coerceIn(0, 100)
        )
    }

    @Test
    fun `parse valid motor info with zero speed`() {
        // Create test data: speed = 0, battery = 85%, temp = 25.5°C
        val data = createTestData(
            speed = 0,
            avgSpeed = 0,
            mileage = 12345,
            tripMeters = 500,
            tripSeconds = 120,
            temp = 255,  // 25.5°C × 10
            battery = 85
        )
        
        val result = parseMotorInfo(data)
        
        assertNotNull(result)
        assertEquals(0.0, result!!.speed, 0.001)
        assertEquals(85, result.battery)
        assertEquals(25.5, result.temp, 0.1)
        assertEquals(12.345, result.mileage, 0.001)
    }

    @Test
    fun `parse motor info with normal speed`() {
        // speed = 25.3 km/h = 25300 m/h
        val data = createTestData(
            speed = 25300,
            avgSpeed = 18500,
            mileage = 100000,
            tripMeters = 2500,
            tripSeconds = 600,
            temp = 300,  // 30.0°C
            battery = 50
        )
        
        val result = parseMotorInfo(data)
        
        assertNotNull(result)
        assertEquals(25.3, result!!.speed, 0.001)
        assertEquals(18.5, result.avgSpeed, 0.001)
        assertEquals(50, result.battery)
        assertEquals(100.0, result.mileage, 0.001)
    }

    @Test
    fun `parse motor info with max speed`() {
        // Max speed ~65 km/h = 65000 m/h (fits in i16)
        val data = createTestData(
            speed = 32000,  // 32 km/h
            avgSpeed = 20000,
            mileage = 500000,
            tripMeters = 10000,
            tripSeconds = 3600,
            temp = 450,  // 45°C (warning level)
            battery = 100
        )
        
        val result = parseMotorInfo(data)
        
        assertNotNull(result)
        assertEquals(32.0, result!!.speed, 0.001)
        assertEquals(100, result.battery)
        assertEquals(45.0, result.temp, 0.1)
    }

    @Test
    fun `parse motor info with negative temperature`() {
        // temp = -5.0°C = -50 raw
        val data = createTestData(
            speed = 15000,
            avgSpeed = 12000,
            mileage = 50000,
            tripMeters = 1000,
            tripSeconds = 300,
            temp = -50,  // -5.0°C
            battery = 70
        )
        
        val result = parseMotorInfo(data)
        
        assertNotNull(result)
        assertEquals(-5.0, result!!.temp, 0.1)
    }

    @Test
    fun `parse motor info with low battery`() {
        val data = createTestData(
            speed = 10000,
            avgSpeed = 8000,
            mileage = 200000,
            tripMeters = 500,
            tripSeconds = 180,
            temp = 280,
            battery = 5  // Low battery warning
        )
        
        val result = parseMotorInfo(data)
        
        assertNotNull(result)
        assertEquals(5, result!!.battery)
    }

    @Test
    fun `return null for insufficient data`() {
        val shortData = ByteArray(20)  // Less than required 32 bytes
        
        val result = parseMotorInfo(shortData)
        
        assertNull(result)
    }

    @Test
    fun `battery value clamped to valid range`() {
        // Test battery > 100 gets clamped
        val data = createTestData(
            speed = 0,
            avgSpeed = 0,
            mileage = 0,
            tripMeters = 0,
            tripSeconds = 0,
            temp = 250,
            battery = 150  // Invalid, should be clamped to 100
        )
        
        val result = parseMotorInfo(data)
        
        assertNotNull(result)
        assertEquals(100, result!!.battery)
    }

    /**
     * Helper to create test data matching the protocol format
     */
    private fun createTestData(
        speed: Int,
        avgSpeed: Int,
        mileage: Int,
        tripMeters: Int,
        tripSeconds: Int,
        temp: Int,
        battery: Int
    ): ByteArray {
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        
        // Bytes 0-3: header (skip)
        buffer.putInt(0)
        
        // Bytes 4-5: speed
        buffer.putShort(speed.toShort())
        
        // Bytes 6-7: avg speed
        buffer.putShort(avgSpeed.toShort())
        
        // Bytes 8-11: mileage
        buffer.putInt(mileage)
        
        // Bytes 12-13: padding
        buffer.putShort(0)
        
        // Bytes 14-15: trip meters
        buffer.putShort(tripMeters.toShort())
        
        // Bytes 16-17: trip seconds
        buffer.putShort(tripSeconds.toShort())
        
        // Bytes 18-21: padding
        buffer.putInt(0)
        
        // Bytes 22-23: temperature
        buffer.putShort(temp.toShort())
        
        // Bytes 24-25: padding
        buffer.putShort(0)
        
        // Bytes 26-27: battery
        buffer.putShort(battery.toShort())
        
        // Bytes 28-31: padding
        buffer.putInt(0)
        
        return buffer.array()
    }
}
