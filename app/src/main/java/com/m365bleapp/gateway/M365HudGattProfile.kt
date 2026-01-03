package com.m365bleapp.gateway

import java.util.UUID

/**
 * GATT Profile for M365 HUD Gateway
 * 
 * This defines the custom BLE service and characteristics used to
 * broadcast scooter telemetry data to Rokid Glasses or other BLE clients.
 */
object M365HudGattProfile {
    
    // Custom Service UUID (UUID v4 to avoid conflicts)
    val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    
    // Telemetry Characteristic (Notify + Read)
    // Contains: speed, battery, temp, mileage, etc.
    val TELEMETRY_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    
    // Connection Status Characteristic (Read only)
    val STATUS_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567892")
    
    // Time Characteristic (Notify + Read)
    // Contains: current time for HUD display
    val TIME_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567893")
    
    // Glasses Battery Characteristic (Write only)
    // Glasses write their battery level to this characteristic
    val GLASSES_BATTERY_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567894")
    
    // Client Characteristic Configuration Descriptor (Standard UUID)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    /**
     * Telemetry Data Format (20 bytes):
     * 
     * Byte 0-1:   Speed (i16 LE, ×100, km/h)      → 25.30 km/h = 2530
     * Byte 2:     Scooter Battery (u8, 0-100%)   → 85%
     * Byte 3-4:   Temperature (i16 LE, ×10, °C)  → 28.5°C = 285
     * Byte 5-8:   Total Mileage (u32 LE, m)      → 12345 m
     * Byte 9-10:  Avg Speed (u16 LE, ×100)       → 18.50 km/h = 1850
     * Byte 11-12: Remaining Range (u16 LE, ×10, km) → 15.5 km = 155
     * Byte 13:    Connection State (u8)          → 0=Disconnected, 1=Connecting, 2=Ready
     * Byte 14-15: Trip Meters (u16 LE)           → 2500 m
     * Byte 16-17: Trip Seconds (u16 LE)          → 600 s
     * Byte 18-19: CRC16                          → Checksum
     */
    const val TELEMETRY_DATA_SIZE = 20
    
    /**
     * Time Data Format (8 bytes):
     * 
     * Byte 0:     Hour (u8, 0-23)
     * Byte 1:     Minute (u8, 0-59)
     * Byte 2:     Second (u8, 0-59)
     * Byte 3:     Phone Battery (u8, 0-100%)
     * Byte 4-7:   Reserved / Timestamp
     */
    const val TIME_DATA_SIZE = 8
    
    // Connection states
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_READY = 2
}
