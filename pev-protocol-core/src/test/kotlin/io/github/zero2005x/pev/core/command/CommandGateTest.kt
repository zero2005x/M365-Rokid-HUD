package io.github.zero2005x.pev.core.command

import io.github.zero2005x.pev.core.identity.DeviceIdentity
import io.github.zero2005x.pev.core.identity.Family
import io.github.zero2005x.pev.core.identity.IdentitySource
import io.github.zero2005x.pev.core.telemetry.Evidence
import io.github.zero2005x.pev.core.telemetry.FieldId
import io.github.zero2005x.pev.core.telemetry.PhysUnit
import io.github.zero2005x.pev.core.telemetry.Reading
import io.github.zero2005x.pev.core.telemetry.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandGateTest {
    private val gate = CommandGate()
    private val id = DeviceIdentity(Family.BEGODE, "A2", "fw1", source = IdentitySource.USER_SELECTED)
    private val still = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to Reading.valid(0.0, 1_000, Evidence.SYNTHETIC)))

    private fun spec(evidence: Evidence = Evidence.VENDOR_STATIC, riding: Boolean = true, fw: Set<String> = emptySet()) =
        CommandSpec(
            "limit", "Speed limit", CommandScope(Family.BEGODE, setOf("A2"), fw),
            listOf(ParamSpec("kmh", PhysUnit.KMH, 10.0, 30.0)),
            evidence, ConfirmKind.NONE, RetryPolicy.NEVER, riding, "risk", listOf("synthetic"),
        )

    private fun session(auth: Boolean = true, exp: Boolean = true) =
        WriteSession("dev", auth).also { if (exp) it.enableExperimental(id) }

    private fun eval(
        s: CommandSpec = spec(),
        i: DeviceIdentity = id,
        ws: WriteSession = session(),
        p: Map<String, Double> = mapOf("kmh" to 20.0),
        t: TelemetrySnapshot = still,
        now: Long = 1_500,
    ) = gate.evaluate(s, i, ws, p, t, now)

    private fun denied(d: GateDecision) = (d as GateDecision.Denied).reason

    @Test
    fun allowsExperimentalWhenEverythingHolds() = assertEquals(GateDecision.Allowed, eval())

    @Test
    fun unverifiedWithoutOptInIsDenied() = assertTrue("opt-in" in denied(eval(ws = session(exp = false))))

    @Test
    fun verifiedCommandNeedsNoOptIn() =
        assertEquals(GateDecision.Allowed, eval(s = spec(Evidence.VEHICLE_VERIFIED), ws = session(exp = false)))

    @Test
    fun unauthenticatedIsDenied() = assertTrue("authenticated" in denied(eval(ws = session(auth = false))))

    @Test
    fun gattHintOnlyProfileIsDenied() {
        val hint = id.copy(source = IdentitySource.GATT_HINT)
        assertTrue("identified" in denied(eval(i = hint, ws = session().also { it.enableExperimental(hint) })))
    }

    @Test
    fun otherModelOrFirmwareIsDenied() {
        assertTrue("scope" in denied(eval(i = id.copy(model = "A3"))))
        assertTrue("scope" in denied(eval(s = spec(fw = setOf("fw2")))))
        assertTrue("scope" in denied(eval(s = spec(fw = setOf("fw1")), i = id.copy(firmware = null))))
        assertEquals(GateDecision.Allowed, eval(s = spec(fw = setOf("fw1"))))
    }

    @Test
    fun identityChangeRevokesExperimentalConsent() {
        val changed = id.copy(firmware = "fw9")
        assertTrue("opt-in" in denied(eval(i = changed)))
    }

    @Test
    fun explicitRevokeDeniesAgain() {
        val ws = session()
        ws.revokeExperimental()
        assertTrue("opt-in" in denied(eval(ws = ws)))
    }

    @Test
    fun paramRangeAndSetAreChecked() {
        assertTrue("range" in denied(eval(p = mapOf("kmh" to 99.0))))
        assertTrue("mismatch" in denied(eval(p = emptyMap())))
    }

    @Test
    fun unknownOrStaleSpeedBlocksRidingCommands() {
        assertTrue("unknown" in denied(eval(t = TelemetrySnapshot())))
        assertTrue("unknown" in denied(eval(now = 60_000)))
    }

    @Test
    fun movingBlocksRidingCommandsButNotCosmeticOnes() {
        val moving = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to Reading.valid(-5.0, 1_000, Evidence.SYNTHETIC)))
        assertTrue("standstill" in denied(eval(t = moving)))
        assertEquals(GateDecision.Allowed, eval(s = spec(riding = false), t = moving))
    }
}
