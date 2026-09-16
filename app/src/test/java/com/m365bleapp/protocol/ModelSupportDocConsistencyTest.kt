package com.m365bleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `doc/MODEL_SUPPORT.md` honest against the registry.
 *
 * ## Why this test exists
 *
 * The support matrix is the document a user reads to decide whether their
 * scooter works, and a table in a Markdown file has no compiler. Without a
 * check, the registry and the table drift — and the failure is asymmetric: a
 * registry change that adds a capability silently leaves the documentation
 * claiming the scooter cannot be read, which is the direction that makes the
 * project look *less* capable than it is and encourages someone to "fix" the
 * registry to match stale prose.
 *
 * The checks are deliberately coarse. They compare the **readable / not readable**
 * verdict per model, not every cell, so the table stays editable for prose while
 * the claim that matters — can this app read this scooter? — cannot go stale.
 */
class ModelSupportDocConsistencyTest {

    private fun doc(): String {
        val candidates = listOf(
            File("../doc/MODEL_SUPPORT.md"),
            File("doc/MODEL_SUPPORT.md"),
            File("../../doc/MODEL_SUPPORT.md"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue(
            "could not locate doc/MODEL_SUPPORT.md from ${File(".").absolutePath}; " +
                "this test guards the user-facing support table and must not silently skip",
            file != null
        )
        return file!!.readText()
    }

    /**
     * Rows of the §0 matrix: `| **Xiaomi M365** | ✅ | ✅ | ✅ | … |`.
     *
     * Returns display name to the list of capability cells, in order. The cell
     * values are `✅`, `—`, or `⛔`.
     */
    private fun matrixRows(text: String): Map<String, List<String>> {
        val section = text.substringAfter("### The matrix", "")
            .substringBefore("\n**Capability cells")
        val rows = mutableMapOf<String, List<String>>()

        for (line in section.lines()) {
            if (!line.trimStart().startsWith("|")) continue
            val cells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.size < 6) continue
            // Model names in the table are bolded.
            val name = cells[0].removeSurrounding("**")
            if (name.startsWith("Model") || name.startsWith("---") || name.startsWith(":")) continue
            if (!name.contains(" ")) continue
            rows[name] = cells.drop(1)
        }
        return rows
    }

    @Test
    fun `the matrix parses and is not empty`() {
        val rows = matrixRows(doc())
        assertTrue(
            "failed to parse any rows from the §0 matrix; the table format changed " +
                "and this guard is now inert",
            rows.size >= 10
        )
    }

    @Test
    fun `every registry model appears in the matrix`() {
        val rows = matrixRows(doc())
        val missing = ScooterModel.entries
            .filter { it != ScooterModel.UNKNOWN }
            .map { it.displayName }
            .filterNot { rows.containsKey(it) }

        assertTrue(
            "these models exist in the registry but not in doc/MODEL_SUPPORT.md §0: " +
                "$missing. A user cannot find out whether their scooter works.",
            missing.isEmpty()
        )
    }

    @Test
    fun `the matrix claims readability exactly when the registry does`() {
        val rows = matrixRows(doc())

        for (model in ScooterModel.entries.filter { it != ScooterModel.UNKNOWN }) {
            val cells = rows[model.displayName] ?: continue
            // Cells: Recognised, Connectable, Speed, Battery, Temp, Odometer,
            // Range, Trip, Confidence, Blocker
            val speedCell = cells.getOrNull(2) ?: continue
            val batteryCell = cells.getOrNull(3) ?: continue

            val docSaysReadable = speedCell == "✅" || batteryCell == "✅"
            assertEquals(
                "${model.displayName}: doc/MODEL_SUPPORT.md says readable=$docSaysReadable " +
                    "but the registry says producesTelemetry=${model.producesTelemetry}. " +
                    "Update whichever is wrong — the documentation is what a user trusts.",
                model.producesTelemetry,
                docSaysReadable
            )
        }
    }

    @Test
    fun `a model with no capabilities is never marked with a capability tick`() {
        val rows = matrixRows(doc())

        for (model in ScooterModel.entries.filter { it != ScooterModel.UNKNOWN }) {
            if (model.producesTelemetry) continue
            val cells = rows[model.displayName] ?: continue
            // Indices 2..7 are Speed..Trip.
            val ticks = cells.drop(2).take(6).filter { it == "✅" }
            assertTrue(
                "${model.displayName} has no capabilities in the registry but the matrix " +
                    "shows $ticks. The detail screen would hide those rows, contradicting the doc.",
                ticks.isEmpty()
            )
        }
    }

    @Test
    fun `no model is documented as verified`() {
        // Nothing has been hardware-confirmed. A stray "Verified" in the table
        // would be the single most misleading thing this document could say.
        val text = doc()
        val verifiedRows = text.lines().filter { it.startsWith("| **") && it.contains("Verified") }
        assertTrue(
            "these matrix rows claim Verified, but no capture has confirmed any model: " +
                verifiedRows.joinToString(" / "),
            verifiedRows.isEmpty()
        )
    }

    @Test
    fun `the document states that nothing is verified`() {
        // The absence of "Verified" rows is only meaningful if the document also
        // says so, rather than merely omitting the marker.
        val text = doc()
        assertTrue(
            "doc/MODEL_SUPPORT.md must state explicitly that nothing is verified yet",
            text.contains("Nothing is marked") && text.contains("Verified")
        )
    }

    @Test
    fun `the document explains how to capture the artefacts that would unblock rows`() {
        // The blocking items are the point of the document; without capture
        // instructions a reader has no next step.
        val text = doc()
        assertTrue(
            "the document should tell a reader how to produce a capture",
            text.contains("HCI snoop log")
        )
    }

    // --- the single-source claim -------------------------------------------

    @Test
    fun `the identity rule is not restated in the documentation`() {
        // README and BLE_PROTOCOL_GUIDE must point at ninebot-ble/src/identity.rs
        // rather than repeating the prefix and UUID, which is how three copies
        // drifted apart in the first place.
        val readme = listOf(File("../README.md"), File("README.md"))
            .firstOrNull { it.isFile } ?: return
        val text = readme.readText()

        assertTrue(
            "README.md must reference the single source of the identity rule",
            text.contains("identity.rs")
        )
        assertTrue(
            "README.md must still name the constant so a reader can find it",
            text.contains("XIAOMI_SCOOTER_MATCH")
        )
    }
}
