package com.example.polyglotpocket.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Single DataStore instance per process (required by DataStore).
private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

/**
 * Stores the session JWT with Preferences DataStore. The value is encrypted with
 * [Encryptor] (AES/GCM, key in the Android Keystore), so it is never on disk in
 * clear text.
 */
object TokenStore {
    private val KEY_TOKEN = stringPreferencesKey("token")

    suspend fun save(context: Context, token: String) {
        context.authDataStore.edit { it[KEY_TOKEN] = Encryptor.encrypt(token) }
    }

    suspend fun get(context: Context): String? {
        val stored = context.authDataStore.data.map { it[KEY_TOKEN] }.first() ?: return null
        return try {
            Encryptor.decrypt(stored)
        } catch (e: Exception) {
            // Key gone (app reinstalled) or corrupted data: treat as no token.
            null
        }
    }

    suspend fun clear(context: Context) {
        context.authDataStore.edit { it.remove(KEY_TOKEN) }
    }
}
