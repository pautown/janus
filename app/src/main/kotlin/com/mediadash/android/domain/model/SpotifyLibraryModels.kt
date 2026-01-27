package com.mediadash.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Spotify library data models for BLE transmission to CarThing.
 * These are compact representations optimized for bandwidth.
 */

/**
 * Library overview with stats and current state.
 */
@Serializable
data class SpotifyLibraryOverview(
    @SerialName("u") val userName: String,           // User display name
    @SerialName("lt") val likedCount: Int,           // Total liked tracks
    @SerialName("al") val albumsCount: Int,          // Total saved albums
    @SerialName("pl") val playlistsCount: Int,       // Total playlists
    @SerialName("ar") val artistsCount: Int,         // Followed artists
    @SerialName("ct") val currentTrack: String?,     // Currently playing track name
    @SerialName("ca") val currentArtist: String?,    // Currently playing artist
    @SerialName("pr") val isPremium: Boolean,        // Premium account?
    @SerialName("t") val timestamp: Long = System.currentTimeMillis()
)

/**
 * A track item for library lists (recent, liked, queue).
 */
@Serializable
data class SpotifyTrackItem(
    @SerialName("i") val id: String,                 // Spotify track ID
    @SerialName("n") val name: String,               // Track title (truncated to 64 chars)
    @SerialName("a") val artist: String,             // Primary artist (truncated to 48 chars)
    @SerialName("al") val album: String?,            // Album name (truncated to 48 chars)
    @SerialName("d") val durationMs: Long,           // Duration in milliseconds
    @SerialName("u") val uri: String,                // Spotify URI for playback
    @SerialName("im") val imageUrl: String?          // Album art URL (smallest usable)
)

/**
 * An album item for library lists.
 */
@Serializable
data class SpotifyAlbumItem(
    @SerialName("i") val id: String,                 // Spotify album ID
    @SerialName("n") val name: String,               // Album title (truncated)
    @SerialName("a") val artist: String,             // Primary artist (truncated)
    @SerialName("tc") val trackCount: Int,           // Number of tracks
    @SerialName("u") val uri: String,                // Spotify URI
    @SerialName("im") val imageUrl: String?,         // Cover art URL
    @SerialName("y") val year: String?,              // Release year
    @SerialName("h") val artHash: String? = null     // Album art hash (CRC32) for requesting art
)

/**
 * A playlist item for library lists.
 */
@Serializable
data class SpotifyPlaylistItem(
    @SerialName("i") val id: String,                 // Spotify playlist ID
    @SerialName("n") val name: String,               // Playlist name (truncated)
    @SerialName("o") val owner: String?,             // Owner display name
    @SerialName("tc") val trackCount: Int,           // Number of tracks
    @SerialName("u") val uri: String,                // Spotify URI
    @SerialName("im") val imageUrl: String?,         // Cover art URL
    @SerialName("pu") val isPublic: Boolean?         // Public playlist?
)

/**
 * Paginated response for track lists (recent, liked).
 */
@Serializable
data class SpotifyTrackListResponse(
    @SerialName("ty") val type: String,              // "recent" or "liked"
    @SerialName("it") val items: List<SpotifyTrackItem>,
    @SerialName("o") val offset: Int,                // Current offset
    @SerialName("l") val limit: Int,                 // Items per page
    @SerialName("tt") val total: Int,                // Total available
    @SerialName("hm") val hasMore: Boolean,          // More items available?
    @SerialName("t") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Paginated response for album lists.
 */
@Serializable
data class SpotifyAlbumListResponse(
    @SerialName("it") val items: List<SpotifyAlbumItem>,
    @SerialName("o") val offset: Int,
    @SerialName("l") val limit: Int,
    @SerialName("tt") val total: Int,
    @SerialName("hm") val hasMore: Boolean,
    @SerialName("t") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Paginated response for playlist lists.
 */
@Serializable
data class SpotifyPlaylistListResponse(
    @SerialName("it") val items: List<SpotifyPlaylistItem>,
    @SerialName("o") val offset: Int,
    @SerialName("l") val limit: Int,
    @SerialName("tt") val total: Int,
    @SerialName("hm") val hasMore: Boolean,
    @SerialName("t") val timestamp: Long = System.currentTimeMillis()
)

/**
 * An artist item for library lists (followed artists).
 */
@Serializable
data class SpotifyArtistItem(
    @SerialName("i") val id: String,                 // Spotify artist ID
    @SerialName("n") val name: String,               // Artist name (truncated)
    @SerialName("g") val genres: List<String>,       // Up to 3 genres
    @SerialName("f") val followers: Int,             // Follower count
    @SerialName("u") val uri: String,                // Spotify URI
    @SerialName("im") val imageUrl: String?,         // Artist image URL
    @SerialName("ah") val artHash: String? = null    // Art hash for requesting art
)

/**
 * Cursor-paginated response for artist lists.
 * Artists use cursor pagination, not offset/limit.
 */
@Serializable
data class SpotifyArtistListResponse(
    @SerialName("it") val items: List<SpotifyArtistItem>,
    @SerialName("tt") val total: Int,
    @SerialName("hm") val hasMore: Boolean,
    @SerialName("nc") val nextCursor: String? = null,  // Cursor for next page
    @SerialName("t") val timestamp: Long = System.currentTimeMillis()
)

/**
 * Helper to truncate strings for BLE transmission.
 */
fun String.truncateForBle(maxLen: Int): String {
    return if (length <= maxLen) this else substring(0, maxLen - 2) + ".."
}
