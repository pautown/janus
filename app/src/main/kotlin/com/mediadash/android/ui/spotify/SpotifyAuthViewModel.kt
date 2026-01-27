package com.mediadash.android.ui.spotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediadash.android.di.SpotifyDataStore
import com.spotsdk.SpotSDK
import com.spotsdk.SpotifyResult
import com.spotsdk.api.SpotifyApiClient
import com.spotsdk.api.PlaybackStateResponse
import com.spotsdk.api.SpotifyApiService
import com.spotsdk.api.createSimple
import com.spotsdk.auth.SpotifyAuthManager
import com.spotsdk.auth.TokenResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import javax.inject.Inject

/**
 * Tab options for the Spotify library browser.
 */
enum class SpotifyTab {
    OVERVIEW,
    RECENT,
    LIKED,
    ALBUMS,
    ARTISTS,
    PLAYLISTS
}

/**
 * UI state for Spotify authentication.
 */
data class SpotifyAuthUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingStats: Boolean = false,
    val userName: String? = null,
    val userEmail: String? = null,
    val userImageUrl: String? = null,
    val errorMessage: String? = null,
    val accessToken: String? = null,
    val clientId: String = "",
    val isClientIdConfigured: Boolean = false,
    val showClientIdInput: Boolean = false,
    // User profile data
    val userId: String? = null,
    val country: String? = null,
    val product: String? = null, // free, premium, etc.
    val followerCount: Int = 0,
    // Library stats
    val savedTracksCount: Int? = null,
    val savedAlbumsCount: Int? = null,
    val playlistsCount: Int? = null,
    val followedArtistsCount: Int? = null,
    // Recent activity
    val recentTrackName: String? = null,
    val recentTrackArtist: String? = null,
    val currentlyPlaying: String? = null,
    // Playback controls
    val shuffleState: ShuffleState = ShuffleState.OFF,
    val repeatState: RepeatState = RepeatState.OFF,
    val isTogglingPlayback: Boolean = false,
    // Queue
    val queueTracks: List<QueueTrack> = emptyList(),
    val isLoadingQueue: Boolean = false,
    // Tab navigation
    val selectedTab: SpotifyTab = SpotifyTab.OVERVIEW,
    // Library lists
    val recentTracks: List<RecentTrackItem> = emptyList(),
    val savedTracks: List<SavedTrackItem> = emptyList(),
    val savedAlbums: List<SavedAlbumItem> = emptyList(),
    val followedArtists: List<ArtistItem> = emptyList(),
    val playlists: List<PlaylistItem> = emptyList(),
    // Loading states for lists
    val isLoadingRecent: Boolean = false,
    val isLoadingSaved: Boolean = false,
    val isLoadingAlbums: Boolean = false,
    val isLoadingArtists: Boolean = false,
    val isLoadingPlaylists: Boolean = false,
    // Pagination
    val hasMoreRecent: Boolean = false,
    val hasMoreSaved: Boolean = false,
    val hasMoreAlbums: Boolean = false,
    val hasMoreArtists: Boolean = false,
    val nextArtistsCursor: String? = null,
    val hasMorePlaylists: Boolean = false,
    // Track save state checking
    val savedTrackIds: Set<String> = emptySet()
)

/**
 * Simplified track info for the queue display.
 */
data class QueueTrack(
    val name: String,
    val artist: String,
    val albumName: String?,
    val imageUrl: String?,
    val durationMs: Long,
    val uri: String
)

/**
 * Track info for recently played list.
 */
data class RecentTrackItem(
    val id: String,
    val name: String,
    val artist: String,
    val albumName: String?,
    val imageUrl: String?,
    val playedAt: String?,
    val uri: String,
    val durationMs: Long
)

/**
 * Track info for saved/liked tracks list.
 */
data class SavedTrackItem(
    val id: String,
    val name: String,
    val artist: String,
    val albumName: String?,
    val imageUrl: String?,
    val addedAt: String?,
    val uri: String,
    val durationMs: Long
)

/**
 * Album info for saved albums list.
 */
data class SavedAlbumItem(
    val id: String,
    val name: String,
    val artist: String,
    val imageUrl: String?,
    val trackCount: Int,
    val addedAt: String?,
    val uri: String
)

/**
 * Playlist info for playlists list.
 */
data class PlaylistItem(
    val id: String,
    val name: String,
    val ownerName: String?,
    val imageUrl: String?,
    val trackCount: Int,
    val isPublic: Boolean?,
    val uri: String
)

/**
 * Artist info for followed artists list.
 */
data class ArtistItem(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val genres: List<String>,
    val followerCount: Int,
    val uri: String
)

/**
 * Shuffle states for Spotify playback.
 */
enum class ShuffleState {
    OFF,        // Regular playback order
    ON,         // Standard shuffle
    SMART       // Smart shuffle (Spotify's AI-based shuffle)
}

/**
 * Repeat states for Spotify playback.
 */
enum class RepeatState {
    OFF,        // No repeat
    TRACK,      // Repeat current track
    CONTEXT     // Repeat playlist/album
}

/**
 * Events that can occur during Spotify authentication.
 */
sealed class SpotifyAuthEvent {
    data object Login : SpotifyAuthEvent()
    data object Logout : SpotifyAuthEvent()
    data object ClearError : SpotifyAuthEvent()
    data object ToggleClientIdInput : SpotifyAuthEvent()
    data class SaveClientId(val clientId: String) : SpotifyAuthEvent()
    data object ClearClientId : SpotifyAuthEvent()
    data object RefreshLibraryStats : SpotifyAuthEvent()
    data object ToggleShuffle : SpotifyAuthEvent()
    data object ToggleRepeat : SpotifyAuthEvent()
    data object RefreshPlaybackState : SpotifyAuthEvent()
    data object RefreshQueue : SpotifyAuthEvent()
    data class SkipToPosition(val position: Int) : SpotifyAuthEvent()
    // Tab navigation
    data class SelectTab(val tab: SpotifyTab) : SpotifyAuthEvent()
    // Library browsing
    data object LoadRecentTracks : SpotifyAuthEvent()
    data object LoadMoreRecentTracks : SpotifyAuthEvent()
    data object LoadSavedTracks : SpotifyAuthEvent()
    data object LoadMoreSavedTracks : SpotifyAuthEvent()
    data object LoadSavedAlbums : SpotifyAuthEvent()
    data object LoadMoreSavedAlbums : SpotifyAuthEvent()
    data object LoadFollowedArtists : SpotifyAuthEvent()
    data object LoadMoreFollowedArtists : SpotifyAuthEvent()
    data object LoadPlaylists : SpotifyAuthEvent()
    data object LoadMorePlaylists : SpotifyAuthEvent()
    // Track saving/removing
    data class SaveTrack(val trackId: String) : SpotifyAuthEvent()
    data class RemoveSavedTrack(val trackId: String) : SpotifyAuthEvent()
    data class ToggleSaveTrack(val trackId: String, val currentlySaved: Boolean) : SpotifyAuthEvent()
    // Playback
    data class PlayTrack(val uri: String) : SpotifyAuthEvent()
    data class PlayContext(val contextUri: String, val offsetUri: String? = null) : SpotifyAuthEvent()
}

