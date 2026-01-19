package com.mediadash.android.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediadash.android.ble.GattServerManager
import com.mediadash.android.ble.GattServerService
import com.mediadash.android.data.local.SettingsManager
import com.mediadash.android.data.media.LyricsManager
import com.mediadash.android.data.media.MediaSessionListener
import com.mediadash.android.data.repository.MediaRepository
import com.mediadash.android.domain.model.LyricsLine
import com.mediadash.android.domain.model.LyricsState
import com.mediadash.android.domain.model.ConnectionStatus
import com.mediadash.android.domain.model.MediaState
import com.mediadash.android.domain.model.PlaybackCommand
import com.mediadash.android.media.PodcastPlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Source of currently playing media.
 */
enum class MediaSource {
    INTERNAL_PODCAST,   // From app's internal podcast player
    EXTERNAL_APP        // From another app like Spotify, YouTube Music, etc.
}

/**
 * UI state for the main screen.
 */
data class MainUiState(
    val mediaState: MediaState? = null,
    val albumArtBitmap: Bitmap? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val isServiceRunning: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val albumArtRequestActive: Boolean = false,
    val lastAlbumArtRequestHash: String? = null,
    val mediaSource: MediaSource? = null,
    val lyricsEnabled: Boolean = false,
    val currentLyrics: LyricsState? = null,
    val currentLyricsLineIndex: Int = -1
)

/**
 * UI events from the main screen.
 */
sealed class MainUiEvent {
    data object ToggleService : MainUiEvent()
    data object PlayPause : MainUiEvent()
    data object Next : MainUiEvent()
    data object Previous : MainUiEvent()
    data class SeekTo(val positionMs: Long) : MainUiEvent()
    data object SkipForward30 : MainUiEvent()
    data object SkipBack30 : MainUiEvent()
    data object OpenNotificationSettings : MainUiEvent()
    data object EnableBluetooth : MainUiEvent()
    data class ToggleLyrics(val enabled: Boolean) : MainUiEvent()
}

