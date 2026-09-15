@file:Suppress("DEPRECATION")

package com.m365bleapp.pairing

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.util.Locale

/** 配對序號與 App 配對金鑰均由 Android Keystore 保護。 */
@Suppress("DEPRECATION")
class EncryptedPairingStore(context: Context) : PairingCredentialStore {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "vehicle_pairing",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private fun prefix(address: String): String {
        require(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(address))
        return address.uppercase(Locale.ROOT)
    }
    override fun load(address: String): PairingCredentials? {
        val prefix = prefix(address)
        val serial = prefs.getString("${prefix}/serial", null) ?: return null
        val encoded = prefs.getString("${prefix}/key", null) ?: return null
        val key = try { Base64.decode(encoded, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { return null }
        return try { PairingCredentials(serial, key) } catch (_: IllegalArgumentException) { null }
        finally { key.fill(0) }
    }
    override fun save(address: String, credentials: PairingCredentials) {
        val prefix = prefix(address)
        val key = credentials.keyCopy()
        try {
            val saved = prefs.edit().putString("${prefix}/serial", credentials.serial)
                .putString("${prefix}/key", Base64.encodeToString(key, Base64.NO_WRAP)).commit()
            if (!saved) throw IOException("無法安全儲存配對資料")
        } finally { key.fill(0) }
    }
}
