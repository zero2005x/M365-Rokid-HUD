package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiMotorInfoDecoderTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // wire-captured, owner's M365 MIScooter8964, hardware-evidence/logcat-spin-raw.txt (sha256 E92CF09A...),
    // 2026-09-20 14:30:39 decrypted reply data (stationary, wheel off ground).
    private val stationary = hex("0000000000080000330000000000eb1a06000000eb0036010000000000000000")

    // wire-captured, same file, 2026-09-20 14:34:02 raw MotorInfo block; speed bytes 76 EA (i16 = -5514 m/h).
    private val negativeSpeed = hex("0000000000080000330076ea0000101b06000000640036010000000000000000")

    @Test
    fun decodesCapturedStationaryBlock() {
        val s = XiaomiMotorInfoDecoder.decode(stationary, 100)
        assertEquals(51.0, s[FieldId.SOC_PERCENT].value!!, 0.0)
        assertEquals(0.0, s[FieldId.SPEED_KMH].value!!, 0.0)
        assertEquals(400107.0, s[FieldId.TOTAL_DISTANCE_M].value!!, 0.0)
        assertEquals(31.0, s[FieldId.TEMP_FRAME].value!!, 1e-9)
        assertEquals(Evidence.WIRE_CAPTURED, s[FieldId.SPEED_KMH].evidence)
    }

    @Test
    fun speedStaysSignedAsDecoded() {
        val s = XiaomiMotorInfoDecoder.decode(negativeSpeed, 100)
        assertEquals(-5.514, s[FieldId.SPEED_KMH].value!!, 1e-9)
        assertEquals(400144.0, s[FieldId.TOTAL_DISTANCE_M].value!!, 0.0)
    }

    @Test
    fun forwardSpeedAbove32kmhIsNotWrappedNegative() {
        val b = stationary.copyOf()
        b[10] = 0x10; b[11] = 0x27 // 10000 m/h
        assertEquals(10.0, XiaomiMotorInfoDecoder.decode(b, 1)[FieldId.SPEED_KMH].value!!, 1e-9)
    }

    @Test
    fun shortPayloadIsInvalidNotZero() {
        val s = XiaomiMotorInfoDecoder.decode(ByteArray(10), 5)
        for (id in listOf(FieldId.SOC_PERCENT, FieldId.SPEED_KMH, FieldId.TOTAL_DISTANCE_M, FieldId.TEMP_FRAME)) {
            assertEquals(FieldState.INVALID, s[id].state)
            assertNull(s[id].value)
        }
        assertEquals(FieldState.NOT_PROVIDED, s[FieldId.PACK_VOLTAGE].state)
    }

    @Test
    fun socOutOfRangeIsInvalid() {
        val b = stationary.copyOf()
        b[8] = 0xFF.toByte()
        assertEquals(FieldState.INVALID, XiaomiMotorInfoDecoder.decode(b, 1)[FieldId.SOC_PERCENT].state)
    }

    @Test
    fun negativeTemperatureKeepsSign() {
        val b = stationary.copyOf()
        b[22] = 0x9C.toByte(); b[23] = 0xFF.toByte() // -100 => -10.0 C
        assertEquals(-10.0, XiaomiMotorInfoDecoder.decode(b, 1)[FieldId.TEMP_FRAME].value!!, 1e-9)
    }
}
