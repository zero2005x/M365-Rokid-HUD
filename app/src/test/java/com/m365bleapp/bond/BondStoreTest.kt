package com.m365bleapp.bond

import org.junit.Assert.*
import org.junit.Test

class BondStoreTest {
    private class Persistence : BondPersistence {
        var data: ByteArray? = null
        override fun read() = data?.copyOf()
        override fun write(plain: ByteArray) { data?.fill(0); data = plain.copyOf() }
    }
    private class Preferences : BondPreferences {
        val values = mutableMapOf<String, String>()
        var fail = false
        override fun all() = values.toMap()
        override fun update(values: Map<String, String?>) {
            check(!fail)
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
        }
    }
    private val persistence = Persistence()
    private val prefs = Preferences()
    private val store = BondStore(persistence, prefs)
    private val mac = "AA:BB:CC:DD:EE:FF"
    @Test fun xiaomiDualWriteAndExistingLoginLookup() {
        store.putXiaomi(mac.lowercase(), ByteArray(12) { it.toByte() })
        assertEquals("000102030405060708090a0b", prefs.values[mac + "_token"])
        store.export(setOf(mac)).use { assertArrayEquals(ByteArray(12) { i -> i.toByte() }, it.entries.single().credential) }
        assertEquals(24, prefs.values[mac + "_token"]!!.length)
    }
    @Test fun ninebotOnlyUsesRandomKey() {
        store.putNinebot(mac, ByteArray(16) { it.toByte() })
        assertFalse(prefs.values.containsKey(mac + "_token"))
        assertEquals(32, prefs.values[mac + "_nb_random"]!!.length)
        assertEquals(BondFamily.NINEBOT, store.list().single().family)
    }
    @Test fun migrationPreservesPrefsAndDoesNotReplaceStore() {
        prefs.values[mac + "_token"] = "01".repeat(12)
        prefs.values["invalid_token"] = "bad"
        store.migrateXiaomi(); store.migrateXiaomi()
        assertEquals(1, store.list().size)
        assertEquals("01".repeat(12), prefs.values[mac + "_token"])
        store.putXiaomi(mac, ByteArray(12) { 2 })
        prefs.values[mac + "_token"] = "03".repeat(12)
        store.migrateXiaomi()
        store.export(setOf(mac)).use { assertEquals(2, it.entries.single().credential[0].toInt()) }
    }
    @Test fun conflictsKeepByDefaultAndExplicitReplaceOverwrites() {
        store.putXiaomi(mac, ByteArray(12) { 1 })
        val other = BondEntry("11:22:33:44:55:66", BondFamily.NINEBOT, ByteArray(16) { 4 })
        BondEntry(mac, BondFamily.XIAOMI, ByteArray(12) { 2 }).use { incoming ->
            assertEquals(ImportResult(1, 0, 1), store.importEntries(listOf(incoming, other), setOf(mac, other.mac), emptySet()))
            assertEquals("01".repeat(12), prefs.values[mac + "_token"])
            assertEquals(ImportResult(0, 1, 0), store.importEntries(listOf(incoming), setOf(mac), setOf(mac)))
            assertEquals("02".repeat(12), prefs.values[mac + "_token"])
        }
        other.close()
    }
    @Test fun removalDeletesBothFamiliesAndStore() {
        store.putXiaomi(mac, ByteArray(12)); prefs.values[mac + "_nb_random"] = "00".repeat(16)
        store.remove(mac)
        assertTrue(store.list().isEmpty()); assertFalse(prefs.values.keys.any { it.startsWith(mac) })
    }
    @Test fun replacingFamilyClearsOldKey() {
        store.putXiaomi(mac, ByteArray(12)); store.putNinebot(mac, ByteArray(16))
        assertNull(prefs.values[mac + "_token"]); assertNotNull(prefs.values[mac + "_nb_random"])
    }
    @Test fun prefsFailureRollsBackStore() {
        store.putXiaomi(mac, ByteArray(12)); prefs.fail = true
        try { store.putNinebot(mac, ByteArray(16)); fail() } catch (_: IllegalStateException) {}
        assertEquals(BondFamily.XIAOMI, store.list().single().family)
    }
    @Test fun exportOnlySelectedRecords() {
        store.putXiaomi(mac, ByteArray(12)); store.putNinebot("11:22:33:44:55:66", ByteArray(16))
        store.export(setOf(mac)).use { assertEquals(listOf(mac), it.entries.map { e -> e.mac }) }
    }
    @Test fun exportPermitRequiresLockExpiresAndIsSingleUse() {
        var clock = 100L; val permit = ExportPermit { clock }
        assertFalse(permit.valid())
        try { permit.grant(false); fail() } catch (_: IllegalArgumentException) {}
        permit.grant(true); clock += 59_999; assertTrue(permit.valid())
        clock++; assertFalse(permit.valid())
        try { permit.consume(); fail() } catch (_: IllegalStateException) {}
        permit.grant(true); permit.consume(); assertFalse(permit.valid())
    }
    @Test fun knownAnswerImportIsReadableAtExistingXiaomiKey() {
        val hex = BondFormatTest.VECTOR.toCharArray()
        val file = try { BondInput.hex(hex, 256) } finally { hex.fill('\u0000') }
        val password = "correct horse battery".toCharArray()
        try {
            BondEnvelope.open(file, password).use { doc -> store.importEntries(doc.entries, setOf(mac), emptySet()) }
            assertEquals("000102030405060708090a0b", prefs.values[mac.uppercase() + "_token"])
        } finally { file.fill(0); password.fill('\u0000') }
    }
}
