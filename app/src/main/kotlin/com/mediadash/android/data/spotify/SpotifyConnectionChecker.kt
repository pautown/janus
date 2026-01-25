package com.mediadash.android.data.spotify

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mediadash.android.di.SpotifyDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class to check Spotify connection status.
 * Used by BLE service to respond to connection status requests.
 */
@Singleton
class SpotifyConnectionChecker @Inject constructor(
    @SpotifyDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_TOKEN_EXPIRY = longPreferencesKey("token_expiry")
        private val KEY_CLIENT_ID = stringPreferencesKey("spotify_client_id")
    }

    /**
     * Check if Spotify is connected (has valid, non-expired tokens).
     *
     * @return Pair of (isConnected, errorMessage)
     */
    suspend fun checkConnection(): Pair<Boolean, String?> {
        return try {
            val prefs = dataStore.data.first()

            val clientId = prefs[KEY_CLIENT_ID]
            val accessToken = prefs[KEY_ACCESS_TOKEN]
            val tokenExpiry = prefs[KEY_TOKEN_EXPIRY] ?: 0L

            // Check if client ID is configured
            if (clientId.isNullOrBlank() || clientId.length < 20) {
                return Pair(false, null) // Not configured, but not an error
            }

            // Check if we have a token
            if (accessToken.isNullOrBlank()) {
                return Pair(false, null) // Not logged in
            }

            // Check if token is expired
            if (System.currentTimeMillis() >= tokenExpiry) {
                return Pair(false, "token_expired")
            }

            // Token is valid
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Get a simple connected/disconnected/error status string.
     */
    suspend fun getConnectionStatus(): String {
        val (connected, error) = checkConnection()
        return when {
            connected -> "connected"
            error != null -> "error:$error"
            else -> "disconnected"
        }
    }
}
