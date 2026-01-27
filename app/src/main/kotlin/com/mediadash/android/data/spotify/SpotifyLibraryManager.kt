package com.mediadash.android.data.spotify

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mediadash.android.di.SpotifyDataStore
import com.mediadash.android.domain.model.*
import com.mediadash.android.util.CRC32Util
import com.spotsdk.api.PlayRequest
import com.spotsdk.api.SpotifyApiClient
import com.spotsdk.api.createSimple
import com.spotsdk.auth.SpotifyAuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for Spotify library browsing.
 * Fetches library data (recent, liked, albums, playlists) via Spotify Web API.
 * Includes automatic token refresh when tokens expire.
 */
@Singleton
class SpotifyLibraryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @SpotifyDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val TAG = "SpotifyLibraryMgr"
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_TOKEN_EXPIRY = longPreferencesKey("token_expiry")
        private val KEY_CLIENT_ID = stringPreferencesKey("spotify_client_id")
        private const val REDIRECT_URI = "janus://spotify-callback"
        private const val MAX_TRACK_NAME = 64
        private const val MAX_ARTIST_NAME = 48
        private const val MAX_ALBUM_NAME = 48
        private const val MAX_PLAYLIST_NAME = 48
    }

    // Mutex to prevent concurrent token refresh attempts
    private val refreshMutex = Mutex()

    /**
     * Check if Spotify is connected with a valid token (or can be refreshed).
     */
    suspend fun isConnected(): Boolean {
        return try {
            val prefs = dataStore.data.first()
            val accessToken = prefs[KEY_ACCESS_TOKEN]
            val refreshToken = prefs[KEY_REFRESH_TOKEN]
            val tokenExpiry = prefs[KEY_TOKEN_EXPIRY] ?: 0L

            // Connected if we have a valid token or can refresh
            if (!accessToken.isNullOrBlank() && System.currentTimeMillis() < tokenExpiry) {
                true
            } else if (!refreshToken.isNullOrBlank()) {
                // We have a refresh token, try to refresh
                val newToken = refreshAccessToken()
                newToken != null
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking connection", e)
            false
        }
    }

    /**
     * Get access token, refreshing if needed.
     */
    private suspend fun getAccessToken(): String? {
        return try {
            val prefs = dataStore.data.first()
            val accessToken = prefs[KEY_ACCESS_TOKEN]
            val tokenExpiry = prefs[KEY_TOKEN_EXPIRY] ?: 0L

            // Return existing token if still valid (with 60s buffer)
            if (!accessToken.isNullOrBlank() && System.currentTimeMillis() < (tokenExpiry - 60000)) {
                return accessToken
            }

            // Token expired or about to expire, try to refresh
            Log.d(TAG, "Token expired or expiring soon, attempting refresh...")
            refreshAccessToken()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting access token", e)
            null
        }
    }

    /**
     * Refresh the access token using the stored refresh token.
     */
    private suspend fun refreshAccessToken(): String? = refreshMutex.withLock {
        try {
            val prefs = dataStore.data.first()
            val refreshToken = prefs[KEY_REFRESH_TOKEN]
            val clientId = prefs[KEY_CLIENT_ID]

            if (refreshToken.isNullOrBlank()) {
                Log.w(TAG, "No refresh token available")
                return null
            }

            if (clientId.isNullOrBlank()) {
                Log.w(TAG, "No client ID available")
                return null
            }

            Log.d(TAG, "Refreshing access token...")

            val authManager = SpotifyAuthManager(context, clientId, REDIRECT_URI)
            val tokenResult = authManager.refreshAccessToken(refreshToken)

            // Save new tokens
            dataStore.edit { prefs ->
                prefs[KEY_ACCESS_TOKEN] = tokenResult.accessToken
                prefs[KEY_TOKEN_EXPIRY] = System.currentTimeMillis() + (tokenResult.expiresIn * 1000L)
                // Update refresh token if a new one was provided
                tokenResult.refreshToken?.let { newRefresh ->
                    prefs[KEY_REFRESH_TOKEN] = newRefresh
                }
            }

            Log.i(TAG, "✅ Token refreshed successfully, expires in ${tokenResult.expiresIn}s")
            tokenResult.accessToken
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to refresh token: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch library overview stats.
     */
    suspend fun fetchOverview(): SpotifyLibraryOverview? {
        val accessToken = getAccessToken() ?: return null

        return try {
            Log.d(TAG, "=== Fetching library overview ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)

            // Fetch user profile
            val profileResponse = withContext(Dispatchers.IO) {
                apiClient.apiService.getCurrentUser()
            }
            val profile = profileResponse.body()

            // Fetch current playback
            val playbackResponse = withContext(Dispatchers.IO) {
                apiClient.apiService.getPlaybackState()
            }
            val playback = playbackResponse.body()

            // Fetch counts (using limit=1 to just get totals)
            val likedResponse = withContext(Dispatchers.IO) {
                apiClient.apiService.getSavedTracks(limit = 1, offset = 0)
            }
            val albumsResponse = withContext(Dispatchers.IO) {
                apiClient.apiService.getSavedAlbums(limit = 1, offset = 0)
            }
            val playlistsResponse = withContext(Dispatchers.IO) {
                apiClient.apiService.getPlaylists(limit = 1, offset = 0)
            }
            val artistsResponse = withContext(Dispatchers.IO) {
                apiClient.apiService.getFollowedArtists(limit = 1)
            }

            val overview = SpotifyLibraryOverview(
                userName = profile?.displayName ?: profile?.id ?: "User",
                likedCount = likedResponse.body()?.total ?: 0,
                albumsCount = albumsResponse.body()?.total ?: 0,
                playlistsCount = playlistsResponse.body()?.total ?: 0,
                artistsCount = artistsResponse.body()?.artists?.total ?: 0,
                currentTrack = playback?.item?.name,
                currentArtist = playback?.item?.artists?.firstOrNull()?.name,
                isPremium = profile?.product == "premium"
            )

            Log.d(TAG, "Overview: ${overview.userName}, ${overview.likedCount} liked, ${overview.albumsCount} albums")
            overview
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching overview: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch recently played tracks.
     */
    suspend fun fetchRecentTracks(limit: Int = 20): SpotifyTrackListResponse? {
        val accessToken = getAccessToken() ?: return null

        return try {
            Log.d(TAG, "=== Fetching recent tracks (limit=$limit) ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.getRecentlyPlayed(limit = limit)
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to get recent tracks: ${response.code()}")
                return null
            }

            val body = response.body() ?: return null

            val tracks = body.items?.mapNotNull { item ->
                val track = item.track ?: return@mapNotNull null
                SpotifyTrackItem(
                    id = track.id ?: return@mapNotNull null,
                    name = track.name?.truncateForBle(MAX_TRACK_NAME) ?: "Unknown",
                    artist = track.artists?.firstOrNull()?.name?.truncateForBle(MAX_ARTIST_NAME) ?: "Unknown",
                    album = track.album?.name?.truncateForBle(MAX_ALBUM_NAME),
                    durationMs = track.durationMs ?: 0,
                    uri = track.uri ?: "spotify:track:${track.id}",
                    imageUrl = track.album?.images?.lastOrNull()?.url // Smallest image
                )
            } ?: emptyList()

            val result = SpotifyTrackListResponse(
                type = "recent",
                items = tracks,
                offset = 0,
                limit = limit,
                total = tracks.size,
                hasMore = body.cursors?.after != null
            )

            Log.d(TAG, "Got ${tracks.size} recent tracks")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching recent tracks: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch liked/saved tracks.
     */
    suspend fun fetchLikedTracks(offset: Int = 0, limit: Int = 20): SpotifyTrackListResponse? {
        val accessToken = getAccessToken() ?: return null

        return try {
            Log.d(TAG, "=== Fetching liked tracks (offset=$offset, limit=$limit) ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.getSavedTracks(limit = limit, offset = offset)
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to get liked tracks: ${response.code()}")
                return null
            }

            val body = response.body() ?: return null

            val tracks = body.items?.mapNotNull { savedTrack ->
                val track = savedTrack.track ?: return@mapNotNull null
                SpotifyTrackItem(
                    id = track.id ?: return@mapNotNull null,
                    name = track.name?.truncateForBle(MAX_TRACK_NAME) ?: "Unknown",
                    artist = track.artists?.firstOrNull()?.name?.truncateForBle(MAX_ARTIST_NAME) ?: "Unknown",
                    album = track.album?.name?.truncateForBle(MAX_ALBUM_NAME),
                    durationMs = track.durationMs ?: 0,
                    uri = track.uri ?: "spotify:track:${track.id}",
                    imageUrl = track.album?.images?.lastOrNull()?.url
                )
            } ?: emptyList()

            val result = SpotifyTrackListResponse(
                type = "liked",
                items = tracks,
                offset = offset,
                limit = limit,
                total = body.total ?: 0,
                hasMore = (offset + tracks.size) < (body.total ?: 0)
            )

            Log.d(TAG, "Got ${tracks.size} liked tracks (total: ${body.total})")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching liked tracks: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch saved albums.
     */
    suspend fun fetchSavedAlbums(offset: Int = 0, limit: Int = 20): SpotifyAlbumListResponse? {
        val accessToken = getAccessToken() ?: return null

        return try {
            Log.d(TAG, "=== Fetching saved albums (offset=$offset, limit=$limit) ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.getSavedAlbums(limit = limit, offset = offset)
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to get saved albums: ${response.code()}")
                return null
            }

            val body = response.body() ?: return null

            val albums = body.items?.mapNotNull { savedAlbum ->
                val album = savedAlbum.album ?: return@mapNotNull null
                val albumName = album.name?.truncateForBle(MAX_ALBUM_NAME) ?: "Unknown"
                val artistName = album.artists?.firstOrNull()?.name?.truncateForBle(MAX_ARTIST_NAME) ?: "Unknown"
                // Generate art hash using artist|album for consistent cache lookup
                val artHash = CRC32Util.generateAlbumArtHash(artistName, albumName)
                SpotifyAlbumItem(
                    id = album.id ?: return@mapNotNull null,
                    name = albumName,
                    artist = artistName,
                    trackCount = album.totalTracks ?: 0,
                    uri = album.uri ?: "spotify:album:${album.id}",
                    imageUrl = album.images?.lastOrNull()?.url,
                    year = album.releaseDate?.take(4),
                    artHash = artHash
                )
            } ?: emptyList()

            val result = SpotifyAlbumListResponse(
                items = albums,
                offset = offset,
                limit = limit,
                total = body.total ?: 0,
                hasMore = (offset + albums.size) < (body.total ?: 0)
            )

            Log.d(TAG, "Got ${albums.size} saved albums (total: ${body.total})")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching saved albums: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch user playlists.
     */
    suspend fun fetchPlaylists(offset: Int = 0, limit: Int = 20): SpotifyPlaylistListResponse? {
        val accessToken = getAccessToken() ?: return null

        return try {
            Log.d(TAG, "=== Fetching playlists (offset=$offset, limit=$limit) ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.getPlaylists(limit = limit, offset = offset)
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to get playlists: ${response.code()}")
                return null
            }

            val body = response.body() ?: return null

            val playlists = body.items?.mapNotNull { playlist ->
                SpotifyPlaylistItem(
                    id = playlist.id ?: return@mapNotNull null,
                    name = playlist.name?.truncateForBle(MAX_PLAYLIST_NAME) ?: "Unknown",
                    owner = playlist.owner?.displayName ?: playlist.owner?.id,
                    trackCount = playlist.tracks?.total ?: 0,
                    uri = playlist.uri ?: "spotify:playlist:${playlist.id}",
                    imageUrl = playlist.images?.lastOrNull()?.url,
                    isPublic = playlist.isPublic
                )
            } ?: emptyList()

            val result = SpotifyPlaylistListResponse(
                items = playlists,
                offset = offset,
                limit = limit,
                total = body.total ?: 0,
                hasMore = (offset + playlists.size) < (body.total ?: 0)
            )

            Log.d(TAG, "Got ${playlists.size} playlists (total: ${body.total})")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching playlists: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch followed artists.
     * Artists use cursor-based pagination (not offset/limit).
     */
    suspend fun fetchFollowedArtists(limit: Int = 20, afterCursor: String? = null): SpotifyArtistListResponse? {
        val accessToken = getAccessToken() ?: return null

        return try {
            Log.d(TAG, "=== Fetching followed artists (limit=$limit, after=$afterCursor) ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = false)

            val response = withContext(Dispatchers.IO) {
                apiClient.apiService.getFollowedArtists(limit = limit, after = afterCursor)
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to get followed artists: ${response.code()}")
                return null
            }

            val body = response.body()?.artists ?: return null

            val artists = body.items?.mapNotNull { artist ->
                val artistName = artist.name?.truncateForBle(MAX_ARTIST_NAME) ?: "Unknown"
                // Generate art hash using artist name for consistent cache lookup
                val artHash = CRC32Util.generateArtistArtHash(artistName)
                SpotifyArtistItem(
                    id = artist.id ?: return@mapNotNull null,
                    name = artistName,
                    genres = artist.genres?.take(3) ?: emptyList(),
                    followers = artist.followers?.total ?: 0,
                    uri = artist.uri ?: "spotify:artist:${artist.id}",
                    imageUrl = artist.images?.lastOrNull()?.url, // Smallest image
                    artHash = artHash
                )
            } ?: emptyList()

            val result = SpotifyArtistListResponse(
                items = artists,
                total = body.total ?: 0,
                hasMore = body.cursors?.after != null,
                nextCursor = body.cursors?.after
            )

            Log.d(TAG, "Got ${artists.size} followed artists (total: ${body.total})")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching followed artists: ${e.message}", e)
            null
        }
    }

    /**
     * Play a Spotify URI (track, album, or playlist).
     */
    suspend fun playUri(uri: String): Boolean {
        val accessToken = getAccessToken() ?: return false

        return try {
            Log.d(TAG, "=== Playing URI: $uri ===")
            val apiClient = SpotifyApiClient.createSimple(accessToken, enableLogging = true)

            val response = withContext(Dispatchers.IO) {
                if (uri.contains(":track:")) {
                    // For single tracks, use uris array
                    apiClient.apiService.play(
                        PlayRequest(uris = listOf(uri))
                    )
                } else {
                    // For albums/playlists, use contextUri
                    apiClient.apiService.play(
                        PlayRequest(contextUri = uri)
                    )
                }
            }

            val success = response.isSuccessful || response.code() == 204
            Log.d(TAG, "Play URI result: $success (code: ${response.code()})")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception playing URI: ${e.message}", e)
            false
        }
    }
}
