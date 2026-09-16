package com.m365bleapp.protocol

/**
 * Scooter error / warning codes reported by the ESC `0x1B` register.
 *
 * ## Provenance
 *
 * The table is transcribed from the decompiled Scootbatt 1.9.2 dispatch for
 * register `0x1B` (`C1138os.mo2829h`, case 4), which switches on the 16-bit code
 * and maps each value to a human string. Scootbatt hard-codes English text, so the
 * descriptions here are its wording — deliberately **not** localised, because the
 * strings are the community's vocabulary for these faults and a paraphrase would
 * make them harder to match against forum threads and service manuals.
 *
 * Codes 16, 17, 25, 26, 28–31, 33, 34, 40, 47 and 48 fall through to "unknown" in
 * Scootbatt as well; they are listed in [RESERVED] so the gap is documented rather
 * than looking like an oversight.
 *
 * ## ⚠️ Not verified against real hardware
 *
 * No scooter has been attached to this project. Which codes a given model can
 * actually raise is unknown; the mapping is a faithful copy of a third-party
 * app's table, nothing more.
 */
object ScooterErrorCodes {

    /** No fault reported. */
    const val NONE = 0

    /**
     * Code → description.
     *
     * Codes absent from this map are reserved or unknown; use [describe] rather
     * than indexing directly.
     */
    val BY_CODE: Map<Int, String> = mapOf(
        0 to "None - all OK",
        10 to "BLE or ESC failure",
        11 to "Phase A sensor failure",
        12 to "Phase B sensor failure",
        13 to "Phase C sensor failure",
        14 to "Throttle handle failure",
        15 to "Brake handle failure",
        18 to "Hall sensors failure",
        19 to "Wrong main battery voltage",
        20 to "Wrong ext battery voltage",
        21 to "No BMS data",
        22 to "Invalid BMS config",
        23 to "BMS has default S/N",
        24 to "Supply voltage out of range",
        27 to "ESC config invalid (change SN)",
        32 to "Missing IoT device",
        35 to "ESC has default S/N",
        36 to "eBMS connector or charging failure",
        37 to "BMS connector or charging failure",
        38 to "Charging over-current",
        39 to "Battery overheat",
        41 to "Ext battery overheat",
        42 to "No eBMS data",
        43 to "Invalid eBMS config",
        44 to "eBMS has default S/N",
        45 to "Battery cell deep discharge",
        46 to "Ext battery cell deep discharge",
        49 to "Wrong BMS firmware version",
        50 to "Wrong eBMS firmware version",
        51 to "Wrong BLE firmware version",
        52 to "BMS firmware incompatible with DRV",
        53 to "Incompatible external battery",
        54 to "Motor C phase disconnected",
    )

    /**
     * Codes Scootbatt maps to "unknown", kept explicit.
     *
     * They are gaps in the vendor's own table, not omissions here. Listing them
     * means a future reader does not have to re-derive that.
     */
    val RESERVED: Set<Int> = setOf(16, 17, 25, 26, 28, 29, 30, 31, 33, 34, 40, 47, 48)

    /** Highest code with a known description. */
    val MAX_KNOWN: Int = BY_CODE.keys.max()

    /**
     * True when [code] means "no fault".
     *
     * Anything that is not [NONE] is a reported condition, including codes with
     * no description: treating an unknown code as healthy would hide a real fault.
     */
    fun isHealthy(code: Int): Boolean = code == NONE

    /**
     * Human-readable description for [code].
     *
     * Unknown and reserved codes are described rather than mapped to null, so a
     * fault with no table entry still surfaces as a fault on the HUD instead of
     * disappearing. The numeric value is always included for diagnosis.
     */
    fun describe(code: Int): String = when {
        code == NONE -> BY_CODE.getValue(NONE)
        BY_CODE.containsKey(code) -> BY_CODE.getValue(code)
        code in RESERVED -> "Reserved code $code"
        else -> "Unknown error code ($code)"
    }

    /**
     * Severity hint for display.
     *
     * Derived, not from the vendor table: Scootbatt surfaces every non-zero code
     * identically. Grouping them here exists so the HUD can decide how loudly to
     * shout, and the grouping is a judgement call documented as such.
     */
    enum class Severity {
        /** Nothing wrong. */
        OK,

        /** Rideable, but something needs attention. */
        WARNING,

        /** Should not be ridden until resolved. */
        FAULT,
    }

    /**
     * Codes considered rideable-but-attention.
     *
     * Default-charged config and missing-BMS-data codes read as warnings because
     * they indicate an unconfigured or partially-present system rather than a
     * failed component.
     */
    private val WARNING_CODES = setOf(21, 23, 27, 32, 35, 42, 49, 50, 51, 52)

    /** Classifies [code]. Unknown codes are treated as faults, never as OK. */
    fun severityOf(code: Int): Severity = when {
        isHealthy(code) -> Severity.OK
        code in WARNING_CODES -> Severity.WARNING
        else -> Severity.FAULT
    }
}
