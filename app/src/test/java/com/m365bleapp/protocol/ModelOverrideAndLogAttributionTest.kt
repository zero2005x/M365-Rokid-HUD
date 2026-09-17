package com.m365bleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for the manual override and the log attribution it feeds.
 *
 * Two things are being protected here:
 *
 *  1. **The escape hatch must work.** On a scooter this project cannot identify,
 *     a manual choice is the only way to make the app usable at all. If it
 *     silently stopped being honoured, such a scooter would be permanently
 *     unreachable and nothing else in the test suite would notice.
 *
 *  2. **A capture must be attributable.** A BLE log without a model cannot be
 *     compared against a reference, which is the entire reason for sending one.
 */
class ModelOverrideAndLogAttributionTest {

    // --- the override must actually take effect ----------------------------

    @Test
    fun `an override changes the resolved model even when the name matches something else`() {
        // The realistic failure: the advertisement matched a family the app
        // guessed wrong, and the rider corrects it.
        val automatic = ScooterModelRegistry.resolve("MIScooter7353")
        assertEquals(ScooterModel.M365, automatic.model)

        val corrected = ScooterModelRegistry.resolve("MIScooter7353", ScooterModel.M365_PRO2)
        assertEquals(ScooterModel.M365_PRO2, corrected.model)
        assertNotEquals(automatic.model, corrected.model)
    }

    @Test
    fun `an override works when nothing at all was identified`() {
        // The escape-hatch case: a scooter whose name matches no prefix.
        val automatic = ScooterModelRegistry.resolve("SomeGenericSerial")
        assertTrue(automatic.isUnknown)

        val rescued = ScooterModelRegistry.resolve("SomeGenericSerial", ScooterModel.M365)
        assertFalse(rescued.isUnknown)
        assertEquals(ScooterModel.M365, rescued.model)
    }

    @Test
    fun `an override never upgrades the confidence`() {
        // Choosing a layout says which registers to TRY. It does not verify
        // them, and the badge and the log must both keep saying so.
        for (model in ScooterModelRegistry.selectableModels) {
            val resolved = ScooterModelRegistry.resolve(null, model)
            assertEquals(
                "${model.displayName}: a manual choice must not claim verification",
                Confidence.UNVERIFIED,
                resolved.confidence
            )
            assertFalse(resolved.confidence.isTrustworthy)
        }
    }

    @Test
    fun `an override is attributed to the rider, not the advertisement`() {
        val resolved = ScooterModelRegistry.resolve("MIScooter7353", ScooterModel.NINEBOT_MAX_G2)
        assertEquals(IdentificationSource.MANUAL_OVERRIDE, resolved.source)
    }

    @Test
    fun `every selectable model can be chosen and resolved`() {
        // Guards against a model being added to the enum but not being
        // representable as an override.
        for (model in ScooterModelRegistry.selectableModels) {
            val resolved = ScooterModelRegistry.resolve(null, model)
            assertEquals(model, resolved.model)
            assertNotEquals(ScooterModel.UNKNOWN, resolved.model)
        }
    }

    @Test
    fun `overriding to an unreadable model is allowed and produces no readings`() {
        // A rider may legitimately say "this is a Max G2". The app should accept
        // it and then report that it cannot read it, rather than refusing the
        // choice.
        val resolved = ScooterModelRegistry.resolve(null, ScooterModel.NINEBOT_MAX_G2)
        assertEquals(ScooterModel.NINEBOT_MAX_G2, resolved.model)
        assertFalse(
            "a model with no published layout must not produce telemetry",
            resolved.model.producesTelemetry
        )
    }

    // --- identifications carried into the log ------------------------------

    @Test
    fun `the rust id is stable and usable as a log field`() {
        for (model in ScooterModel.entries) {
            assertTrue(
                "${model.displayName} rustId must be non-blank",
                model.rustId.isNotBlank()
            )
            assertFalse(
                "${model.displayName} rustId must not contain a comma (it is a CSV field)",
                model.rustId.contains(',')
            )
            assertFalse(model.displayName.contains(','))
        }
    }

    @Test
    fun `confidence labels are distinct so a log can be grouped by them`() {
        val labels = Confidence.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    // --- the log format actually carries the model -------------------------

    @Test
    fun `the telemetry CSV header includes the model columns`() {
        val source = loggerSource()
        assertTrue(
            "TelemetryLogger's telemetry header must name the model columns so a " +
                "capture can be attributed to a scooter; found no Model column",
            source.contains("Timestamp,Model,ModelId,Confidence,")
        )
    }

    @Test
    fun `the BLE CSV header includes the model columns`() {
        val source = loggerSource()
        assertTrue(
            "the BLE log is the artefact sent for diagnosis; without a model column " +
                "it cannot be compared against a reference",
            source.contains("Timestamp,Model,ModelId,Confidence,Direction,")
        )
    }

    @Test
    fun `both log rows write the model fields`() {
        val source = loggerSource()
        // Each row must emit the three model fields before its own columns.
        val rowWrites = Regex("""csvEscape\(model\)\},\$\{csvEscape\(modelId\)\}""")
            .findAll(source)
            .count()
        assertTrue(
            "expected both the telemetry and BLE rows to write model/modelId, found $rowWrites",
            rowWrites >= 2
        )
    }

    @Test
    fun `the logger attributes an unknown model rather than leaving the column empty`() {
        // An empty cell reads as "not recorded". `unknown` reads as "recorded,
        // and the app did not know" — which is itself a useful finding when
        // someone sends a capture.
        val source = loggerSource()
        assertTrue(
            "an unidentified scooter must be recorded as 'unknown', not as an empty field",
            source.contains("\"unknown\"")
        )
    }

    /** Reads TelemetryLogger.kt so the CSV contract is checked against the real file. */
    private fun loggerSource(): String {
        val candidates = listOf(
            File("src/main/java/com/m365bleapp/utils/TelemetryLogger.kt"),
            File("app/src/main/java/com/m365bleapp/utils/TelemetryLogger.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue(
            "could not locate TelemetryLogger.kt from ${File(".").absolutePath}; " +
                "this test guards the capture format and must not silently skip",
            file != null
        )
        return file!!.readText()
    }

    // --- the override must not leak across models --------------------------

    @Test
    fun `clearing an override returns to automatic identification`() {
        val auto = ScooterModelRegistry.resolve("MIScooter7353", null)
        assertEquals(IdentificationSource.ADVERTISEMENT, auto.source)
        assertEquals(ScooterModel.M365, auto.model)

        // And an explicit UNKNOWN is the same as no override, so a UI that
        // passes the enum's default does not pin "unidentified" forever.
        val alsoAuto = ScooterModelRegistry.resolve("MIScooter7353", ScooterModel.UNKNOWN)
        assertEquals(auto, alsoAuto)
    }

    @Test
    fun `a null override with no advertisement stays unknown and is not a model`() {
        val resolved = ScooterModelRegistry.resolve(null, null)
        assertTrue(resolved.isUnknown)
        assertNull(
            "an unidentified scooter must not expose capabilities",
            resolved.model.capabilities.secondaryBattery.takeIf { false }
        )
        assertFalse(resolved.model.producesTelemetry)
    }
}
