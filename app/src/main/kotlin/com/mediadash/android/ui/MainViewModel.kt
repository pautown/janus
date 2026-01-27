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
import com.mediadash.android.data.spotify.SpotifyPlaybackController
import com.mediadash.android.domain.model.LyricsLine
import com.mediadash.android.domain.model.LyricsState
import com.mediadash.android.domain.model.ConnectionStatus
import com.mediadash.android.domain.model.MediaState
import com.mediadash.android.media.PodcastPlayerManager
import com.spotsdk.models.Album
import com.spotsdk.models.Artist
import com.spotsdk.models.Track
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
    val currentLyricsLineIndex: Int = -1,
    val showAlbumDetail: Boolean = false,
    val showArtistDetail: Boolean = false,
    val albumDetail: Album? = null,
    val artistDetail: Artist? = null,
    val artistTopTracks: List<Track>? = null,
    val isLoadingDetail: Boolean = false
)

/**
 * UI events from the main screen.
 * Note: Playback controls (PlayPause, Next, Previous, etc.) are handled by
 * PodcastPlayerViewModel on the PodcastPlayerPage (page 3).
 */
sealed class MainUiEvent {
    data object ToggleService : MainUiEvent()
    data object OpenNotificationSettings : MainUiEvent()
    data object EnableBluetooth : MainUiEvent()
    data class ToggleLyrics(val enabled: Boolean) : MainUiEvent()
    data class ViewAlbum(val albumId: String) : MainUiEvent()
    data class ViewArtist(val artistId: String) : MainUiEvent()
    data object DismissAlbumDetail : MainUiEvent()
    data object DismissArtistDetail : MainUiEvent()
}

/**
 * Internal helper to combine detail-related state flows.
 */
private data class DetailState(
    val showAlbum: Boolean,
    val showArtist: Boolean,
    val album: Album?,
    val artist: Artist?,
    val topTracks: List<Track>?,
    val isLoading: Boolean
)

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
    private val lyricsManager: LyricsManager,
    private val spotifyPlaybackController: SpotifyPlaybackController
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

    // Album/Artist detail state
    private val _showAlbumDetail = MutableStateFlow(false)
    private val _showArtistDetail = MutableStateFlow(false)
    private val _albumDetail = MutableStateFlow<Album?>(null)
    private val _artistDetail = MutableStateFlow<Artist?>(null)
    private val _artistTopTracks = MutableStateFlow<List<Track>?>(null)
    private val _isLoadingDetail = MutableStateFlow(false)

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
    }.combine(
        combine(
            _showAlbumDetail,
            _showArtistDetail,
            _albumDetail,
            _artistDetail,
            _artistTopTracks
        ) { showAlbum, showArtist, album, artist, topTracks ->
            DetailState(showAlbum, showArtist, album, artist, topTracks, false)
        }.combine(_isLoadingDetail) { detail, loading ->
            detail.copy(isLoading = loading)
        }
    ) { mainState, detailState ->
        mainState.copy(
            showAlbumDetail = detailState.showAlbum,
            showArtistDetail = detailState.showArtist,
            albumDetail = detailState.album,
            artistDetail = detailState.artist,
            artistTopTracks = detailState.topTracks,
            isLoadingDetail = detailState.isLoading
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
     * Handles UI events.
     */
    fun onEvent(event: MainUiEvent) {
        when (event) {
            is MainUiEvent.ToggleService -> toggleService()
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
            is MainUiEvent.ViewAlbum -> fetchAlbumDetail(event.albumId)
            is MainUiEvent.ViewArtist -> fetchArtistDetail(event.artistId)
            is MainUiEvent.DismissAlbumDetail -> {
                _showAlbumDetail.value = false
                _albumDetail.value = null
            }
            is MainUiEvent.DismissArtistDetail -> {
                _showArtistDetail.value = false
                _artistDetail.value = null
                _artistTopTracks.value = null
            }
        }
    }

    private fun fetchAlbumDetail(albumId: String) {
        viewModelScope.launch {
            _isLoadingDetail.value = true
            _showAlbumDetail.value = true
            val album = spotifyPlaybackController.fetchAlbumDetails(albumId)
            _albumDetail.value = album
            _isLoadingDetail.value = false
        }
    }

    private fun fetchArtistDetail(artistId: String) {
        viewModelScope.launch {
            _isLoadingDetail.value = true
            _showArtistDetail.value = true
            val result = spotifyPlaybackController.fetchArtistDetails(artistId)
            if (result != null) {
                _artistDetail.value = result.first
                _artistTopTracks.value = result.second
            }
            _isLoadingDetail.value = false
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
}
