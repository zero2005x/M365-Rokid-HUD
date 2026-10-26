package com.m365bleapp.protocol

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDeviceStreamSessionsTest {

    private fun frame(type: Int = 0, discriminator: Int = 24, vararg words: Int): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 0x55
        bytes[1] = 0xAA.toByte()
        for (slot in 0..7) {
            val word = words.getOrElse(slot) { slot + 1 }
            bytes[2 + slot * 2] = (word ushr 8).toByte()
            bytes[3 + slot * 2] = word.toByte()
        }
        bytes[18] = type.toByte()
        bytes[19] = discriminator.toByte()
        for (offset in 20..23) bytes[offset] = 0x5A
        return bytes
    }

    @Test
    fun `begode session parses GW device info and live telemetry and ages stale readings`() {
        val session = BegodeA2Session(inputEvidence = Evidence.SYNTHETIC)

        val gwUpdate = session.onNotification("GW1511014".toByteArray(Charsets.US_ASCII), nowMs = 1_000L)
        assertTrue(gwUpdate is BegodeA2Session.Update.DeviceInfo)
        assertEquals("GW1511014", (gwUpdate as BegodeA2Session.Update.DeviceInfo).ascii)

        val rawTempWord = (-1600) and 0xFFFF
        val liveBytes = frame(0, 24, 6120, 0, 30, 0, 0, rawTempWord, 137, 1)
        val liveUpdate = session.onNotification(liveBytes, nowMs = 2_000L)
        assertTrue(session.isReady)
        assertTrue(liveUpdate is BegodeA2Session.Update.Telemetry)
        val telemetryUpdate = liveUpdate as BegodeA2Session.Update.Telemetry
        assertTrue(telemetryUpdate.newlyReady)
        assertEquals(1, telemetryUpdate.framesInChunk)
        val info = telemetryUpdate.motorInfo
        assertEquals(61.20, info.packVoltageV!!, 0.001)
        assertEquals(-1600 / 340.0 + 36.53, info.temp, 0.001)
        assertEquals(-1, info.battery)
        assertTrue(info.speed.isNaN())
        assertEquals(FieldState.VALID, info.typedFields!![FieldId.PACK_VOLTAGE]!!.state)
        assertEquals(FieldState.VALID, info.typedFields!![FieldId.TEMP_IMU]!!.state)

        // Still fresh at 2_800ms (STALE_AFTER_MS = 1_000ms)
        assertNull(session.ageIfStale(nowMs = 2_800L))

        // Stale at 3_500ms
        val staleInfo = session.ageIfStale(nowMs = 3_500L)
        assertNotNull(staleInfo)
        assertNull(staleInfo!!.packVoltageV)
        assertTrue(staleInfo.temp.isNaN())
        assertEquals(FieldState.STALE, staleInfo.typedFields!![FieldId.PACK_VOLTAGE]!!.state)
        assertEquals(FieldState.STALE, staleInfo.typedFields!![FieldId.TEMP_IMU]!!.state)
    }
}
