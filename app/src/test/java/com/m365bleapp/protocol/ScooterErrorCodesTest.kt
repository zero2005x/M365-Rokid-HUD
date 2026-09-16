package com.m365bleapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ScooterErrorCodes].
 *
 * The table is a transcription of Scootbatt's, so these tests chiefly guard
 * against transcription slips and against a later edit silently reclassifying a
 * fault as healthy — the one failure mode with a safety consequence.
 */
class ScooterErrorCodesTest {

    @Test
    fun `code zero is the only healthy code`() {
        assertTrue(ScooterErrorCodes.isHealthy(0))
        assertTrue(ScooterErrorCodes.BY_CODE.containsKey(0))

        // Spot-check that ordinary faults are not considered healthy.
        for (code in listOf(10, 19, 39, 54)) {
            assertFalse("code $code must not be healthy", ScooterErrorCodes.isHealthy(code))
        }
    }

    @Test
    fun `an unknown code is not treated as healthy`() {
        // The safety-critical direction: a fault the table has never heard of
        // must never render as "all OK", or a real fault disappears silently.
        assertFalse(ScooterErrorCodes.isHealthy(9999))
        assertFalse(ScooterErrorCodes.isHealthy(-1))
    }

    @Test
    fun `describe returns the vendor wording for known codes`() {
        assertEquals("None - all OK", ScooterErrorCodes.describe(0))
        assertEquals("BLE or ESC failure", ScooterErrorCodes.describe(10))
        assertEquals("Brake handle failure", ScooterErrorCodes.describe(15))
        assertEquals("Battery overheat", ScooterErrorCodes.describe(39))
        assertEquals("Motor C phase disconnected", ScooterErrorCodes.describe(54))
    }

    @Test
    fun `describe always includes the number for unknown codes`() {
        // A rider reporting a fault needs the number; "unknown" alone is useless.
        val text = ScooterErrorCodes.describe(1234)
        assertTrue("expected the code in '$text'", text.contains("1234"))
        assertTrue(text.contains("Unknown"))
    }

    @Test
    fun `reserved codes are described as reserved rather than unknown`() {
        // These are gaps in the vendor's table, and saying so saves the next
        // reader from re-deriving that fact.
        for (code in ScooterErrorCodes.RESERVED) {
            val text = ScooterErrorCodes.describe(code)
            assertTrue("code $code should read as reserved, got '$text'", text.contains("Reserved"))
        }
    }

    @Test
    fun `reserved codes are not in the described map`() {
        // Overlap would mean a code is both documented and declared a gap.
        for (code in ScooterErrorCodes.RESERVED) {
            assertFalse(
                "code $code is reserved but also described",
                ScooterErrorCodes.BY_CODE.containsKey(code)
            )
        }
    }

    @Test
    fun `every known code has a non-empty description`() {
        for ((code, text) in ScooterErrorCodes.BY_CODE) {
            assertTrue("code $code has a blank description", text.isNotBlank())
        }
    }

    @Test
    fun `the table covers exactly the documented shapes`() {
        // 33 described codes plus 13 reserved gaps is the whole `0x1B` surface.
        // The count is asserted explicitly so that adding or dropping a row is a
        // deliberate act with a failing test, not a silent edit.
        assertEquals(33, ScooterErrorCodes.BY_CODE.size)
        assertEquals(13, ScooterErrorCodes.RESERVED.size)
        assertEquals(54, ScooterErrorCodes.MAX_KNOWN)
    }

    @Test
    fun `severity treats zero as ok and unknown codes as faults`() {
        assertEquals(
            ScooterErrorCodes.Severity.OK,
            ScooterErrorCodes.severityOf(0)
        )
        // An unrecognised code must be loud, not silent.
        assertEquals(
            ScooterErrorCodes.Severity.FAULT,
            ScooterErrorCodes.severityOf(4242)
        )
        assertEquals(
            ScooterErrorCodes.Severity.FAULT,
            ScooterErrorCodes.severityOf(39)
        )
    }

    @Test
    fun `severity marks unconfigured system codes as warnings not faults`() {
        assertEquals(
            ScooterErrorCodes.Severity.WARNING,
            ScooterErrorCodes.severityOf(23) // BMS has default S/N
        )
        assertEquals(
            ScooterErrorCodes.Severity.WARNING,
            ScooterErrorCodes.severityOf(21) // No BMS data
        )
    }

    @Test
    fun `warning codes are all real codes in the table`() {
        // A warning entry for a code with no description would be unreachable
        // through describe() and is almost certainly a typo.
        for (code in listOf(21, 23, 27, 32, 35, 42, 49, 50, 51, 52)) {
            assertNotNull(
                "warning code $code has no description",
                ScooterErrorCodes.BY_CODE[code]
            )
        }
    }
}
