package com.m365hud.glass

import java.util.UUID

/**
 * GATT Profile for M365 HUD Gateway (Mirror from Phone app)
 * Must match the phone app's M365HudGattProfile exactly.
 */
object GattProfile {
    
    // Custom Service UUID - Must match phone Gateway
    val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    
    // Telemetry Characteristic (Notify + Read)
    val TELEMETRY_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567891")
    
    // Time Characteristic (Notify + Read)
    val TIME_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567893")
    
    // Glasses Battery Characteristic (Write only - we write our battery level to phone)
    val GLASSES_BATTERY_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567894")
    
    // Client Characteristic Configuration Descriptor
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    // Connection states
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_READY = 2
}
