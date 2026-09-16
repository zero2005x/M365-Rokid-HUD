package com.m365bleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for the Kotlin-side model registry.
 *
 * The most important test here is [rust model ids match the Rust registry]:
 * the Rust crate is the authority for register layouts, this enum exists so the
 * UI can name a model without a JNI round trip, and the two are edited by hand.
 * Without a check they drift, and a drifted id means the UI shows one scooter's
 * name while the Rust layer decodes another's registers.
 */
class ScooterModelRegistryTest {

    // --- the two registries must agree -------------------------------------

    @Test
    fun `rust model ids match the Rust registry`() {
        // Locate the Rust source relative to this module. The test runs with the
        // module as the working directory.
        val candidates = listOf(
            File("../ninebot-ble/src/model/mod.rs"),
            File("ninebot-ble/src/model/mod.rs"),
        )
        val source = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "could not locate ninebot-ble/src/model/mod.rs from ${File(".").absolutePath}; " +
                "this test guards the Kotlin/Rust model-id mapping and must not silently skip",
            source
        )

        val text = source!!.readText()
        // The ModelId enum body: from `pub enum ModelId {` to its closing brace.
        val enumBody = text
            .substringAfter("pub enum ModelId {")
            .substringBefore("\n}")

        val rustVariants = Regex("""^\s{2}([A-Za-z0-9_]+),""", RegexOption.MULTILINE)
            .findAll(enumBody)
            .map { it.groupValues[1] }
            .toSet()

        assertTrue("failed to parse any ModelId variants", rustVariants.isNotEmpty())

        val kotlinModels = ScooterModel.entries
            .filter { it != ScooterModel.UNKNOWN }
            // The Ninebot/Segway families are recognised for display but have no
            // Rust ModelId yet, because there is no register map to describe.
            .filter { it.rustId !in NINEBOT_ONLY_IDS }
            .map { it.rustId }
            .toSet()

        val missingOnRust = kotlinModels - rustVariants
        assertTrue(
            "these ScooterModel.rustId values have no ModelId in Rust: $missingOnRust. " +
                "Either add them to ninebot-ble/src/model/mod.rs or to NINEBOT_ONLY_IDS.",
            missingOnRust.isEmpty()
        )

