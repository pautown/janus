package com.mediadash.android.data.repository

import android.graphics.Bitmap
import com.mediadash.android.domain.model.AlbumArtChunk
import com.mediadash.android.domain.model.MediaState
import com.mediadash.android.domain.model.PlaybackCommand
import com.mediadash.android.domain.model.PodcastListResponse
import com.mediadash.android.domain.model.RecentEpisodesResponse
import com.mediadash.android.domain.model.PodcastEpisodesResponse
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for media playback state.
 */
interface MediaRepository {
    /**
     * Current media playback state.
     */
    val currentMediaState: StateFlow<MediaState?>

    /**
     * Current album art chunks ready for transmission.
     */
    val currentAlbumArtChunks: StateFlow<List<AlbumArtChunk>?>

    /**
     * Current album art bitmap for UI display.
     */
    val currentAlbumArtBitmap: StateFlow<Bitmap?>

    /**
     * Processes a playback command from the Go client.
     */
    suspend fun processCommand(command: PlaybackCommand)

    /**
     * Gets podcast information from the current media metadata.
     * Returns null if the current media is not a podcast or metadata is unavailable.
     * @deprecated Use getPodcastList() + getRecentEpisodes() + getPodcastEpisodes() instead
     */
    suspend fun getPodcastInfo(): com.mediadash.android.domain.model.PodcastInfoResponse?

    /**
     * Gets list of all subscribed podcast channels (names only, no episodes).
     * Used for A-Z podcast list view.
     */
    suspend fun getPodcastList(): PodcastListResponse?

    /**
     * Gets recent episodes across all subscribed podcasts.
     * @param limit Maximum number of recent episodes to return
     */
    suspend fun getRecentEpisodes(limit: Int = 30): RecentEpisodesResponse?

    /**
     * Gets episodes for a specific podcast with pagination.
     * @param podcastId The podcast ID
     * @param offset Starting episode index (0-based)
     * @param limit Maximum episodes to return
     */
    suspend fun getPodcastEpisodes(podcastId: String, offset: Int = 0, limit: Int = 15): PodcastEpisodesResponse?

    /**
     * Plays a specific podcast episode.
     * @param podcastId The podcast ID
     * @param episodeIndex The 0-based episode index within the podcast's episode list
     * @return true if playback was initiated successfully
     */
    suspend fun playPodcastEpisode(podcastId: String, episodeIndex: Int): Boolean
}
