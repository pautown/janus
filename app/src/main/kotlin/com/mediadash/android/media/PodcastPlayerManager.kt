package com.mediadash.android.media

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.mediadash.android.data.media.MediaControllerManager
import com.mediadash.android.data.media.PlaybackSource
import com.mediadash.android.data.media.PlaybackSourceTracker
import com.mediadash.android.domain.model.PodcastEpisode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackSourceTracker: PlaybackSourceTracker,
    private val mediaControllerManager: MediaControllerManager
) {
    companion object {
        private const val TAG = "PodcastAudio"
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val _playbackState = MutableStateFlow(PodcastPlaybackState())
    val playbackState: StateFlow<PodcastPlaybackState> = _playbackState.asStateFlow()

    private val _playlist = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val playlist: StateFlow<List<PlaylistItem>> = _playlist.asStateFlow()

    private var isConnected = false

    fun connect() {
        if (isConnected) return

        val sessionToken = SessionToken(context, ComponentName(context, PodcastPlayerService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(playerListener)
                isConnected = true
                updatePlaybackState()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        isConnected = false
    }

    /**
     * Returns the URI to use for playback - prefers local file if downloaded and exists,
     * otherwise falls back to streaming URL.
     */
    private fun getPlaybackUri(episode: PodcastEpisode): String {
        Log.d(TAG, "┌─ getPlaybackUri() for episode: ${episode.id}")
        Log.d(TAG, "│  Title: ${episode.title}")
        Log.d(TAG, "│  isDownloaded: ${episode.isDownloaded}")
        Log.d(TAG, "│  localFilePath: ${episode.localFilePath ?: "null"}")
        Log.d(TAG, "│  audioUrl: ${episode.audioUrl}")

        if (episode.isDownloaded && !episode.localFilePath.isNullOrEmpty()) {
            val file = File(episode.localFilePath)
            val fileExists = file.exists()
            Log.d(TAG, "│  Local file exists: $fileExists")
            if (fileExists) {
                val localUri = android.net.Uri.fromFile(file).toString()
                Log.d(TAG, "└─ USING LOCAL FILE: $localUri")
                return localUri
            } else {
                Log.w(TAG, "│  WARNING: File marked as downloaded but not found on disk!")
            }
        }
        Log.d(TAG, "└─ USING STREAMING URL: ${episode.audioUrl}")
        return episode.audioUrl
    }

    fun playEpisode(episode: PodcastEpisode, podcastTitle: String) {
        Log.i(TAG, "══════════════════════════════════════════════════════════")
        Log.i(TAG, "▶ playEpisode() called")
        Log.i(TAG, "  Podcast: $podcastTitle")
        Log.i(TAG, "  Episode: ${episode.title}")
        Log.i(TAG, "  Episode ID: ${episode.id}")
        Log.i(TAG, "  Duration: ${episode.duration}ms")

        val playbackUri = getPlaybackUri(episode)

        val mediaItem = MediaItem.Builder()
            .setUri(playbackUri)
            .setMediaId(episode.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(podcastTitle)
                    .setArtworkUri(episode.artworkUrl?.let { android.net.Uri.parse(it) })
                    .build()
            )
            .build()

        if (mediaController == null) {
            Log.e(TAG, "  ERROR: mediaController is null! Cannot play.")
        } else {
            Log.d(TAG, "  Setting media item and starting playback...")
        }

        mediaController?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
            Log.i(TAG, "  ✓ Playback started")
        }

        _playbackState.update {
            it.copy(
                currentEpisodeId = episode.id,
                currentEpisodeTitle = episode.title,
                currentPodcastTitle = podcastTitle,
                artworkUrl = episode.artworkUrl,
                duration = episode.duration
            )
        }
    }

    fun playPlaylist(episodes: List<PodcastEpisode>, podcastTitle: String, startIndex: Int = 0) {
        Log.i(TAG, "══════════════════════════════════════════════════════════")
        Log.i(TAG, "▶ playPlaylist() called")
        Log.i(TAG, "  Podcast: $podcastTitle")
        Log.i(TAG, "  Episode count: ${episodes.size}")
        Log.i(TAG, "  Start index: $startIndex")

        episodes.forEachIndexed { index, episode ->
            val isDownloaded = episode.isDownloaded
            val source = if (isDownloaded) "LOCAL" else "STREAM"
            Log.d(TAG, "  [$index] ${episode.title} [$source]")
        }

        val mediaItems = episodes.map { episode ->
            MediaItem.Builder()
                .setUri(getPlaybackUri(episode))
                .setMediaId(episode.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setArtist(podcastTitle)
                        .setArtworkUri(episode.artworkUrl?.let { android.net.Uri.parse(it) })
                        .build()
                )
                .build()
        }

        val playlistItems = episodes.map { episode ->
            PlaylistItem(
                episodeId = episode.id,
                episodeTitle = episode.title,
                podcastTitle = podcastTitle,
                audioUrl = episode.audioUrl,
                artworkUrl = episode.artworkUrl,
                duration = episode.duration
            )
        }

        _playlist.value = playlistItems

        if (mediaController == null) {
            Log.e(TAG, "  ERROR: mediaController is null! Cannot play playlist.")
        }

        mediaController?.apply {
            setMediaItems(mediaItems, startIndex, 0L)
            prepare()
            play()
            Log.i(TAG, "  ✓ Playlist playback started at index $startIndex")
        }

        _playbackState.update {
            it.copy(
                playlist = playlistItems,
                currentIndex = startIndex
            )
        }
    }

    fun addToPlaylist(episode: PodcastEpisode, podcastTitle: String) {
        Log.i(TAG, "══════════════════════════════════════════════════════════")
        Log.i(TAG, "▶ addToPlaylist() called")
        Log.i(TAG, "  Podcast: $podcastTitle")
        Log.i(TAG, "  Episode: ${episode.title}")
        Log.i(TAG, "  Episode ID: ${episode.id}")
        Log.i(TAG, "  isDownloaded: ${episode.isDownloaded}")

        val mediaItem = MediaItem.Builder()
            .setUri(getPlaybackUri(episode))
            .setMediaId(episode.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(podcastTitle)
                    .setArtworkUri(episode.artworkUrl?.let { android.net.Uri.parse(it) })
                    .build()
            )
            .build()

        mediaController?.addMediaItem(mediaItem)
        Log.i(TAG, "  ✓ Added to playlist (new size: ${_playlist.value.size + 1})")

        val playlistItem = PlaylistItem(
            episodeId = episode.id,
            episodeTitle = episode.title,
            podcastTitle = podcastTitle,
            audioUrl = episode.audioUrl,
            artworkUrl = episode.artworkUrl,
            duration = episode.duration
        )

        _playlist.update { it + playlistItem }
        _playbackState.update { it.copy(playlist = _playlist.value) }
    }

    fun removeFromPlaylist(index: Int) {
        mediaController?.removeMediaItem(index)
        _playlist.update { list ->
            list.toMutableList().apply { removeAt(index) }
        }
        _playbackState.update { it.copy(playlist = _playlist.value) }
    }

    fun play() {
        mediaController?.play()
    }

    fun pause() {
        mediaController?.pause()
    }

    fun playPause() {
        mediaController?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    fun seekForward(ms: Long = 30_000) {
        mediaController?.let {
            val newPosition = (it.currentPosition + ms).coerceAtMost(it.duration)
            it.seekTo(newPosition)
        }
    }

    fun seekBackward(ms: Long = 15_000) {
        mediaController?.let {
            val newPosition = (it.currentPosition - ms).coerceAtLeast(0)
            it.seekTo(newPosition)
        }
    }

    fun skipToNext() {
        mediaController?.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        mediaController?.seekToPreviousMediaItem()
    }

    fun setPlaybackSpeed(speed: Float) {
        mediaController?.setPlaybackSpeed(speed)
        _playbackState.update { it.copy(playbackSpeed = speed) }
    }

    fun skipToPlaylistItem(index: Int) {
        mediaController?.seekTo(index, 0L)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "◆ onIsPlayingChanged: isPlaying=$isPlaying")
            updatePlaybackState()

            // Notify PlaybackSourceTracker about podcast playback state changes
            // This handles both BLE-initiated and direct app UI playback
            val state = _playbackState.value
            if (isPlaying) {
                // Podcast started playing - track it as the active source
                val podcastId = state.currentPodcastTitle ?: ""
                val episodeId = state.currentEpisodeId ?: ""
                val title = "${state.currentPodcastTitle} - ${state.currentEpisodeTitle}"
                Log.d(TAG, "Notifying tracker: podcast started - $title")
                playbackSourceTracker.onMediaDashPodcastStarted(
                    podcastId = podcastId,
                    episodeId = episodeId,
                    episodeIndex = state.currentIndex,
                    title = title
                )
            } else {
                // Podcast paused - track the position
                val position = mediaController?.currentPosition ?: state.position
                Log.d(TAG, "Notifying tracker: podcast paused at ${position}ms")
                playbackSourceTracker.onPaused(position)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.d(TAG, "◆ onPlaybackStateChanged: $stateName")
            updatePlaybackState()

            // Track when playback ends or stops
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                Log.d(TAG, "Notifying tracker: podcast stopped/ended")
                playbackSourceTracker.onStopped()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val reasonName = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                else -> "UNKNOWN($reason)"
            }
            Log.i(TAG, "◆ onMediaItemTransition: ${mediaItem?.mediaMetadata?.title} (reason: $reasonName)")
            updatePlaybackState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            Log.d(TAG, "◆ onPositionDiscontinuity: ${oldPosition.positionMs}ms -> ${newPosition.positionMs}ms")
            updatePlaybackState()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "◆ onPlayerError: ${error.errorCodeName}")
            Log.e(TAG, "  Message: ${error.message}")
            Log.e(TAG, "  Cause: ${error.cause?.message ?: "none"}")
        }
    }

    private fun updatePlaybackState() {
        mediaController?.let { controller ->
            val currentMediaItem = controller.currentMediaItem
            val isPlaying = controller.isPlaying
            val episodeTitle = currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
            val podcastTitle = currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
            val artworkUrl = currentMediaItem?.mediaMetadata?.artworkUri?.toString()
            val duration = controller.duration.coerceAtLeast(0)
            val position = controller.currentPosition.coerceAtLeast(0)

            _playbackState.update {
                it.copy(
                    isPlaying = isPlaying,
                    currentEpisodeId = currentMediaItem?.mediaId,
                    currentEpisodeTitle = episodeTitle,
                    currentPodcastTitle = podcastTitle,
                    artworkUrl = artworkUrl,
                    duration = duration,
                    position = position,
                    playbackSpeed = controller.playbackParameters.speed,
                    currentIndex = controller.currentMediaItemIndex
                )
            }

            // Share podcast state via BLE (same as external apps like Spotify)
            // This allows the golang client to receive podcast metadata and album art
            if (episodeTitle.isNotEmpty() || podcastTitle.isNotEmpty()) {
                mediaControllerManager.setPodcastState(
                    isPlaying = isPlaying,
                    episodeTitle = episodeTitle,
                    podcastTitle = podcastTitle,
                    artworkUrl = artworkUrl,
                    duration = duration,
                    position = position
                )
            }
        }
    }

    fun updatePosition() {
        mediaController?.let { controller ->
            _playbackState.update {
                it.copy(position = controller.currentPosition.coerceAtLeast(0))
            }
        }
    }
}
