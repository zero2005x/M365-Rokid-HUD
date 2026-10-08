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
    private val still = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to Reading.valid(0.0, 1_000, Evidence.WIRE_CAPTURED, "capture:stationary")))

    private fun spec(evidence: Evidence = Evidence.VENDOR_STATIC, riding: Boolean = true, fw: Set<String> = emptySet()) =
        CommandSpec(
            "limit", "Speed limit", CommandScope(Family.BEGODE, setOf("A2"), fw),
            listOf(ParamSpec("kmh", PhysUnit.KMH, 10.0, 30.0)),
            evidence, ConfirmKind.NONE, RetryPolicy.NEVER, riding, "risk", listOf("synthetic"),
        )

    private fun session(auth: Boolean = true, exp: Boolean = true) =
        WriteSession("dev", "connection1", auth).also { if (exp) it.enableExperimental(id) }

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
        val moving = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to Reading.valid(-5.0, 1_000, Evidence.WIRE_CAPTURED, "capture:moving")))
        assertTrue("standstill" in denied(eval(t = moving)))
        assertEquals(GateDecision.Allowed, eval(s = spec(riding = false), t = moving))
    }
    @Test
    fun returningToOldProfileDoesNotRestoreConsent() {
        val ws = session()
        eval(i = id.copy(firmware = "fw9"), ws = ws)
        assertTrue("opt-in" in denied(eval(ws = ws)))
    }

    @Test
    fun packAndSelectionSourceChangesRevokeConsent() {
        val ws = session()
        eval(i = id.copy(packParams = mapOf("cells" to "20")), ws = ws)
        assertTrue("opt-in" in denied(eval(ws = ws)))
        val sourceSession = session()
        eval(i = id.copy(source = IdentitySource.IN_BAND_QUERY), ws = sourceSession)
        assertTrue("opt-in" in denied(eval(ws = sourceSession)))
    }

    @Test
    fun syntheticCommandsAndMissingSourcesCannotBeOptedIn() {
        assertTrue("evidence" in denied(eval(s = spec(Evidence.SYNTHETIC))))
        assertTrue("evidence" in denied(eval(s = spec().copy(sourceRefs = emptyList()))))
    }

    @Test
    fun speedRequiresTimestampSourceAndObservedPhysicalEvidence() {
        val r = still[FieldId.SPEED_KMH]
        val invalid = listOf(r.copy(observedAtMs = null), r.copy(observedAtMs = 1_501),
            r.copy(source = null), r.copy(evidence = Evidence.SYNTHETIC),
            r.copy(evidence = Evidence.VENDOR_STATIC), r.copy(value = Double.NaN))
        invalid.forEach { assertTrue("unknown" in denied(eval(t = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to it))))) }
    }

    @Test fun invalidGateConfigurationRejected() {
        for (config in listOf(-1L to 0.5, 0L to -0.5, 0L to Double.NaN, 0L to Double.POSITIVE_INFINITY)) {
            try { CommandGate(config.first, config.second); throw AssertionError("invalid safety configuration accepted") }
            catch (_: IllegalArgumentException) { /* constructor refused it */ }
        }
    }

    @Test fun blankSourcesInvalidSpeedAndLowerParameterBoundAreRejected() {
        assertTrue("evidence" in denied(eval(s = spec().copy(sourceRefs = listOf(" ")))))
        assertTrue("range" in denied(eval(p = mapOf("kmh" to -1.0))))
        val r = still[FieldId.SPEED_KMH]
        for (invalid in listOf(r.copy(value = null), r.copy(state = io.github.zero2005x.pev.core.telemetry.FieldState.STALE),
            r.copy(observedAtMs = -1), r.copy(source = ""))) {
            assertTrue("unknown" in denied(eval(t = TelemetrySnapshot(mapOf(FieldId.SPEED_KMH to invalid)))))
        }
    }

}
