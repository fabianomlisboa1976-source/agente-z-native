package dev.mindmax.v4.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wraps EncryptedSharedPreferences so the rest of the app never imports Tink/Keystore APIs.
 * The master key lives in Android Keystore (AES-256-GCM); values are AEAD-encrypted with
 * AES-256-SIV keys and AES-256-GCM values.
 *
 * The single API key stored here is the only one that lives outside Room — the Settings
 * table only holds a sentinel `__ENC__:` marker, so plaintext key values never sit on
 * disk in SQLite.
 *
 * Degradation: if the device has no lock screen configured, Android Keystore still
 * generates a software-backed master key. The API key is encrypted at rest either way.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "mindmax_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Returns the decrypted API key, or null if none stored. */
    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_API_KEY, value).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
    }
}
