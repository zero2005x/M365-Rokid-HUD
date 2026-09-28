package com.m365bleapp.protocol

/** Advertised-name prefixes shared by every Xiaomi/Mi model entry. */
private val XIAOMI_NAME_PREFIXES = listOf("MIScooter", "Mi Scooter")

/**
 * How much a model identification can be trusted.
 *
 * Mirrors `ninebot_ble::model::Confidence`. The two are kept in step by
 * [ScooterModelRegistryTest]; the Rust side is the authority for register
 * layouts, this side exists because the UI cannot read Rust enums without a JNI
 * round trip.
 *
 * Declared least-confident first so the natural ordering is meaningful:
 * `confidence >= Confidence.DOCUMENTED` reads the way it behaves.
 */
enum class Confidence(val label: String, val description: String) {
    /**
     * Inferred from an advertisement, or chosen by the rider.
     *
     * An advertisement carries a name and maybe manufacturer data. That is
     * enough to guess a family and nothing more: Xiaomi, Ninebot and current
     * Segway models all advertise the same Nordic UART service, and the same
     * model name spans several wire generations. Never present this as fact.
     */
    UNVERIFIED(
        "Unverified",
        "Identified from the advertisement only, or set manually."
    ),

    /**
     * Taken from a published reverse-engineering source but not confirmed on
     * hardware by this project.
     */
    DOCUMENTED(
        "Documented",
        "Layout taken from published documentation; not yet confirmed on this device."
    ),

    /**
     * Confirmed against traffic from a real scooter of this model.
     *
     * **Nothing claims this yet.** No capture has been supplied, and promoting a
     * model to VERIFIED on the strength of a name match would make the badge
     * meaningless.
     */
    VERIFIED(
        "Verified",
        "Confirmed against a real scooter of this model."
    );

    /** True when the reading may be shown without a caveat. */
    val isTrustworthy: Boolean get() = this >= DOCUMENTED
}

/**
 * What a model can report, and what may be controlled on it.
 *
 * Capabilities rather than a model check, so the UI asks "does this scooter have
 * a second battery?" instead of "is this an ESx?". The former stays correct when
 * a new model is added; the latter needs editing in every screen.
 */
data class ModelCapabilities(
    val speed: Boolean = true,
    val batteryPercent: Boolean = true,
    /**
     * A second battery pack.
     *
     * Present on Ninebot ESx (external pack) and on Max models with an add-on.
     * When false the UI must **not** render a second battery row at all, rather
     * than rendering it as 0% — a zero looks like a real reading.
     */
    val secondaryBattery: Boolean = false,
    val temperature: Boolean = true,
    val totalMileage: Boolean = true,
    val remainingRange: Boolean = true,
    val tripDistance: Boolean = true,
    val tripTime: Boolean = true,
    val tailLight: Boolean = true,
    val motorLock: Boolean = true,
) {
    companion object {
        /**
         * Capabilities for a model with no verified register layout.
         *
         * Everything that would require a register read is switched **off**.
         * This is the default for any model this app cannot actually read, so
         * the UI degrades to "nothing to show" rather than to plausible zeros.
         */
        val NONE = ModelCapabilities(
            speed = false,
            batteryPercent = false,
            secondaryBattery = false,
            temperature = false,
            totalMileage = false,
            remainingRange = false,
            tripDistance = false,
            tripTime = false,
            tailLight = false,
            motorLock = false,
        )
    }
}

/**
 * A scooter model this app knows about.
 *
 * [rustId] must match the variant name in `ninebot_ble::model::ModelId`. The
 * mapping is asserted by a test, so a rename on one side fails the build rather
 * than silently desynchronising the two registries.
 */
