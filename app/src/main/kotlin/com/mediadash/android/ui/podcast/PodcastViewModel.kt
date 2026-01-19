package com.mediadash.android.ui.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediadash.android.data.repository.PodcastRepository
import com.mediadash.android.domain.model.DownloadState
import com.mediadash.android.domain.model.Podcast
import com.mediadash.android.domain.model.PodcastEpisode
import com.mediadash.android.media.DownloadProgress
import com.mediadash.android.media.EpisodeDownloadManager
import com.mediadash.android.media.PodcastPlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PodcastUiState(
    val searchQuery: String = "",
    val searchResults: List<Podcast> = emptyList(),
    val subscribedPodcasts: List<Podcast> = emptyList(),
    val selectedPodcast: Podcast? = null,
    val episodes: List<PodcastEpisode> = emptyList(),
    val downloadedEpisodes: List<PodcastEpisode> = emptyList(),
    val activeDownloads: Map<String, DownloadProgress> = emptyMap(),
    val isSearching: Boolean = false,
    val isLoading: Boolean = false,
    val isSubscribing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val showAddFeedDialog: Boolean = false,
    val showDownloadsSection: Boolean = false,
    val showUnsubscribeDialog: Boolean = false,
    val episodeLimit: Int = 15,
    val totalEpisodeCount: Int = 0,
    val canLoadMore: Boolean = false
)

sealed class PodcastEvent {
    data class SearchQueryChanged(val query: String) : PodcastEvent()
    object PerformSearch : PodcastEvent()
    data class SelectPodcast(val podcast: Podcast) : PodcastEvent()
    data class SubscribeToPodcast(val podcast: Podcast) : PodcastEvent()
    data class UnsubscribeFromPodcast(val podcastId: String) : PodcastEvent()
    data class AddCustomFeed(val feedUrl: String) : PodcastEvent()
    data class ImportOPMLFeeds(val feedUrls: List<String>) : PodcastEvent()
    data class RefreshPodcast(val podcastId: String) : PodcastEvent()
    data class PlayEpisode(val episode: PodcastEpisode) : PodcastEvent()
    data class DownloadEpisode(val episode: PodcastEpisode) : PodcastEvent()
    data class CancelDownload(val episodeId: String) : PodcastEvent()
    data class DeleteDownload(val episode: PodcastEpisode) : PodcastEvent()
    object ShowAddFeedDialog : PodcastEvent()
    object HideAddFeedDialog : PodcastEvent()
    object ShowUnsubscribeDialog : PodcastEvent()
    object HideUnsubscribeDialog : PodcastEvent()
    object ConfirmUnsubscribe : PodcastEvent()
    object LoadMoreEpisodes : PodcastEvent()
    object ToggleDownloadsSection : PodcastEvent()
    object ClearError : PodcastEvent()
    object ClearSuccessMessage : PodcastEvent()
    object ClearSelection : PodcastEvent()
    // Mini-player control events
    object PlayPause : PodcastEvent()
    object SkipBackward : PodcastEvent()
    object SkipForward : PodcastEvent()
    object PreviousTrack : PodcastEvent()
    object NextTrack : PodcastEvent()
}

