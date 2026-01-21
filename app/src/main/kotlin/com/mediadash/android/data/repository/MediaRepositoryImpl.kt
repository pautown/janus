package com.mediadash.android.data.repository

import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.util.Log
import com.mediadash.android.data.media.MediaControllerManager
import com.mediadash.android.data.media.PlaybackSource
import com.mediadash.android.data.media.PlaybackSourceTracker
import com.mediadash.android.domain.model.AlbumArtChunk
import com.mediadash.android.domain.model.MediaState
import com.mediadash.android.domain.model.PlaybackCommand
import com.mediadash.android.domain.model.PodcastListResponse
import com.mediadash.android.domain.model.PodcastChannelInfo
import com.mediadash.android.domain.model.RecentEpisodesResponse
import com.mediadash.android.domain.model.RecentEpisodeInfo
import com.mediadash.android.domain.model.PodcastEpisodesResponse
import com.mediadash.android.domain.model.PodcastEpisodeInfo
import com.mediadash.android.domain.model.generatePodcastHash
import com.mediadash.android.domain.model.generateEpisodeHash
import com.mediadash.android.media.PodcastPlayerManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of MediaRepository that delegates to MediaControllerManager.
 * Tracks playback sources to enable smart resume functionality.
 */
@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val mediaControllerManager: MediaControllerManager,
    private val podcastRepository: PodcastRepository,
    private val podcastPlayerManager: PodcastPlayerManager,
    private val playbackSourceTracker: PlaybackSourceTracker
) : MediaRepository {

    companion object {
        private const val TAG = "MediaRepositoryImpl"
    }

    override val currentMediaState: StateFlow<MediaState?>
        get() = mediaControllerManager.currentMediaState

    override val currentAlbumArtChunks: StateFlow<List<AlbumArtChunk>?>
        get() = mediaControllerManager.currentAlbumArtChunks

    override val currentAlbumArtBitmap: StateFlow<Bitmap?>
        get() = mediaControllerManager.currentAlbumArtBitmap

    override suspend fun processCommand(command: PlaybackCommand) {
        val controller = mediaControllerManager.getActiveController()
        if (controller == null) {
            Log.w(TAG, "No active controller to process command: ${command.action}")
            return
        }

        val transportControls = controller.transportControls

        when (command.action) {
            PlaybackCommand.ACTION_PLAY -> {
                Log.d(TAG, "Executing: play")
                // Play the currently active source (whatever was last playing or paused)
                val activeSource = playbackSourceTracker.getCurrentActiveSource()
                Log.d(TAG, "Active source for play: $activeSource")
                when (activeSource) {
                    PlaybackSource.MEDIADASH_PODCAST -> {
                        Log.d(TAG, "Playing internal podcast")
                        podcastPlayerManager.play()
                    }
                    PlaybackSource.EXTERNAL_APP, PlaybackSource.NONE -> {
                        Log.d(TAG, "Playing external app")
                        transportControls.play()
                    }
                }
            }

            PlaybackCommand.ACTION_PAUSE -> {
                Log.d(TAG, "Executing: pause")
                // Pause the currently active source (does NOT change which source is active)
                val activeSource = playbackSourceTracker.getCurrentActiveSource()
                Log.d(TAG, "Active source for pause: $activeSource")
                when (activeSource) {
                    PlaybackSource.MEDIADASH_PODCAST -> {
                        Log.d(TAG, "Pausing internal podcast")
                        podcastPlayerManager.pause()
                    }
                    PlaybackSource.EXTERNAL_APP, PlaybackSource.NONE -> {
                        Log.d(TAG, "Pausing external app")
                        transportControls.pause()
                    }
                }
            }

            PlaybackCommand.ACTION_TOGGLE -> {
                // Toggle the currently active source
                val activeSource = playbackSourceTracker.getCurrentActiveSource()
                val isPodcastPlaying = playbackSourceTracker.isPodcastCurrentlyPlaying()
                val isExternalPlaying = playbackSourceTracker.isExternalAppCurrentlyPlaying()
                val anythingPlaying = isPodcastPlaying || isExternalPlaying

                Log.d(TAG, "Executing: toggle (activeSource=$activeSource, podcastPlaying=$isPodcastPlaying, externalPlaying=$isExternalPlaying)")

                if (anythingPlaying) {
                    // Something is playing - pause it
                    when {
                        isPodcastPlaying -> {
                            Log.d(TAG, "Pausing internal podcast")
                            podcastPlayerManager.pause()
                        }
                        isExternalPlaying -> {
                            Log.d(TAG, "Pausing external app")
                            transportControls.pause()
                        }
                    }
                } else {
                    // Nothing is playing - resume the last active source
                    when (activeSource) {
                        PlaybackSource.MEDIADASH_PODCAST -> {
                            Log.d(TAG, "Resuming internal podcast")
                            podcastPlayerManager.play()
                        }
                        PlaybackSource.EXTERNAL_APP, PlaybackSource.NONE -> {
                            Log.d(TAG, "Resuming external app")
                            transportControls.play()
                        }
                    }
                }
            }

            PlaybackCommand.ACTION_NEXT -> {
                Log.d(TAG, "Executing: next")
                transportControls.skipToNext()
            }

            PlaybackCommand.ACTION_PREVIOUS -> {
                Log.d(TAG, "Executing: previous")
                transportControls.skipToPrevious()
            }

            PlaybackCommand.ACTION_STOP -> {
                Log.d(TAG, "Executing: stop")
                // Stop the currently active source
                val activeSource = playbackSourceTracker.getCurrentActiveSource()
                when (activeSource) {
                    PlaybackSource.MEDIADASH_PODCAST -> {
                        Log.d(TAG, "Stopping internal podcast")
                        podcastPlayerManager.pause()
                    }
                    PlaybackSource.EXTERNAL_APP, PlaybackSource.NONE -> {
                        Log.d(TAG, "Stopping external app")
                        transportControls.stop()
                    }
                }
            }

            PlaybackCommand.ACTION_SEEK -> {
                Log.d(TAG, "Executing: seek to ${command.value}ms")
                transportControls.seekTo(command.value)
            }

            PlaybackCommand.ACTION_VOLUME -> {
                val volume = command.value.toInt().coerceIn(0, 100)
                Log.d(TAG, "Executing: volume to $volume")
                mediaControllerManager.setVolume(volume)
            }

            PlaybackCommand.ACTION_PODCAST_INFO_REQUEST -> {
                Log.d(TAG, "Podcast info request received - handled by GattServerService")
                // This command is handled in GattServerService by emitting the podcast info request event
                // The actual response is sent via GattServerManager.notifyPodcastInfo()
            }

            PlaybackCommand.ACTION_REQUEST_PODCAST_LIST -> {
                Log.d(TAG, "Podcast list request received - handled by GattServerService")
                // This command is handled in GattServerService
            }

            PlaybackCommand.ACTION_REQUEST_RECENT_EPISODES -> {
                Log.d(TAG, "Recent episodes request received - handled by GattServerService")
                // This command is handled in GattServerService
            }

            PlaybackCommand.ACTION_REQUEST_PODCAST_EPISODES -> {
                Log.d(TAG, "Podcast episodes request received - handled by GattServerService")
                // This command is handled in GattServerService
                // Parameters: podcastId, offset, limit
            }

            PlaybackCommand.ACTION_PLAY_EPISODE -> {
                val episodeHash = command.episodeHash

                if (episodeHash.isNullOrEmpty()) {
                    Log.w(TAG, "play_episode: missing episodeHash")
                    return
                }

                Log.d(TAG, "Executing: play_episode (hash=$episodeHash)")

                val success = playEpisodeByHash(episodeHash)
                if (success) {
                    Log.i(TAG, "Successfully started podcast playback via hash")
                } else {
                    Log.e(TAG, "Failed to start podcast playback via hash")
                }
            }

            PlaybackCommand.ACTION_PLAY_PODCAST_EPISODE -> {
                // DEPRECATED: use play_episode with episodeHash
                val podcastId = command.podcastId
                val episodeIndex = command.episodeIndex

                if (podcastId.isNullOrEmpty()) {
                    Log.w(TAG, "play_podcast_episode: missing podcastId")
                    return
                }
                if (episodeIndex < 0) {
                    Log.w(TAG, "play_podcast_episode: invalid episodeIndex: $episodeIndex")
                    return
                }

                Log.d(TAG, "Executing: play_podcast_episode (podcast=$podcastId, episode=$episodeIndex)")

                val success = playPodcastEpisode(podcastId, episodeIndex)
                if (success) {
                    Log.i(TAG, "Successfully started podcast playback")
                } else {
                    Log.e(TAG, "Failed to start podcast playback")
                }
            }

            else -> {
                Log.w(TAG, "Unknown command action: ${command.action}")
            }
        }
    }

    override suspend fun getPodcastInfo(): com.mediadash.android.domain.model.PodcastInfoResponse? {
        try {
            // Get all subscribed podcasts
            val subscribedPodcasts = podcastRepository.getSubscribedPodcasts().first()

            if (subscribedPodcasts.isEmpty()) {
                Log.w(TAG, "No subscribed podcasts found")
                return null
            }

            Log.d(TAG, "Found ${subscribedPodcasts.size} subscribed podcasts")

            // Build podcast list with episodes (limit episodes per podcast to manage size)
            val maxEpisodesPerPodcast = 20
            val podcastShowInfoList = subscribedPodcasts.map { podcast ->
                // Get limited episodes (already sorted by pubDate DESC - most recent first)
                val episodes = podcastRepository.getEpisodesForPodcastLimited(podcast.id, maxEpisodesPerPodcast).first()
                    .map { episode ->
                        com.mediadash.android.domain.model.PodcastEpisodeInfo(
                            title = episode.title,
                            duration = episode.duration,
                            publishDate = episode.pubDateFormatted.ifBlank {
                                formatTimestampToDate(episode.pubDate)
                            },
                            pubDate = episode.pubDate  // Include timestamp for proper sorting on client
                        )
                    }

                // Get actual total episode count (not just the limited list)
                val totalEpisodeCount = podcastRepository.getEpisodeCountForPodcast(podcast.id)

                com.mediadash.android.domain.model.PodcastShowInfo(
                    id = podcast.id,
                    title = podcast.title,
                    author = podcast.author,
                    description = podcast.description.take(200), // Truncate
                    imageUrl = podcast.artworkUrl,
                    episodeCount = totalEpisodeCount,  // Use real total count
                    episodes = episodes
                )
            }

            // Check for currently playing episode
            var currentlyPlaying: com.mediadash.android.domain.model.CurrentlyPlayingInfo? = null
            val controller = mediaControllerManager.getActiveController()
            val metadata = controller?.metadata
            if (metadata != null) {
                val showName = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                val episodeTitle = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""

                // Find matching podcast
                val matchingPodcast = podcastShowInfoList.find { podcast ->
                    podcast.title.equals(showName, ignoreCase = true)
                }

                if (matchingPodcast != null) {
                    val episodeIndex = matchingPodcast.episodes.indexOfFirst { episode ->
                        episode.title.equals(episodeTitle, ignoreCase = true)
                    }.coerceAtLeast(0)

                    currentlyPlaying = com.mediadash.android.domain.model.CurrentlyPlayingInfo(
                        podcastId = matchingPodcast.id,
                        episodeTitle = episodeTitle,
                        episodeIndex = episodeIndex
                    )
                }
            }

            Log.d(TAG, "Returning ${podcastShowInfoList.size} podcasts with episodes")

            return com.mediadash.android.domain.model.PodcastInfoResponse(
                podcasts = podcastShowInfoList,
                currentlyPlaying = currentlyPlaying
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching podcasts from repository", e)
            return null
        }
    }

    /**
     * Format a timestamp to a readable date string.
     */
    private fun formatTimestampToDate(timestamp: Long): String {
        if (timestamp == 0L) return ""
        return try {
            val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            dateFormat.format(timestamp)
        } catch (e: Exception) {
            ""
        }
    }

    override suspend fun playPodcastEpisode(podcastId: String, episodeIndex: Int): Boolean {
        Log.d(TAG, "Playing podcast episode: podcastId/hash=$podcastId, episodeIndex=$episodeIndex")

        try {
            // Ensure the player is connected
            podcastPlayerManager.connect()

            // Get the podcast from repository - look up by hash since Go client sends hashes
            val subscribedPodcasts = podcastRepository.getSubscribedPodcasts().first()
            val podcast = subscribedPodcasts.find { generatePodcastHash(it.id) == podcastId }

            if (podcast == null) {
                Log.w(TAG, "Podcast not found for hash: $podcastId")
                return false
            }

            Log.d(TAG, "Found podcast: ${podcast.title} (hash=$podcastId)")

            // Get episodes for this podcast using ORIGINAL ID
            val episodes = podcastRepository.getEpisodesForPodcast(podcast.id).first()

            if (episodeIndex < 0 || episodeIndex >= episodes.size) {
                Log.w(TAG, "Episode index out of bounds: $episodeIndex (total: ${episodes.size})")
                return false
            }

            val episode = episodes[episodeIndex]
            Log.d(TAG, "Found episode: ${episode.title}")

            // Play via PodcastPlayerManager - this automatically starts playback
            // Note: PlaybackSourceTracker is notified via PodcastPlayerManager.onIsPlayingChanged() callback
            podcastPlayerManager.playEpisode(episode, podcast.title)

            Log.i(TAG, "Podcast playback started: ${podcast.title} - ${episode.title}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error playing podcast episode", e)
            return false
        }
    }

    /**
     * Play an episode by its hash.
     * Hash = CRC32(feedUrl + "|" + pubDateSec + "|" + durationSec)
     *
     * Searches all subscribed podcasts for an episode matching the hash.
     */
    suspend fun playEpisodeByHash(episodeHash: String): Boolean {
        Log.d(TAG, "Playing episode by hash: $episodeHash")

        try {
            // Ensure the player is connected
            podcastPlayerManager.connect()

            // Get all subscribed podcasts
            val subscribedPodcasts = podcastRepository.getSubscribedPodcasts().first()

            // Search through all podcasts for the matching episode
            for (podcast in subscribedPodcasts) {
                val feedUrl = podcast.feedUrl ?: continue

                val episodes = podcastRepository.getEpisodesForPodcast(podcast.id).first()

                for (episode in episodes) {
                    val hash = generateEpisodeHash(feedUrl, episode.pubDate, episode.duration)
                    if (hash == episodeHash) {
                        Log.d(TAG, "Found episode: ${episode.title} in podcast: ${podcast.title}")

                        // Play via PodcastPlayerManager
                        // Note: PlaybackSourceTracker is notified via PodcastPlayerManager.onIsPlayingChanged() callback
                        podcastPlayerManager.playEpisode(episode, podcast.title)

                        Log.i(TAG, "Podcast playback started via hash: ${podcast.title} - ${episode.title}")
                        return true
                    }
                }
            }

            Log.w(TAG, "Episode not found for hash: $episodeHash")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error playing episode by hash", e)
            return false
        }
    }

    // ============================================================================
    // New Lazy Loading Methods
    // ============================================================================

    override suspend fun getPodcastList(): PodcastListResponse? {
        try {
            val subscribedPodcasts = podcastRepository.getSubscribedPodcasts().first()

            if (subscribedPodcasts.isEmpty()) {
                Log.w(TAG, "No subscribed podcasts found")
                return null
            }

            Log.d(TAG, "Building podcast list for ${subscribedPodcasts.size} podcasts")

            // Build lightweight podcast channel list (no episodes)
            val podcastChannels = subscribedPodcasts.map { podcast ->
                val episodeCount = podcastRepository.getEpisodeCountForPodcast(podcast.id)
                PodcastChannelInfo(
                    id = podcast.id,
                    title = podcast.title,
                    author = podcast.author,
                    episodeCount = episodeCount
                )
            }.sortedBy { it.title.lowercase() }  // Sort A-Z

            // Check for currently playing
            val currentlyPlaying = getCurrentlyPlayingInfo(podcastChannels.map { it.id })

            Log.d(TAG, "Returning podcast list with ${podcastChannels.size} channels")

            return PodcastListResponse(
                podcasts = podcastChannels,
                currentlyPlaying = currentlyPlaying
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting podcast list", e)
            return null
        }
    }

    override suspend fun getRecentEpisodes(limit: Int): RecentEpisodesResponse? {
        try {
            val subscribedPodcasts = podcastRepository.getSubscribedPodcasts().first()

            if (subscribedPodcasts.isEmpty()) {
                Log.w(TAG, "No subscribed podcasts found")
                return null
            }

            Log.d(TAG, "Building recent episodes list from ${subscribedPodcasts.size} podcasts")

            // Collect all episodes from all podcasts with their podcast context
            val allEpisodes = mutableListOf<RecentEpisodeInfo>()

            for (podcast in subscribedPodcasts) {
                val feedUrl = podcast.feedUrl ?: continue  // Skip podcasts without feedUrl
                val episodes = podcastRepository.getEpisodesForPodcast(podcast.id).first()
                episodes.forEachIndexed { index, episode ->
                    allEpisodes.add(
                        RecentEpisodeInfo(
                            podcastId = podcast.id,
                            podcastTitle = podcast.title,
                            feedUrl = feedUrl,
                            title = episode.title,
                            duration = episode.duration,
                            publishDate = episode.pubDateFormatted.ifBlank {
                                formatTimestampToDate(episode.pubDate)
                            },
                            pubDate = episode.pubDate,
                            episodeIndex = index
                        )
                    )
                }
            }

            // Sort by pubDate descending (most recent first) and take limit
            val recentEpisodes = allEpisodes
                .sortedByDescending { it.pubDate }
                .take(limit)

            Log.d(TAG, "Returning ${recentEpisodes.size} recent episodes (total available: ${allEpisodes.size})")

            return RecentEpisodesResponse(
                episodes = recentEpisodes,
                totalCount = allEpisodes.size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recent episodes", e)
            return null
        }
    }

    override suspend fun getPodcastEpisodes(podcastId: String, offset: Int, limit: Int): PodcastEpisodesResponse? {
        try {
            val subscribedPodcasts = podcastRepository.getSubscribedPodcasts().first()

            // Look up podcast by hash - the podcastId parameter is actually a hash from the Go client
            val podcast = subscribedPodcasts.find { generatePodcastHash(it.id) == podcastId }

            if (podcast == null) {
                Log.w(TAG, "Podcast not found for hash: $podcastId")
                // Log available hashes for debugging
                subscribedPodcasts.take(5).forEach { p ->
                    Log.d(TAG, "  Available: ${p.title} -> hash=${generatePodcastHash(p.id)}")
                }
                return null
            }

            val feedUrl = podcast.feedUrl
            if (feedUrl == null) {
                Log.w(TAG, "Podcast missing feedUrl: ${podcast.title}")
                return null
            }

            Log.d(TAG, "Getting episodes for podcast: ${podcast.title} (hash=$podcastId, offset=$offset, limit=$limit)")

            // Get all episodes for this podcast using the ORIGINAL ID (not hash)
            val allEpisodes = podcastRepository.getEpisodesForPodcast(podcast.id).first()
            val totalEpisodes = allEpisodes.size

            // Apply pagination
            val paginatedEpisodes = allEpisodes
                .drop(offset)
                .take(limit)
                .map { episode ->
                    PodcastEpisodeInfo(
                        title = episode.title,
                        duration = episode.duration,
                        publishDate = episode.pubDateFormatted.ifBlank {
                            formatTimestampToDate(episode.pubDate)
                        },
                        pubDate = episode.pubDate
                    )
                }

            val hasMore = offset + paginatedEpisodes.size < totalEpisodes

            Log.d(TAG, "Returning ${paginatedEpisodes.size} episodes (offset=$offset, hasMore=$hasMore, total=$totalEpisodes)")

            return PodcastEpisodesResponse(
                podcastId = podcastId,
                podcastTitle = podcast.title,
                feedUrl = feedUrl,
                totalEpisodes = totalEpisodes,
                offset = offset,
                hasMore = hasMore,
                episodes = paginatedEpisodes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting podcast episodes", e)
            return null
        }
    }

    /**
     * Helper to get currently playing info if it's a podcast.
     */
    private fun getCurrentlyPlayingInfo(podcastIds: List<String>): com.mediadash.android.domain.model.CurrentlyPlayingInfo? {
        val controller = mediaControllerManager.getActiveController() ?: return null
        val metadata = controller.metadata ?: return null

        val showName = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: return null
        val episodeTitle = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: return null

        // Try to match to a podcast - for now return null if no match
        // The actual matching would require looking up episodes
        return null
    }
}