        val missingOnKotlin = rustVariants - kotlinModels
        assertTrue(
            "these Rust ModelId variants have no ScooterModel: $missingOnKotlin. " +
                "The UI cannot name them, so the badge would read 'Unidentified'.",
            missingOnKotlin.isEmpty()
        )
    }

    @Test
    fun `rust ids are unique`() {
        val ids = ScooterModel.entries.map { it.rustId }
        assertEquals(
            "duplicate rustId values would make the two registries ambiguous",
            ids.size,
            ids.toSet().size
        )
    }

    @Test
    fun `display names are unique`() {
        val names = ScooterModel.entries.map { it.displayName }
        assertEquals(names.size, names.toSet().size)
    }

    // --- identification ----------------------------------------------------

    @Test
    fun `an M365 name is identified but only as unverified`() {
        val result = ScooterModelRegistry.fromAdvertisementName("MIScooter7353")
        assertNotNull(result)
        assertEquals(Confidence.UNVERIFIED, result!!.second)
    }

    @Test
    fun `identification is case insensitive`() {
        val lower = ScooterModelRegistry.fromAdvertisementName("miscooter1234")
        val upper = ScooterModelRegistry.fromAdvertisementName("MISCOOTER1234")
        assertNotNull(lower)
        assertEquals(lower, upper)
    }

    @Test
    fun `an unknown name yields null rather than a default model`() {
        // Defaulting to a model would make the badge claim something the
        // advertisement never said.
        assertNull(ScooterModelRegistry.fromAdvertisementName("JBL Speaker"))
        assertNull(ScooterModelRegistry.fromAdvertisementName(""))
        assertNull(ScooterModelRegistry.fromAdvertisementName(null))
    }

    @Test
    fun `a longer prefix wins over a shorter one`() {
        // "NB" and "NBScooter" both exist as prefixes; the specific one must win
        // or every Ninebot would collapse to the same model.
        val result = ScooterModelRegistry.fromAdvertisementName("NBScooter1234")
        assertNotNull("an NB-prefixed name should match something", result)
    }

    // --- override ----------------------------------------------------------

    @Test
    fun `a manual override beats the advertisement`() {
        val resolved = ScooterModelRegistry.resolve(
            advertisedName = "MIScooter7353",
            override = ScooterModel.NINEBOT_MAX_G2,
        )
        assertEquals(ScooterModel.NINEBOT_MAX_G2, resolved.model)
        assertEquals(IdentificationSource.MANUAL_OVERRIDE, resolved.source)
    }

    @Test
    fun `a manual override is still unverified`() {
        // Choosing a model says which layout to TRY. It does not make the layout
        // correct, and the badge must not imply otherwise.
        val resolved = ScooterModelRegistry.resolve(null, ScooterModel.M365)
        assertEquals(Confidence.UNVERIFIED, resolved.confidence)
        assertFalse(resolved.confidence.isTrustworthy)
    }

    @Test
    fun `an override of UNKNOWN is treated as no override`() {
        // UNKNOWN is the absence of a choice, not a choice.
        val resolved = ScooterModelRegistry.resolve("MIScooter7353", ScooterModel.UNKNOWN)
        assertEquals(ScooterModel.M365, resolved.model)
        assertEquals(IdentificationSource.ADVERTISEMENT, resolved.source)
    }

    @Test
    fun `with no name and no override the model is unknown`() {
        val resolved = ScooterModelRegistry.resolve(null, null)
        assertEquals(ScooterModel.UNKNOWN, resolved.model)
        assertTrue(resolved.isUnknown)
        assertEquals(IdentificationSource.NONE, resolved.source)
    }

    // --- badges ------------------------------------------------------------

    @Test
    fun `a badge never claims more confidence than it has`() {
        val unknown = Identification(ScooterModel.UNKNOWN, Confidence.UNVERIFIED, IdentificationSource.NONE)
        assertEquals("Unidentified", unknown.badgeText)

        val guessed = Identification(ScooterModel.M365, Confidence.UNVERIFIED, IdentificationSource.ADVERTISEMENT)
        assertTrue("a guess must be labelled: ${guessed.badgeText}", guessed.badgeText.contains("Unverified"))

        val verified = Identification(ScooterModel.M365, Confidence.VERIFIED, IdentificationSource.ADVERTISEMENT)
        assertEquals("Xiaomi M365", verified.badgeText)
    }

    @Test
    fun `no model in the registry claims to be verified`() {
        // Nothing has been hardware-confirmed. If a capture changes that,
        // promote the specific model and update this test.
        for (model in ScooterModel.entries) {
            val resolved = ScooterModelRegistry.resolve(null, model)
            assertFalse(
                "${model.displayName} must not be presented as verified",
                resolved.confidence == Confidence.VERIFIED
            )
        }
    }

    // --- capabilities ------------------------------------------------------

    @Test
    fun `an unidentified model can produce no telemetry`() {
        val caps = ScooterModel.UNKNOWN.capabilities
        assertFalse("an unidentified scooter must not claim speed", caps.speed)
        assertFalse("an unidentified scooter must not claim battery", caps.batteryPercent)
        assertFalse(ScooterModel.UNKNOWN.producesTelemetry)
    }

    @Test
    fun `models with no verified register layout produce no telemetry`() {
        // These are recognisable but unreadable. Their capabilities must be off,
        // or the UI would render zeros that look like readings.
        for (model in listOf(
            ScooterModel.MI3,
            ScooterModel.NINEBOT_ESX,
            ScooterModel.NINEBOT_MAX_G30,
            ScooterModel.NINEBOT_E_SERIES,
            ScooterModel.NINEBOT_F_SERIES,
            ScooterModel.NINEBOT_T15,
            ScooterModel.NINEBOT_MAX_G2,
            ScooterModel.NINEBOT_F2,
            ScooterModel.NINEBOT_D_SERIES,
        )) {
            assertFalse(
                "${model.displayName} has no published register layout and must not claim telemetry",
                model.producesTelemetry
            )
        }
    }

    @Test
    fun `the verified Xiaomi models do claim telemetry`() {
        for (model in listOf(
            ScooterModel.M365,
            ScooterModel.M365_PRO,
            ScooterModel.M365_PRO2,
            ScooterModel.MI_1S,
            ScooterModel.MI_LITE,
        )) {
            assertTrue("${model.displayName} should produce telemetry", model.producesTelemetry)
        }
    }

    @Test
    fun `no model claims a second battery yet`() {
        // The M365 family has one pack. Ninebot ESx has an external one, but its
        // layout is unverified, so no model may claim it — and the UI hides the
        // row entirely rather than showing 0%.
        for (model in ScooterModel.entries) {
            assertFalse(
                "${model.displayName} claims a secondary battery but no layout for one is verified",
                model.capabilities.secondaryBattery
            )
        }
    }

    @Test
    fun `capabilities NONE switches off everything requiring a register read`() {
        val none = ModelCapabilities.NONE
        assertFalse(none.speed)
        assertFalse(none.batteryPercent)
        assertFalse(none.secondaryBattery)
        assertFalse(none.temperature)
        assertFalse(none.totalMileage)
        assertFalse(none.remainingRange)
        assertFalse(none.tripDistance)
        assertFalse(none.tripTime)
        assertFalse(none.tailLight)
        assertFalse(none.motorLock)
    }

    @Test
    fun `a model never offers a control it cannot support`() {
        // The dashboard gates its lock and tail-light buttons on these flags. A
        // model that claims a control without claiming the telemetry that would
        // confirm it is connected would render a button that writes to a register
        // nothing has verified — and a motor lock is safety-relevant while the
        // scooter may be moving.
        for (model in ScooterModel.entries) {
            val caps = model.capabilities
            if (caps.motorLock || caps.tailLight) {
                assertTrue(
                    "${model.displayName} offers a control but reports no telemetry; " +
                        "an unverified write path must not be reachable",
                    model.producesTelemetry
                )
            }
        }
    }

    @Test
    fun `the M365 keeps its controls so existing users lose nothing`() {
        // Regression guard: resolving capabilities from a null advertised name
        // (rather than the connected scooter's name) would classify every scooter
        // as unidentified and silently remove the lock and light controls from
        // the dashboard for M365 owners.
        val resolved = ScooterModelRegistry.resolve("MIScooter7353")
        assertTrue("an M365 by name must resolve to a known model", !resolved.isUnknown)
        assertTrue(
            "the M365 must still expose its lock control; removing it is a user-visible regression",
            resolved.model.capabilities.motorLock
        )
        assertTrue(resolved.model.capabilities.tailLight)
    }

    @Test
    fun `an unidentified scooter offers no controls at all`() {
        val resolved = ScooterModelRegistry.resolve("SomeGenericSerial")
        assertTrue(resolved.isUnknown)
        assertFalse(resolved.model.capabilities.motorLock)
        assertFalse(resolved.model.capabilities.tailLight)
    }

    // --- picker ------------------------------------------------------------

    @Test
    fun `the override picker excludes UNKNOWN`() {
        assertFalse(ScooterModelRegistry.selectableModels.contains(ScooterModel.UNKNOWN))
        assertEquals(ScooterModel.entries.size - 1, ScooterModelRegistry.selectableModels.size)
    }

    @Test
    fun `every selectable model has a display name`() {
        for (model in ScooterModelRegistry.selectableModels) {
            assertTrue(model.displayName.isNotBlank())
        }
    }

    // --- confidence --------------------------------------------------------

    @Test
    fun `confidence ordering runs from least to most trusted`() {
        assertTrue(Confidence.VERIFIED > Confidence.DOCUMENTED)
        assertTrue(Confidence.DOCUMENTED > Confidence.UNVERIFIED)
    }

    @Test
    fun `only documented and verified are trustworthy`() {
        assertFalse(Confidence.UNVERIFIED.isTrustworthy)
        assertTrue(Confidence.DOCUMENTED.isTrustworthy)
        assertTrue(Confidence.VERIFIED.isTrustworthy)
    }

    private companion object {
        /**
         * Models this app recognises for display but which have no Rust
         * `ModelId`, because there is no register layout to describe.
         *
         * Listed explicitly so adding a new one is a deliberate act: the
         * sync test above fails if a `rustId` is neither in the Rust enum nor
         * here, which is exactly the prompt wanted when someone adds a model to
         * only one side.
         */
        val NINEBOT_ONLY_IDS = setOf(
            "NinebotESx",
            "NinebotMaxG30",
            "NinebotE",
            "NinebotF",
            "NinebotT15",
            "NinebotMaxG2",
            "NinebotF2",
            "NinebotD",
        )
    }
}
