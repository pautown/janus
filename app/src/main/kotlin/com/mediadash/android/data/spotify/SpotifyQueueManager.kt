package com.mediadash.android.data.spotify

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mediadash.android.di.SpotifyDataStore
import com.mediadash.android.domain.model.QueueResponse
import com.mediadash.android.domain.model.QueueTrack
import com.spotsdk.api.SpotifyApiClient
import com.spotsdk.api.createSimple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for Spotify queue operations.
 * Used by BLE service to fetch queue and skip to positions.
 */
@Singleton
class SpotifyQueueManager @Inject constructor(
    @SpotifyDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val TAG = "SpotifyQueueManager"
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_TOKEN_EXPIRY = longPreferencesKey("token_expiry")
    }

    /**
     * Check if Spotify is connected with a valid token.
     */
    suspend fun isConnected(): Boolean {
        return try {
            val prefs = dataStore.data.first()
            val accessToken = prefs[KEY_ACCESS_TOKEN]
            val tokenExpiry = prefs[KEY_TOKEN_EXPIRY] ?: 0L
            val currentTime = System.currentTimeMillis()

            Log.d(TAG, "=== Checking Spotify connection ===")
            Log.d(TAG, "   Access token present: ${!accessToken.isNullOrBlank()}")
            Log.d(TAG, "   Token expiry: $tokenExpiry")
            Log.d(TAG, "   Current time: $currentTime")
            Log.d(TAG, "   Token valid: ${currentTime < tokenExpiry}")

            val isConnected = !accessToken.isNullOrBlank() && currentTime < tokenExpiry
            Log.d(TAG, "   Result: $isConnected")
            isConnected
        } catch (e: Exception) {
            Log.e(TAG, "Error checking connection", e)
            false
        }
    }

    /**
     * Get the current access token.
     */
    private suspend fun getAccessToken(): String? {
        return try {
            val prefs = dataStore.data.first()
            val accessToken = prefs[KEY_ACCESS_TOKEN]
            val tokenExpiry = prefs[KEY_TOKEN_EXPIRY] ?: 0L

            if (!accessToken.isNullOrBlank() && System.currentTimeMillis() < tokenExpiry) {
                accessToken
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting access token", e)
            null
        }
    }

    /**
     * Fetch the current playback queue from Spotify.
     *
     * @return QueueResponse if successful, null otherwise
     */
    suspend fun fetchQueue(): QueueResponse? {
        val accessToken = getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Cannot fetch queue: no valid access token")
            return null
        }

        return try {
            Log.d(TAG, "=== Fetching Spotify queue ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.getQueue()
            }

            Log.d(TAG, "Queue response code: ${response.code()}")

            if (response.isSuccessful) {
                val queueResponse = response.body()
                if (queueResponse != null) {
                    // Convert currently playing track
                    val currentTrack = queueResponse.currentlyPlaying?.let { track ->
                        if (track.name != null) {
                            QueueTrack(
                                title = track.name,
                                artist = track.artists?.firstOrNull()?.name ?: "Unknown Artist",
                                album = track.album?.name,
                                durationMs = track.durationMs,
                                uri = track.uri
                            )
                        } else null
                    }

                    // Convert queue tracks
                    val tracks = queueResponse.queue?.mapNotNull { track ->
                        if (track.name != null) {
                            QueueTrack(
                                title = track.name,
                                artist = track.artists?.firstOrNull()?.name ?: "Unknown Artist",
                                album = track.album?.name,
                                durationMs = track.durationMs,
                                uri = track.uri
                            )
                        } else null
                    } ?: emptyList()

                    Log.d(TAG, "Queue has ${tracks.size} tracks, currently playing: ${currentTrack?.title}")

                    QueueResponse(
                        service = QueueResponse.SERVICE_SPOTIFY,
                        tracks = tracks,
                        currentlyPlaying = currentTrack,
                        timestamp = System.currentTimeMillis()
                    )
                } else {
                    Log.d(TAG, "Queue response body is null")
                    null
                }
            } else if (response.code() == 204) {
                Log.d(TAG, "No active device for queue (204)")
                // Return empty queue response
                QueueResponse(
                    service = QueueResponse.SERVICE_SPOTIFY,
                    tracks = emptyList(),
                    currentlyPlaying = null,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Failed to get queue: ${response.code()}")
                Log.e(TAG, "Error body: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching queue: ${e.message}", e)
            null
        }
    }

    /**
     * Skip to a specific position in the queue.
     * Position 0 = first track in queue (requires 1 skip), position 1 = second track (2 skips), etc.
     *
     * @param queueIndex 0-based index in the queue
     * @return true if successful, false otherwise
     */
    suspend fun skipToPosition(queueIndex: Int): Boolean {
        val accessToken = getAccessToken()
        if (accessToken == null) {
            Log.e(TAG, "Cannot skip: no valid access token")
            return false
        }

        if (queueIndex < 0) {
            Log.e(TAG, "Invalid queue index: $queueIndex")
            return false
        }

        return try {
            // Need to skip (queueIndex + 1) times to reach the desired track
            // queueIndex 0 = first track in queue = 1 skip
            val skipsNeeded = queueIndex + 1
            Log.d(TAG, "=== Skipping to queue position $queueIndex (${skipsNeeded} skips) ===")

            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

            var success = true
            for (i in 1..skipsNeeded) {
                Log.d(TAG, "Skip $i of $skipsNeeded...")
                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.skipToNext()
                }

                Log.d(TAG, "Skip $i response code: ${response.code()}")

                if (!response.isSuccessful && response.code() != 204) {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to skip at step $i: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                    success = false
                    break
                }

                // Small delay between skips to avoid rate limiting
                if (i < skipsNeeded) {
                    delay(100)
                }
            }

            if (success) {
                Log.d(TAG, "Successfully skipped to queue position $queueIndex")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception skipping to position: ${e.message}", e)
            false
        }
    }
}
