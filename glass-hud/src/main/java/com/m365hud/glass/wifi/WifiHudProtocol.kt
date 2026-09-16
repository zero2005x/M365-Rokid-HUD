package com.m365hud.glass.wifi

import com.m365hud.glass.TelemetryData
import java.io.DataInputStream
import java.io.IOException

/** TCP length prefix includes the type byte, and all payloads use the BLE wire format. */
internal object WifiHudProtocol {
    data class Frame(val type: Byte, val payload: ByteArray)

    fun readFrame(input: DataInputStream): Frame {
        val length = input.readInt()
        if (length !in 1..1024) throw IOException("Invalid HUD frame length: $length")
        val type = input.readByte()
        val payload = ByteArray(length - 1)
        input.readFully(payload)
        return Frame(type, payload)
    }

    fun telemetry(payload: ByteArray): TelemetryData? =
        TelemetryData.fromBytes(payload).takeIf { it.isValid }
}
