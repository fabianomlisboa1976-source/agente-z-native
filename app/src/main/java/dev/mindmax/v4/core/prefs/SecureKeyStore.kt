package dev.mindmax.v4.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wraps EncryptedSharedPreferences so the rest of the app never imports Tink/Keystore APIs.
 *
 * Two implementations:
 *   - [EncryptedBackend] — production path. Master key lives in Android Keystore
 *     (AES-256-GCM); values are AEAD-encrypted with AES-256-SIV (keys) and
 *     AES-256-GCM (values).
 *   - [PlaintextBackend] — emergency fallback used only when the device's
 *     Keystore refuses to mint a key (corrupted state, broken TEE, restored
 *     backup that pre-dates the master key). Plaintext is better than crashing
 *     the entire app — the user can still set a key and continue chatting.
 *
 * The single API key stored here is the only one that lives outside Room —
 * the Settings table only holds a sentinel `__ENC__:` marker so plaintext key
 * values never sit on disk in SQLite.
 *
 * Degradation: if the device has no lock screen configured, Android Keystore
 * still generates a software-backed master key. The API key is encrypted at
 * rest either way.
 */
class SecureKeyStore(context: Context) {

    private val backend: Backend = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            "mindmax_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        EncryptedBackend(prefs).also {
            Log.i(TAG, "SecureKeyStore using EncryptedSharedPreferences.")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "EncryptedSharedPreferences unavailable (${t.javaClass.simpleName}: ${t.message}); falling back to plaintext prefs.")
        PlaintextBackend(context)
    }

    /** Returns the decrypted API key, or null if none stored. */
    fun getApiKey(): String? = backend.getString(KEY_API_KEY)

    fun setApiKey(value: String) {
        backend.putString(KEY_API_KEY, value)
    }

    fun clearApiKey() {
        backend.remove(KEY_API_KEY)
    }

    private interface Backend {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun remove(key: String)
    }

    private class EncryptedBackend(private val prefs: SharedPreferences) : Backend {
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }
    }

    /**
     * Last-resort fallback that stores the key unencrypted in a private SharedPreferences
     * file. Used only when the Keystore can't mint a master key — exactly the case where
     * crashing the app would block every install that hits the broken device.
     */
    private class PlaintextBackend(context: Context) : Backend {
        private val prefs: SharedPreferences = context.applicationContext
            .getSharedPreferences("mindmax_secure_fallback", Context.MODE_PRIVATE)

        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val TAG = "SecureKeyStore"
    }
}
