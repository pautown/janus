package com.mediadash.android.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents the current media playback state.
 * This is serialized to JSON and sent via BLE to the Go client.
 * All time values are in milliseconds.
 */
@Serializable
data class MediaState(
    val isPlaying: Boolean,
    val playbackState: String,  // "playing", "paused", "stopped"
    val trackTitle: String,
    val artist: String,
    val album: String,
    val duration: Long,         // milliseconds
    val position: Long,         // milliseconds
    val volume: Int,            // 0-100
    val albumArtHash: String? = null,  // CRC32 as decimal string
    val mediaChannel: String? = null   // App name being controlled (e.g., "Spotify", "YouTube Music")
) {
    companion object {
        val EMPTY = MediaState(
            isPlaying = false,
            playbackState = "stopped",
            trackTitle = "",
            artist = "",
            album = "",
            duration = 0L,
            position = 0L,
            volume = 0,
            albumArtHash = null,
            mediaChannel = null
        )
    }
}
