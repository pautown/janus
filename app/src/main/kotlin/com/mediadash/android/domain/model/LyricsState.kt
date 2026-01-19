package com.mediadash.android.domain.model

import kotlinx.serialization.Serializable

/**
 * Represents synced lyrics with timestamps.
 * Uses compact format for efficient BLE transmission.
 */
@Serializable
data class LyricsState(
    val hash: String,           // CRC32 hash of "artist|track" for identification
    val trackTitle: String,
    val artist: String,
    val synced: Boolean,        // true if has timestamps, false for plain lyrics
    val lines: List<LyricsLine>,
    val source: String? = null  // e.g., "lrclib", "genius"
) {
    companion object {
        val EMPTY = LyricsState(
            hash = "",
            trackTitle = "",
            artist = "",
            synced = false,
            lines = emptyList(),
            source = null
        )
    }
}

/**
 * A single line of lyrics with optional timestamp.
 * Timestamp is in milliseconds from track start.
 */
@Serializable
data class LyricsLine(
    val t: Long,        // timestamp in ms (0 if unsynced)
    val l: String       // lyrics text
)

/**
 * Compact BLE format for lyrics transmission.
 * Uses short field names to minimize bandwidth.
 */
@Serializable
data class CompactLyricsResponse(
    val h: String,              // hash
    val s: Boolean,             // synced (has timestamps)
    val n: Int,                 // total line count
    val c: Int,                 // chunk index (0-based)
    val m: Int,                 // max chunks (total)
    val l: List<CompactLyricsLine>  // lyrics lines in this chunk
)

/**
 * Compact lyrics line for BLE.
 */
@Serializable
data class CompactLyricsLine(
    val t: Long,        // timestamp in ms
    val l: String       // text
)

/**
 * Request for lyrics via BLE.
 */
@Serializable
data class LyricsRequest(
    val action: String,         // "get" or "clear"
    val hash: String? = null,   // optional hash to request specific lyrics
    val artist: String? = null,
    val track: String? = null
) {
    companion object {
        const val ACTION_GET = "get"
        const val ACTION_CLEAR = "clear"
    }
}
