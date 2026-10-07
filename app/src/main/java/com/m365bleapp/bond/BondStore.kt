package com.m365bleapp.bond

interface BondPersistence {
    fun read(): ByteArray?
    fun write(plain: ByteArray)
}
interface BondPreferences {
    fun all(): Map<String, String>
    fun update(values: Map<String, String?>)
}
data class BondSummary(val mac: String, val family: BondFamily, val label: String?, val model: String?) {
    val maskedMac get() = "••:••:••:••:" + mac.takeLast(5)
}
data class ImportResult(val added: Int, val replaced: Int, val kept: Int)

/** One serialized writer for registration, migration, manual entry and import. */
class BondStore(private val persistence: BondPersistence, private val prefs: BondPreferences) {
    private fun read(): BondDocument {
        val plain = persistence.read() ?: return BondDocument(emptyList())
        return try { BondJson.decode(plain, store = true) } finally { plain.fill(0) }
    }
    private fun write(entries: List<BondEntry>) {
        val plain = BondJson.encode(entries, "store")
        try { persistence.write(plain) } finally { plain.fill(0) }
    }
    @Synchronized fun list(): List<BondSummary> = read().use { document ->
        document.entries.map { BondSummary(it.mac, it.family, it.label, it.model) }
    }
    @Synchronized fun export(macs: Set<String>): BondDocument = read().use { document ->
        BondDocument(document.entries.filter { it.mac in macs }.map { it.duplicate() })
    }
    @Synchronized fun putXiaomi(mac: String, token12: ByteArray, label: String? = null, model: String? = null) =
        put(BondEntry(BondInput.mac(mac), BondFamily.XIAOMI, token12.copyOf(), label, model))
    @Synchronized fun putNinebot(mac: String, random16: ByteArray, label: String? = null, model: String? = null) =
        put(BondEntry(BondInput.mac(mac), BondFamily.NINEBOT, random16.copyOf(), label, model))
    private fun put(entry: BondEntry) {
        entry.use { importEntries(listOf(it), setOf(it.mac), setOf(it.mac)) }
    }
    @Synchronized fun importEntries(entries: List<BondEntry>, selected: Set<String>, replace: Set<String>): ImportResult {
        require(entries.size <= 64 && entries.map { it.mac }.distinct().size == entries.size)
        return read().use { original ->
            val updated = original.entries.toMutableList()
            val values = linkedMapOf<String, String?>()
            var added = 0; var replaced = 0; var kept = 0
            entries.filter { it.mac in selected }.forEach { entry ->
                val existing = updated.indexOfFirst { it.mac == entry.mac }
                if (existing >= 0 && entry.mac !in replace) kept++
                else {
                    if (existing >= 0) { updated.removeAt(existing); replaced++ } else added++
                    updated.add(entry)
                    values[entry.mac + entry.family.suffix] = prefsHex(entry.credential)
                    // A MAC has one family. Clear the old family's login material on replacement.
                    values[entry.mac + BondFamily.entries.first { it != entry.family }.suffix] = null
                }
            }
            require(updated.size <= 64)
            if (values.isNotEmpty()) {
                write(updated)
                try { prefs.update(values) }
                catch (e: Exception) { write(original.entries); throw e }
            }
            ImportResult(added, replaced, kept)
        }
    }
    @Synchronized fun remove(mac: String) {
        val address = BondInput.mac(mac)
        read().use { original ->
            write(original.entries.filterNot { it.mac == address })
            try { prefs.update(mapOf(address + "_token" to null, address + "_nb_random" to null)) }
            catch (e: Exception) { write(original.entries); throw e }
        }
    }
    @Synchronized fun migrateXiaomi() {
        read().use { original ->
            val additions = mutableListOf<BondEntry>()
            try {
                prefs.all().forEach { (key, value) ->
                    if (!key.endsWith("_token") || !value.matches(Regex("[0-9a-fA-F]{24}"))) return@forEach
                    val mac = try { BondInput.mac(key.removeSuffix("_token")) } catch (_: Exception) { return@forEach }
                    if (original.entries.any { it.mac == mac } || additions.any { it.mac == mac }) return@forEach
                    require(original.entries.size + additions.size < 64)
                    val chars = value.toCharArray()
                    try { additions.add(BondEntry(mac, BondFamily.XIAOMI, BondInput.hex(chars, 12))) }
                    finally { chars.fill('\u0000') }
                }
                if (additions.isNotEmpty()) write(original.entries + additions)
            } finally { additions.forEach { it.close() } }
        }
    }
    // EncryptedSharedPreferences requires String values; never used for JSON encoding.
    private fun prefsHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        try {
            bytes.forEachIndexed { i, b ->
                chars[i * 2] = "0123456789abcdef"[(b.toInt() and 255) ushr 4]
                chars[i * 2 + 1] = "0123456789abcdef"[b.toInt() and 15]
            }
            return String(chars)
        } finally { chars.fill('\u0000') }
    }
}

/** Monotonic clock; confirmation is consumed by exactly one write. */
class ExportPermit(private val now: () -> Long) {
    private var granted: Long? = null
    @Synchronized fun grant(secure: Boolean) { require(secure); granted = now() }
    @Synchronized fun valid(): Boolean = granted?.let { now() - it in 0 until 60_000 } ?: false
    @Synchronized fun consume() { check(valid()); clear() }
    @Synchronized fun clear() { granted = null }
}
