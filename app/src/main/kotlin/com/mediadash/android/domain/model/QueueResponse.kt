package com.mediadash.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response containing the current playback queue.
 * Sent back via BLE when a request_queue command is received.
 */
@Serializable
data class QueueResponse(
    val service: String,              // Service name (e.g., "spotify")
    val tracks: List<QueueTrack>,     // List of tracks in queue
    val currentlyPlaying: QueueTrack?, // Currently playing track (not in queue list)
    val timestamp: Long               // Unix timestamp when queue was fetched
) {
    companion object {
        const val SERVICE_SPOTIFY = "spotify"
    }

    /**
     * Convert to compact format for BLE transmission.
     * Uses short field names to reduce payload size.
     */
    fun toCompact(): CompactQueueResponse {
        return CompactQueueResponse(
            s = service,
            c = currentlyPlaying?.toCompact(),
            q = tracks.map { it.toCompact() },
            t = timestamp
        )
    }
}

/**
 * Individual track in the queue.
 */
@Serializable
data class QueueTrack(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val uri: String                   // Spotify URI for potential future use
) {
    fun toCompact(): CompactQueueTrack {
        return CompactQueueTrack(
            t = title,
            a = artist,
            b = album,
            d = (durationMs / 1000).toInt(), // Convert to seconds
            u = uri
        )
    }
}

/**
 * Compact queue response for BLE transmission.
 * Uses short field names to minimize payload size.
 */
@Serializable
data class CompactQueueResponse(
    @SerialName("s") val s: String,              // service
    @SerialName("c") val c: CompactQueueTrack?,  // currently playing
    @SerialName("q") val q: List<CompactQueueTrack>, // queue
    @SerialName("t") val t: Long                 // timestamp
)

/**
 * Compact track for BLE transmission.
 */
@Serializable
data class CompactQueueTrack(
    @SerialName("t") val t: String,    // title
    @SerialName("a") val a: String,    // artist
    @SerialName("b") val b: String?,   // album
    @SerialName("d") val d: Int,       // duration in seconds
    @SerialName("u") val u: String     // uri
)
