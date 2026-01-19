package com.mediadash.android.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediadash.android.data.repository.PodcastRepository
import com.mediadash.android.domain.model.PodcastEpisode
import com.mediadash.android.media.PodcastPlaybackState
import com.mediadash.android.media.PodcastPlayerManager
import com.mediadash.android.media.PlaylistItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PodcastPlayerUiState(
    val playbackState: PodcastPlaybackState = PodcastPlaybackState(),
    val showPlaylist: Boolean = false,
    val showSpeedSelector: Boolean = false,
    val sleepTimerMinutes: Int? = null,
    val sleepTimerRemaining: Long = 0L
)

sealed class PodcastPlayerEvent {
    object PlayPause : PodcastPlayerEvent()
    object SkipForward : PodcastPlayerEvent()
    object SkipBackward : PodcastPlayerEvent()
    object NextTrack : PodcastPlayerEvent()
    object PreviousTrack : PodcastPlayerEvent()
    data class SeekTo(val position: Long) : PodcastPlayerEvent()
    data class SetPlaybackSpeed(val speed: Float) : PodcastPlayerEvent()
    object TogglePlaylist : PodcastPlayerEvent()
    object ToggleSpeedSelector : PodcastPlayerEvent()
    data class PlayFromPlaylist(val index: Int) : PodcastPlayerEvent()
    data class RemoveFromPlaylist(val index: Int) : PodcastPlayerEvent()
    data class SetSleepTimer(val minutes: Int?) : PodcastPlayerEvent()
    data class PlayEpisode(val episode: PodcastEpisode, val podcastTitle: String) : PodcastPlayerEvent()
    data class PlayPlaylist(val episodes: List<PodcastEpisode>, val podcastTitle: String, val startIndex: Int) : PodcastPlayerEvent()
    data class AddToQueue(val episode: PodcastEpisode, val podcastTitle: String) : PodcastPlayerEvent()
}

@HiltViewModel
class PodcastPlayerViewModel @Inject constructor(
    private val playerManager: PodcastPlayerManager,
    private val podcastRepository: PodcastRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PodcastPlayerUiState())
    val uiState: StateFlow<PodcastPlayerUiState> = _uiState.asStateFlow()

    private var positionUpdateJob: kotlinx.coroutines.Job? = null
    private var sleepTimerJob: kotlinx.coroutines.Job? = null

    init {
        // Connect to the player service
        playerManager.connect()

        // Observe playback state
        viewModelScope.launch {
            playerManager.playbackState.collect { playbackState ->
                _uiState.update { it.copy(playbackState = playbackState) }
            }
        }

        // Start position updates
        startPositionUpdates()
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (true) {
                delay(500) // Update every 500ms
                playerManager.updatePosition()
            }
        }
    }

    fun onEvent(event: PodcastPlayerEvent) {
        when (event) {
            is PodcastPlayerEvent.PlayPause -> {
                playerManager.playPause()
            }
            is PodcastPlayerEvent.SkipForward -> {
                playerManager.seekForward()
            }
            is PodcastPlayerEvent.SkipBackward -> {
                playerManager.seekBackward()
            }
            is PodcastPlayerEvent.NextTrack -> {
                playerManager.skipToNext()
            }
            is PodcastPlayerEvent.PreviousTrack -> {
                playerManager.skipToPrevious()
            }
            is PodcastPlayerEvent.SeekTo -> {
                playerManager.seekTo(event.position)
            }
            is PodcastPlayerEvent.SetPlaybackSpeed -> {
                playerManager.setPlaybackSpeed(event.speed)
                _uiState.update { it.copy(showSpeedSelector = false) }
            }
            is PodcastPlayerEvent.TogglePlaylist -> {
                _uiState.update { it.copy(showPlaylist = !it.showPlaylist) }
            }
            is PodcastPlayerEvent.ToggleSpeedSelector -> {
                _uiState.update { it.copy(showSpeedSelector = !it.showSpeedSelector) }
            }
            is PodcastPlayerEvent.PlayFromPlaylist -> {
                playerManager.skipToPlaylistItem(event.index)
                _uiState.update { it.copy(showPlaylist = false) }
            }
            is PodcastPlayerEvent.RemoveFromPlaylist -> {
                playerManager.removeFromPlaylist(event.index)
            }
            is PodcastPlayerEvent.SetSleepTimer -> {
                setSleepTimer(event.minutes)
            }
            is PodcastPlayerEvent.PlayEpisode -> {
                playerManager.playEpisode(event.episode, event.podcastTitle)
                markEpisodeAsPlayed(event.episode.id)
            }
            is PodcastPlayerEvent.PlayPlaylist -> {
                playerManager.playPlaylist(event.episodes, event.podcastTitle, event.startIndex)
            }
            is PodcastPlayerEvent.AddToQueue -> {
                playerManager.addToPlaylist(event.episode, event.podcastTitle)
            }
        }
    }

    private fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()

        if (minutes == null) {
            _uiState.update { it.copy(sleepTimerMinutes = null, sleepTimerRemaining = 0L) }
            return
        }

        val endTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
        _uiState.update { it.copy(sleepTimerMinutes = minutes) }

        sleepTimerJob = viewModelScope.launch {
            while (true) {
                val remaining = endTime - System.currentTimeMillis()
                if (remaining <= 0) {
                    playerManager.pause()
                    _uiState.update { it.copy(sleepTimerMinutes = null, sleepTimerRemaining = 0L) }
                    break
                }
                _uiState.update { it.copy(sleepTimerRemaining = remaining) }
                delay(1000)
            }
        }
    }

    private fun markEpisodeAsPlayed(episodeId: String) {
        viewModelScope.launch {
            podcastRepository.markEpisodeAsPlayed(episodeId, true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        positionUpdateJob?.cancel()
        sleepTimerJob?.cancel()
        playerManager.disconnect()
    }

    companion object {
        val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    }
}
