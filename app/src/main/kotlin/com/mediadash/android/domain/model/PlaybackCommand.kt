package com.mediadash.android.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents a playback command received from the Go client.
 * Commands are written to the Playback Control characteristic.
 */
@Serializable
data class PlaybackCommand(
    val action: String,           // play, pause, next, previous, seek, volume, stop, toggle, play_episode
    val value: Long = 0,          // For volume: 0-100, for seek: milliseconds
    val podcastId: String? = null,      // For podcast commands: podcast hash
    val episodeHash: String? = null,    // For play_episode: episode hash (CRC32 of feedUrl+pubDate+duration)
    val episodeIndex: Int = -1,         // DEPRECATED: use episodeHash instead
    val offset: Int = 0,                // For request_podcast_episodes: pagination offset
    val limit: Int = 15,                // For request_podcast_episodes: episodes per page
    val channel: String? = null         // For select_media_channel: app name to control
) {
    companion object {
        // Valid action types
        const val ACTION_PLAY = "play"
        const val ACTION_PAUSE = "pause"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NEXT = "next"
        const val ACTION_PREVIOUS = "previous"
        const val ACTION_SEEK = "seek"
        const val ACTION_VOLUME = "volume"
        const val ACTION_STOP = "stop"
        const val ACTION_PLAY_EPISODE = "play_episode"              // New: play by episode hash
        const val ACTION_PLAY_PODCAST_EPISODE = "play_podcast_episode"  // DEPRECATED: use play_episode

        // Podcast data requests (lazy loading)
        const val ACTION_REQUEST_PODCAST_LIST = "request_podcast_list"           // Get all podcast channel names
        const val ACTION_REQUEST_RECENT_EPISODES = "request_recent_episodes"     // Get recent episodes across all podcasts
        const val ACTION_REQUEST_PODCAST_EPISODES = "request_podcast_episodes"   // Get episodes for specific podcast (paginated)

        // Media channels request (list of audio apps with active sessions)
        const val ACTION_REQUEST_MEDIA_CHANNELS = "request_media_channels"

        // Media channel selection (switch which app to control)
        const val ACTION_SELECT_MEDIA_CHANNEL = "select_media_channel"

        // Legacy - still supported for backwards compatibility
        const val ACTION_PODCAST_INFO_REQUEST = "request_podcast_info"

        val VALID_ACTIONS = setOf(
            ACTION_PLAY, ACTION_PAUSE, ACTION_TOGGLE,
            ACTION_NEXT, ACTION_PREVIOUS,
            ACTION_SEEK, ACTION_VOLUME, ACTION_STOP,
            ACTION_PODCAST_INFO_REQUEST,
            ACTION_PLAY_EPISODE,
            ACTION_PLAY_PODCAST_EPISODE,  // DEPRECATED
            ACTION_REQUEST_PODCAST_LIST,
            ACTION_REQUEST_RECENT_EPISODES,
            ACTION_REQUEST_PODCAST_EPISODES,
            ACTION_REQUEST_MEDIA_CHANNELS,
            ACTION_SELECT_MEDIA_CHANNEL
        )
    }

    fun isValid(): Boolean = action in VALID_ACTIONS
}
