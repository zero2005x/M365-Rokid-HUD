package io.github.zero2005x.pev.core.identity

/** Protocol families. GATT UUID/name only yields candidates; see [IdentitySource]. */
enum class Family {
    XIAOMI_SCOOTER, NINEBOT_SCOOTER, ZYDTECH_SCOOTER,
    BEGODE, KINGSONG, INMOTION_I1, INMOTION_I2, VETERAN, UNKNOWN,
}

/** How a family/model claim was obtained, weakest first. */
enum class IdentitySource { GATT_HINT, USER_SELECTED, IN_BAND_QUERY }

/** One claim. Unknown stays null: model is never guessed from name, MAC or voltage range. */
data class DeviceIdentity(
    val family: Family = Family.UNKNOWN,
    val model: String? = null,
    val firmware: String? = null,
    val board: String? = null,
    val packParams: Map<String, String> = emptyMap(),
    val source: IdentitySource = IdentitySource.GATT_HINT,
) {
    /** Exact-model profile is only known when a model was established beyond a GATT hint. */
    val isExact: Boolean
        get() = family != Family.UNKNOWN && model != null && source != IdentitySource.GATT_HINT

    /** Stable key used to bind write-session consent; any change revokes it. */
    val profileKey: String
        get() = listOf(family.name, model, firmware, board).joinToString("|") { it ?: "?" }
}