enum class ScooterModel(
    val rustId: String,
    val displayName: String,
    /**
     * Advertised-name prefixes that suggest this model.
     *
     * **Advisory only.** A prefix narrows nothing on its own — see
     * [Confidence.UNVERIFIED] — and [ScooterModelRegistry] never treats a match
     * as more than a hint.
     */
    val namePrefixes: List<String> = emptyList(),
    val capabilities: ModelCapabilities = ModelCapabilities.NONE,
) {
    M365(
        rustId = "M365",
        displayName = "Xiaomi M365",
        namePrefixes = XIAOMI_NAME_PREFIXES,
        capabilities = ModelCapabilities(
            speed = true,
            batteryPercent = true,
            temperature = true,
            totalMileage = true,
            remainingRange = true,
            tripDistance = true,
            tripTime = true,
            tailLight = true,
            motorLock = true,
        ),
    ),

    M365_PRO(
        rustId = "M365Pro",
        displayName = "Xiaomi M365 Pro",
        namePrefixes = XIAOMI_NAME_PREFIXES,
        capabilities = ModelCapabilities(
            speed = true,
            batteryPercent = true,
            temperature = true,
            totalMileage = true,
            remainingRange = true,
            tripDistance = true,
            tripTime = true,
            tailLight = true,
            motorLock = true,
        ),
    ),

    M365_PRO2(
        rustId = "M365Pro2",
        displayName = "Xiaomi M365 Pro 2",
        namePrefixes = XIAOMI_NAME_PREFIXES,
        capabilities = ModelCapabilities(
            speed = true,
            batteryPercent = true,
            temperature = true,
            totalMileage = true,
            remainingRange = true,
            tripDistance = true,
            tripTime = true,
            tailLight = true,
            motorLock = true,
        ),
    ),

    MI_1S(
        rustId = "Mi1S",
        displayName = "Xiaomi Mi 1S",
        namePrefixes = XIAOMI_NAME_PREFIXES,
        capabilities = ModelCapabilities(
            speed = true,
            batteryPercent = true,
            temperature = true,
            totalMileage = true,
            remainingRange = true,
            tripDistance = true,
            tripTime = true,
            tailLight = true,
            motorLock = true,
        ),
    ),

    MI_LITE(
        rustId = "MiLite",
        displayName = "Xiaomi Mi Lite",
        namePrefixes = XIAOMI_NAME_PREFIXES,
        capabilities = ModelCapabilities(
            speed = true,
            batteryPercent = true,
            temperature = true,
            totalMileage = true,
            remainingRange = true,
            tripDistance = true,
            tripTime = true,
            tailLight = true,
            motorLock = true,
        ),
    ),

    /**
     * Xiaomi Mi 3. On the Xiaomi MCU platform but its BLE protocol is unconfirmed,
     * so it gets no capabilities.
     */
    MI3(
        rustId = "Mi3",
        displayName = "Xiaomi Mi 3",
        namePrefixes = XIAOMI_NAME_PREFIXES,
        capabilities = ModelCapabilities.NONE,
    ),

    // --- Ninebot / Segway families -----------------------------------------
    //
    // Recognisable by name, but no telemetry: their register layouts are not
    // published and this project refuses to guess them. Capabilities are NONE so
    // the UI shows no readings rather than zeros.

    NINEBOT_ESX(rustId = "NinebotESx", displayName = "Ninebot ESx"),
    NINEBOT_MAX_G30(rustId = "NinebotMaxG30", displayName = "Ninebot Max G30"),
    NINEBOT_E_SERIES(rustId = "NinebotE", displayName = "Ninebot E-series"),
    NINEBOT_F_SERIES(rustId = "NinebotF", displayName = "Ninebot F-series"),
    NINEBOT_T15(rustId = "NinebotT15", displayName = "Ninebot Air T15"),

    NINEBOT_MAX_G2(
        rustId = "NinebotMaxG2",
        displayName = "Ninebot Max G2",
        namePrefixes = listOf("NB", "Ninebot", "Segway"),
    ),
    NINEBOT_F2(
        rustId = "NinebotF2",
        displayName = "Ninebot F2",
        namePrefixes = listOf("NB", "Ninebot", "Segway"),
    ),
    NINEBOT_D_SERIES(
        rustId = "NinebotD",
        displayName = "Ninebot D-series",
        namePrefixes = listOf("NB", "Ninebot", "Segway"),
    ),

    /** No model could be identified. */
    UNKNOWN(rustId = "Unknown", displayName = "Unidentified");

    /** True when the app can actually produce readings for this model. */
    val producesTelemetry: Boolean
        get() = capabilities.speed || capabilities.batteryPercent
}

