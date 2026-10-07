package com.m365bleapp.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A scooter MAC and its Xiaomi Mi-auth credential. Neither value is logged. */
data class PairingTokenRecord(val mac: String, val tokenHex: String)

/** Portable, password-encrypted backup. Keystore keys cannot travel to another phone. */
object PairingTokenBackup {
    private val magic = "M365PBK1".toByteArray(Charsets.US_ASCII)
    private const val saltSize = 16
    private const val nonceSize = 12
    private const val iterations = 210_000
    private const val maxRecords = 1_000
    const val MAX_FILE_BYTES = 64 * 1024

    fun normalizeMac(input: String): String {
        val hex = input.filterNot { it == ':' || it == '-' || it.isWhitespace() }
            .uppercase(Locale.ROOT)
        require(hex.length == 12 && hex.all { it in '0'..'9' || it in 'A'..'F' }) {
            "Enter a 12-digit Bluetooth MAC address"
        }
        return hex.chunked(2).joinToString(":")
    }

    fun normalizeToken(input: String): String {
        val trimmed = input.trim().removePrefix("0x").removePrefix("0X")
        val hex = trimmed.filterNot { it == ':' || it == '-' || it.isWhitespace() }
            .uppercase(Locale.ROOT)
        require(hex.length == 24 && hex.all { it in '0'..'9' || it in 'A'..'F' }) {
            "Enter a 12-byte token (24 hexadecimal digits)"
        }
        return hex
    }

    fun encode(records: List<PairingTokenRecord>, password: CharArray): ByteArray {
        require(password.size >= 12) { "Use a backup password of at least 12 characters" }
        require(records.isNotEmpty()) { "There are no pairing tokens to export" }
        require(records.size <= maxRecords) { "Too many pairing tokens" }
        val normalized = records.map { PairingTokenRecord(normalizeMac(it.mac), normalizeToken(it.tokenHex)) }
        require(normalized.map { it.mac }.distinct().size == normalized.size) { "Duplicate scooter address" }

        val plain = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(normalized.size)
                normalized.sortedBy { it.mac }.forEach { record ->
                    data.write(hexBytes(record.mac.replace(":", "")))
                    data.write(hexBytes(record.tokenHex))
                }
            }
            output.toByteArray()
        }
        val salt = ByteArray(saltSize).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(nonceSize).also(SecureRandom()::nextBytes)
        val header = magic + salt + nonce
        val key = deriveKey(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            return header + cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
    }

    fun decode(file: ByteArray, password: CharArray): List<PairingTokenRecord> {
        require(file.size in (magic.size + saltSize + nonceSize + 16)..MAX_FILE_BYTES) {
            "Invalid backup size"
        }
        require(file.copyOfRange(0, magic.size).contentEquals(magic)) { "Unsupported backup format" }
        val saltEnd = magic.size + saltSize
        val headerEnd = saltEnd + nonceSize
        val salt = file.copyOfRange(magic.size, saltEnd)
        val nonce = file.copyOfRange(saltEnd, headerEnd)
        val key = deriveKey(password, salt)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(file, 0, headerEnd)
            cipher.doFinal(file, headerEnd, file.size - headerEnd)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Wrong password or damaged backup")
        }
        try {
            return DataInputStream(ByteArrayInputStream(plain)).use { data ->
                val count = data.readInt()
                require(count in 1..maxRecords && plain.size == 4 + count * 18) {
                    "Invalid backup contents"
                }
                val records = List(count) {
                    val macBytes = ByteArray(6).also(data::readFully)
                    val tokenBytes = ByteArray(12).also(data::readFully)
                    val mac = macBytes.toHex().chunked(2).joinToString(":")
                    val token = tokenBytes.toHex()
                    PairingTokenRecord(mac, token)
                }
                require(records.map { it.mac }.distinct().size == count) { "Duplicate scooter address" }
                records
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
}