/**
 * ViewModel for the main screen.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaRepository: MediaRepository,
    private val gattServerManager: GattServerManager,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val podcastPlayerManager: PodcastPlayerManager,
    private val settingsManager: SettingsManager,
    private val lyricsManager: LyricsManager
) : ViewModel() {

    private val _hasNotificationPermission = MutableStateFlow(
        MediaSessionListener.isPermissionGranted(context)
    )

    private val _isBluetoothEnabled = MutableStateFlow(
        bluetoothAdapter?.isEnabled == true
    )

    private val _openBluetoothSettings = MutableStateFlow(false)
    val openBluetoothSettings: StateFlow<Boolean> = _openBluetoothSettings

    // Track the last fetched track for lyrics auto-fetch
    private var lastLyricsTrackKey: String? = null

    // Position update timer for live progress tracking
    private var positionUpdateJob: Job? = null
    private val _positionTick = MutableStateFlow(0L)

    // Track external app position for live estimation
    private var lastExternalPosition: Long = 0L
    private var lastExternalPositionTime: Long = 0L
    private var lastExternalTrackKey: String? = null

    init {
        // Start/stop position updates based on playback state
        viewModelScope.launch {
            combine(
                mediaRepository.currentMediaState,
                podcastPlayerManager.playbackState
            ) { externalState, podcastState ->
                podcastState.isPlaying || externalState?.isPlaying == true
            }.collect { isPlaying ->
                if (isPlaying) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                }
            }
        }
    }

    private fun startPositionUpdates() {
        if (positionUpdateJob?.isActive == true) return
        positionUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                // Update podcast player position
                podcastPlayerManager.updatePosition()
                // Increment tick to trigger UI update for external apps
                _positionTick.value = System.currentTimeMillis()
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    val uiState: StateFlow<MainUiState> = combine(
        mediaRepository.currentMediaState,
        mediaRepository.currentAlbumArtBitmap,
        gattServerManager.connectionStatus,
        gattServerManager.isRunning,
        _hasNotificationPermission,
        _isBluetoothEnabled,
        gattServerManager.albumArtRequestActive,
        gattServerManager.lastAlbumArtRequestHash,
        podcastPlayerManager.playbackState,
        settingsManager.lyricsEnabled,
        lyricsManager.currentLyrics,
        _positionTick
    ) { flows ->
        val externalMediaState = flows[0] as? MediaState
        val podcastPlaybackState = flows[8] as com.mediadash.android.media.PodcastPlaybackState
        val lyricsEnabled = flows[9] as Boolean
        val currentLyrics = flows[10] as? LyricsState
        val positionTick = flows[11] as Long

        // Determine if internal podcast player has an episode loaded
        val hasInternalEpisode = podcastPlaybackState.currentEpisodeId != null &&
                podcastPlaybackState.currentEpisodeTitle.isNotBlank()

        // Internal player is active if it has an episode and is playing,
        // OR if it has an episode loaded and no external media is playing
        val useInternalState = hasInternalEpisode && (
                podcastPlaybackState.isPlaying ||
                externalMediaState?.isPlaying != true
        )

        // Determine media source
        val mediaSource = when {
            podcastPlaybackState.isPlaying && hasInternalEpisode -> MediaSource.INTERNAL_PODCAST
            externalMediaState?.isPlaying == true -> MediaSource.EXTERNAL_APP
            hasInternalEpisode -> MediaSource.INTERNAL_PODCAST  // Paused internal podcast
            else -> null
        }

        // Calculate estimated position for external apps
        val estimatedExternalPosition = if (externalMediaState != null) {
            val trackKey = "${externalMediaState.artist}|${externalMediaState.trackTitle}"
            // Reset tracking when track changes or position jumps (seek)
            if (trackKey != lastExternalTrackKey ||
                kotlin.math.abs(externalMediaState.position - lastExternalPosition) > 2000) {
                lastExternalTrackKey = trackKey
                lastExternalPosition = externalMediaState.position
                lastExternalPositionTime = System.currentTimeMillis()
            }
            // Estimate current position based on elapsed time while playing
            if (externalMediaState.isPlaying && positionTick > 0) {
                val elapsed = System.currentTimeMillis() - lastExternalPositionTime
                (lastExternalPosition + elapsed).coerceAtMost(externalMediaState.duration)
            } else {
                // When paused, update tracking to current position
                lastExternalPosition = externalMediaState.position
                lastExternalPositionTime = System.currentTimeMillis()
                externalMediaState.position
            }
        } else 0L

        // Use internal podcast player state when appropriate
        // This ensures accurate position/duration tracking for internal podcasts
        val displayMediaState = if (useInternalState) {
            MediaState(
                isPlaying = podcastPlaybackState.isPlaying,
                playbackState = if (podcastPlaybackState.isPlaying) "playing" else "paused",
                trackTitle = podcastPlaybackState.currentEpisodeTitle,
                artist = podcastPlaybackState.currentPodcastTitle,
                album = "",
                duration = podcastPlaybackState.duration,
                position = podcastPlaybackState.position,
                volume = externalMediaState?.volume ?: 100,
                albumArtHash = null
            )
        } else if (externalMediaState != null) {
            // Use estimated position for external apps
            externalMediaState.copy(position = estimatedExternalPosition)
        } else {
            null
        }

        // Calculate current lyrics line index based on playback position
        val currentLineIndex = if (currentLyrics != null && currentLyrics.synced && displayMediaState != null) {
            findCurrentLyricsLine(displayMediaState.position, currentLyrics.lines)
        } else {
            -1
        }

        // Trigger lyrics fetch if track changed and lyrics are enabled
        if (lyricsEnabled && displayMediaState != null) {
            val trackKey = "${displayMediaState.artist}|${displayMediaState.trackTitle}"
            if (trackKey != lastLyricsTrackKey && displayMediaState.trackTitle.isNotBlank()) {
                Log.d("LYRICS", "🎵 VIEWMODEL - Track changed, triggering lyrics fetch")
                Log.d("LYRICS", "   Previous: $lastLyricsTrackKey")
                Log.d("LYRICS", "   New: $trackKey")
                lastLyricsTrackKey = trackKey
                fetchLyricsForTrack(displayMediaState.artist, displayMediaState.trackTitle)
            }
        }

        MainUiState(
            mediaState = displayMediaState,
            albumArtBitmap = flows[1] as? Bitmap,
            connectionStatus = flows[2] as ConnectionStatus,
            isServiceRunning = flows[3] as Boolean,
            hasNotificationPermission = flows[4] as Boolean,
            isBluetoothEnabled = flows[5] as Boolean,
            albumArtRequestActive = flows[6] as Boolean,
            lastAlbumArtRequestHash = flows[7] as? String,
            mediaSource = mediaSource,
            lyricsEnabled = lyricsEnabled,
            currentLyrics = currentLyrics,
            currentLyricsLineIndex = currentLineIndex
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    /**
     * Finds the current lyrics line based on playback position.
     */
    private fun findCurrentLyricsLine(positionMs: Long, lines: List<LyricsLine>): Int {
        if (lines.isEmpty()) return -1

        // Binary search for the current line
        var left = 0
        var right = lines.size - 1
        var result = -1

        while (left <= right) {
            val mid = (left + right) / 2
            if (lines[mid].t <= positionMs) {
                result = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }

        return result
    }

    /**
     * Fetches lyrics for the given track.
     */
    private fun fetchLyricsForTrack(artist: String, track: String) {
        Log.d("LYRICS", "🎵 VIEWMODEL - fetchLyricsForTrack called")
        Log.d("LYRICS", "   Artist: $artist")
        Log.d("LYRICS", "   Track: $track")
        viewModelScope.launch {
            val result = lyricsManager.fetchLyrics(artist, track)
            if (result != null) {
                Log.d("LYRICS", "   ✅ Lyrics loaded: ${result.lines.size} lines")
            } else {
                Log.d("LYRICS", "   ⚠️ No lyrics returned")
            }
        }
    }

    /**
     * Check if internal podcast player has an episode loaded (playing or paused).
     */
    private val hasInternalEpisodeLoaded: Boolean
        get() = podcastPlayerManager.playbackState.value.let {
            it.currentEpisodeId != null && it.currentEpisodeTitle.isNotBlank()
        }

    /**
     * Handles UI events.
     */
    fun onEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.ToggleService -> toggleService()
            is MainUiEvent.PlayPause -> handlePlayPause()
            is MainUiEvent.Next -> handleNext()
            is MainUiEvent.Previous -> handlePrevious()
            is MainUiEvent.SeekTo -> handleSeekTo(event.positionMs)
            is MainUiEvent.SkipForward30 -> handleSkipRelative(30_000L)
            is MainUiEvent.SkipBack30 -> handleSkipRelative(-30_000L)
            is MainUiEvent.OpenNotificationSettings -> {
                // This will be handled by the UI layer
            }
            is MainUiEvent.EnableBluetooth -> {
                _openBluetoothSettings.value = true
            }
            is MainUiEvent.ToggleLyrics -> {
                Log.d("LYRICS", "🎵 VIEWMODEL - ToggleLyrics event: ${event.enabled}")
                settingsManager.setLyricsEnabled(event.enabled)
            }
        }
    }

    private fun handlePlayPause() {
        if (hasInternalEpisodeLoaded) {
            // Route to internal podcast player
            podcastPlayerManager.playPause()
        } else {
            // Route to external media
            sendCommand(PlaybackCommand.ACTION_TOGGLE)
        }
    }

    private fun handleNext() {
        if (hasInternalEpisodeLoaded) {
            podcastPlayerManager.skipToNext()
        } else {
            sendCommand(PlaybackCommand.ACTION_NEXT)
        }
    }

    private fun handlePrevious() {
        if (hasInternalEpisodeLoaded) {
            podcastPlayerManager.skipToPrevious()
        } else {
            sendCommand(PlaybackCommand.ACTION_PREVIOUS)
        }
    }

    private fun handleSeekTo(positionMs: Long) {
        if (hasInternalEpisodeLoaded) {
            podcastPlayerManager.seekTo(positionMs)
        } else {
            sendSeekCommand(positionMs)
        }
    }

    private fun handleSkipRelative(deltaMs: Long) {
        if (hasInternalEpisodeLoaded) {
            if (deltaMs > 0) {
                podcastPlayerManager.seekForward(deltaMs)
            } else {
                podcastPlayerManager.seekBackward(-deltaMs)
            }
        } else {
            skipRelative(deltaMs)
        }
    }

    /**
     * Refreshes the Bluetooth and notification permission status.
     */
    fun refreshPermissionStatus() {
        _hasNotificationPermission.value = MediaSessionListener.isPermissionGranted(context)
        _isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
    }

    /**
     * Called when Bluetooth settings intent has been launched.
     */
    fun onBluetoothSettingsOpened() {
        _openBluetoothSettings.value = false
    }

    private fun toggleService() {
        viewModelScope.launch {
            if (gattServerManager.isRunning.value) {
                context.stopService(Intent(context, GattServerService::class.java))
            } else {
                val intent = Intent(context, GattServerService::class.java)
                context.startForegroundService(intent)
            }
        }
    }

    private fun sendCommand(action: String) {
        viewModelScope.launch {
            mediaRepository.processCommand(PlaybackCommand(action = action))
        }
    }

    private fun sendSeekCommand(positionMs: Long) {
        viewModelScope.launch {
            mediaRepository.processCommand(
                PlaybackCommand(
                    action = PlaybackCommand.ACTION_SEEK,
                    value = positionMs.coerceAtLeast(0)
                )
            )
        }
    }

    private fun skipRelative(deltaMs: Long) {
        val currentPosition = uiState.value.mediaState?.position ?: 0L
        val duration = uiState.value.mediaState?.duration ?: 0L
        val newPosition = (currentPosition + deltaMs).coerceIn(0L, duration)
        sendSeekCommand(newPosition)
    }
}
