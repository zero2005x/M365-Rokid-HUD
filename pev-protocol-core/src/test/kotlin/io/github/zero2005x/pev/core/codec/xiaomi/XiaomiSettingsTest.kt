package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.command.CommandCoordinator
import io.github.zero2005x.pev.core.command.CommandOutcome
import io.github.zero2005x.pev.core.command.CommandPlan
import io.github.zero2005x.pev.core.command.ConfirmKind
import io.github.zero2005x.pev.core.command.CommandExecutionContext
import io.github.zero2005x.pev.core.command.CommandBinding
import io.github.zero2005x.pev.core.command.CurrentWriteState
import io.github.zero2005x.pev.core.command.WriteSession
import io.github.zero2005x.pev.core.identity.DeviceIdentity
import io.github.zero2005x.pev.core.identity.Family
import io.github.zero2005x.pev.core.identity.IdentitySource
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.FieldState
import io.github.zero2005x.pev.core.telemetry.Reading
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot
import io.github.zero2005x.pev.core.transport.PevTransport
import io.github.zero2005x.pev.core.transport.TransportNotification
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiSettingsTest {
    private fun plan(r: PlanResult) = (r as PlanResult.Ok).plan
    private fun refusal(r: PlanResult) = (r as PlanResult.Refused).reason
    private fun step(p: CommandPlan) = p.steps.single().bytes
    private fun reply(reg: Int, vararg data: Int) =
        byteArrayOf(0x23, 1, reg.toByte()) + data.map { it.toByte() }.toByteArray() + ByteArray(4)
    private fun identity() = DeviceIdentity(Family.XIAOMI_SCOOTER, "M365", source = IdentitySource.USER_SELECTED)
    private fun observed(raw: Int) = XiaomiStatusWordObservation(
        XiaomiEscDecoder.statusWord(byteArrayOf(raw.toByte(), (raw ushr 8).toByte()), 10),
        CommandBinding("m365-owner", "session-1", identity().profileKey),
    )

    @Test
    fun kersWritesLevelThenZero() {
        assertArrayEquals(byteArrayOf(4, 0x20, 2, 0x7B, 1, 0), step(plan(XiaomiSettings.kersPlan(1))))
        assertEquals(ConfirmKind.READBACK, plan(XiaomiSettings.kersPlan(0)).spec.confirm)
    }

    @Test
    fun kersOutOfRangeRefused() {
        assertTrue("range" in refusal(XiaomiSettings.kersPlan(3)))
        assertTrue("range" in refusal(XiaomiSettings.kersPlan(-1)))
    }

    @Test
    fun cruiseOnOff() {
        assertArrayEquals(byteArrayOf(4, 0x20, 2, 0x7C, 1, 0), step(plan(XiaomiSettings.cruisePlan(true))))
        assertArrayEquals(byteArrayOf(4, 0x20, 2, 0x7C, 0, 0), step(plan(XiaomiSettings.cruisePlan(false))))
    }

    @Test
    fun tailLightPreservesNeighbourBitAndHonoursOrder() {
        // current word has mph bit (0x10) set; turning tail light on must keep it => 0x0012
        val be = plan(XiaomiSettings.tailLightPlan(observed(0x0010), true, StatusWordWriteOrder.BIG_ENDIAN, 10))
        assertArrayEquals(byteArrayOf(4, 0x20, 2, 0x7D, 0x00, 0x12), step(be))
        val le = plan(XiaomiSettings.tailLightPlan(observed(0x0010), true, StatusWordWriteOrder.LITTLE_ENDIAN, 10))
        assertArrayEquals(byteArrayOf(4, 0x20, 2, 0x7D, 0x12, 0x00), step(le))
    }

    @Test
    fun unitsClearsOnlyItsOwnBitAndKeepsHighByte() {
        val p = plan(XiaomiSettings.unitsPlan(observed(0x1212), false, StatusWordWriteOrder.LITTLE_ENDIAN, 10))
        assertArrayEquals(byteArrayOf(4, 0x20, 2, 0x7D, 0x02, 0x12), step(p))
    }

    @Test
    fun staleOrMissingWordRefusesAndNeverAssumesZero() {
        assertTrue("fresh" in refusal(XiaomiSettings.tailLightPlan(null, true, StatusWordWriteOrder.BIG_ENDIAN, 10)))
        val invalidWord = observed(0).let { it.copy(reading = it.reading.copy(value = XiaomiEscDecoder.StatusWord(0x10000))) }
        assertTrue("u16" in refusal(XiaomiSettings.unitsPlan(invalidWord, true, StatusWordWriteOrder.BIG_ENDIAN, 10)))
        assertTrue("fresh" in refusal(XiaomiSettings.unitsPlan(observed(0), true, StatusWordWriteOrder.BIG_ENDIAN, 2011)))
    }

    @Test
    fun readbackAcceptsIntendedWordAndRejectsSwappedOrOtherRegister() {
        val p = plan(XiaomiSettings.tailLightPlan(observed(0x0010), true, StatusWordWriteOrder.BIG_ENDIAN, 10))
        val expect = p.readback!!
        assertTrue(!expect.expect(reply(0x7D, 0x12, 0, 0)))
        assertTrue(!expect.expect(reply(0x7D, 0x12, 0).also { it[0] = 0x25 }))
        assertTrue(!expect.expect(reply(0x7D, 0x12, 0).also { it[1] = 2 }))
        assertArrayEquals(byteArrayOf(3, 0x20, 1, 0x7D, 2), expect.request)
        assertTrue(expect.expect(reply(0x7D, 0x12, 0x00)))
        assertTrue(!expect.expect(reply(0x7D, 0x00, 0x12)))
        assertTrue(!expect.expect(reply(0x7C, 0x12, 0x00)))
        assertTrue(!expect.expect(ByteArray(2)))
        assertTrue(!expect.expect(reply(0x7D, 0x12)))
    }

    @Test
    fun byteReadbackChecksRegisterAndValue() {
        val e = plan(XiaomiSettings.kersPlan(2)).readback!!
        assertTrue(!e.expect(reply(0x7B, 2, 0, 0)))
        assertTrue(e.expect(reply(0x7B, 2, 0)))
        assertTrue(!e.expect(reply(0x7B, 1, 0)))
        assertTrue(!e.expect(reply(0x7C, 2, 0)))
        assertTrue(!e.expect(ByteArray(1)))
        assertTrue(!e.expect(reply(0x7B, 2)))
        assertTrue(!e.expect(reply(0x7B, 2, 1)))
        val foreignSource = reply(0x7B, 2, 0).also { it[0] = 0x25 }
        assertTrue(!e.expect(foreignSource))
        val writeEcho = reply(0x7B, 2, 0).also { it[1] = 2 }
        assertTrue(!e.expect(writeEcho))
    }

    @Test
    fun specsAreExperimentalOnlyAndM365Scoped() {
        for (s in XiaomiSettings.ALL) {
            assertEquals(Evidence.VENDOR_STATIC, s.evidence)
            assertEquals(setOf("M365"), s.scope.models)
        }
        assertTrue(XiaomiSettings.KERS.affectsRiding && XiaomiSettings.CRUISE.affectsRiding)
        assertTrue(!XiaomiSettings.TAIL_LIGHT.affectsRiding && !XiaomiSettings.UNITS.affectsRiding)
    }

    private class Scripted(val replies: ArrayDeque<ByteArray?>) : PevTransport {
        override val mtu = 20
        override val connected = true
        override val deviceId = "m365-owner"
        override val connectionId = "session-1"
        override var notificationSequence = 0L
        val written = mutableListOf<List<Byte>>()
        override fun write(bytes: ByteArray): Boolean { written += bytes.toList(); return true }
        override fun writeForConnection(bytes: ByteArray, expectedConnectionId: String): Boolean =
            expectedConnectionId == connectionId && write(bytes)
        override fun awaitNotify(timeoutMs: Long) = replies.removeFirstOrNull()
        override fun awaitNotifyAfter(timeoutMs: Long, afterSequence: Long): TransportNotification? =
            replies.removeFirstOrNull()?.let { TransportNotification(++notificationSequence, connectionId, it) }
    }

    private fun context(p: CommandPlan): CommandExecutionContext {
        val identity = identity()
        val session = WriteSession("m365-owner", "session-1", true).also { it.enableExperimental(identity) }
        val speed = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to Reading.valid(0.0, 10, Evidence.WIRE_CAPTURED, "xiaomi.B0")))
        val state = CurrentWriteState("m365-owner", identity, session, p.parameterValues, speed, 10)
        return CommandExecutionContext(state) { state }
    }

    @Test
    fun coordinatorConfirmsViaReadbackEndToEnd() {
        val t = Scripted(ArrayDeque(listOf(reply(0x7B, 1, 0))))
        val p = plan(XiaomiSettings.kersPlan(1))
        val r = CommandCoordinator(t, {}, 10).execute(p, context(p))
        assertEquals(CommandOutcome.READBACK_CONFIRMED, r.outcome)
        assertEquals(2, t.written.size)
    }

    @Test
    fun wrongOrderIsReportedUnconfirmedNotSuccess() {
        // scooter stored the swapped word => readback differs from intended
        val t = Scripted(ArrayDeque(listOf(reply(0x7D, 0x00, 0x12))))
        val p = plan(XiaomiSettings.tailLightPlan(observed(0x0010), true, StatusWordWriteOrder.BIG_ENDIAN, 10))
        assertEquals(CommandOutcome.TIMEOUT, CommandCoordinator(t, {}, 10).execute(p, context(p)).outcome)
    }

    @Test fun wordObservationMustBeValidKnownAndNonfuture() {
        val valid = observed(0)
        val reading = valid.reading
        for (invalidReading in listOf(reading.copy(state = FieldState.STALE), reading.copy(value = null),
            reading.copy(evidence = Evidence.SYNTHETIC), reading.copy(source = "other"),
            reading.copy(observedAtMs = -1), reading.copy(observedAtMs = 11))) {
            val invalid = valid.copy(reading = invalidReading)
            assertTrue(XiaomiSettings.unitsPlan(invalid, true, StatusWordWriteOrder.LITTLE_ENDIAN, 10) is PlanResult.Refused)
        }
        assertEquals(2010L, plan(XiaomiSettings.unitsPlan(valid, true, StatusWordWriteOrder.LITTLE_ENDIAN, 2010)).validUntilMs)
    }

    @Test fun statusReadCannotCrossDeviceSessionOrProfile() {
        val initial = observed(0)
        for (binding in listOf(initial.binding.copy(deviceId = "other"), initial.binding.copy(connectionId = "old-session"),
            initial.binding.copy(profileKey = "other-profile"))) {
            val p = plan(XiaomiSettings.unitsPlan(initial.copy(binding = binding), true, StatusWordWriteOrder.LITTLE_ENDIAN, 10))
            val transport = Scripted(ArrayDeque())
            assertEquals(CommandOutcome.REJECTED, CommandCoordinator(transport).execute(p, context(p)).outcome)
            assertTrue(transport.written.isEmpty())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun payloadEncoderRejectsUnrelatedBits() { XiaomiSettings.statusWordPayload(0, 0x80, true, StatusWordWriteOrder.LITTLE_ENDIAN) }

    @Test(expected = IllegalArgumentException::class)
    fun payloadEncoderRejectsOutOfRangeWord() { XiaomiSettings.statusWordPayload(-1, 2, true, StatusWordWriteOrder.LITTLE_ENDIAN) }
}
