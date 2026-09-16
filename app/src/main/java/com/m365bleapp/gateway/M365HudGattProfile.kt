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
     * Display Preferences Characteristic (Read + Notify).
     *
     * The phone pushes a bitmask telling the glasses WHICH telemetry fields to
     * show. Added so the rider can pick the HUD contents from the phone while
     * the glasses stay in a pocket-free, glance-only role.
     *
     * Why a separate characteristic instead of extra bytes inside the telemetry
     * frame:
     *
     *  - The telemetry frame is a fixed 20 bytes with its CRC covering bytes
     *    0..17. Embedding a bitmask would mean growing the frame and moving the
     *    CRC, which breaks every glasses build in the field. A new
     *    characteristic is additive: old glasses never subscribe to it and keep
     *    working unchanged.
     *  - Preferences change rarely; telemetry changes ~10x/second. Keeping them
     *    apart means the bitmask is not re-sent 10 times a second.
     *  - Read + Notify means the glasses get the current value two ways: they
     *    read it once on connect (so a reconnect restores the rider's choice
     *    with no phone-side action), and they are notified whenever it changes.
     */
    val DISPLAY_PREFS_CHAR_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567895")

    /**
     * Display Preferences payload format (7 bytes, little-endian):
     *
     *   Byte 0:      Version (u8) — currently [DISPLAY_PREFS_VERSION]
     *   Byte 1-4:    Bitmask (u32 LE) — see [DisplayField]
     *   Byte 5:      Text scale percent (u8) — 100 = normal, range
     *                [DISPLAY_PREFS_MIN_SCALE]..[DISPLAY_PREFS_MAX_SCALE]
     *   Byte 6:      Reserved (u8) — MUST be 0; receivers ignore it
     *
     * The version byte exists so the format can change later without a flag
     * day. A receiver that does not recognise the version MUST keep its current
     * layout rather than guessing.
     *
     * A bitmask of 0 is treated as "no preference known" and falls back to
     * [DisplayField.DEFAULT_MASK] — showing nothing at all is never what a
     * rider wants, and it is also what a mis-read or an all-zero flash page
     * would look like.
     */
    const val DISPLAY_PREFS_VERSION = 1
    const val DISPLAY_PREFS_SIZE = 7
    const val DISPLAY_PREFS_MIN_SCALE = 80
    const val DISPLAY_PREFS_MAX_SCALE = 140

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
     * Byte 18-19: CRC16 (u16 LE)                 → Checksum, see CRC16_SPEC
     */
    const val TELEMETRY_DATA_SIZE = 20

    /**
     * Authoritative checksum contract for this profile.
     *
     * Variant:      CRC-16/MODBUS
     *   - width:      16
     *   - polynomial: 0xA001 (reflected form of 0x8005)
     *   - init:       0xFFFF
     *   - reflected:  yes (input and output)
     *   - final xor:  none
     * Covered bytes: 0..17 inclusive (everything before the CRC field itself)
     *
     * Both ends MUST implement exactly this. They previously did not — the
     * producer used CRC-16/MODBUS while the glasses client used
     * CRC-16/CCITT-FALSE (poly 0x1021, non-reflected) — so the receiver's
     * validity check failed for essentially every packet and silently rejected
     * all valid telemetry.
     */
    const val CRC16_SPEC = "CRC-16/MODBUS (poly 0xA001, init 0xFFFF, reflected, no final xor) over bytes 0..17"

    /** Number of leading bytes covered by the telemetry CRC. */
    const val TELEMETRY_CRC_COVERED_BYTES = 18
    
    /**
     * Time Data Format (8 bytes):
     * 
     * Byte 0:     Hour (u8, 0-23)
     * Byte 1:     Minute (u8, 0-59)
     * Byte 2:     Second (u8, 0-59)
     * Byte 3:     Phone Battery (u8, 0-100%)
     * Byte 4-7:   Timestamp (u32 LE, Unix epoch seconds)
     *             Note: a u32 seconds counter wraps in 2038.
     */
    const val TIME_DATA_SIZE = 8
    
    // Connection states
    const val STATE_DISCONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_READY = 2
}

/**
 * Which telemetry fields the glasses HUD renders.
 *
 * Bit assignments are part of the wire contract. **Never renumber an existing
 * bit** — a field that is removed keeps its bit reserved so that an older
 * glasses build and a newer phone never disagree about what bit 5 means.
 *
 * Bits a given glasses build does not know about are ignored when rendering,
 * which is what makes adding a field below backward compatible.
 */
object DisplayField {
    /** Current speed. The hero element; almost always on. */
    const val SPEED = 1 shl 0

    /** Scooter battery percentage. */
    const val SCOOTER_BATTERY = 1 shl 1

    /** Phone battery percentage. */
    const val PHONE_BATTERY = 1 shl 2

    /** Glasses' own battery percentage. */
    const val GLASSES_BATTERY = 1 shl 3

    /** Clock (HH:mm). */
    const val TIME = 1 shl 4

    /** BLE link quality indicator (the signal/stale icon). */
    const val SIGNAL_QUALITY = 1 shl 5

    /** Controller / frame temperature. */
    const val TEMPERATURE = 1 shl 6

    /** Total odometer. */
    const val TOTAL_MILEAGE = 1 shl 7

    /** Remaining range estimate. */
    const val REMAINING_RANGE = 1 shl 8

    /** Average speed. */
    const val AVG_SPEED = 1 shl 9

    /** Trip distance. */
    const val TRIP_DISTANCE = 1 shl 10

    /** Trip time. */
    const val TRIP_TIME = 1 shl 11

    /**
     * What a glasses build shows when it has never received a preference, or
     * receives a mask of 0.
     *
     * Matches the historical hard-coded layout (time, phone battery, glasses
     * battery, speed, scooter battery, signal) so an un-updated phone paired
     * with an updated glasses produces no visible change.
     */
    const val DEFAULT_MASK = SPEED or SCOOTER_BATTERY or PHONE_BATTERY or
        GLASSES_BATTERY or TIME or SIGNAL_QUALITY

    /** Every field this version knows how to render. */
    const val ALL_MASK = SPEED or SCOOTER_BATTERY or PHONE_BATTERY or
        GLASSES_BATTERY or TIME or SIGNAL_QUALITY or TEMPERATURE or
        TOTAL_MILEAGE or REMAINING_RANGE or AVG_SPEED or TRIP_DISTANCE or TRIP_TIME
}