/**
 * Identifies a scooter from what a scan can see, and remembers a manual override.
 *
 * ## Why identification is advisory and overridable
 *
 * Nothing observable before connecting identifies a scooter's protocol. The
 * advertised name covers several wire generations of the same family, and the
 * GATT service list is not a discriminator either — Xiaomi, Ninebot and current
 * Segway models all expose the same Nordic UART service, and some answer on only
 * one of the services they advertise.
 *
 * So this registry returns a *hint* with an honest confidence, and the rider can
 * override it. The override is the escape hatch that makes the app usable on a
 * scooter this project has never seen and cannot test — without it, an
 * unidentifiable scooter would be permanently unreadable with no recourse.
 */
object ScooterModelRegistry {

    /**
     * Bluetooth SIG company identifier for Ninebot/Xiaomi scooter advertisements.
     *
     * `0x424E` (`16974`). Its manufacturer-specific data carries the vendor's own
     * model code in byte 0 and the protocol version in byte 1; see
     * [fromManufacturerTypeCode]. A scooter that does not advertise it is not
     * necessarily a scooter this app cannot talk to — only one this app cannot
     * *name* from the advertisement.
     */
    const val MANUFACTURER_COMPANY_ID: Int = 16974

    /**
     * Models offered in the manual override picker, in a sensible order.
     *
     * Excludes [ScooterModel.UNKNOWN], which is the absence of a choice.
     */
    val selectableModels: List<ScooterModel> =
        ScooterModel.entries.filter { it != ScooterModel.UNKNOWN }

    /**
     * Best guess from an advertisement.
     *
     * Always returns [Confidence.UNVERIFIED]: a name prefix cannot justify more,
     * and the whole point of returning a confidence is to stop a guess being
     * rendered as a fact.
     *
     * Returns `null` when nothing matched, so the caller can distinguish "no
     * idea" from "probably an M365" instead of defaulting to a model.
     */
    fun fromAdvertisementName(advertisedName: String?): Pair<ScooterModel, Confidence>? {
        val name = advertisedName?.trim().orEmpty()
        if (name.isEmpty()) return null

        // Longest prefixes first, so "NBScooter" is not shadowed by "NB".
        val candidates = ScooterModel.entries
            .filter { it != ScooterModel.UNKNOWN }
            .flatMap { model -> model.namePrefixes.map { it to model } }
            .sortedByDescending { (prefix, _) -> prefix.length }

        for ((prefix, model) in candidates) {
            if (name.startsWith(prefix, ignoreCase = true)) {
                return model to Confidence.UNVERIFIED
            }
        }
        return null
    }

    /**
     * The model named by a scooter's own advertised type code, if we know it.
     *
     * ## Why this beats a name prefix
     *
     * Ninebot/Xiaomi scooters put manufacturer-specific data in the advertisement
     * under company id **16974 (`0x424E`)**, and its first byte is the vendor's own
     * model code — the same integer that identifies the model internally. That is a
     * declaration by the scooter, not an inference from a string, and it survives
     * the thing that makes [fromAdvertisementName] unreliable: the same model name
     * spanning several wire generations.
     *
     * Codes come from an independent implementation — **m365 Tools 1.8.0**
     * (`app.peretti.m365tools`), statically analysed: it reads the byte and matches
     * it against its own device table
     * (`doc/reverse-engineering/m365tools-reports/01-scan-and-identification.md`).
     * The Xiaomi entries in that table were cross-checked against this project's
     * own extraction of the same table and agree.
     *
     * Confidence is [Confidence.DOCUMENTED], not `VERIFIED`: this project has not
     * confirmed on hardware that a given scooter advertises the code we expect,
     * and no capture of a real `0x424E` payload exists yet.
     *
     * Only codes this project can actually name are listed. An unlisted code
     * returns `null` so the caller can fall back, rather than being forced onto the
     * nearest model.
     */
    fun fromManufacturerTypeCode(typeCode: Int): Pair<ScooterModel, Confidence>? {
        val model = MANUFACTURER_TYPE_CODES[typeCode] ?: return null
        return model to Confidence.DOCUMENTED
    }

