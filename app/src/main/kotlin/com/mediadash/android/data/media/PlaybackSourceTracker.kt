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
 * Key behavior:
 * - Play/pause commands target the CURRENT active media source (whatever is actually playing)
 * - If nothing is playing, target the most recently paused source
 * - The only exception is the PodcastPlayerPage play button which explicitly switches to internal podcast
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

    // Track actual playing state for each source
    private var isPodcastPlaying = false
    private var isExternalAppPlaying = false

    /**
     * Called when MediaDash's podcast player starts playing.
     */
    fun onMediaDashPodcastStarted(podcastId: String, episodeId: String, episodeIndex: Int, title: String) {
        Log.i(TAG, "MediaDash podcast started: $title (podcast=$podcastId, episode=$episodeId)")
        isPodcastPlaying = true
        _activeSource.value = PlaybackSource.MEDIADASH_PODCAST
        _pausedContent.value = PausedContentInfo(
            source = PlaybackSource.MEDIADASH_PODCAST,
            podcastId = podcastId,
            episodeId = episodeId,
            episodeIndex = episodeIndex,
            title = title
        )
    }

    /**
     * Called when MediaDash's podcast player state changes.
     */
    fun onMediaDashPodcastPlayingChanged(isPlaying: Boolean) {
        isPodcastPlaying = isPlaying
        Log.d(TAG, "Podcast playing changed: $isPlaying")
        if (isPlaying) {
            _activeSource.value = PlaybackSource.MEDIADASH_PODCAST
        }
    }

    /**
     * Called when an external app (Spotify, etc.) becomes the active media source.
     */
    fun onExternalAppStarted(packageName: String, title: String?) {
        Log.i(TAG, "External app started: $packageName - $title")
        isExternalAppPlaying = true
        _activeSource.value = PlaybackSource.EXTERNAL_APP
        _pausedContent.value = PausedContentInfo(
            source = PlaybackSource.EXTERNAL_APP,
            externalAppPackage = packageName,
            title = title
        )
    }

    /**
     * Called when external app playback state changes.
     */
    fun onExternalAppPlayingChanged(isPlaying: Boolean, packageName: String?, title: String?) {
        isExternalAppPlaying = isPlaying
        Log.d(TAG, "External app playing changed: $isPlaying ($packageName)")
        if (isPlaying) {
            _activeSource.value = PlaybackSource.EXTERNAL_APP
            if (packageName != null) {
                _pausedContent.value = PausedContentInfo(
                    source = PlaybackSource.EXTERNAL_APP,
                    externalAppPackage = packageName,
                    title = title
                )
            }
        }
    }

    /**
     * Called when playback is paused (from any source).
     * Records the position for potential resume.
     * IMPORTANT: Does NOT change the active source - pause just pauses current.
     */
    fun onPaused(positionMs: Long) {
        val current = _pausedContent.value
        Log.i(TAG, "Playback paused at ${positionMs}ms: source=${current.source}, title=${current.title}")
        _pausedContent.value = current.copy(positionMs = positionMs)
        // Update playing states
        if (_activeSource.value == PlaybackSource.MEDIADASH_PODCAST) {
            isPodcastPlaying = false
        } else {
            isExternalAppPlaying = false
        }
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
        isPodcastPlaying = false
        _activeSource.value = PlaybackSource.NONE
    }

    /**
     * Gets the source that is CURRENTLY playing, or was most recently active.
     * This is the source that should receive play/pause commands.
     */
    fun getCurrentActiveSource(): PlaybackSource {
        // If something is actually playing right now, use that
        return when {
            isPodcastPlaying -> PlaybackSource.MEDIADASH_PODCAST
            isExternalAppPlaying -> PlaybackSource.EXTERNAL_APP
            // Nothing is playing - use the last known active source
            else -> _activeSource.value
        }
    }

    /**
     * Returns true if the internal podcast player is currently playing.
     */
    fun isPodcastCurrentlyPlaying(): Boolean = isPodcastPlaying

    /**
     * Returns true if an external app is currently playing.
     */
    fun isExternalAppCurrentlyPlaying(): Boolean = isExternalAppPlaying

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
