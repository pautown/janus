package com.mediadash.android.domain.model

import kotlinx.serialization.Serializable

/**
 * Legacy response - sent to CarThing when podcast information is requested.
 * Contains all subscribed podcasts with their episodes.
 * @deprecated Use PodcastListResponse + lazy loading instead
 */
@Serializable
data class PodcastInfoResponse(
    val podcasts: List<PodcastShowInfo>,
    val currentlyPlaying: CurrentlyPlayingInfo? = null
)

/**
 * Information about a single podcast show with its episodes.
 */
@Serializable
data class PodcastShowInfo(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val imageUrl: String,
    val episodeCount: Int,
    val episodes: List<PodcastEpisodeInfo>
)

/**
 * Information about currently playing episode.
 */
@Serializable
data class CurrentlyPlayingInfo(
    val podcastId: String,
    val episodeTitle: String,
    val episodeIndex: Int
)

/**
 * Metadata for a single podcast episode.
 */
@Serializable
data class PodcastEpisodeInfo(
    val title: String,
    val duration: Long,         // milliseconds
    val publishDate: String,    // Human-readable date for display
    val pubDate: Long = 0L      // Unix timestamp (ms) for sorting - most recent episodes have higher values
)

// ============================================================================
// New Lazy Loading Response Types
// ============================================================================

/**
 * Response containing just podcast channel metadata (no episodes).
 * Used for A-Z podcast list view - episodes are loaded on-demand.
 */
@Serializable
data class PodcastListResponse(
    val podcasts: List<PodcastChannelInfo>,
    val currentlyPlaying: CurrentlyPlayingInfo? = null
)

/**
 * Minimal podcast channel info for list display.
 * Does NOT include episodes - those are fetched separately.
 */
@Serializable
data class PodcastChannelInfo(
    val id: String,
    val title: String,
    val author: String,
    val episodeCount: Int       // Total episode count for display
)

/**
 * Response containing recent episodes across all podcasts.
 * Used for "Recent Episodes" view.
 */
@Serializable
data class RecentEpisodesResponse(
    val episodes: List<RecentEpisodeInfo>,
    val totalCount: Int         // Total number of recent episodes available
)

/**
 * Episode info with parent podcast context for recent episodes view.
 */
@Serializable
data class RecentEpisodeInfo(
    val podcastId: String,
    val podcastTitle: String,
    val feedUrl: String,        // RSS feed URL for episode hash generation
    val title: String,
    val duration: Long,         // milliseconds
    val publishDate: String,    // Human-readable date for display
    val pubDate: Long,          // Unix timestamp (ms) for sorting
    val episodeIndex: Int       // Index within podcast (for backward compat with C plugin)
)

/**
 * Response containing episodes for a specific podcast with pagination.
 * Used when user browses into a podcast from A-Z view.
 */
@Serializable
data class PodcastEpisodesResponse(
    val podcastId: String,
    val podcastTitle: String,
    val feedUrl: String,        // RSS feed URL for episode hash generation
    val totalEpisodes: Int,     // Total episode count for this podcast
    val offset: Int,            // Current offset (for pagination)
    val hasMore: Boolean,       // True if more episodes available after this batch
    val episodes: List<PodcastEpisodeInfo>
)
