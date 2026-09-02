package dev.mindmax.v4.core.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Non-secret prefs (UI choices, last-selected provider). Stored via DataStore because
 * SharedPreferences is the legacy path and DataStore handles Flow reads/writes cleanly.
 */
class AppPrefs(private val context: Context) {

    private val Context.dataStore by preferencesDataStore(name = "mindmax_prefs")

    private val keyLastProvider = stringPreferencesKey("last_provider")

    val lastProviderFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[keyLastProvider]
    }

    suspend fun setLastProvider(providerId: String) {
        context.dataStore.edit { prefs ->
            prefs[keyLastProvider] = providerId
        }
    }

    @Suppress("unused")
    private fun ignore(prefs: Preferences) = Unit
}
