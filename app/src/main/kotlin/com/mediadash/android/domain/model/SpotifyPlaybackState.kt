package com.mediadash.android.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents Spotify playback state including shuffle, repeat, and current track.
 * Used for BLE transmission to CarThing.
 */
@Serializable
data class SpotifyPlaybackState(
    val shuffleEnabled: Boolean = false,
    val repeatMode: String = "off",  // "off", "track", "context"
    val currentTrackId: String? = null,
    val currentAlbumId: String? = null,
    val currentArtistId: String? = null,  // Primary artist
    val isPlaying: Boolean = false,
    val isTrackLiked: Boolean = false,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        // Repeat mode constants (matching Spotify API)
        const val REPEAT_OFF = "off"
        const val REPEAT_TRACK = "track"
        const val REPEAT_CONTEXT = "context"
    }
}