@HiltViewModel
class PodcastViewModel @Inject constructor(
    private val podcastRepository: PodcastRepository,
    private val downloadManager: EpisodeDownloadManager,
    private val playerManager: PodcastPlayerManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PodcastUiState())
    val uiState: StateFlow<PodcastUiState> = _uiState.asStateFlow()

    // Expose playback state for the mini-player
    val playbackState = playerManager.playbackState

    private var searchJob: Job? = null
    private var episodeCollectionJob: Job? = null

    init {
        playerManager.connect()
        observeSubscribedPodcasts()
        observeDownloadedEpisodes()
        observeActiveDownloads()
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.disconnect()
    }

    private fun observeSubscribedPodcasts() {
        viewModelScope.launch {
            podcastRepository.getSubscribedPodcasts().collect { podcasts ->
                _uiState.update { it.copy(subscribedPodcasts = podcasts) }
            }
        }
    }

    private fun observeDownloadedEpisodes() {
        viewModelScope.launch {
            podcastRepository.getDownloadedEpisodes().collect { episodes ->
                _uiState.update { it.copy(downloadedEpisodes = episodes) }
            }
        }
    }

    private fun observeActiveDownloads() {
        viewModelScope.launch {
            downloadManager.activeDownloads.collect { downloads ->
                _uiState.update { it.copy(activeDownloads = downloads) }
            }
        }
    }

    fun onEvent(event: PodcastEvent) {
        when (event) {
            is PodcastEvent.SearchQueryChanged -> {
                _uiState.update { it.copy(searchQuery = event.query) }
                debounceSearch(event.query)
            }
            is PodcastEvent.PerformSearch -> {
                performSearch(_uiState.value.searchQuery)
            }
            is PodcastEvent.SelectPodcast -> {
                selectPodcast(event.podcast)
            }
            is PodcastEvent.SubscribeToPodcast -> {
                subscribeToPodcast(event.podcast)
            }
            is PodcastEvent.UnsubscribeFromPodcast -> {
                unsubscribeFromPodcast(event.podcastId)
            }
            is PodcastEvent.AddCustomFeed -> {
                addCustomFeed(event.feedUrl)
            }
            is PodcastEvent.ImportOPMLFeeds -> {
                importOPMLFeeds(event.feedUrls)
            }
            is PodcastEvent.RefreshPodcast -> {
                refreshPodcast(event.podcastId)
            }
            is PodcastEvent.PlayEpisode -> {
                playEpisode(event.episode)
            }
            is PodcastEvent.DownloadEpisode -> {
                downloadEpisode(event.episode)
            }
            is PodcastEvent.CancelDownload -> {
                cancelDownload(event.episodeId)
            }
            is PodcastEvent.DeleteDownload -> {
                deleteDownload(event.episode)
            }
            is PodcastEvent.ShowAddFeedDialog -> {
                _uiState.update { it.copy(showAddFeedDialog = true) }
            }
            is PodcastEvent.HideAddFeedDialog -> {
                _uiState.update { it.copy(showAddFeedDialog = false) }
            }
            is PodcastEvent.ShowUnsubscribeDialog -> {
                _uiState.update { it.copy(showUnsubscribeDialog = true) }
            }
            is PodcastEvent.HideUnsubscribeDialog -> {
                _uiState.update { it.copy(showUnsubscribeDialog = false) }
            }
            is PodcastEvent.ConfirmUnsubscribe -> {
                _uiState.value.selectedPodcast?.let { podcast ->
                    unsubscribeFromPodcast(podcast.id)
                }
                _uiState.update { it.copy(showUnsubscribeDialog = false) }
            }
            is PodcastEvent.LoadMoreEpisodes -> {
                loadMoreEpisodes()
            }
            is PodcastEvent.ToggleDownloadsSection -> {
                _uiState.update { it.copy(showDownloadsSection = !it.showDownloadsSection) }
            }
            is PodcastEvent.ClearError -> {
                _uiState.update { it.copy(error = null) }
            }
            is PodcastEvent.ClearSuccessMessage -> {
                _uiState.update { it.copy(successMessage = null) }
            }
            is PodcastEvent.ClearSelection -> {
                _uiState.update { it.copy(selectedPodcast = null, episodes = emptyList(), episodeLimit = 15, totalEpisodeCount = 0, canLoadMore = false) }
            }
            // Mini-player control events
            is PodcastEvent.PlayPause -> {
                playerManager.playPause()
            }
            is PodcastEvent.SkipBackward -> {
                playerManager.seekBackward(15_000) // 15 seconds back
            }
            is PodcastEvent.SkipForward -> {
                playerManager.seekForward(30_000) // 30 seconds forward
            }
            is PodcastEvent.PreviousTrack -> {
                playerManager.skipToPrevious()
            }
            is PodcastEvent.NextTrack -> {
                playerManager.skipToNext()
            }
        }
    }

    private fun debounceSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            delay(300)
            performSearch(query)
        }
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }

            podcastRepository.searchPodcasts(query)
                .onSuccess { podcasts ->
                    val subscribedIds = _uiState.value.subscribedPodcasts.map { it.id }.toSet()
                    val resultsWithSubscriptionStatus = podcasts.map { podcast ->
                        podcast.copy(isSubscribed = podcast.id in subscribedIds)
                    }
                    _uiState.update { it.copy(searchResults = resultsWithSubscriptionStatus, isSearching = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = "Search failed: ${error.message}", isSearching = false) }
                }
        }
    }

    private fun selectPodcast(podcast: Podcast) {
        // Cancel any previous episode collection to avoid race conditions
        episodeCollectionJob?.cancel()

        val initialLimit = 15
        _uiState.update {
            it.copy(
                selectedPodcast = podcast,
                episodes = emptyList(),
                isLoading = true,
                episodeLimit = initialLimit,
                totalEpisodeCount = 0,
                canLoadMore = false
            )
        }

        episodeCollectionJob = viewModelScope.launch {
            // Get total count first
            val totalCount = podcastRepository.getEpisodeCountForPodcast(podcast.id)

            // Collect limited episodes
            podcastRepository.getEpisodesForPodcastLimited(podcast.id, initialLimit).collect { episodes ->
                _uiState.update {
                    it.copy(
                        episodes = episodes,
                        isLoading = false,
                        totalEpisodeCount = totalCount,
                        canLoadMore = episodes.size < totalCount
                    )
                }
            }
        }
    }

    private fun loadMoreEpisodes() {
        val currentState = _uiState.value
        val podcast = currentState.selectedPodcast ?: return
        if (!currentState.canLoadMore || currentState.isLoadingMore) return

        episodeCollectionJob?.cancel()

        val newLimit = currentState.episodeLimit + 15
        _uiState.update { it.copy(isLoadingMore = true, episodeLimit = newLimit) }

        episodeCollectionJob = viewModelScope.launch {
            podcastRepository.getEpisodesForPodcastLimited(podcast.id, newLimit).collect { episodes ->
                _uiState.update {
                    it.copy(
                        episodes = episodes,
                        isLoadingMore = false,
                        canLoadMore = episodes.size < currentState.totalEpisodeCount
                    )
                }
            }
        }
    }

    private fun subscribeToPodcast(podcast: Podcast) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubscribing = true, error = null) }

            podcastRepository.subscribeToPodcast(podcast)
                .onSuccess {
                    _uiState.update {
                        val updatedResults = it.searchResults.map { p ->
                            if (p.id == podcast.id) p.copy(isSubscribed = true) else p
                        }
                        it.copy(
                            isSubscribing = false,
                            searchResults = updatedResults,
                            successMessage = "Subscribed to ${podcast.title}"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSubscribing = false,
                            error = "Failed to subscribe: ${error.message}"
                        )
                    }
                }
        }
    }

    private fun unsubscribeFromPodcast(podcastId: String) {
        viewModelScope.launch {
            podcastRepository.unsubscribe(podcastId)

            if (_uiState.value.selectedPodcast?.id == podcastId) {
                _uiState.update { it.copy(selectedPodcast = null, episodes = emptyList()) }
            }

            _uiState.update {
                val updatedResults = it.searchResults.map { p ->
                    if (p.id == podcastId) p.copy(isSubscribed = false) else p
                }
                it.copy(searchResults = updatedResults, successMessage = "Unsubscribed")
            }
        }
    }

    private fun addCustomFeed(feedUrl: String) {
        val trimmedUrl = feedUrl.trim()
        if (trimmedUrl.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a valid URL") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubscribing = true, error = null, showAddFeedDialog = false) }

            podcastRepository.subscribeToFeed(trimmedUrl)
                .onSuccess { podcast ->
                    _uiState.update {
                        it.copy(
                            isSubscribing = false,
                            successMessage = "Added ${podcast.title}"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSubscribing = false,
                            error = error.message ?: "Failed to add feed"
                        )
                    }
                }
        }
    }

    private fun importOPMLFeeds(feedUrls: List<String>) {
        if (feedUrls.isEmpty()) {
            _uiState.update { it.copy(error = "No feeds found in OPML file") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubscribing = true, error = null, showAddFeedDialog = false) }

            var successCount = 0
            var duplicateCount = 0
            var failCount = 0

            feedUrls.forEach { url ->
                // Check if already subscribed first to categorize properly
                val isSubscribed = podcastRepository.isFeedSubscribed(url)
                if (isSubscribed) {
                    duplicateCount++
                } else {
                    podcastRepository.subscribeToFeed(url)
                        .onSuccess { successCount++ }
                        .onFailure { error ->
                            // Double-check for duplicate errors from the repository
                            if (error.message?.contains("Already subscribed") == true) {
                                duplicateCount++
                            } else {
                                failCount++
                            }
                        }
                }
            }

            val message = buildString {
                if (successCount > 0) {
                    append("Imported $successCount podcast${if (successCount > 1) "s" else ""}")
                }
                if (duplicateCount > 0) {
                    if (isNotEmpty()) append(" • ")
                    append("$duplicateCount already subscribed")
                }
                if (failCount > 0) {
                    if (isNotEmpty()) append(" • ")
                    append("$failCount failed")
                }
                if (isEmpty()) {
                    append("No podcasts imported")
                }
            }

            _uiState.update {
                it.copy(
                    isSubscribing = false,
                    successMessage = message
                )
            }
        }
    }

    private fun refreshPodcast(podcastId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            podcastRepository.refreshPodcast(podcastId)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, successMessage = "Refreshed") }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Refresh failed: ${error.message}")
                    }
                }
        }
    }

    private fun playEpisode(episode: PodcastEpisode) {
        // Get podcast title from selected podcast or find it from subscribed podcasts
        val podcastTitle = _uiState.value.selectedPodcast?.title
            ?: _uiState.value.subscribedPodcasts.find { it.id == episode.podcastId }?.title
            ?: "Unknown Podcast"

        // Play the episode
        playerManager.playEpisode(episode, podcastTitle)

        // Mark as played
        viewModelScope.launch {
            podcastRepository.markEpisodeAsPlayed(episode.id, true)
        }
    }

    private fun downloadEpisode(episode: PodcastEpisode) {
        viewModelScope.launch {
            downloadManager.downloadEpisode(episode)
                .onSuccess {
                    _uiState.update { it.copy(successMessage = "Downloaded: ${episode.title}") }
                }
                .onFailure { error ->
                    if (error.message != "Download cancelled") {
                        _uiState.update { it.copy(error = "Download failed: ${error.message}") }
                    }
                }
        }
    }

    private fun cancelDownload(episodeId: String) {
        viewModelScope.launch {
            downloadManager.cancelDownload(episodeId)
        }
    }

    private fun deleteDownload(episode: PodcastEpisode) {
        viewModelScope.launch {
            downloadManager.deleteDownload(episode)
                .onSuccess {
                    _uiState.update { it.copy(successMessage = "Deleted download: ${episode.title}") }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = "Failed to delete: ${error.message}") }
                }
        }
    }
}
