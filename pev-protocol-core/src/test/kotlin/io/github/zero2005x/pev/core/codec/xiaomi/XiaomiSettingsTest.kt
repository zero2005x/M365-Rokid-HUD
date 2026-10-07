package io.github.zero2005x.pev.core.codec.xiaomi

import io.github.zero2005x.pev.core.command.CommandCoordinator
import io.github.zero2005x.pev.core.command.CommandOutcome
import io.github.zero2005x.pev.core.command.CommandPlan
import io.github.zero2005x.pev.core.command.ConfirmKind
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.transport.PevTransport
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
        val be = plan(XiaomiSettings.tailLightPlan(0x0010, true, StatusWordWriteOrder.BIG_ENDIAN))
        assertArrayEquals(byteArrayOf(4, 0x20, 2, 0x7D, 0x00, 0x12), step(be))
        val le = plan(XiaomiSettings.tailLightPlan(0x0010, true, StatusWordWriteOrder.LITTLE_ENDIAN))
        assertArrayEquals(byteArrayOf(4, 0x20, 2, 0x7D, 0x12, 0x00), step(le))
    }

    @Test
    fun unitsClearsOnlyItsOwnBitAndKeepsHighByte() {
        val p = plan(XiaomiSettings.unitsPlan(0x1212, false, StatusWordWriteOrder.LITTLE_ENDIAN))
        assertArrayEquals(byteArrayOf(4, 0x20, 2, 0x7D, 0x02, 0x12), step(p))
    }

    @Test
    fun staleOrMissingWordRefusesAndNeverAssumesZero() {
        assertTrue("fresh" in refusal(XiaomiSettings.tailLightPlan(null, true, StatusWordWriteOrder.BIG_ENDIAN)))
        assertTrue("u16" in refusal(XiaomiSettings.unitsPlan(0x10000, true, StatusWordWriteOrder.BIG_ENDIAN)))
        assertTrue("u16" in refusal(XiaomiSettings.unitsPlan(-1, true, StatusWordWriteOrder.BIG_ENDIAN)))
    }

    @Test
    fun readbackAcceptsIntendedWordAndRejectsSwappedOrOtherRegister() {
        val p = plan(XiaomiSettings.tailLightPlan(0x0010, true, StatusWordWriteOrder.BIG_ENDIAN))
        val expect = p.readback!!
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
        assertTrue(e.expect(reply(0x7B, 2, 0)))
        assertTrue(!e.expect(reply(0x7B, 1, 0)))
        assertTrue(!e.expect(reply(0x7C, 2, 0)))
        assertTrue(!e.expect(ByteArray(1)))
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
        val written = mutableListOf<List<Byte>>()
        override fun write(bytes: ByteArray): Boolean { written += bytes.toList(); return true }
        override fun awaitNotify(timeoutMs: Long) = replies.removeFirstOrNull()
    }

    @Test
    fun coordinatorConfirmsViaReadbackEndToEnd() {
        val t = Scripted(ArrayDeque(listOf(reply(0x7B, 1, 0))))
        val r = CommandCoordinator(t, {}, 10).execute(plan(XiaomiSettings.kersPlan(1)))
        assertEquals(CommandOutcome.READBACK_CONFIRMED, r.outcome)
        assertEquals(2, t.written.size)
    }

    @Test
    fun wrongOrderIsReportedUnconfirmedNotSuccess() {
        // scooter stored the swapped word => readback differs from intended
        val t = Scripted(ArrayDeque(listOf(reply(0x7D, 0x00, 0x12))))
        val p = plan(XiaomiSettings.tailLightPlan(0x0010, true, StatusWordWriteOrder.BIG_ENDIAN))
        assertEquals(CommandOutcome.SENT_UNCONFIRMED, CommandCoordinator(t, {}, 10).execute(p).outcome)
    }
}
