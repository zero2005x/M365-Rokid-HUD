package io.github.zero2005x.pev.core.vehicle

/**
 * Android-free profile records. A MAC is an address hint, not a permanent identity.
 * The codec has no global override: a manual model exists only after it is bound
 * to one record.
 */
data class VehicleProfileRecord(
    val addressHint: String,
    val manualModelId: String,
    val displayName: String,
)

object VehicleProfileCodec {
    fun encode(records: List<VehicleProfileRecord>): String =
        records.joinToString("\n") { "${escape(it.addressHint)}\t${escape(it.manualModelId)}\t${escape(it.displayName)}" }

    fun decode(raw: String): List<VehicleProfileRecord> =
        raw.lineSequence().filter { it.isNotEmpty() }.mapNotNull { line ->
            val parts = split(line)
            if (parts.size != 3 || parts[0].isBlank()) null
            else VehicleProfileRecord(parts[0], parts[1], parts[2])
        }.toList()

    fun manualModelId(records: List<VehicleProfileRecord>, address: String): String? =
        records.firstOrNull { it.addressHint.equals(address, ignoreCase = true) }
            ?.manualModelId
            ?.ifBlank { null }

    fun bind(
        records: List<VehicleProfileRecord>,
        address: String,
        modelId: String,
        displayName: String,
    ): List<VehicleProfileRecord> {
        val kept = records.filterNot { it.addressHint.equals(address, ignoreCase = true) }
        if (modelId.isBlank()) return kept
        return kept + VehicleProfileRecord(address, modelId, displayName)
    }

    private fun escape(value: String) = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    private fun split(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        line.forEach { ch ->
            when {
                escaped -> {
                    current.append(when (ch) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> ch
                    })
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == '\t' -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (escaped) return emptyList()
        fields += current.toString()
        return fields
    }
}

data class DiscoveryEntry(
    val address: String,
    val discoveryIndex: Int,
    val saved: Boolean,
    val recognized: Boolean,
)

/** Saved devices stay in their own section. RSSI never reorders either section. */
object NearbyListOrder {
    fun order(entries: List<DiscoveryEntry>, showUnrecognized: Boolean): List<DiscoveryEntry> {
        val saved = entries.filter { it.saved }.sortedBy { it.discoveryIndex }
        val nearby = entries.filter { !it.saved && (showUnrecognized || it.recognized) }
            .sortedBy { it.discoveryIndex }
        return saved + nearby
    }
}
