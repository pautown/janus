package com.mediadash.android.data.spotify

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mediadash.android.di.SpotifyDataStore
import com.mediadash.android.domain.model.SpotifyPlaybackState
import com.spotsdk.api.SpotifyApiClient
import com.spotsdk.api.createSimple
import com.spotsdk.models.Album
import com.spotsdk.models.Artist
import com.spotsdk.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller for Spotify playback operations that require Web API access.
 * Handles shuffle, repeat, and like/unlike track operations.
 *
 * Used by BLE service to execute Spotify-specific commands from CarThing.
 */
@Singleton
class SpotifyPlaybackController @Inject constructor(
    @SpotifyDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val TAG = "SpotifyPlaybackCtrl"
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_TOKEN_EXPIRY = longPreferencesKey("token_expiry")
    }

    // Cached playback state for toggle operations
    private var cachedShuffleState: Boolean = false
    private var cachedRepeatState: String = "off"
    private var cachedCurrentTrackId: String? = null
    private var cachedCurrentAlbumId: String? = null
    private var cachedCurrentArtistId: String? = null

    /**
     * Check if Spotify is connected with a valid token.
     */
    suspend fun isConnected(): Boolean {
        return try {
            val prefs = dataStore.data.first()
            val accessToken = prefs[KEY_ACCESS_TOKEN]
            val tokenExpiry = prefs[KEY_TOKEN_EXPIRY] ?: 0L
            val currentTime = System.currentTimeMillis()
            !accessToken.isNullOrBlank() && currentTime < tokenExpiry
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
     * Fetch current playback state (shuffle, repeat, current track).
     * Updates cached state for toggle operations.
     *
     * @return SpotifyPlaybackState if successful, null otherwise
     */
    suspend fun fetchPlaybackState(): SpotifyPlaybackState? {
        val accessToken = getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Cannot fetch playback state: no valid access token")
            return null
        }

        return try {
            Log.d(TAG, "=== Fetching Spotify playback state ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.getPlaybackState()
            }

            Log.d(TAG, "Playback state response code: ${response.code()}")

            if (response.isSuccessful) {
                val state = response.body()
                if (state != null) {
                    // Update cached state
                    cachedShuffleState = state.shuffleState ?: false
                    cachedRepeatState = state.repeatState ?: "off"
                    cachedCurrentTrackId = state.item?.id
                    cachedCurrentAlbumId = state.item?.album?.id
                    cachedCurrentArtistId = state.item?.artists?.firstOrNull()?.id

                    val playbackState = SpotifyPlaybackState(
                        shuffleEnabled = cachedShuffleState,
                        repeatMode = cachedRepeatState,
                        currentTrackId = cachedCurrentTrackId,
                        currentAlbumId = cachedCurrentAlbumId,
                        currentArtistId = cachedCurrentArtistId,
                        isPlaying = state.isPlaying ?: false,
                        deviceId = state.device?.id,
                        deviceName = state.device?.name
                    )

                    Log.d(TAG, "Playback state: shuffle=$cachedShuffleState, repeat=$cachedRepeatState, trackId=$cachedCurrentTrackId, albumId=$cachedCurrentAlbumId, artistId=$cachedCurrentArtistId")
                    playbackState
                } else {
                    Log.d(TAG, "Playback state body is null (no active session)")
                    null
                }
            } else if (response.code() == 204) {
                Log.d(TAG, "No active device (204)")
                null
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Failed to get playback state: ${response.code()}")
                Log.e(TAG, "Error body: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching playback state: ${e.message}", e)
            null
        }
    }

    /**
     * Set shuffle state.
     *
     * @param enabled true to enable shuffle, false to disable
     * @return true if successful, false otherwise
     */
    suspend fun setShuffle(enabled: Boolean): Boolean {
        val accessToken = getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Cannot set shuffle: no valid access token")
            return false
        }

        return try {
            Log.d(TAG, "=== Setting shuffle to $enabled ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.setShuffle(state = enabled)
            }

            Log.d(TAG, "Set shuffle response code: ${response.code()}")

            if (response.isSuccessful || response.code() == 204) {
                cachedShuffleState = enabled
                Log.d(TAG, "Successfully set shuffle to $enabled")
                true
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Failed to set shuffle: ${response.code()}")
                Log.e(TAG, "Error body: $errorBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception setting shuffle: ${e.message}", e)
            false
        }
    }

    /**
     * Toggle shuffle state.
     *
     * @return true if successful, false otherwise
     */
    suspend fun toggleShuffle(): Boolean {
        // Refresh state first to ensure we have current value
        fetchPlaybackState()
        return setShuffle(!cachedShuffleState)
    }

    /**
     * Set repeat mode.
     *
     * @param mode "off", "track", or "context"
     * @return true if successful, false otherwise
     */
    suspend fun setRepeat(mode: String): Boolean {
        val accessToken = getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Cannot set repeat: no valid access token")
            return false
        }

        val validMode = when (mode.lowercase()) {
            "off" -> "off"
            "track" -> "track"
            "context" -> "context"
            else -> {
                Log.e(TAG, "Invalid repeat mode: $mode")
                return false
            }
        }

        return try {
            Log.d(TAG, "=== Setting repeat to $validMode ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.setRepeat(state = validMode)
            }

            Log.d(TAG, "Set repeat response code: ${response.code()}")

            if (response.isSuccessful || response.code() == 204) {
                cachedRepeatState = validMode
                Log.d(TAG, "Successfully set repeat to $validMode")
                true
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Failed to set repeat: ${response.code()}")
                Log.e(TAG, "Error body: $errorBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception setting repeat: ${e.message}", e)
            false
        }
    }

    /**
     * Cycle repeat mode: off -> track -> context -> off
     *
     * @return true if successful, false otherwise
     */
    suspend fun cycleRepeat(): Boolean {
        // Refresh state first
        fetchPlaybackState()

        val nextMode = when (cachedRepeatState) {
            "off" -> "track"
            "track" -> "context"
            else -> "off"
        }

        Log.d(TAG, "Cycling repeat from $cachedRepeatState to $nextMode")
        return setRepeat(nextMode)
    }

    /**
     * Save a track to the user's library (like).
     *
     * @param trackId Spotify track ID, or null to use current track
     * @return true if successful, false otherwise
     */
    suspend fun saveTrack(trackId: String?): Boolean {
        val accessToken = getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Cannot save track: no valid access token")
            return false
        }

        // Use provided trackId or fall back to cached current track
        val targetTrackId = trackId ?: cachedCurrentTrackId
        if (targetTrackId.isNullOrBlank()) {
            // Try to get current track
            fetchPlaybackState()
            if (cachedCurrentTrackId.isNullOrBlank()) {
                Log.w(TAG, "Cannot save track: no track ID provided and no current track")
                return false
            }
        }

        val finalTrackId = targetTrackId ?: cachedCurrentTrackId!!

        return try {
            Log.d(TAG, "=== Saving track $finalTrackId to library ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.saveTracks(ids = finalTrackId)
            }

            Log.d(TAG, "Save track response code: ${response.code()}")

            if (response.isSuccessful || response.code() == 200) {
                Log.d(TAG, "Successfully saved track $finalTrackId")
                true
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Failed to save track: ${response.code()}")
                Log.e(TAG, "Error body: $errorBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception saving track: ${e.message}", e)
            false
        }
    }

    /**
     * Remove a track from the user's library (unlike).
     *
     * @param trackId Spotify track ID, or null to use current track
     * @return true if successful, false otherwise
     */
    suspend fun removeTrack(trackId: String?): Boolean {
        val accessToken = getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Cannot remove track: no valid access token")
            return false
        }

        // Use provided trackId or fall back to cached current track
        val targetTrackId = trackId ?: cachedCurrentTrackId
        if (targetTrackId.isNullOrBlank()) {
            // Try to get current track
            fetchPlaybackState()
            if (cachedCurrentTrackId.isNullOrBlank()) {
                Log.w(TAG, "Cannot remove track: no track ID provided and no current track")
                return false
            }
        }

        val finalTrackId = targetTrackId ?: cachedCurrentTrackId!!

        return try {
            Log.d(TAG, "=== Removing track $finalTrackId from library ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.removeSavedTracks(ids = finalTrackId)
            }

            Log.d(TAG, "Remove track response code: ${response.code()}")

            if (response.isSuccessful || response.code() == 200) {
                Log.d(TAG, "Successfully removed track $finalTrackId")
                true
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Failed to remove track: ${response.code()}")
                Log.e(TAG, "Error body: $errorBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception removing track: ${e.message}", e)
            false
        }
    }

    /**
     * Check if a track is saved in the user's library.
     *
     * @param trackId Spotify track ID, or null to use current track
     * @return true if saved, false otherwise (or on error)
     */
    suspend fun isTrackSaved(trackId: String?): Boolean {
        val accessToken = getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Cannot check track: no valid access token")
            return false
        }

        val targetTrackId = trackId ?: cachedCurrentTrackId
        if (targetTrackId.isNullOrBlank()) {
            fetchPlaybackState()
            if (cachedCurrentTrackId.isNullOrBlank()) {
                Log.w(TAG, "Cannot check track: no track ID")
                return false
            }
        }

        val finalTrackId = targetTrackId ?: cachedCurrentTrackId!!

        return try {
            Log.d(TAG, "=== Checking if track $finalTrackId is saved ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.checkSavedTracks(ids = finalTrackId)
            }

            Log.d(TAG, "Check saved track response code: ${response.code()}")

            if (response.isSuccessful) {
                val results = response.body()
                val isSaved = results?.firstOrNull() ?: false
                Log.d(TAG, "Track $finalTrackId is saved: $isSaved")
                isSaved
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Failed to check track: ${response.code()}")
                Log.e(TAG, "Error body: $errorBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception checking track: ${e.message}", e)
            false
        }
    }

    /**
     * Fetch full album details by Spotify album ID.
     *
     * @param albumId Spotify album ID
     * @return Album object if successful, null otherwise
     */
    suspend fun fetchAlbumDetails(albumId: String): Album? {
        val accessToken = getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Cannot fetch album details: no valid access token")
            return null
        }

        return try {
            Log.d(TAG, "=== Fetching album details for $albumId ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.getAlbum(albumId)
            }

            if (response.isSuccessful) {
                response.body()
            } else {
                Log.e(TAG, "Failed to get album details: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching album details: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch artist details and top tracks by Spotify artist ID.
     *
     * @param artistId Spotify artist ID
     * @return Pair of (Artist, top tracks list) if successful, null otherwise
     */
    suspend fun fetchArtistDetails(artistId: String): Pair<Artist, List<Track>>? {
        val accessToken = getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "Cannot fetch artist details: no valid access token")
            return null
        }

        return try {
            Log.d(TAG, "=== Fetching artist details for $artistId ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)

            val artistResponse = withContext(Dispatchers.IO) {
                apiClient.apiService.getArtist(artistId)
            }

            val topTracksResponse = withContext(Dispatchers.IO) {
                apiClient.apiService.getArtistTopTracks(artistId)
            }

            val artist = if (artistResponse.isSuccessful) artistResponse.body() else null
            val topTracks = if (topTracksResponse.isSuccessful) topTracksResponse.body()?.tracks else null

            if (artist != null) {
                Pair(artist, topTracks ?: emptyList())
            } else {
                Log.e(TAG, "Failed to get artist details: ${artistResponse.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching artist details: ${e.message}", e)
            null
        }
    }

    /**
     * Get the current cached shuffle state.
     */
    fun getCachedShuffleState(): Boolean = cachedShuffleState

    /**
     * Get the current cached repeat state.
     */
    fun getCachedRepeatState(): String = cachedRepeatState

    /**
     * Get the current cached track ID.
     */
    fun getCachedCurrentTrackId(): String? = cachedCurrentTrackId

    /**
     * Get the current cached album ID.
     */
    fun getCachedCurrentAlbumId(): String? = cachedCurrentAlbumId

    /**
     * Get the current cached artist ID (primary artist).
     */
    fun getCachedCurrentArtistId(): String? = cachedCurrentArtistId
}
