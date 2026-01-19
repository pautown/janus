package com.mediadash.android.data.media

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identifies the source of media playback.
 */
enum class PlaybackSource {
    /** No known source / nothing was playing */
    NONE,
    /** MediaDash's internal podcast player (PodcastPlayerManager) */
    MEDIADASH_PODCAST,
    /** External app (Spotify, YouTube Music, etc.) controlled via MediaController */
    EXTERNAL_APP
}

/**
 * Information about the last paused content for resume functionality.
 */
data class PausedContentInfo(
    val source: PlaybackSource = PlaybackSource.NONE,
    /** For MEDIADASH_PODCAST: the podcast ID */
    val podcastId: String? = null,
    /** For MEDIADASH_PODCAST: the episode ID */
    val episodeId: String? = null,
    /** For MEDIADASH_PODCAST: the episode index */
    val episodeIndex: Int = -1,
    /** For EXTERNAL_APP: the package name of the app */
    val externalAppPackage: String? = null,
    /** Position in milliseconds when paused */
    val positionMs: Long = 0L,
    /** Track/episode title for logging */
    val title: String? = null
)

/**
 * Tracks the source of playback to enable proper resume functionality.
 *
 * When the user pauses content (either MediaDash podcast or external app),
 * this tracker remembers what was paused. When a "play" command arrives,
 * we can resume the correct content from the correct source.
 */
@Singleton
class PlaybackSourceTracker @Inject constructor() {

    companion object {
        private const val TAG = "PlaybackSourceTracker"
    }

    private val _activeSource = MutableStateFlow(PlaybackSource.NONE)
    val activeSource: StateFlow<PlaybackSource> = _activeSource.asStateFlow()

    private val _pausedContent = MutableStateFlow(PausedContentInfo())
    val pausedContent: StateFlow<PausedContentInfo> = _pausedContent.asStateFlow()

    /**
     * Called when MediaDash's podcast player starts playing.
     */
    fun onMediaDashPodcastStarted(podcastId: String, episodeId: String, episodeIndex: Int, title: String) {
        Log.i(TAG, "MediaDash podcast started: $title (podcast=$podcastId, episode=$episodeId)")
        _activeSource.value = PlaybackSource.MEDIADASH_PODCAST
        // Clear paused content since we're now playing
        _pausedContent.value = PausedContentInfo(
            source = PlaybackSource.MEDIADASH_PODCAST,
            podcastId = podcastId,
            episodeId = episodeId,
            episodeIndex = episodeIndex,
            title = title
        )
    }

    /**
     * Called when an external app (Spotify, etc.) becomes the active media source.
     */
    fun onExternalAppStarted(packageName: String, title: String?) {
        Log.i(TAG, "External app started: $packageName - $title")
        _activeSource.value = PlaybackSource.EXTERNAL_APP
        _pausedContent.value = PausedContentInfo(
            source = PlaybackSource.EXTERNAL_APP,
            externalAppPackage = packageName,
            title = title
        )
    }

    /**
     * Called when playback is paused (from any source).
     * Records the position for potential resume.
     */
    fun onPaused(positionMs: Long) {
        val current = _pausedContent.value
        Log.i(TAG, "Playback paused at ${positionMs}ms: source=${current.source}, title=${current.title}")
        _pausedContent.value = current.copy(positionMs = positionMs)
    }

    /**
     * Called when playback resumes.
     */
    fun onResumed() {
        Log.i(TAG, "Playback resumed: source=${_activeSource.value}")
    }

    /**
     * Called when playback stops completely.
     */
    fun onStopped() {
        Log.i(TAG, "Playback stopped")
        _activeSource.value = PlaybackSource.NONE
    }

    /**
     * Determines which source should handle a generic "play" command.
     * Returns the source that was last active/paused.
     */
    fun getSourceForPlayCommand(): PlaybackSource {
        val paused = _pausedContent.value
        return if (paused.source != PlaybackSource.NONE) {
            Log.d(TAG, "Play command will target: ${paused.source} (${paused.title})")
            paused.source
        } else {
            Log.d(TAG, "Play command will target: EXTERNAL_APP (default, nothing tracked)")
            PlaybackSource.EXTERNAL_APP // Default to external app controller
        }
    }

    /**
     * Gets the paused MediaDash podcast info if that was the last paused source.
     */
    fun getPausedPodcastInfo(): PausedContentInfo? {
        val paused = _pausedContent.value
        return if (paused.source == PlaybackSource.MEDIADASH_PODCAST && paused.podcastId != null) {
            paused
        } else {
            null
        }
    }

    /**
     * Updates the current track title (used when external apps change tracks).
     */
    fun updateExternalAppTitle(packageName: String, title: String?) {
        val current = _pausedContent.value
        if (current.source == PlaybackSource.EXTERNAL_APP) {
            _pausedContent.value = current.copy(
                externalAppPackage = packageName,
                title = title
            )
        }
    }
}
