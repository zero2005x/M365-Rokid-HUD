package com.m365bleapp.bond

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AndroidBondStore {
    @Volatile private var instance: BondStore? = null
    fun get(context: Context, prefs: SharedPreferences): BondStore = instance ?: synchronized(this) {
        instance ?: BondStore(KeystoreFile(context.applicationContext), object : BondPreferences {
            override fun all() = prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
            override fun update(values: Map<String, String?>) {
                val editor = prefs.edit()
                values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
                check(editor.commit())
            }
        }).also { instance = it }
    }
    private class KeystoreFile(context: Context) : BondPersistence {
        private val file = File(context.noBackupFilesDir, "pairing-bonds.enc")
        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("hud_pairing_bonds_v1", null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("hud_pairing_bonds_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        override fun read(): ByteArray? {
            if (!file.exists()) return null
            require(file.length() in 28..BondEnvelope.MAX_BYTES.toLong())
            val bytes = file.readBytes()
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                cipher.doFinal(bytes, 12, bytes.size - 12)
            } finally { bytes.fill(0) }
        }
        override fun write(plain: ByteArray) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.iv + cipher.doFinal(plain)
            val temporary = File(file.parentFile, file.name + ".tmp")
            try {
                FileOutputStream(temporary).use { it.write(sealed); it.fd.sync() }
                check(temporary.renameTo(file))
            } finally { sealed.fill(0); temporary.delete() }
        }
    }
}

object BondFiles {
    fun read(context: Context, uri: Uri): ByteArray {
        require(uri.scheme == "content")
        return context.contentResolver.openInputStream(uri)?.use(BondStreams::read) ?: error("Cannot read document")
    }
    fun write(context: Context, uri: Uri, bytes: ByteArray, permit: ExportPermit) {
        require(uri.scheme == "content")
        check(permit.valid())
        context.contentResolver.openOutputStream(uri, "wt")?.use { permit.consume(); it.write(bytes) } ?: error("Cannot write document")
    }
}
