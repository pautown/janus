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
    val offset: Int = 0,                // For library/podcast pagination: offset
    val limit: Int = 20,                // For library/podcast pagination: items per page
    val channel: String? = null,        // For select_media_channel: app name to control
    val service: String? = null,        // For check_connection: service name (e.g., "spotify")
    val queueIndex: Int = -1,           // For queue_shift: position in queue to skip to (0-based)
    val trackId: String? = null,        // For like_track/unlike_track: Spotify track ID (null = current track)
    val uri: String? = null,            // For play_uri: Spotify URI (spotify:track:... or spotify:album:... etc.)
    val after: String? = null            // For library_artists: cursor for pagination
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

        // Connection status requests (check if services are authenticated/connected)
        const val ACTION_CHECK_CONNECTION = "check_connection"           // Check specific service (uses 'service' field)
        const val ACTION_CHECK_ALL_CONNECTIONS = "check_all_connections" // Check all services

        // Queue requests (for Spotify and other authorized services)
        const val ACTION_REQUEST_QUEUE = "request_queue"                 // Get current playback queue
        const val ACTION_QUEUE_SHIFT = "queue_shift"                     // Skip to specific position in queue (uses 'queueIndex' field)

        // Spotify-specific controls (require Spotify OAuth authentication)
        const val ACTION_SHUFFLE_ON = "shuffle_on"                       // Enable shuffle
        const val ACTION_SHUFFLE_OFF = "shuffle_off"                     // Disable shuffle
        const val ACTION_REPEAT_OFF = "repeat_off"                       // Disable repeat
        const val ACTION_REPEAT_TRACK = "repeat_track"                   // Repeat current track
        const val ACTION_REPEAT_CONTEXT = "repeat_context"               // Repeat playlist/album
        const val ACTION_LIKE_TRACK = "like_track"                       // Save track to library (uses 'trackId' field, null = current)
        const val ACTION_UNLIKE_TRACK = "unlike_track"                   // Remove track from library (uses 'trackId' field, null = current)
        const val ACTION_REQUEST_SPOTIFY_STATE = "request_spotify_state" // Request fresh shuffle/repeat/liked state from Spotify API

        // Spotify library browsing (require Spotify OAuth authentication)
        const val ACTION_REQUEST_LIBRARY_OVERVIEW = "library_overview"   // Get library overview/stats
        const val ACTION_REQUEST_LIBRARY_RECENT = "library_recent"       // Get recently played (uses offset, limit)
        const val ACTION_REQUEST_LIBRARY_LIKED = "library_liked"         // Get saved tracks (uses offset, limit)
        const val ACTION_REQUEST_LIBRARY_ALBUMS = "library_albums"       // Get saved albums (uses offset, limit)
        const val ACTION_REQUEST_LIBRARY_PLAYLISTS = "library_playlists" // Get playlists (uses offset, limit)
        const val ACTION_REQUEST_LIBRARY_ARTISTS = "library_artists"     // Get followed artists (uses limit, after cursor)
        const val ACTION_PLAY_SPOTIFY_URI = "play_uri"                   // Play Spotify URI (uses 'uri' field)

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
            ACTION_SELECT_MEDIA_CHANNEL,
            ACTION_CHECK_CONNECTION,
            ACTION_CHECK_ALL_CONNECTIONS,
            ACTION_REQUEST_QUEUE,
            ACTION_QUEUE_SHIFT,
            // Spotify-specific
            ACTION_SHUFFLE_ON,
            ACTION_SHUFFLE_OFF,
            ACTION_REPEAT_OFF,
            ACTION_REPEAT_TRACK,
            ACTION_REPEAT_CONTEXT,
            ACTION_LIKE_TRACK,
            ACTION_UNLIKE_TRACK,
            ACTION_REQUEST_SPOTIFY_STATE,
            // Spotify library browsing
            ACTION_REQUEST_LIBRARY_OVERVIEW,
            ACTION_REQUEST_LIBRARY_RECENT,
            ACTION_REQUEST_LIBRARY_LIKED,
            ACTION_REQUEST_LIBRARY_ALBUMS,
            ACTION_REQUEST_LIBRARY_PLAYLISTS,
            ACTION_REQUEST_LIBRARY_ARTISTS,
            ACTION_PLAY_SPOTIFY_URI
        )
    }

    fun isValid(): Boolean = action in VALID_ACTIONS
}
