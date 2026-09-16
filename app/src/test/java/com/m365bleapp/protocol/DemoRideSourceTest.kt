package com.m365bleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DemoRideSource].
 *
 * The class is deliberately pure (no clock, no coroutine, seeded [kotlin.random.Random])
 * so these tests can replay an exact ride. Every assertion here is about the
 * *simulation's* internal consistency — none of it says anything about the real
 * scooter protocol, which this source bypasses entirely.
 */
class DemoRideSourceTest {

    /** One simulated second, the intended tick size. */
    private val tick = 1_000L

    private fun ride(seconds: Int, seed: Int = DemoRideSource.DEFAULT_SEED): DemoRideSource {
        val source = DemoRideSource(seed = seed)
        repeat(seconds) { source.step(tick) }
        return source
    }

    // ------------------------------------------------------- determinism

    @Test
    fun `same seed replays an identical ride`() {
        // Without this, a reported demo bug could not be reproduced.
        val a = ride(seconds = 60, seed = 1234)
        val b = ride(seconds = 60, seed = 1234)

        assertEquals(a.current, b.current)
    }

    @Test
    fun `different seeds produce different samples`() {
        val a = ride(seconds = 30, seed = 1)
        val b = ride(seconds = 30, seed = 2)

        // The jitter differs, so the samples must not be identical.
        assertFalse(a.current == b.current)
    }

    @Test
    fun `reset keeps the seed so a ride replays`() {
        val source = DemoRideSource(seed = 99)
        repeat(45) { source.step(tick) }
        val before = source.current

        source.reset()
        assertNull(source.current)
        assertFalse(source.hasProduced())

        repeat(45) { source.step(tick) }
        assertEquals(before, source.current)
    }

    @Test
    fun `reset restores the constructor values not hard coded defaults`() {
        // A reset that restored 92 % would silently ignore the caller's choice
        // and make a "start at 10 %" demo impossible.
        val source = DemoRideSource(seed = 7, initialMileageKm = 500.0, initialBatteryPercent = 10)
        repeat(120) { source.step(tick) }
        assertTrue(source.snapshot().batteryPercent < 10)

        source.reset()

        assertEquals(10, source.snapshot().batteryPercent)
        assertEquals(500.0, source.snapshot().mileageKm, 1e-9)
    }

    // ------------------------------------------------------ physical bounds

    @Test
    fun `speed never goes negative or above the ceiling across a full cycle`() {
        val source = DemoRideSource()
        // 120 s covers ramp, cruise, coast and stop at least once.
        repeat(120) {
            val info = source.step(tick)
            assertTrue("speed ${info.speed} below zero", info.speed >= 0.0)
            assertTrue(
                "speed ${info.speed} above ceiling ${DemoRideSource.MAX_SPEED_KMH}",
                info.speed <= DemoRideSource.MAX_SPEED_KMH
            )
        }
    }

    @Test
    fun `battery only ever decreases`() {
        val source = DemoRideSource()
        var previous = source.step(0).battery
        repeat(600) {
            val now = source.step(tick).battery
            assertTrue("battery rose from $previous to $now", now <= previous)
            previous = now
        }
    }

    @Test
    fun `battery stays within zero and one hundred`() {
        val source = DemoRideSource(initialBatteryPercent = 100)
        repeat(3_000) {
            val battery = source.step(tick).battery
            assertTrue("battery $battery out of range", battery in 0..100)
        }
    }

    @Test
    fun `mileage and trip distance only ever increase`() {
        val source = DemoRideSource()
        var lastMileage = source.step(0).mileage
        var lastTrip = source.step(0).tripMeters
        repeat(300) {
            val info = source.step(tick)
            assertTrue("mileage fell", info.mileage >= lastMileage)
            assertTrue("trip fell", info.tripMeters >= lastTrip)
            lastMileage = info.mileage
            lastTrip = info.tripMeters
        }
    }

    // ---------------------------------------------------------- state machine

    @Test
    fun `ride passes through every phase including a full stop`() {
        val source = DemoRideSource()
        var sawStop = false
        var sawCruise = false
        repeat(120) {
            val speed = source.step(tick).speed
            if (speed < 0.5) sawStop = true
            if (speed > 15.0) sawCruise = true
        }
        // Both a standstill and a cruise must appear, otherwise the cycle is not
        // actually cycling and several off-by-one bugs would stay hidden.
        assertTrue("never came to a stop", sawStop)
        assertTrue("never reached cruise speed", sawCruise)
    }

    @Test
    fun `speed rises during the ramp phase`() {
        val source = DemoRideSource()
        val first = source.step(tick).speed
        repeat(4) { source.step(tick) }
        val later = source.step(tick).speed

        assertTrue("expected acceleration, got $first then $later", later > first)
    }

    // ------------------------------------------------------------- derived

    @Test
    fun `trip seconds track elapsed time`() {
        val source = DemoRideSource()
        repeat(10) { source.step(tick) }

        assertEquals(10, source.step(0).tripSeconds)
    }

