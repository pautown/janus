package com.mediadash.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.zip.CRC32

/**
 * Compact BLE transfer models - optimized for minimal BLE bandwidth.
 * Uses short field names to reduce JSON payload size.
 *
 * Key design principles:
 * - No episode index - episodes are identified by hash and sorted by pubDate
 * - Episode hash = CRC32(feedUrl + pubDate + duration) for unique identification
 * - Unix timestamps in SECONDS (not milliseconds) for compactness
 * - Duration in SECONDS (not milliseconds)
 */

// ============================================================================
// Hash Generation Functions
// ============================================================================

/**
 * Generates a unique hash identifier for an episode.
 * Hash = CRC32(feedUrl + "|" + pubDate + "|" + duration)
 *
 * This ensures uniqueness because:
 * - feedUrl identifies the podcast
 * - pubDate is typically unique per episode within a podcast
 * - duration adds extra uniqueness for edge cases
 *
 * @param feedUrl The podcast's RSS feed URL
 * @param pubDate Unix timestamp in milliseconds
 * @param duration Duration in milliseconds
 * @return 8-character hex string (CRC32)
 */
fun generateEpisodeHash(feedUrl: String, pubDate: Long, duration: Long): String {
    // Use seconds for the hash to match what we send over BLE
    val pubDateSec = pubDate / 1000
    val durationSec = duration / 1000
    val input = "$feedUrl|$pubDateSec|$durationSec"
    val crc = CRC32()
    crc.update(input.toByteArray())
    return String.format("%08x", crc.value)
}

/**
 * Generates a compact hash identifier for a podcast channel.
 * Uses the podcast ID directly if short, otherwise CRC32 hash.
 */
fun generatePodcastHash(podcastId: String): String {
    if (podcastId.length <= 8) return podcastId
    val crc = CRC32()
    crc.update(podcastId.toByteArray())
    return String.format("%08x", crc.value)
}

/**
 * Finds a podcast by its hash from a list of podcasts.
 */
fun <T> findPodcastByHash(podcasts: List<T>, targetHash: String, getId: (T) -> String): T? {
    return podcasts.find { generatePodcastHash(getId(it)) == targetHash }
}

/**
 * Finds an episode by its hash from a list of episodes.
 * Requires feedUrl to compute hash for comparison.
 */
fun <T> findEpisodeByHash(
    episodes: List<T>,
    targetHash: String,
    feedUrl: String,
    getPubDate: (T) -> Long,
    getDuration: (T) -> Long
): T? {
    return episodes.find { ep ->
        generateEpisodeHash(feedUrl, getPubDate(ep), getDuration(ep)) == targetHash
    }
}

// ============================================================================
// Compact Episode Models (for BLE transfer)
// ============================================================================

/**
 * Compact episode info for BLE transfer (used in recent episodes list).
 * Episodes identified by hash for new API, includes podcastHash+index for backward compat.
 *
 * JSON: {"h":"abc12345","p":"def67890","c":"Podcast Name","t":"Episode Title","d":3600,"u":1704499200,"i":0}
 */
@Serializable
data class CompactEpisode(
    @SerialName("h") val hash: String,        // Episode hash (feedUrl+pubDate+duration CRC32)
    @SerialName("p") val podcastHash: String, // Podcast hash (for backward compat with C plugin)
    @SerialName("c") val channel: String,     // Podcast/channel title
    @SerialName("t") val title: String,       // Episode title
    @SerialName("d") val duration: Int,       // Duration in SECONDS
    @SerialName("u") val pubDate: Long,       // Unix timestamp in SECONDS for sorting
    @SerialName("i") val index: Int           // Episode index (for backward compat with C plugin)
)

/**
 * Compact podcast channel info for BLE transfer.
 *
 * JSON: {"h":"abc12345","n":"Podcast Name","c":50}
 */
@Serializable
data class CompactPodcast(
    @SerialName("h") val hash: String,        // Podcast ID hash
    @SerialName("n") val name: String,        // Podcast title
    @SerialName("c") val count: Int           // Episode count
)

// ============================================================================
// Compact Response Types (for BLE transfer)
// ============================================================================

/**
 * Compact podcast list response.
 * Type header: 1
 */
@Serializable
data class CompactPodcastListResponse(
    @SerialName("p") val podcasts: List<CompactPodcast>,
    @SerialName("np") val nowPlaying: CompactNowPlaying? = null
)

/**
 * Compact recent episodes response.
 * Type header: 2
 */
@Serializable
data class CompactRecentEpisodesResponse(
    @SerialName("e") val episodes: List<CompactEpisode>,
    @SerialName("t") val total: Int
)

