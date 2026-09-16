package com.m365bleapp.protocol

import com.m365bleapp.repository.MotorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PlaintextTelemetryMapper].
 *
 * These cover the register→field mapping that the repository used to hold inline,
 * where it could not be tested. The two rules worth stating: an undecodable value
 * changes nothing, and each update touches only its own field.
 */
class PlaintextTelemetryMapperTest {

    private fun le16(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    private val seeded = MotorInfo(
        speed = 10.0,
        battery = 55,
        temp = 30.0,
        mileage = 123.4,
        remainingKm = 12.0,
    )

    // --------------------------------------------------------------- decode

    @Test
    fun `speed register decodes with the default scale`() {
        val update = PlaintextTelemetryMapper.decode(
            PlaintextTelemetryMapper.REG_SPEED, le16(221)
        )

        assertEquals(PlaintextTelemetryMapper.Update.Speed(22.1), update)
    }

    @Test
    fun `speed register honours the Xiaomi scale`() {
        // 100x difference: passing the wrong flag is the likeliest mistake here.
        val update = PlaintextTelemetryMapper.decode(
            register = PlaintextTelemetryMapper.REG_SPEED,
            payload = le16(22100),
            xiaomi = true,
        )

        assertEquals(PlaintextTelemetryMapper.Update.Speed(22.1), update)
    }

    @Test
    fun `an unknown register is ignored rather than throwing`() {
        // A scooter may answer registers this app does not model.
        assertSame(
            PlaintextTelemetryMapper.Update.Ignored,
            PlaintextTelemetryMapper.decode(0x99, le16(1))
        )
    }

    @Test
    fun `an empty payload is ignored even for a known register`() {
        assertSame(
            PlaintextTelemetryMapper.Update.Ignored,
            PlaintextTelemetryMapper.decode(PlaintextTelemetryMapper.REG_SPEED, ByteArray(0))
        )
    }

    @Test
    fun `a too short payload is ignored rather than decoding a partial value`() {
        // One byte cannot hold a u16 speed. Guessing would produce a wrong number.
        assertSame(
            PlaintextTelemetryMapper.Update.Ignored,
            PlaintextTelemetryMapper.decode(
                PlaintextTelemetryMapper.REG_SPEED,
                byteArrayOf(0x01),
            )
        )
    }

    @Test
    fun `error register decodes the code description and fault flag`() {
        val update = PlaintextTelemetryMapper.decode(
            PlaintextTelemetryMapper.REG_ERROR, le16(39)
        )

        val error = update as PlaintextTelemetryMapper.Update.Error
        assertEquals(39, error.code)
        assertEquals("Battery overheat", error.description)
        assertTrue(error.fault)
    }

    @Test
    fun `a healthy error code is not marked as a fault`() {
        val update = PlaintextTelemetryMapper.decode(
            PlaintextTelemetryMapper.REG_ERROR, le16(0)
        ) as PlaintextTelemetryMapper.Update.Error

        assertEquals(0, update.code)
        assertFalse(update.fault)
    }

    @Test
    fun `temperature register decodes celsius`() {
        val update = PlaintextTelemetryMapper.decode(
            PlaintextTelemetryMapper.REG_TEMPERATURE, le16(285)
        )

        assertEquals(PlaintextTelemetryMapper.Update.Temperature(28.5), update)
    }

    @Test
    fun `health register decodes a percentage`() {
        val update = PlaintextTelemetryMapper.decode(
            PlaintextTelemetryMapper.REG_HEALTH, byteArrayOf(94)
        )

        assertEquals(PlaintextTelemetryMapper.Update.Health(94), update)
    }

    @Test
    fun `decode from a frame uses the command byte as the register`() {
        val frame = FrameCodec.decode(
            FrameCodec.encodeRequest(
                protocol = FrameCodec.Protocol.P2,
                source = 0x20,
                destination = 0x3E,
                register = PlaintextTelemetryMapper.REG_SPEED.toByte(),
                argument = FrameCodec.REPLY_ARG_NINEBOT,
                payload = le16(150),
            )
        )

        assertEquals(
            PlaintextTelemetryMapper.Update.Speed(15.0),
            PlaintextTelemetryMapper.decode(frame)
        )
    }

    // ---------------------------------------------------------------- apply

    @Test
    fun `applying speed changes only the speed`() {
        val result = requireNotNull(
            PlaintextTelemetryMapper.apply(
                seeded,
                PlaintextTelemetryMapper.Update.Speed(22.1),
            )
        )

        assertEquals(22.1, result.speed, 1e-9)
        // Everything else must be untouched: a speed reply carrying a battery
        // value would mean the register mapping had shifted.
        assertEquals(seeded.battery, result.battery)
        assertEquals(seeded.mileage, result.mileage, 1e-9)
        assertEquals(seeded.remainingKm, result.remainingKm, 1e-9)
    }

    @Test
    fun `applying an ignored update returns the previous sample unchanged`() {
        // Identity, not a copy: a needless new sample would re-trigger every
        // collector and repaint the HUD for no reason.
        assertSame(seeded, PlaintextTelemetryMapper.apply(seeded, PlaintextTelemetryMapper.Update.Ignored))
    }

    @Test
    fun `applying to a null current seeds a placeholder`() {
        val result = requireNotNull(
            PlaintextTelemetryMapper.apply(
                null,
                PlaintextTelemetryMapper.Update.Speed(7.5),
            )
        )

        assertEquals(7.5, result.speed, 1e-9)
        // The extended fields stay null, so "never measured" is distinguishable
        // from a genuine zero.
        assertNull(result.errorCode)
        assertNull(result.batteryHealthPercent)
    }

    @Test
    fun `an ignored update leaves a null current as null`() {
        assertNull(PlaintextTelemetryMapper.apply(null, PlaintextTelemetryMapper.Update.Ignored))
    }

    @Test
    fun `applying temperature updates both the new and the legacy field`() {
        // An older consumer reads only `temp`, so it must stay in step.
        val result = requireNotNull(
            PlaintextTelemetryMapper.apply(
                seeded,
                PlaintextTelemetryMapper.Update.Temperature(31.5),
            )
        )

        assertEquals(31.5, requireNotNull(result.escTemperatureC), 1e-9)
        assertEquals(31.5, result.temp, 1e-9)
    }

    @Test
    fun `applying an error stores the code and description together`() {
        val result = requireNotNull(
            PlaintextTelemetryMapper.apply(
                seeded,
                PlaintextTelemetryMapper.Update.Error(45, "Battery cell deep discharge", true),
            )
        )

        assertEquals(45, result.errorCode)
        assertEquals("Battery cell deep discharge", result.errorDescription)
    }

    @Test
    fun `applying health sets only the health field`() {
        val result = requireNotNull(
            PlaintextTelemetryMapper.apply(
                seeded,
                PlaintextTelemetryMapper.Update.Health(88),
            )
        )

        assertEquals(88, result.batteryHealthPercent)
        assertEquals(seeded.speed, result.speed, 1e-9)
    }

    @Test
    fun `successive updates accumulate instead of overwriting each other`() {
        // The real loop reads one register per tick, so a mapping that replaced the
        // whole sample would blank the other fields on every tick.
        var info: MotorInfo? = seeded

        info = PlaintextTelemetryMapper.apply(info, PlaintextTelemetryMapper.Update.Speed(18.0))
        info = PlaintextTelemetryMapper.apply(info, PlaintextTelemetryMapper.Update.Temperature(26.0))
        info = PlaintextTelemetryMapper.apply(info, PlaintextTelemetryMapper.Update.Health(91))
        info = PlaintextTelemetryMapper.apply(
            info,
            PlaintextTelemetryMapper.Update.Error(11, "Phase A sensor failure", true),
        )

        val result = requireNotNull(info)
        assertEquals(18.0, result.speed, 1e-9)
        assertEquals(26.0, requireNotNull(result.escTemperatureC), 1e-9)
        assertEquals(91, result.batteryHealthPercent)
        assertEquals(11, result.errorCode)
        // The fields no update touched survive.
        assertEquals(seeded.battery, result.battery)
        assertEquals(seeded.mileage, result.mileage, 1e-9)
    }
}