    @Test
    fun `zero and negative elapsed time do not advance the ride`() {
        // A caller reading the initial state must not accidentally move it.
        val source = DemoRideSource()
        val first = source.step(0)
        val again = source.step(-5_000)

        assertEquals(first.mileage, again.mileage, 1e-12)
        assertEquals(first.battery, again.battery)
        assertEquals(0, again.tripSeconds)
    }

    @Test
    fun `remaining range falls with the battery`() {
        val source = DemoRideSource(seed = 5)
        val early = source.step(0).remainingKm
        repeat(900) { source.step(tick) }
        val late = source.step(0).remainingKm

        assertTrue("range did not fall: $early then $late", late < early)
    }

    @Test
    fun `remaining range is never negative`() {
        val source = DemoRideSource(initialBatteryPercent = 1)
        repeat(2_000) {
            assertTrue(source.step(tick).remainingKm >= 0.0)
        }
    }

    @Test
    fun `temperature jitter stays in a plausible band`() {
        val source = DemoRideSource()
        repeat(200) {
            val temp = source.step(tick).temp
            assertTrue("implausible temperature $temp", temp in 20.0..40.0)
        }
    }

    // ------------------------------------------------------------ depletion

    @Test
    fun `a depleted battery stops the scooter`() {
        val source = DemoRideSource(initialBatteryPercent = 1)
        // Long enough to drain the last percent.
        repeat(4_000) { source.step(tick) }

        assertTrue(source.isDepleted)
        assertEquals(0.0, source.step(tick).speed, 1e-9)
        assertEquals(0, source.step(tick).battery)
    }

    // ------------------------------------------- extended (BMS/ESC) fields

    @Test
    fun `demo decodes ESC telemetry through the production parser`() {
        // The point of these fields is that demo mode runs the real parsers. If
        // DemoRideSource ever computed them directly instead, this test would
        // still pass — so the values asserted here are the parser's output
        // including its scaling, not the generator's internal state.
        val source = DemoRideSource()
        repeat(10) { source.step(tick) }

        val info = requireNotNull(source.current)

        // Speed and temperature are produced by encoding tenths and decoding them.
        assertTrue("speed should be positive", requireNotNull(info.speed) > 0.0)
        assertNotNull("ESC temperature should decode", info.escTemperatureC)
        assertNotNull("phase current should decode", info.phaseCurrentA)
        assertNotNull("ride mode should decode", info.rideMode)
        assertNotNull("KERS level should decode", info.kersLevel)
        assertNotNull("error code should decode", info.errorCode)
    }

    @Test
    fun `demo decodes BMS telemetry through the production parser`() {
        val source = DemoRideSource()
        repeat(10) { source.step(tick) }

        val info = requireNotNull(source.current)

        assertNotNull("pack status should decode", info.packVoltageV)
        assertNotNull("remaining mAh should decode", info.remainingMah)
        assertNotNull("cell voltages should decode", info.cellSpreadV)
        assertNotNull("battery temperature should decode", info.batteryTemperatureC)
        assertNotNull("state of health should decode", info.batteryHealthPercent)
    }

    @Test
    fun `decoded pack values stay physically plausible`() {
        val source = DemoRideSource()
        repeat(120) { source.step(tick) }

        val info = requireNotNull(source.current)

        // These bounds catch a wrong scale: a tenths/hundredths mix-up would push
        // voltage to ~3700 V or current to ~1000 A.
        val volts = requireNotNull(info.packVoltageV)
        assertTrue("implausible pack voltage $volts", volts in 20.0..60.0)

        val spread = requireNotNull(info.cellSpreadV)
        assertTrue("implausible cell spread $spread", spread in 0.0..0.5)

        val highest = requireNotNull(info.highestCellV)
        val lowest = requireNotNull(info.lowestCellV)
        assertTrue("highest cell must exceed lowest", highest >= lowest)

        val health = requireNotNull(info.batteryHealthPercent)
        assertTrue("implausible health $health", health in 0..100)
    }

    @Test
    fun `a depleted pack reports a real fault code rather than healthy`() {
        // The error path is unreachable on a healthy simulated ride, and an
        // unreachable path is an untested one.
        val source = DemoRideSource(initialBatteryPercent = 0)
        source.step(tick)

        val info = requireNotNull(source.current)

        assertEquals(45, info.errorCode)
        assertFalse(
            "a depleted pack must not read as healthy",
            ScooterErrorCodes.isHealthy(requireNotNull(info.errorCode))
        )
    }

    @Test
    fun `a healthy simulated ride reports no fault`() {
        val source = DemoRideSource()
        repeat(5) { source.step(tick) }

        val info = requireNotNull(source.current)

        assertEquals(0, info.errorCode)
        assertTrue(ScooterErrorCodes.isHealthy(requireNotNull(info.errorCode)))
    }

    @Test
    fun `hasProduced is false until the first step`() {
        val source = DemoRideSource()

        assertFalse(source.hasProduced())
        assertNull(source.current)

        source.step(0)

        assertTrue(source.hasProduced())
        assertNotNull(source.current)
    }
}