/**
 * Compact podcast episodes response (paginated).
 * Type header: 3
 */
@Serializable
data class CompactPodcastEpisodesResponse(
    @SerialName("h") val podcastHash: String,   // Podcast ID hash
    @SerialName("n") val name: String,          // Podcast title
    @SerialName("t") val total: Int,            // Total episodes
    @SerialName("o") val offset: Int,           // Current offset (for pagination display)
    @SerialName("m") val more: Boolean,         // Has more episodes
    @SerialName("e") val episodes: List<CompactEpisodeSimple>
)

/**
 * Simplified episode info when podcast context is already known.
 * Used in CompactPodcastEpisodesResponse where channel name is redundant.
 *
 * JSON: {"h":"a1b2c3d4","t":"Episode Title","d":3600,"u":1704499200}
 */
@Serializable
data class CompactEpisodeSimple(
    @SerialName("h") val hash: String,        // Episode hash (for playback)
    @SerialName("t") val title: String,       // Episode title
    @SerialName("d") val duration: Int,       // Duration in SECONDS
    @SerialName("u") val pubDate: Long        // Unix timestamp in SECONDS for sorting
)

/**
 * Compact now playing info.
 */
@Serializable
data class CompactNowPlaying(
    @SerialName("h") val podcastHash: String,   // Podcast ID hash
    @SerialName("e") val episodeHash: String,   // Episode hash (for identification)
    @SerialName("t") val title: String          // Episode title
)

// ============================================================================
// Converters: Full models -> Compact models
// ============================================================================

/**
 * Convert RecentEpisodeInfo to CompactEpisode.
 * Uses feedUrl from the episode info to generate hash.
 * Includes podcastHash and index for backward compatibility with C plugin.
 */
fun RecentEpisodeInfo.toCompact(): CompactEpisode = CompactEpisode(
    hash = generateEpisodeHash(feedUrl, pubDate, duration),
    podcastHash = generatePodcastHash(podcastId),
    channel = podcastTitle,
    title = title,
    duration = (duration / 1000).toInt(),  // ms -> seconds
    pubDate = pubDate / 1000,              // ms -> seconds
    index = episodeIndex
)

/**
 * Convert PodcastChannelInfo to CompactPodcast
 */
fun PodcastChannelInfo.toCompact(): CompactPodcast = CompactPodcast(
    hash = generatePodcastHash(id),
    name = title,
    count = episodeCount
)

/**
 * Convert PodcastEpisodeInfo to CompactEpisodeSimple.
 * Requires feedUrl from the parent podcast.
 */
fun PodcastEpisodeInfo.toCompactSimple(feedUrl: String): CompactEpisodeSimple = CompactEpisodeSimple(
    hash = generateEpisodeHash(feedUrl, pubDate, duration),
    title = title,
    duration = (duration / 1000).toInt(),  // ms -> seconds
    pubDate = pubDate / 1000               // ms -> seconds
)

/**
 * Convert CurrentlyPlayingInfo to CompactNowPlaying.
 * Requires feedUrl and episode details to generate episode hash.
 */
fun CurrentlyPlayingInfo.toCompact(feedUrl: String, pubDate: Long, duration: Long): CompactNowPlaying = CompactNowPlaying(
    podcastHash = generatePodcastHash(podcastId),
    episodeHash = generateEpisodeHash(feedUrl, pubDate, duration),
    title = episodeTitle
)

/**
 * Convert full PodcastListResponse to compact format
 */
fun PodcastListResponse.toCompact(): CompactPodcastListResponse = CompactPodcastListResponse(
    podcasts = podcasts.map { it.toCompact() },
    nowPlaying = null  // Now playing requires episode details, handle separately
)

/**
 * Convert full RecentEpisodesResponse to compact format.
 * Each episode already contains feedUrl for hash generation.
 */
fun RecentEpisodesResponse.toCompact(): CompactRecentEpisodesResponse = CompactRecentEpisodesResponse(
    episodes = episodes.map { it.toCompact() },
    total = totalCount
)

/**
 * Convert full PodcastEpisodesResponse to compact format.
 * Uses feedUrl from the response to generate episode hashes.
 */
fun PodcastEpisodesResponse.toCompact(): CompactPodcastEpisodesResponse = CompactPodcastEpisodesResponse(
    podcastHash = generatePodcastHash(podcastId),
    name = podcastTitle,
    total = totalEpisodes,
    offset = offset,
    more = hasMore,
    episodes = episodes.map { it.toCompactSimple(feedUrl) }
)
