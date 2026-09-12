package com.example.polyglotpocket.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Creates a single instance of DataStore for the entire app to prevent file locks or read/write conflicts.
private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")


// stores the session JWT with Preferences DataStore. The value is encrypted (key in Android Keystore), so it is never on disk
// in clear text
object TokenStore {
    
    // The specific label (key) we use to find our token inside the DataStore.
    private val KEY_TOKEN = stringPreferencesKey("token")

    // Takes a raw token from the server, encrypts it, and saves it to the phone asynchronously.
    suspend fun save(context: Context, token: String) {
        context.authDataStore.edit { it[KEY_TOKEN] = Encryptor.encrypt(token) }
    }

    // Fetches the encrypted token from the phone, decrypts it, and returns the raw string.
    suspend fun get(context: Context): String? {
        val stored = context.authDataStore.data.map { it[KEY_TOKEN] }.first() ?: return null
        return try {
            Encryptor.decrypt(stored)
        } catch (e: Exception) {
            // If decryption fails (e.g., the user cleared their lock screen and wiped the security keys),
            // we silently treat it as if the user is logged out, preventing the app from crashing.
            null
        }
    }

    // Deletes the token completely (used when the user logs out or the token expires).
    suspend fun clear(context: Context) {
        context.authDataStore.edit { it.remove(KEY_TOKEN) }
    }
}
