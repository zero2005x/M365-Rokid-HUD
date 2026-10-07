package com.m365bleapp.bond

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BondFormatTest {
    private val password get() = "correct horse battery".toCharArray()
    companion object { const val VECTOR = "5246424f4e4401000186a0000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a6a7a8a9aaab3d8433dda0721f193807e6681bf94d56745abf6a49da176ea9317691966a4255a2430e53a42f7d85010417d3c9081d0ba5f520db5f221d84f4560558f6506e1e802b10266494730680ce6d62a12d12139fd3e9174860f9ccae5c24a66de121223eac3cf6e4d1f8327f0d4cc80651c7ca28330957297df288abb74c82d374c42a14d18d9b0b024e13d8229d8fbe7307b2fec4d82999ac5896fe2a9462f8a9488e15ae3f5ef1045d957d5beb8a882206f1c90923dc08371c398bdd56f11e4ae72a27014188e3d5b484b8511c80de5defaaf31a7299bac0f8dccb" }
    private fun bytes(hex: String) = BondInput.hex(hex.toCharArray(), hex.length / 2)
    private fun entry() = BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.XIAOMI, ByteArray(12) { it.toByte() }, "My M365", "M365")
    private fun seal(entries: List<BondEntry>) = BondEnvelope.seal(entries, password, iterations = 100_000)
    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} }
    private fun json(entries: String, extra: String = "") = "{\"schema\":\"rideflux-bond/v1\",\"createdAt\":\"2026-10-05T00:00:00Z\",\"entries\":[$entries]$extra}".toByteArray()
    private fun raw(mac: String = "AA:BB:CC:DD:EE:FF", family: String = "xiaomi_mi", hex: String = "000102030405060708090a0b") =
        "{\"mac\":\"$mac\",\"family\":\"$family\",\"credentialHex\":\"$hex\"}"

    @Test fun knownAnswerSealsExactly() {
        entry().use { e ->
            val file = BondEnvelope.seal(listOf(e), password, "2026-10-05T00:00:00Z", 100_000,
                ByteArray(16) { it.toByte() }, ByteArray(12) { (0xa0 + it).toByte() })
            assertEquals(256, file.size)
            assertArrayEquals(bytes(VECTOR), file)
        }
    }
    @Test fun knownAnswerOpensExactly() {
        BondEnvelope.open(bytes(VECTOR), password).use { doc ->
            assertEquals(1, doc.entries.size); val e = doc.entries.single()
            assertEquals("AA:BB:CC:DD:EE:FF", e.mac); assertEquals(BondFamily.XIAOMI, e.family)
            assertArrayEquals(ByteArray(12) { it.toByte() }, e.credential)
        }
    }
    @Test fun independentOpenerReadsHudExport() {
        val records = listOf(entry(), BondEntry("11:22:33:44:55:66", BondFamily.NINEBOT, ByteArray(16) { (it + 50).toByte() }))
        try {
            val file = seal(records)
            val spec = PBEKeySpec(password, file.copyOfRange(11, 27), ByteBuffer.wrap(file, 7, 4).int, 256)
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, file.copyOfRange(27, 39)))
            cipher.updateAAD(file, 0, 39)
            val plain = cipher.doFinal(file, 39, file.size - 39)
            BondJson.decode(plain).use { doc ->
                assertEquals(listOf(BondFamily.XIAOMI, BondFamily.NINEBOT), doc.entries.map { it.family })
                assertEquals(listOf(12, 16), doc.entries.map { it.credential.size })
            }
            plain.fill(0); key.fill(0); spec.clearPassword()
        } finally { records.forEach { it.close() } }
    }
    @Test fun passwordPolicyAndConfirmation() {
        assertFalse(BondInput.strong("abcde1234".toCharArray()))
        assertFalse(BondInput.strong("aaaaaaaaab".toCharArray()))
        assertTrue(BondInput.strong("aaaaaaabcd".toCharArray()))
        assertFalse(BondInput.passwords(password, "different password".toCharArray()))
    }
    @Test fun wrongPasswordAndTamperingFail() {
        val file = bytes(VECTOR)
        try { BondEnvelope.open(file, "wrong password".toCharArray()); fail() } catch (_: Exception) {}
        for (offset in listOf(6, 10, 11, 27, 39, 255)) {
            val changed = file.copyOf(); changed[offset] = (changed[offset].toInt() xor 1).toByte()
            try { BondEnvelope.open(changed, password); fail("Tampering accepted at $offset") } catch (_: Exception) {}
        }
    }
    @Test fun rejectBadMagicAndOversizeBeforeDerivation() {
        reject { BondEnvelope.open(ByteArray(BondEnvelope.MAX_BYTES + 1), charArrayOf()) }
        val bad = bytes(VECTOR); bad[0] = 0
        reject { BondEnvelope.open(bad, password) }
        val expensive = bytes(VECTOR); ByteBuffer.wrap(expensive, 7, 4).putInt(Int.MAX_VALUE)
        reject { BondEnvelope.open(expensive, password) }
    }
    @Test fun unknownFamiliesSkippedAndUnknownFieldsIgnored() {
        val file = json(raw() + "," + raw("11:22:33:44:55:66", "future_family", "ABCD"), ",\"future\":{\"items\":[true,null,3]}")
        BondJson.decode(file).use { assertEquals(1, it.entries.size); assertEquals(1, it.skipped) }
    }
    @Test fun malformedEntryRejectsWholeDocument() {
        reject { BondJson.decode(json(raw() + "," + raw())) }
        reject { BondJson.decode(json(raw(hex = "00"))) }
        reject { BondJson.decode(json(raw(family = "ninebot_crypto"))) }
        reject { BondJson.decode(json(raw(mac = "aa:bb:cc:dd:ee:ff"))) }
        reject { BondJson.decode(json((1..65).joinToString(",") { raw("00:00:00:00:00:%02X".format(it)) })) }
        reject { BondJson.decode(json(raw().dropLast(1) + ",\"label\":\"bad\\nlabel\"}")) }
        reject { BondJson.decode(json(raw().dropLast(1) + ",\"label\":\"${"x".repeat(65)}\"}")) }
    }
    @Test fun manualMacForms() {
        listOf("aa:bb:cc:dd:ee:ff", "aabbccddeeff", "aa-bb-cc-dd-ee-ff").forEach { assertEquals("AA:BB:CC:DD:EE:FF", BondInput.mac(it)) }
        reject { BondInput.mac("AA:BB-CC:DD:EE:FF") }
    }
    @Test fun manualKeyFormsAndLengths() {
        val expected = ByteArray(12) { it.toByte() }
        listOf("000102030405060708090a0b", "0x000102030405060708090a0b", "00 01 02 03 04 05 06 07 08 09 0a 0b", "00:01:02:03:04:05:06:07:08:09:0a:0b").forEach {
            assertArrayEquals(expected, BondInput.hex(it.toCharArray(), 12, true))
        }
        reject { BondInput.hex("00".repeat(11).toCharArray(), 12, true) }
        reject { BondInput.hex("00".repeat(13).toCharArray(), 12, true) }
        assertEquals(16, BondInput.hex("0x".plus("AA ".repeat(16)).toCharArray(), 16, true).size)
    }
    @Test fun utf8MetadataAndPasswordsRoundTrip() {
        BondEntry("AA:BB:CC:DD:EE:FF", BondFamily.XIAOMI, ByteArray(12), "滑板車🛴").use { entry ->
            val password = "口令abcd滑板車1234".toCharArray()
            BondEnvelope.open(BondEnvelope.seal(listOf(entry), password, iterations = 100_000), password).use {
                assertEquals(entry.label, it.entries.single().label)
            }
        }
    }
    @Test fun maskedAddressContainsOnlyLastTwoOctets() { entry().use { assertEquals("••:••:••:••:EE:FF", it.maskedMac) } }
}