/**
 * ViewModel for Spotify authentication flow.
 */
@HiltViewModel
class SpotifyAuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @SpotifyDataStore private val dataStore: DataStore<Preferences>
) : ViewModel() {

    companion object {
        private const val TAG = "SPOTIFY"

        // DataStore keys for token persistence
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_TOKEN_EXPIRY = longPreferencesKey("token_expiry")
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
        private val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        private val KEY_USER_IMAGE = stringPreferencesKey("user_image")
        private val KEY_CLIENT_ID = stringPreferencesKey("spotify_client_id")

        // Spotify OAuth configuration
        private const val REDIRECT_URI = "janus://spotify-callback"
    }

    private val _uiState = MutableStateFlow(SpotifyAuthUiState())
    val uiState: StateFlow<SpotifyAuthUiState> = _uiState

    private var authManager: SpotifyAuthManager? = null
    private var pendingAuthIntent: Intent? = null

    init {
        // Check for existing tokens on startup
        viewModelScope.launch {
            loadSavedAuthState()
        }
    }

    /**
     * Loads saved authentication state from DataStore.
     */
    private suspend fun loadSavedAuthState() {
        try {
            val prefs = dataStore.data.first()
            val clientId = prefs[KEY_CLIENT_ID] ?: ""
            val accessToken = prefs[KEY_ACCESS_TOKEN]
            val tokenExpiry = prefs[KEY_TOKEN_EXPIRY] ?: 0L
            val userName = prefs[KEY_USER_NAME]
            val userEmail = prefs[KEY_USER_EMAIL]
            val userImage = prefs[KEY_USER_IMAGE]

            val isClientIdConfigured = clientId.isNotBlank() && clientId.length >= 20

            if (accessToken != null && System.currentTimeMillis() < tokenExpiry && isClientIdConfigured) {
                _uiState.value = SpotifyAuthUiState(
                    isLoggedIn = true,
                    userName = userName,
                    userEmail = userEmail,
                    userImageUrl = userImage,
                    accessToken = accessToken,
                    clientId = clientId,
                    isClientIdConfigured = true
                )
                Log.d(TAG, "Restored saved auth state for user: $userName")
            } else if (accessToken != null && isClientIdConfigured) {
                // Token expired, try to refresh
                val refreshToken = prefs[KEY_REFRESH_TOKEN]
                if (refreshToken != null) {
                    _uiState.value = _uiState.value.copy(
                        clientId = clientId,
                        isClientIdConfigured = true
                    )
                    refreshAccessToken(refreshToken)
                }
            } else {
                // No valid auth, but may have client ID configured
                _uiState.value = _uiState.value.copy(
                    clientId = clientId,
                    isClientIdConfigured = isClientIdConfigured
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved auth state", e)
        }
    }

    /**
     * Handles UI events.
     */
    fun onEvent(event: SpotifyAuthEvent) {
        when (event) {
            is SpotifyAuthEvent.Login -> {
                // Login is handled via getAuthIntent() and handleAuthResult()
            }
            is SpotifyAuthEvent.Logout -> logout()
            is SpotifyAuthEvent.ClearError -> clearError()
            is SpotifyAuthEvent.ToggleClientIdInput -> toggleClientIdInput()
            is SpotifyAuthEvent.SaveClientId -> saveClientId(event.clientId)
            is SpotifyAuthEvent.ClearClientId -> clearClientId()
            is SpotifyAuthEvent.RefreshLibraryStats -> refreshLibraryStats()
            is SpotifyAuthEvent.ToggleShuffle -> toggleShuffle()
            is SpotifyAuthEvent.ToggleRepeat -> toggleRepeat()
            is SpotifyAuthEvent.RefreshPlaybackState -> refreshPlaybackState()
            is SpotifyAuthEvent.RefreshQueue -> refreshQueue()
            is SpotifyAuthEvent.SkipToPosition -> skipToPosition(event.position)
            // Tab navigation
            is SpotifyAuthEvent.SelectTab -> selectTab(event.tab)
            // Library browsing
            is SpotifyAuthEvent.LoadRecentTracks -> loadRecentTracks(loadMore = false)
            is SpotifyAuthEvent.LoadMoreRecentTracks -> loadRecentTracks(loadMore = true)
            is SpotifyAuthEvent.LoadSavedTracks -> loadSavedTracks(loadMore = false)
            is SpotifyAuthEvent.LoadMoreSavedTracks -> loadSavedTracks(loadMore = true)
            is SpotifyAuthEvent.LoadSavedAlbums -> loadSavedAlbums(loadMore = false)
            is SpotifyAuthEvent.LoadMoreSavedAlbums -> loadSavedAlbums(loadMore = true)
            is SpotifyAuthEvent.LoadFollowedArtists -> loadFollowedArtists(loadMore = false)
            is SpotifyAuthEvent.LoadMoreFollowedArtists -> loadFollowedArtists(loadMore = true)
            is SpotifyAuthEvent.LoadPlaylists -> loadPlaylists(loadMore = false)
            is SpotifyAuthEvent.LoadMorePlaylists -> loadPlaylists(loadMore = true)
            // Track saving/removing
            is SpotifyAuthEvent.SaveTrack -> saveTrack(event.trackId)
            is SpotifyAuthEvent.RemoveSavedTrack -> removeSavedTrack(event.trackId)
            is SpotifyAuthEvent.ToggleSaveTrack -> toggleSaveTrack(event.trackId, event.currentlySaved)
            // Playback
            is SpotifyAuthEvent.PlayTrack -> playTrack(event.uri)
            is SpotifyAuthEvent.PlayContext -> playContext(event.contextUri, event.offsetUri)
        }
    }

    /**
     * Manually refresh library stats.
     */
    private fun refreshLibraryStats() {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot refresh library stats: no access token")
            _uiState.value = _uiState.value.copy(
                errorMessage = "Not logged in"
            )
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoadingStats = true, errorMessage = null)

                Log.d(TAG, "=== Manual refresh library stats ===")
                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)
                fetchLibraryStats(apiClient.apiService)

            } catch (e: Exception) {
                Log.e(TAG, "Exception refreshing library stats: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to refresh: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoadingStats = false)
            }
        }
    }

    /**
     * Refreshes the current playback state (shuffle, repeat, currently playing).
     */
    private fun refreshPlaybackState() {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot refresh playback state: no access token")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "=== Refreshing playback state ===")
                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.getPlaybackState()
                }

                Log.d(TAG, "Playback state response code: ${response.code()}")

                if (response.isSuccessful) {
                    val state = response.body()
                    if (state != null) {
                        val shuffleState = when {
                            state.smartShuffle == true -> ShuffleState.SMART
                            state.shuffleState == true -> ShuffleState.ON
                            else -> ShuffleState.OFF
                        }
                        val repeatState = when (state.repeatState) {
                            "track" -> RepeatState.TRACK
                            "context" -> RepeatState.CONTEXT
                            else -> RepeatState.OFF
                        }

                        Log.d(TAG, "Shuffle: $shuffleState, Repeat: $repeatState")
                        Log.d(TAG, "Device: ${state.device?.name} (${state.device?.type})")

                        _uiState.value = _uiState.value.copy(
                            shuffleState = shuffleState,
                            repeatState = repeatState
                        )

                        // Also update currently playing if available
                        val track = state.item
                        if (state.isPlaying == true && track != null) {
                            val artistName = track.artists?.firstOrNull()?.name ?: ""
                            _uiState.value = _uiState.value.copy(
                                currentlyPlaying = "${track.name} - $artistName"
                            )
                        }
                    } else {
                        Log.d(TAG, "No active playback session")
                    }
                } else if (response.code() == 204) {
                    Log.d(TAG, "No active device (204)")
                } else {
                    Log.e(TAG, "Failed to get playback state: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception refreshing playback state: ${e.message}", e)
            }
        }
    }

    /**
     * Refreshes the playback queue.
     */
    private fun refreshQueue() {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot refresh queue: no access token")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoadingQueue = true)
                Log.d(TAG, "=== Refreshing queue ===")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.getQueue()
                }

                Log.d(TAG, "Queue response code: ${response.code()}")

                if (response.isSuccessful) {
                    val queueResponse = response.body()
                    if (queueResponse != null) {
                        val tracks = queueResponse.queue?.mapNotNull { track ->
                            if (track.name != null) {
                                QueueTrack(
                                    name = track.name,
                                    artist = track.artists?.firstOrNull()?.name ?: "Unknown Artist",
                                    albumName = track.album?.name,
                                    imageUrl = track.album?.images?.firstOrNull()?.url,
                                    durationMs = track.durationMs,
                                    uri = track.uri
                                )
                            } else null
                        } ?: emptyList()

                        Log.d(TAG, "Queue has ${tracks.size} tracks")
                        _uiState.value = _uiState.value.copy(queueTracks = tracks)
                    } else {
                        Log.d(TAG, "Queue response body is null")
                        _uiState.value = _uiState.value.copy(queueTracks = emptyList())
                    }
                } else if (response.code() == 204) {
                    Log.d(TAG, "No active device for queue (204)")
                    _uiState.value = _uiState.value.copy(queueTracks = emptyList())
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to get queue: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception refreshing queue: ${e.message}", e)
            } finally {
                _uiState.value = _uiState.value.copy(isLoadingQueue = false)
            }
        }
    }

    /**
     * Skips forward in the queue to a specific position by calling skipToNext repeatedly.
     * Position 1 = first track in queue (requires 1 skip), position 2 = second track (2 skips), etc.
     */
    private fun skipToPosition(position: Int) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot skip to position: no access token")
            _uiState.value = _uiState.value.copy(errorMessage = "Not logged in")
            return
        }

        if (position < 1) {
            Log.e(TAG, "Invalid position: $position")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoadingQueue = true)
                val targetTrack = _uiState.value.queueTracks.getOrNull(position - 1)
                Log.d(TAG, "=== Skipping to position $position: ${targetTrack?.name ?: "unknown"} ===")
                Log.d(TAG, "Will call skipToNext $position time(s)")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                var success = true
                for (i in 1..position) {
                    Log.d(TAG, "Skip $i of $position...")
                    val response = withContext(Dispatchers.IO) {
                        apiClient.apiService.skipToNext()
                    }

                    Log.d(TAG, "Skip $i response code: ${response.code()}")

                    if (!response.isSuccessful && response.code() != 204) {
                        val errorBody = response.errorBody()?.string()
                        Log.e(TAG, "Failed to skip at step $i: ${response.code()}")
                        Log.e(TAG, "Error body: $errorBody")
                        success = false
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "Failed to skip track"
                        )
                        break
                    }

                    // Small delay between skips to avoid rate limiting
                    if (i < position) {
                        kotlinx.coroutines.delay(100)
                    }
                }

                if (success) {
                    Log.d(TAG, "Successfully skipped to position $position")
                    // Update currently playing with target track info
                    if (targetTrack != null) {
                        _uiState.value = _uiState.value.copy(
                            currentlyPlaying = "${targetTrack.name} - ${targetTrack.artist}"
                        )
                    }
                    // Refresh the queue after a short delay
                    kotlinx.coroutines.delay(500)
                    refreshQueue()
                    refreshPlaybackState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception skipping to position: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Error: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoadingQueue = false)
            }
        }
    }

    /**
     * Selects a tab and loads its content if not already loaded.
     */
    private fun selectTab(tab: SpotifyTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)

        // Auto-load content when tab is selected
        when (tab) {
            SpotifyTab.RECENT -> {
                if (_uiState.value.recentTracks.isEmpty() && !_uiState.value.isLoadingRecent) {
                    loadRecentTracks(loadMore = false)
                }
            }
            SpotifyTab.LIKED -> {
                if (_uiState.value.savedTracks.isEmpty() && !_uiState.value.isLoadingSaved) {
                    loadSavedTracks(loadMore = false)
                }
            }
            SpotifyTab.ALBUMS -> {
                if (_uiState.value.savedAlbums.isEmpty() && !_uiState.value.isLoadingAlbums) {
                    loadSavedAlbums(loadMore = false)
                }
            }
            SpotifyTab.ARTISTS -> {
                if (_uiState.value.followedArtists.isEmpty() && !_uiState.value.isLoadingArtists) {
                    loadFollowedArtists(loadMore = false)
                }
            }
            SpotifyTab.PLAYLISTS -> {
                if (_uiState.value.playlists.isEmpty() && !_uiState.value.isLoadingPlaylists) {
                    loadPlaylists(loadMore = false)
                }
            }
            SpotifyTab.OVERVIEW -> {
                // Overview content is loaded on login
            }
        }
    }

    /**
     * Loads recently played tracks.
     */
    private fun loadRecentTracks(loadMore: Boolean) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot load recent tracks: no access token")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoadingRecent = true)
                Log.d(TAG, "=== Loading recent tracks (loadMore=$loadMore) ===")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                // Recently played uses cursor-based pagination
                // We'll fetch up to 50 tracks
                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.getRecentlyPlayed(limit = 50)
                }

                Log.d(TAG, "Recent tracks response code: ${response.code()}")

                if (response.isSuccessful) {
                    val body = response.body()
                    val items = body?.items?.mapNotNull { item ->
                        val track = item.track
                        if (track?.id != null && track.name != null) {
                            RecentTrackItem(
                                id = track.id,
                                name = track.name,
                                artist = track.artists?.firstOrNull()?.name ?: "Unknown Artist",
                                albumName = track.album?.name,
                                imageUrl = track.album?.images?.firstOrNull()?.url,
                                playedAt = item.playedAt,
                                uri = track.uri,
                                durationMs = track.durationMs
                            )
                        } else null
                    } ?: emptyList()

                    Log.d(TAG, "Loaded ${items.size} recent tracks")

                    // Check which tracks are saved
                    val trackIds = items.map { it.id }
                    if (trackIds.isNotEmpty()) {
                        checkSavedTracksStatus(trackIds)
                    }

                    _uiState.value = _uiState.value.copy(
                        recentTracks = items,
                        hasMoreRecent = false // Recently played doesn't have traditional pagination
                    )
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to load recent tracks: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading recent tracks: ${e.message}", e)
            } finally {
                _uiState.value = _uiState.value.copy(isLoadingRecent = false)
            }
        }
    }

    /**
     * Loads saved/liked tracks.
     */
    private fun loadSavedTracks(loadMore: Boolean) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot load saved tracks: no access token")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoadingSaved = true)

                val currentTracks = if (loadMore) _uiState.value.savedTracks else emptyList()
                val offset = currentTracks.size

                Log.d(TAG, "=== Loading saved tracks (offset=$offset, loadMore=$loadMore) ===")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.getSavedTracks(limit = 20, offset = offset)
                }

                Log.d(TAG, "Saved tracks response code: ${response.code()}")

                if (response.isSuccessful) {
                    val body = response.body()
                    val items = body?.items?.mapNotNull { item ->
                        val track = item.track
                        if (track?.id != null && track.name != null) {
                            SavedTrackItem(
                                id = track.id,
                                name = track.name,
                                artist = track.artists?.firstOrNull()?.name ?: "Unknown Artist",
                                albumName = track.album?.name,
                                imageUrl = track.album?.images?.firstOrNull()?.url,
                                addedAt = item.addedAt,
                                uri = track.uri,
                                durationMs = track.durationMs
                            )
                        } else null
                    } ?: emptyList()

                    val total = body?.total ?: 0
                    val newTracks = currentTracks + items
                    val hasMore = newTracks.size < total

                    Log.d(TAG, "Loaded ${items.size} saved tracks, total: ${newTracks.size}/$total")

                    _uiState.value = _uiState.value.copy(
                        savedTracks = newTracks,
                        hasMoreSaved = hasMore
                    )
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to load saved tracks: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading saved tracks: ${e.message}", e)
            } finally {
                _uiState.value = _uiState.value.copy(isLoadingSaved = false)
            }
        }
    }

    /**
     * Loads saved albums.
     */
    private fun loadSavedAlbums(loadMore: Boolean) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot load saved albums: no access token")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoadingAlbums = true)

                val currentAlbums = if (loadMore) _uiState.value.savedAlbums else emptyList()
                val offset = currentAlbums.size

                Log.d(TAG, "=== Loading saved albums (offset=$offset, loadMore=$loadMore) ===")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.getSavedAlbums(limit = 20, offset = offset)
                }

                Log.d(TAG, "Saved albums response code: ${response.code()}")

                if (response.isSuccessful) {
                    val body = response.body()
                    val items = body?.items?.mapNotNull { item ->
                        val album = item.album
                        if (album?.id != null && album.name != null) {
                            SavedAlbumItem(
                                id = album.id,
                                name = album.name,
                                artist = album.artists?.firstOrNull()?.name ?: "Unknown Artist",
                                imageUrl = album.images?.firstOrNull()?.url,
                                trackCount = album.totalTracks ?: 0,
                                addedAt = item.addedAt,
                                uri = album.uri ?: "spotify:album:${album.id}"
                            )
                        } else null
                    } ?: emptyList()

                    val total = body?.total ?: 0
                    val newAlbums = currentAlbums + items
                    val hasMore = newAlbums.size < total

                    Log.d(TAG, "Loaded ${items.size} saved albums, total: ${newAlbums.size}/$total")

                    _uiState.value = _uiState.value.copy(
                        savedAlbums = newAlbums,
                        hasMoreAlbums = hasMore
                    )
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to load saved albums: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading saved albums: ${e.message}", e)
            } finally {
                _uiState.value = _uiState.value.copy(isLoadingAlbums = false)
            }
        }
    }

    /**
     * Loads followed artists.
     * Artists use cursor-based pagination, not offset/limit.
     */
    private fun loadFollowedArtists(loadMore: Boolean) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot load followed artists: no access token")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoadingArtists = true)

                val currentArtists = if (loadMore) _uiState.value.followedArtists else emptyList()
                val cursor = if (loadMore) _uiState.value.nextArtistsCursor else null

                Log.d(TAG, "=== Loading followed artists (cursor=$cursor, loadMore=$loadMore) ===")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.getFollowedArtists(limit = 20, after = cursor)
                }

                Log.d(TAG, "Followed artists response code: ${response.code()}")

                if (response.isSuccessful) {
                    val body = response.body()?.artists
                    val items = body?.items?.mapNotNull { artist ->
                        if (artist.id != null && artist.name != null) {
                            ArtistItem(
                                id = artist.id,
                                name = artist.name,
                                imageUrl = artist.images?.firstOrNull()?.url,
                                genres = artist.genres?.take(3) ?: emptyList(),
                                followerCount = artist.followers?.total ?: 0,
                                uri = artist.uri ?: "spotify:artist:${artist.id}"
                            )
                        } else null
                    } ?: emptyList()

                    val nextCursor = body?.cursors?.after
                    val hasMore = nextCursor != null
                    val newArtists = currentArtists + items

                    Log.d(TAG, "Loaded ${items.size} followed artists, total: ${newArtists.size}, hasMore: $hasMore")

                    _uiState.value = _uiState.value.copy(
                        followedArtists = newArtists,
                        hasMoreArtists = hasMore,
                        nextArtistsCursor = nextCursor
                    )
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to load followed artists: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading followed artists: ${e.message}", e)
            } finally {
                _uiState.value = _uiState.value.copy(isLoadingArtists = false)
            }
        }
    }

    /**
     * Loads user's playlists.
     */
    private fun loadPlaylists(loadMore: Boolean) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot load playlists: no access token")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoadingPlaylists = true)

                val currentPlaylists = if (loadMore) _uiState.value.playlists else emptyList()
                val offset = currentPlaylists.size

                Log.d(TAG, "=== Loading playlists (offset=$offset, loadMore=$loadMore) ===")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.getPlaylists(limit = 20, offset = offset)
                }

                Log.d(TAG, "Playlists response code: ${response.code()}")

                if (response.isSuccessful) {
                    val body = response.body()
                    val items = body?.items?.mapNotNull { playlist ->
                        if (playlist.id != null && playlist.name != null) {
                            PlaylistItem(
                                id = playlist.id,
                                name = playlist.name,
                                ownerName = playlist.owner?.displayName ?: playlist.owner?.id,
                                imageUrl = playlist.images?.firstOrNull()?.url,
                                trackCount = playlist.tracks?.total ?: 0,
                                isPublic = playlist.isPublic,
                                uri = playlist.uri ?: "spotify:playlist:${playlist.id}"
                            )
                        } else null
                    } ?: emptyList()

                    val total = body?.total ?: 0
                    val newPlaylists = currentPlaylists + items
                    val hasMore = newPlaylists.size < total

                    Log.d(TAG, "Loaded ${items.size} playlists, total: ${newPlaylists.size}/$total")

                    _uiState.value = _uiState.value.copy(
                        playlists = newPlaylists,
                        hasMorePlaylists = hasMore
                    )
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to load playlists: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading playlists: ${e.message}", e)
            } finally {
                _uiState.value = _uiState.value.copy(isLoadingPlaylists = false)
            }
        }
    }

    /**
     * Saves a track to the user's library (like).
     */
    private fun saveTrack(trackId: String) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot save track: no access token")
            _uiState.value = _uiState.value.copy(errorMessage = "Not logged in")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "=== Saving track $trackId ===")

                // Optimistically update UI
                _uiState.value = _uiState.value.copy(
                    savedTrackIds = _uiState.value.savedTrackIds + trackId
                )

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.saveTracks(ids = trackId)
                }

                Log.d(TAG, "Save track response code: ${response.code()}")

                if (response.isSuccessful || response.code() == 200) {
                    Log.d(TAG, "Successfully saved track $trackId")
                    // Update saved tracks count
                    _uiState.value = _uiState.value.copy(
                        savedTracksCount = (_uiState.value.savedTracksCount ?: 0) + 1
                    )
                } else {
                    // Revert optimistic update
                    _uiState.value = _uiState.value.copy(
                        savedTrackIds = _uiState.value.savedTrackIds - trackId
                    )
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to save track: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                    _uiState.value = _uiState.value.copy(errorMessage = "Failed to save track")
                }
            } catch (e: Exception) {
                // Revert optimistic update
                _uiState.value = _uiState.value.copy(
                    savedTrackIds = _uiState.value.savedTrackIds - trackId
                )
                Log.e(TAG, "Exception saving track: ${e.message}", e)
                _uiState.value = _uiState.value.copy(errorMessage = "Error: ${e.message}")
            }
        }
    }

    /**
     * Removes a track from the user's library (unlike).
     */
    private fun removeSavedTrack(trackId: String) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot remove track: no access token")
            _uiState.value = _uiState.value.copy(errorMessage = "Not logged in")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "=== Removing track $trackId ===")

                // Optimistically update UI
                val previousSavedIds = _uiState.value.savedTrackIds
                val previousSavedTracks = _uiState.value.savedTracks
                _uiState.value = _uiState.value.copy(
                    savedTrackIds = _uiState.value.savedTrackIds - trackId,
                    savedTracks = _uiState.value.savedTracks.filter { it.id != trackId }
                )

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.removeSavedTracks(ids = trackId)
                }

                Log.d(TAG, "Remove track response code: ${response.code()}")

                if (response.isSuccessful || response.code() == 200) {
                    Log.d(TAG, "Successfully removed track $trackId")
                    // Update saved tracks count
                    val currentCount = _uiState.value.savedTracksCount ?: 1
                    _uiState.value = _uiState.value.copy(
                        savedTracksCount = maxOf(0, currentCount - 1)
                    )
                } else {
                    // Revert optimistic update
                    _uiState.value = _uiState.value.copy(
                        savedTrackIds = previousSavedIds,
                        savedTracks = previousSavedTracks
                    )
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to remove track: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                    _uiState.value = _uiState.value.copy(errorMessage = "Failed to remove track")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception removing track: ${e.message}", e)
                _uiState.value = _uiState.value.copy(errorMessage = "Error: ${e.message}")
            }
        }
    }

    /**
     * Toggles the save state of a track.
     */
    private fun toggleSaveTrack(trackId: String, currentlySaved: Boolean) {
        if (currentlySaved) {
            removeSavedTrack(trackId)
        } else {
            saveTrack(trackId)
        }
    }

    /**
     * Checks which tracks are saved in the user's library.
     */
    private fun checkSavedTracksStatus(trackIds: List<String>) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank() || trackIds.isEmpty()) return

        viewModelScope.launch {
            try {
                // Split into chunks of 50 (API limit)
                trackIds.chunked(50).forEach { chunk ->
                    val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)
                    val response = withContext(Dispatchers.IO) {
                        apiClient.apiService.checkSavedTracks(ids = chunk.joinToString(","))
                    }

                    if (response.isSuccessful) {
                        val savedStatus = response.body() ?: return@forEach
                        val newSavedIds = chunk.filterIndexed { index, _ ->
                            savedStatus.getOrNull(index) == true
                        }.toSet()

                        _uiState.value = _uiState.value.copy(
                            savedTrackIds = _uiState.value.savedTrackIds + newSavedIds
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception checking saved tracks: ${e.message}", e)
            }
        }
    }

    /**
     * Plays a track by its URI.
     */
    private fun playTrack(uri: String) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot play track: no access token")
            _uiState.value = _uiState.value.copy(errorMessage = "Not logged in")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "=== Playing track $uri ===")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.play(
                        body = com.spotsdk.api.PlayRequest(uris = listOf(uri))
                    )
                }

                Log.d(TAG, "Play track response code: ${response.code()}")

                if (response.isSuccessful || response.code() == 204) {
                    Log.d(TAG, "Successfully started playback")
                    // Refresh playback state after a short delay
                    kotlinx.coroutines.delay(500)
                    refreshPlaybackState()
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to play track: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                    if (response.code() == 404) {
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "No active device found. Open Spotify on a device first."
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(errorMessage = "Failed to play track")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception playing track: ${e.message}", e)
                _uiState.value = _uiState.value.copy(errorMessage = "Error: ${e.message}")
            }
        }
    }

    /**
     * Plays a context (album, playlist) with optional offset.
     */
    private fun playContext(contextUri: String, offsetUri: String?) {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot play context: no access token")
            _uiState.value = _uiState.value.copy(errorMessage = "Not logged in")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "=== Playing context $contextUri (offset=$offsetUri) ===")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val offset = offsetUri?.let { com.spotsdk.api.PlayOffset(uri = it) }
                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.play(
                        body = com.spotsdk.api.PlayRequest(
                            contextUri = contextUri,
                            offset = offset
                        )
                    )
                }

                Log.d(TAG, "Play context response code: ${response.code()}")

                if (response.isSuccessful || response.code() == 204) {
                    Log.d(TAG, "Successfully started context playback")
                    kotlinx.coroutines.delay(500)
                    refreshPlaybackState()
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to play context: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                    if (response.code() == 404) {
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "No active device found. Open Spotify on a device first."
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(errorMessage = "Failed to play")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception playing context: ${e.message}", e)
                _uiState.value = _uiState.value.copy(errorMessage = "Error: ${e.message}")
            }
        }
    }

    /**
     * Toggles shuffle mode: OFF -> ON -> SMART -> OFF
     * Note: Smart shuffle may not be available on all accounts/devices.
     */
    private fun toggleShuffle() {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot toggle shuffle: no access token")
            _uiState.value = _uiState.value.copy(errorMessage = "Not logged in")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isTogglingPlayback = true)

                val currentShuffle = _uiState.value.shuffleState
                val newShuffle = when (currentShuffle) {
                    ShuffleState.OFF -> ShuffleState.ON
                    ShuffleState.ON -> ShuffleState.OFF  // Smart shuffle requires special API, skip for now
                    ShuffleState.SMART -> ShuffleState.OFF
                }

                Log.d(TAG, "Toggling shuffle: $currentShuffle -> $newShuffle")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.setShuffle(state = newShuffle == ShuffleState.ON)
                }

                Log.d(TAG, "Set shuffle response code: ${response.code()}")

                if (response.isSuccessful || response.code() == 204) {
                    _uiState.value = _uiState.value.copy(shuffleState = newShuffle)
                    Log.d(TAG, "Shuffle set to: $newShuffle")
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to set shuffle: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Failed to toggle shuffle"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception toggling shuffle: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Error: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(isTogglingPlayback = false)
            }
        }
    }

    /**
     * Toggles repeat mode: OFF -> CONTEXT -> TRACK -> OFF
     */
    private fun toggleRepeat() {
        val accessToken = _uiState.value.accessToken
        if (accessToken.isNullOrBlank()) {
            Log.e(TAG, "Cannot toggle repeat: no access token")
            _uiState.value = _uiState.value.copy(errorMessage = "Not logged in")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isTogglingPlayback = true)

                val currentRepeat = _uiState.value.repeatState
                val newRepeat = when (currentRepeat) {
                    RepeatState.OFF -> RepeatState.CONTEXT
                    RepeatState.CONTEXT -> RepeatState.TRACK
                    RepeatState.TRACK -> RepeatState.OFF
                }

                val newRepeatString = when (newRepeat) {
                    RepeatState.OFF -> "off"
                    RepeatState.CONTEXT -> "context"
                    RepeatState.TRACK -> "track"
                }

                Log.d(TAG, "Toggling repeat: $currentRepeat -> $newRepeat ($newRepeatString)")

                val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

                val response = withContext(Dispatchers.IO) {
                    apiClient.apiService.setRepeat(state = newRepeatString)
                }

                Log.d(TAG, "Set repeat response code: ${response.code()}")

                if (response.isSuccessful || response.code() == 204) {
                    _uiState.value = _uiState.value.copy(repeatState = newRepeat)
                    Log.d(TAG, "Repeat set to: $newRepeat")
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to set repeat: ${response.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Failed to toggle repeat"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception toggling repeat: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Error: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(isTogglingPlayback = false)
            }
        }
    }

    /**
     * Toggles visibility of the client ID input field.
     */
    private fun toggleClientIdInput() {
        _uiState.value = _uiState.value.copy(
            showClientIdInput = !_uiState.value.showClientIdInput
        )
    }

    /**
     * Saves the Spotify client ID to DataStore.
     */
    private fun saveClientId(clientId: String) {
        val trimmedId = clientId.trim()
        viewModelScope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs[KEY_CLIENT_ID] = trimmedId
                }
                val isValid = trimmedId.isNotBlank() && trimmedId.length >= 20
                _uiState.value = _uiState.value.copy(
                    clientId = trimmedId,
                    isClientIdConfigured = isValid,
                    showClientIdInput = false,
                    errorMessage = if (!isValid && trimmedId.isNotBlank())
                        "Client ID appears invalid (should be 32 characters)"
                    else null
                )
                Log.d(TAG, "Saved client ID: ${trimmedId.take(8)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving client ID", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to save client ID"
                )
            }
        }
    }

    /**
     * Clears the saved client ID and logs out.
     */
    private fun clearClientId() {
        viewModelScope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs.remove(KEY_CLIENT_ID)
                    prefs.clear() // Also clear auth tokens
                }
                authManager?.dispose()
                authManager = null
                _uiState.value = SpotifyAuthUiState()
                Log.d(TAG, "Cleared client ID and auth state")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing client ID", e)
            }
        }
    }

    /**
     * Creates an Intent to launch the Spotify OAuth flow.
     * Call this when the user taps the login button.
     */
    fun getAuthIntent(): Intent? {
        val clientId = _uiState.value.clientId
        if (clientId.isBlank()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Please configure your Spotify Client ID first"
            )
            return null
        }

        return try {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            if (authManager == null) {
                authManager = SpotifyAuthManager(context, clientId, REDIRECT_URI)
            }

            val intent = authManager?.buildAuthIntent(
                scopes = SpotifyAuthManager.SCOPES,
                showDialog = false
            )
            pendingAuthIntent = intent
            intent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build auth intent", e)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = "Failed to start login: ${e.message}"
            )
            null
        }
    }

    /**
     * Handles the result from the Spotify OAuth activity.
     */
    fun handleAuthResult(result: ActivityResult) {
        viewModelScope.launch {
            try {
                val data = result.data
                if (data == null || result.resultCode != Activity.RESULT_OK) {
                    if (authManager?.isAuthCancelled(data) == true) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = null
                        )
                        Log.d(TAG, "User cancelled login")
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = "Login was cancelled or failed"
                        )
                    }
                    return@launch
                }

                val (response, exception) = authManager?.parseAuthResult(data)
                    ?: return@launch

                when {
                    exception != null -> {
                        Log.e(TAG, "Auth error: ${exception.error}", exception)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = "Authentication error: ${exception.errorDescription ?: exception.error}"
                        )
                    }
                    response != null -> {
                        exchangeCodeForTokens(response)
                    }
                    else -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = "Unknown authentication error"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling auth result", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Error processing login: ${e.message}"
                )
            }
        }
    }

    /**
     * Exchanges the authorization code for access and refresh tokens.
     */
    private suspend fun exchangeCodeForTokens(response: AuthorizationResponse) {
        try {
            val tokenResult = authManager?.exchangeCodeForTokens(response)
                ?: throw IllegalStateException("AuthManager not initialized")

            // Save tokens
            saveTokens(tokenResult)

            // Update UI state
            _uiState.value = _uiState.value.copy(
                isLoggedIn = true,
                isLoading = false,
                accessToken = tokenResult.accessToken,
                errorMessage = null
            )

            Log.d(TAG, "Login successful, token expires at: ${tokenResult.expiresIn}")

            // Fetch user profile
            fetchUserProfile(tokenResult.accessToken)

        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = "Failed to complete login: ${e.message}"
            )
        }
    }

    /**
     * Refreshes the access token using the refresh token.
     */
    private suspend fun refreshAccessToken(refreshToken: String) {
        val clientId = _uiState.value.clientId
        if (clientId.isBlank()) {
            Log.e(TAG, "Cannot refresh token: no client ID configured")
            return
        }

        try {
            _uiState.value = _uiState.value.copy(isLoading = true)

            if (authManager == null) {
                authManager = SpotifyAuthManager(context, clientId, REDIRECT_URI)
            }

            val tokenResult = authManager?.refreshAccessToken(refreshToken)
                ?: throw IllegalStateException("AuthManager not initialized")

            saveTokens(tokenResult)

            val prefs = dataStore.data.first()
            _uiState.value = _uiState.value.copy(
                isLoggedIn = true,
                isLoading = false,
                userName = prefs[KEY_USER_NAME],
                userEmail = prefs[KEY_USER_EMAIL],
                userImageUrl = prefs[KEY_USER_IMAGE],
                accessToken = tokenResult.accessToken
            )

            Log.d(TAG, "Token refreshed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            // Clear saved state and require re-login
            logout()
        }
    }

    /**
     * Fetches the user's Spotify profile and library stats.
     */
    private suspend fun fetchUserProfile(accessToken: String) {
        Log.d(TAG, "=== fetchUserProfile START ===")
        Log.d(TAG, "Access token (first 20 chars): ${accessToken.take(20)}...")

        try {
            _uiState.value = _uiState.value.copy(isLoadingStats = true)

            Log.d(TAG, "Creating SpotifyApiClient...")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)
            val apiService = apiClient.apiService
            Log.d(TAG, "SpotifyApiClient created successfully")

            // Fetch user profile
            Log.d(TAG, "Fetching user profile from /me...")
            val userResponse = withContext(Dispatchers.IO) {
                apiService.getCurrentUser()
            }
            Log.d(TAG, "User profile response code: ${userResponse.code()}")
            Log.d(TAG, "User profile response successful: ${userResponse.isSuccessful}")

            if (userResponse.isSuccessful) {
                val user = userResponse.body()
                Log.d(TAG, "User body is null: ${user == null}")
                if (user != null) {
                    Log.d(TAG, "User ID: ${user.id}")
                    Log.d(TAG, "User displayName: ${user.displayName}")
                    Log.d(TAG, "User email: ${user.email}")
                    Log.d(TAG, "User country: ${user.country}")
                    Log.d(TAG, "User product: ${user.product}")
                    Log.d(TAG, "User images count: ${user.images?.size ?: 0}")
                    Log.d(TAG, "User followerCount: ${user.followerCount}")

                    val imageUrl = user.images?.firstOrNull()?.url
                    Log.d(TAG, "User image URL: $imageUrl")

                    dataStore.edit { prefs ->
                        prefs[KEY_USER_NAME] = user.displayName ?: user.id
                        prefs[KEY_USER_EMAIL] = user.email ?: ""
                        imageUrl?.let { prefs[KEY_USER_IMAGE] = it }
                    }

                    _uiState.value = _uiState.value.copy(
                        userName = user.displayName ?: user.id,
                        userEmail = user.email,
                        userImageUrl = imageUrl,
                        userId = user.id,
                        country = user.country,
                        product = user.product,
                        followerCount = user.followerCount
                    )

                    Log.d(TAG, "User profile fetched successfully: ${user.displayName}")
                }
            } else {
                val errorBody = userResponse.errorBody()?.string()
                Log.e(TAG, "Failed to fetch user profile: ${userResponse.code()}")
                Log.e(TAG, "Error body: $errorBody")
            }

            // Fetch library stats
            Log.d(TAG, "Starting library stats fetch...")
            fetchLibraryStats(apiService)

            // Fetch playback state (shuffle, repeat)
            Log.d(TAG, "Starting playback state fetch...")
            fetchPlaybackState(apiService)

        } catch (e: Exception) {
            Log.e(TAG, "Exception in fetchUserProfile: ${e.message}", e)
            // Don't fail the login just because profile fetch failed
        } finally {
            _uiState.value = _uiState.value.copy(isLoadingStats = false)
            Log.d(TAG, "=== fetchUserProfile END ===")
        }
    }

    /**
     * Fetches library statistics (saved tracks, albums, playlists, followed artists).
     */
    private suspend fun fetchLibraryStats(apiService: SpotifyApiService) {
        Log.d(TAG, "=== fetchLibraryStats START ===")

        // Fetch saved tracks count
        try {
            Log.d(TAG, "Fetching saved tracks from /me/tracks...")
            val tracksResponse = withContext(Dispatchers.IO) {
                apiService.getSavedTracks(limit = 1)
            }
            Log.d(TAG, "Saved tracks response code: ${tracksResponse.code()}")
            if (tracksResponse.isSuccessful) {
                val body = tracksResponse.body()
                val total = body?.total ?: 0
                Log.d(TAG, "Saved tracks total: $total")
                _uiState.value = _uiState.value.copy(savedTracksCount = total)
            } else {
                val errorBody = tracksResponse.errorBody()?.string()
                Log.e(TAG, "Failed to fetch saved tracks: ${tracksResponse.code()}")
                Log.e(TAG, "Error body: $errorBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching saved tracks: ${e.message}", e)
        }

        // Fetch saved albums count
        try {
            Log.d(TAG, "Fetching saved albums from /me/albums...")
            val albumsResponse = withContext(Dispatchers.IO) {
                apiService.getSavedAlbums(limit = 1)
            }
            Log.d(TAG, "Saved albums response code: ${albumsResponse.code()}")
            if (albumsResponse.isSuccessful) {
                val body = albumsResponse.body()
                val total = body?.total ?: 0
                Log.d(TAG, "Saved albums total: $total")
                _uiState.value = _uiState.value.copy(savedAlbumsCount = total)
            } else {
                val errorBody = albumsResponse.errorBody()?.string()
                Log.e(TAG, "Failed to fetch saved albums: ${albumsResponse.code()}")
                Log.e(TAG, "Error body: $errorBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching saved albums: ${e.message}", e)
        }

        // Fetch playlists count
        try {
            Log.d(TAG, "Fetching playlists from /me/playlists...")
            val playlistsResponse = withContext(Dispatchers.IO) {
                apiService.getPlaylists(limit = 1)
            }
            Log.d(TAG, "Playlists response code: ${playlistsResponse.code()}")
            if (playlistsResponse.isSuccessful) {
                val body = playlistsResponse.body()
                val total = body?.total ?: 0
                Log.d(TAG, "Playlists total: $total")
                _uiState.value = _uiState.value.copy(playlistsCount = total)
            } else {
                val errorBody = playlistsResponse.errorBody()?.string()
                Log.e(TAG, "Failed to fetch playlists: ${playlistsResponse.code()}")
                Log.e(TAG, "Error body: $errorBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching playlists: ${e.message}", e)
        }

        // Fetch followed artists count
        try {
            Log.d(TAG, "Fetching followed artists from /me/following...")
            val artistsResponse = withContext(Dispatchers.IO) {
                apiService.getFollowedArtists(limit = 1)
            }
            Log.d(TAG, "Followed artists response code: ${artistsResponse.code()}")
            if (artistsResponse.isSuccessful) {
                val body = artistsResponse.body()
                val total = body?.artists?.total ?: 0
                Log.d(TAG, "Followed artists total: $total")
                _uiState.value = _uiState.value.copy(followedArtistsCount = total)
            } else {
                val errorBody = artistsResponse.errorBody()?.string()
                Log.e(TAG, "Failed to fetch followed artists: ${artistsResponse.code()}")
                Log.e(TAG, "Error body: $errorBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching followed artists: ${e.message}", e)
        }

        // Fetch recently played
        try {
            Log.d(TAG, "Fetching recently played from /me/player/recently-played...")
            val recentResponse = withContext(Dispatchers.IO) {
                apiService.getRecentlyPlayed(limit = 1)
            }
            Log.d(TAG, "Recently played response code: ${recentResponse.code()}")
            if (recentResponse.isSuccessful) {
                val body = recentResponse.body()
                Log.d(TAG, "Recently played items count: ${body?.items?.size ?: 0}")
                val recentTrack = body?.items?.firstOrNull()?.track
                if (recentTrack != null) {
                    Log.d(TAG, "Recent track: ${recentTrack.name} by ${recentTrack.artists?.firstOrNull()?.name}")
                    _uiState.value = _uiState.value.copy(
                        recentTrackName = recentTrack.name,
                        recentTrackArtist = recentTrack.artists?.firstOrNull()?.name
                    )
                } else {
                    Log.d(TAG, "No recent tracks found")
                }
            } else {
                val errorBody = recentResponse.errorBody()?.string()
                Log.e(TAG, "Failed to fetch recently played: ${recentResponse.code()}")
                Log.e(TAG, "Error body: $errorBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching recently played: ${e.message}", e)
        }

        // Fetch currently playing
        try {
            Log.d(TAG, "Fetching currently playing from /me/player/currently-playing...")
            val currentResponse = withContext(Dispatchers.IO) {
                apiService.getCurrentlyPlaying()
            }
            Log.d(TAG, "Currently playing response code: ${currentResponse.code()}")
            if (currentResponse.isSuccessful) {
                val current = currentResponse.body()
                Log.d(TAG, "Currently playing body is null: ${current == null}")
                Log.d(TAG, "Is playing: ${current?.isPlaying}")
                val currentTrack = current?.item
                Log.d(TAG, "Current track is null: ${currentTrack == null}")
                if (current?.isPlaying == true && currentTrack != null) {
                    val artistName = currentTrack.artists?.firstOrNull()?.name ?: ""
                    Log.d(TAG, "Currently playing: ${currentTrack.name} - $artistName")
                    _uiState.value = _uiState.value.copy(
                        currentlyPlaying = "${currentTrack.name} - $artistName"
                    )
                } else {
                    Log.d(TAG, "Nothing currently playing or track is null")
                }
            } else {
                // 204 No Content is expected when nothing is playing
                if (currentResponse.code() == 204) {
                    Log.d(TAG, "Nothing currently playing (204 No Content)")
                } else {
                    val errorBody = currentResponse.errorBody()?.string()
                    Log.e(TAG, "Failed to fetch currently playing: ${currentResponse.code()}")
                    Log.e(TAG, "Error body: $errorBody")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching currently playing: ${e.message}", e)
        }

        Log.d(TAG, "=== fetchLibraryStats END ===")
    }

    /**
     * Fetches current playback state (shuffle, repeat modes).
     */
    private suspend fun fetchPlaybackState(apiService: SpotifyApiService) {
        Log.d(TAG, "=== fetchPlaybackState START ===")

        try {
            Log.d(TAG, "Fetching playback state from /me/player...")
            val response = withContext(Dispatchers.IO) {
                apiService.getPlaybackState()
            }
            Log.d(TAG, "Playback state response code: ${response.code()}")

            if (response.isSuccessful) {
                val state = response.body()
                if (state != null) {
                    val shuffleState = when {
                        state.smartShuffle == true -> ShuffleState.SMART
                        state.shuffleState == true -> ShuffleState.ON
                        else -> ShuffleState.OFF
                    }
                    val repeatState = when (state.repeatState) {
                        "track" -> RepeatState.TRACK
                        "context" -> RepeatState.CONTEXT
                        else -> RepeatState.OFF
                    }

                    Log.d(TAG, "Shuffle: $shuffleState (raw: ${state.shuffleState}, smart: ${state.smartShuffle})")
                    Log.d(TAG, "Repeat: $repeatState (raw: ${state.repeatState})")
                    Log.d(TAG, "Device: ${state.device?.name} (${state.device?.type})")

                    _uiState.value = _uiState.value.copy(
                        shuffleState = shuffleState,
                        repeatState = repeatState
                    )
                } else {
                    Log.d(TAG, "No active playback session (null body)")
                }
            } else if (response.code() == 204) {
                Log.d(TAG, "No active device (204 No Content)")
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Failed to get playback state: ${response.code()}")
                Log.e(TAG, "Error body: $errorBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching playback state: ${e.message}", e)
        }

        Log.d(TAG, "=== fetchPlaybackState END ===")
    }

    /**
     * Saves tokens to DataStore.
     */
    private suspend fun saveTokens(tokenResult: TokenResult) {
        dataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = tokenResult.accessToken
            tokenResult.refreshToken?.let { prefs[KEY_REFRESH_TOKEN] = it }
            prefs[KEY_TOKEN_EXPIRY] = tokenResult.expiresIn
        }
    }

    /**
     * Logs out and clears auth tokens (preserves client ID).
     */
    private fun logout() {
        viewModelScope.launch {
            try {
                val clientId = _uiState.value.clientId

                dataStore.edit { prefs ->
                    prefs.remove(KEY_ACCESS_TOKEN)
                    prefs.remove(KEY_REFRESH_TOKEN)
                    prefs.remove(KEY_TOKEN_EXPIRY)
                    prefs.remove(KEY_USER_NAME)
                    prefs.remove(KEY_USER_EMAIL)
                    prefs.remove(KEY_USER_IMAGE)
                    // Keep KEY_CLIENT_ID
                }

                authManager?.dispose()
                authManager = null

                _uiState.value = SpotifyAuthUiState(
                    clientId = clientId,
                    isClientIdConfigured = clientId.isNotBlank() && clientId.length >= 20
                )

                Log.d(TAG, "Logged out successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error during logout", e)
            }
        }
    }

    /**
     * Clears any error message.
     */
    private fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /**
     * Returns the current access token if logged in.
     */
    fun getAccessToken(): String? = _uiState.value.accessToken

    /**
     * Returns whether the user is currently logged in.
     */
    fun isLoggedIn(): Boolean = _uiState.value.isLoggedIn

    override fun onCleared() {
        super.onCleared()
        authManager?.dispose()
    }
}