    /**
     * Vendor model codes from the `0x424E` manufacturer data, first byte.
     *
     * Several codes have no [ScooterModel] to map to — Mini, Nano, Mark2/3, VIO,
     * the Kart family and the rebadges — so they are deliberately absent: an
     * unmapped code falls back to the name prefix instead of being reported as a
     * model this app cannot support anyway.
     */
    private val MANUFACTURER_TYPE_CODES: Map<Int, ScooterModel> = mapOf(
        // Xiaomi lineage. `1S_DE` is the German 1S, which shares the 1S layout.
        32 to ScooterModel.M365,
        34 to ScooterModel.M365_PRO,
        37 to ScooterModel.MI_1S,
        40 to ScooterModel.M365_PRO2,
        41 to ScooterModel.MI_LITE,
        43 to ScooterModel.MI_1S,
        46 to ScooterModel.MI3,
        // Ninebot / Segway families this app recognises.
        33 to ScooterModel.NINEBOT_ESX,
        36 to ScooterModel.NINEBOT_MAX_G30,
        35 to ScooterModel.NINEBOT_T15,
        44 to ScooterModel.NINEBOT_F_SERIES,
        45 to ScooterModel.NINEBOT_F_SERIES,
        39 to ScooterModel.NINEBOT_ESX,
    )

    /**
     * The model to use, given an advertisement and any manual override.
     *
     * An override always wins: the rider is looking at the scooter and this
     * registry is guessing from a string. A manual choice is still reported as
     * [Confidence.UNVERIFIED], because choosing a model tells the app which
     * layout to *try* — it does not make that layout correct.
     *
     * The vendor type code in [manufacturerTypeCode] is preferred over the
     * advertised name when present: it is the scooter declaring its own model,
     * where a name is a string several generations share.
     */
    fun resolve(
        advertisedName: String?,
        override: ScooterModel? = null,
        manufacturerTypeCode: Int? = null,
    ): Identification {
        if (override != null && override != ScooterModel.UNKNOWN) {
            return Identification(
                model = override,
                confidence = Confidence.UNVERIFIED,
                source = IdentificationSource.MANUAL_OVERRIDE,
            )
        }
        val declared = manufacturerTypeCode?.let { fromManufacturerTypeCode(it) }
        val guessed = declared ?: fromAdvertisementName(advertisedName)
        return if (guessed == null) {
            Identification(
                model = ScooterModel.UNKNOWN,
                confidence = Confidence.UNVERIFIED,
                source = IdentificationSource.NONE,
            )
        } else {
            Identification(
                model = guessed.first,
                confidence = guessed.second,
                source = if (declared != null) {
                    IdentificationSource.MANUFACTURER_DATA
                } else {
                    IdentificationSource.ADVERTISEMENT
                },
            )
        }
    }
}

/** Where an [Identification] came from, so the UI can explain itself. */
enum class IdentificationSource {
    /** The rider chose it. */
    MANUAL_OVERRIDE,

    /**
     * The scooter declared its own model code in the `0x424E` manufacturer data.
     *
     * The strongest signal available before connecting, and still weaker than a
     * capture: it says which model the scooter claims to be.
     */
    MANUFACTURER_DATA,

    /** Matched an advertised-name prefix. */
    ADVERTISEMENT,

    /** Nothing matched. */
    NONE,
}

/**
 * A model guess together with how much it can be trusted and where it came from.
 *
 * A triple rather than just a model, because the UI has three different things to
 * say and must not collapse them: which scooter we think this is, how sure we
 * are, and whether the rider told us.
 */
data class Identification(
    val model: ScooterModel,
    val confidence: Confidence,
    val source: IdentificationSource,
) {
    /** True when the model was never identified at all. */
    val isUnknown: Boolean get() = model == ScooterModel.UNKNOWN

    /**
     * The badge text.
     *
     * Deliberately includes the confidence for anything not verified, so a badge
     * can never be mistaken for a verified claim.
     */
    val badgeText: String
        get() = when {
            isUnknown -> "Unidentified"
            confidence == Confidence.VERIFIED -> model.displayName
            else -> "${model.displayName} · ${confidence.label}"
        }
}
