package com.mediadash.android.ui.podcast

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.lazy.grid.items as lazyGridItems
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.mediadash.android.data.remote.OPMLParser
import com.mediadash.android.domain.model.DownloadState
import com.mediadash.android.domain.model.Podcast
import com.mediadash.android.domain.model.PodcastEpisode
import com.mediadash.android.media.DownloadProgress
import kotlinx.coroutines.delay

@Composable
fun PodcastPage(
    viewModel: PodcastViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Handle back button based on current state (priority order)
    BackHandler(
        enabled = uiState.showEpisodeActionDialog ||
                uiState.showUnsubscribeDialog ||
                uiState.showAddFeedDialog ||
                uiState.viewMode != PodcastViewMode.LIBRARY ||
                uiState.selectedPodcast != null ||
                uiState.searchQuery.isNotBlank()
    ) {
        when {
            uiState.showEpisodeActionDialog -> viewModel.onEvent(PodcastEvent.HideEpisodeActionDialog)
            uiState.showUnsubscribeDialog -> viewModel.onEvent(PodcastEvent.HideUnsubscribeDialog)
            uiState.showAddFeedDialog -> viewModel.onEvent(PodcastEvent.HideAddFeedDialog)
            uiState.viewMode != PodcastViewMode.LIBRARY -> viewModel.onEvent(PodcastEvent.SetViewMode(PodcastViewMode.LIBRARY))
            uiState.selectedPodcast != null -> viewModel.onEvent(PodcastEvent.ClearSelection)
            uiState.searchQuery.isNotBlank() -> viewModel.onEvent(PodcastEvent.SearchQueryChanged(""))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            // Header with title or back button
            if (uiState.selectedPodcast != null) {
                PodcastDetailHeader(
                    podcast = uiState.selectedPodcast!!,
                    onBack = { viewModel.onEvent(PodcastEvent.ClearSelection) },
                    onRefresh = { viewModel.onEvent(PodcastEvent.RefreshPodcast(uiState.selectedPodcast!!.id)) },
                    onUnsubscribe = { viewModel.onEvent(PodcastEvent.ShowUnsubscribeDialog) },
                    isLoading = uiState.isLoading
                )
            } else {
                // Title
                Text(
                    text = "Podcasts",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Only show search and subscriptions when not viewing a podcast detail
            if (uiState.selectedPodcast == null) {
                // Search Bar
                PodcastSearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = { viewModel.onEvent(PodcastEvent.SearchQueryChanged(it)) },
                    isSearching = uiState.isSearching
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Search Results or View Mode Content
                if (uiState.searchQuery.isNotBlank()) {
                    SearchResultsSection(
                        results = uiState.searchResults,
                        isSearching = uiState.isSearching,
                        isSubscribing = uiState.isSubscribing,
                        onSubscribe = { viewModel.onEvent(PodcastEvent.SubscribeToPodcast(it)) },
                        onSelect = { viewModel.onEvent(PodcastEvent.SelectPodcast(it)) }
                    )
                } else {
                    // View Mode Tabs
                    ViewModeTabs(
                        currentMode = uiState.viewMode,
                        onModeChange = { viewModel.onEvent(PodcastEvent.SetViewMode(it)) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Content based on view mode
                    when (uiState.viewMode) {
                        PodcastViewMode.LIBRARY -> {
                            SubscribedPodcastsSection(
                                podcasts = uiState.subscribedPodcasts,
                                downloadedCount = uiState.downloadedEpisodes.size,
                                onSelect = { viewModel.onEvent(PodcastEvent.SelectPodcast(it)) },
                                onLongPress = { viewModel.onEvent(PodcastEvent.LongPressUnsubscribe(it)) },
                                onAddFeed = { viewModel.onEvent(PodcastEvent.ShowAddFeedDialog) },
                                onShowDownloads = { viewModel.onEvent(PodcastEvent.SetViewMode(PodcastViewMode.DOWNLOADS)) }
                            )
                        }
                        PodcastViewMode.RECENT -> {
                            RecentEpisodesSection(
                                episodes = uiState.recentEpisodes,
                                subscribedPodcasts = uiState.subscribedPodcasts,
                                activeDownloads = uiState.activeDownloads,
                                onPlay = { viewModel.onEvent(PodcastEvent.PlayEpisode(it)) },
                                onLongPress = { viewModel.onEvent(PodcastEvent.LongPressEpisode(it)) }
                            )
                        }
                        PodcastViewMode.DOWNLOADS -> {
                            DownloadedEpisodesSection(
                                episodes = uiState.downloadedEpisodes,
                                activeDownloads = uiState.activeDownloads,
                                onPlay = { viewModel.onEvent(PodcastEvent.PlayEpisode(it)) },
                                onDelete = { viewModel.onEvent(PodcastEvent.DeleteDownload(it)) },
                                onLongPress = { viewModel.onEvent(PodcastEvent.LongPressEpisode(it)) }
                            )
                        }
                    }
                }
            } else {
                // Episode List
                EpisodeListSection(
                    episodes = uiState.episodes,
                    podcastArtworkUrl = uiState.selectedPodcast!!.artworkUrl,
                    isLoading = uiState.isLoading,
                    isLoadingMore = uiState.isLoadingMore,
                    canLoadMore = uiState.canLoadMore,
                    totalEpisodeCount = uiState.totalEpisodeCount,
                    activeDownloads = uiState.activeDownloads,
                    onPlayEpisode = { viewModel.onEvent(PodcastEvent.PlayEpisode(it)) },
                    onDownloadEpisode = { viewModel.onEvent(PodcastEvent.DownloadEpisode(it)) },
                    onCancelDownload = { viewModel.onEvent(PodcastEvent.CancelDownload(it)) },
                    onDeleteDownload = { viewModel.onEvent(PodcastEvent.DeleteDownload(it)) },
                    onLoadMore = { viewModel.onEvent(PodcastEvent.LoadMoreEpisodes) }
                )
            }
        }

        // Add Feed Dialog
        if (uiState.showAddFeedDialog) {
            AddPodcastDialog(
                onDismiss = { viewModel.onEvent(PodcastEvent.HideAddFeedDialog) },
                onAddRssFeed = { viewModel.onEvent(PodcastEvent.AddCustomFeed(it)) },
                onImportOpml = { feedUrls -> viewModel.onEvent(PodcastEvent.ImportOPMLFeeds(feedUrls)) }
            )
        }

        // Unsubscribe Confirmation Dialog
        if (uiState.showUnsubscribeDialog && uiState.selectedPodcast != null) {
            AlertDialog(
                onDismissRequest = { viewModel.onEvent(PodcastEvent.HideUnsubscribeDialog) },
                title = {
                    Text(
                        text = "Unsubscribe",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Text(
                        text = "Unsubscribe from \"${uiState.selectedPodcast!!.title}\"? All episodes will be removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.onEvent(PodcastEvent.ConfirmUnsubscribe) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Unsubscribe")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onEvent(PodcastEvent.HideUnsubscribeDialog) }) {
                        Text(
                            "Cancel",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            )
        }

        // Episode Action Dialog (Download/Delete)
        if (uiState.showEpisodeActionDialog && uiState.episodeForAction != null) {
            val episode = uiState.episodeForAction!!
            val isDownloaded = episode.isDownloaded
            AlertDialog(
                onDismissRequest = { viewModel.onEvent(PodcastEvent.HideEpisodeActionDialog) },
                title = {
                    Text(
                        text = if (isDownloaded) "Delete Download" else "Download Episode",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Text(
                        text = if (isDownloaded) {
                            "Delete the downloaded file for \"${episode.title}\"?"
                        } else {
                            "Download \"${episode.title}\" for offline listening?"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.onEvent(PodcastEvent.ConfirmEpisodeAction) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDownloaded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            contentColor = if (isDownloaded) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(if (isDownloaded) "Delete" else "Download")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onEvent(PodcastEvent.HideEpisodeActionDialog) }) {
                        Text(
                            "Cancel",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            )
        }

        // Error Snackbar
        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.onEvent(PodcastEvent.ClearError) }) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.inversePrimary)
                    }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text(error)
            }

            LaunchedEffect(error) {
                delay(4000)
                viewModel.onEvent(PodcastEvent.ClearError)
            }
        }

        // Success Snackbar
        uiState.successMessage?.let { message ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(message)
            }

            LaunchedEffect(message) {
                delay(2000)
                viewModel.onEvent(PodcastEvent.ClearSuccessMessage)
            }
        }

        // Loading overlay for subscribing
        if (uiState.isSubscribing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Adding podcast...", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}

@Composable
private fun PodcastDetailHeader(
    podcast: Podcast,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onUnsubscribe: () -> Unit,
    isLoading: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = podcast.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onRefresh, enabled = !isLoading) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        IconButton(onClick = onUnsubscribe) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Unsubscribe",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PodcastSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isSearching: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Search iTunes podcasts...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    innerTextField()
                }
            )

            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsSection(
    results: List<Podcast>,
    isSearching: Boolean,
    isSubscribing: Boolean,
    onSubscribe: (Podcast) -> Unit,
    onSelect: (Podcast) -> Unit
) {
    if (isSearching && results.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else if (results.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No podcasts found",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lazyListItems(results, key = { it.id }) { podcast ->
                SearchResultCard(
                    podcast = podcast,
                    onSubscribe = { onSubscribe(podcast) },
                    onSelect = { onSelect(podcast) },
                    isSubscribing = isSubscribing
                )
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    podcast: Podcast,
    onSubscribe: () -> Unit,
    onSelect: () -> Unit,
    isSubscribing: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = podcast.artworkUrl,
                contentDescription = podcast.title,
                modifier = Modifier
                    .size(60.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = podcast.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = podcast.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (podcast.genre.isNotBlank()) {
                    Text(
                        text = podcast.genre,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (podcast.isSubscribed) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Subscribed",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                IconButton(
                    onClick = onSubscribe,
                    enabled = !isSubscribing
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Subscribe",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscribedPodcastsSection(
    podcasts: List<Podcast>,
    downloadedCount: Int,
    onSelect: (Podcast) -> Unit,
    onLongPress: (Podcast) -> Unit,
    onAddFeed: () -> Unit,
    onShowDownloads: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp

    // Calculate columns based on screen width (min 100dp per item + spacing)
    val columns = when {
        screenWidth < 360 -> 3
        screenWidth < 600 -> 4
        screenWidth < 840 -> 5
        else -> 6
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Your Podcasts",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            // Downloads button
            if (downloadedCount > 0) {
                Surface(
                    onClick = onShowDownloads,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$downloadedCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (podcasts.isEmpty()) {
            EmptyPodcastsPlaceholder(onAddFeed = onAddFeed)
        } else {
            // Calculate total items: Add Feed button + optional Downloads card + podcasts
            val specialItemsCount = 1 + (if (downloadedCount > 0) 1 else 0)

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Add Feed button
                item {
                    AddFeedButton(onClick = onAddFeed)
                }

                // Downloads card if there are downloads
                if (downloadedCount > 0) {
                    item {
                        DownloadsCard(
                            count = downloadedCount,
                            onClick = onShowDownloads
                        )
                    }
                }

                lazyGridItems(podcasts, key = { it.id }) { podcast ->
                    PodcastArtworkCard(
                        podcast = podcast,
                        onClick = { onSelect(podcast) },
                        onLongPress = { onLongPress(podcast) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPodcastsPlaceholder(onAddFeed: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Podcasts,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No Podcasts Yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Search for podcasts above or add a custom RSS feed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAddFeed,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Custom Feed")
            }
        }
    }
}

@Composable
private fun AddFeedButton(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Custom Feed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = " ",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2
        )
    }
}

@Composable
private fun DownloadsCard(
    count: Int,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DownloadDone,
                        contentDescription = "Downloads",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Downloads",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ViewModeTabs(
    currentMode: PodcastViewMode,
    onModeChange: (PodcastViewMode) -> Unit
) {
    val tabs = listOf(
        PodcastViewMode.LIBRARY to "Library",
        PodcastViewMode.RECENT to "Recent",
        PodcastViewMode.DOWNLOADS to "Downloads"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEach { (mode, label) ->
            val isSelected = currentMode == mode
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onModeChange(mode) },
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentEpisodesSection(
    episodes: List<PodcastEpisode>,
    subscribedPodcasts: List<Podcast>,
    activeDownloads: Map<String, DownloadProgress>,
    onPlay: (PodcastEpisode) -> Unit,
    onLongPress: (PodcastEpisode) -> Unit
) {
    if (episodes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Podcasts,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No recent episodes",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "Subscribe to podcasts to see episodes here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lazyListItems(episodes, key = { it.id }) { episode ->
                val podcast = subscribedPodcasts.find { it.id == episode.podcastId }
                RecentEpisodeCard(
                    episode = episode,
                    podcastTitle = podcast?.title ?: "",
                    podcastArtworkUrl = podcast?.artworkUrl ?: "",
                    isDownloading = activeDownloads.containsKey(episode.id),
                    downloadProgress = (activeDownloads[episode.id]?.progress ?: 0) / 100f,
                    onPlay = { onPlay(episode) },
                    onLongPress = { onLongPress(episode) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentEpisodeCard(
    episode: PodcastEpisode,
    podcastTitle: String,
    podcastArtworkUrl: String,
    isDownloading: Boolean,
    downloadProgress: Float,
    onPlay: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onLongPress
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Episode artwork
            AsyncImage(
                model = episode.artworkUrl?.ifBlank { podcastArtworkUrl } ?: podcastArtworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Episode info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (podcastTitle.isNotBlank()) {
                    Text(
                        text = podcastTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (episode.duration > 0) {
                        Text(
                            text = formatDuration(episode.duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    if (episode.isDownloaded) {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = "Downloaded",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else if (isDownloading) {
                        CircularProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            // Play button
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedEpisodesSection(
    episodes: List<PodcastEpisode>,
    activeDownloads: Map<String, DownloadProgress>,
    onPlay: (PodcastEpisode) -> Unit,
    onDelete: (PodcastEpisode) -> Unit,
    onLongPress: (PodcastEpisode) -> Unit
) {
    if (episodes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No downloaded episodes",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "Long-press any episode to download",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lazyListItems(episodes, key = { it.id }) { episode ->
                DownloadedEpisodeCard(
                    episode = episode,
                    onPlay = { onPlay(episode) },
                    onDelete = { onDelete(episode) },
                    onLongPress = { onLongPress(episode) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedEpisodeCard(
    episode: PodcastEpisode,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onLongPress
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                AsyncImage(
                    model = episode.artworkUrl,
                    contentDescription = episode.title,
                    modifier = Modifier
                        .size(56.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DownloadDone,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (episode.isPlayed) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (episode.duration > 0) {
                        Text(
                            text = formatDuration(episode.duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (episode.downloadedSize > 0) {
                        Text(
                            text = " • ${formatFileSize(episode.downloadedSize)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete download",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }

            // Play button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastArtworkCard(
    podcast: Podcast,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            AsyncImage(
                model = podcast.artworkUrl,
                contentDescription = podcast.title,
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(1f),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = podcast.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EpisodeListSection(
    episodes: List<PodcastEpisode>,
    podcastArtworkUrl: String,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    totalEpisodeCount: Int,
    activeDownloads: Map<String, DownloadProgress>,
    onPlayEpisode: (PodcastEpisode) -> Unit,
    onDownloadEpisode: (PodcastEpisode) -> Unit,
    onCancelDownload: (String) -> Unit,
    onDeleteDownload: (PodcastEpisode) -> Unit,
    onLoadMore: () -> Unit
) {
    if (isLoading && episodes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    } else if (episodes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No episodes available",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lazyListItems(episodes, key = { it.id }) { episode ->
                val downloadProgress = activeDownloads[episode.id]
                EpisodeCard(
                    episode = episode,
                    podcastArtworkUrl = podcastArtworkUrl,
                    downloadProgress = downloadProgress,
                    onPlay = { onPlayEpisode(episode) },
                    onDownload = { onDownloadEpisode(episode) },
                    onCancelDownload = { onCancelDownload(episode.id) },
                    onDeleteDownload = { onDeleteDownload(episode) }
                )
            }

            // Load More button
            if (canLoadMore || isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoadingMore) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Loading more...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                                )
                            }
                        } else {
                            Button(
                                onClick = onLoadMore,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Text(
                                    text = "Load More (${episodes.size} of $totalEpisodeCount)",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: PodcastEpisode,
    podcastArtworkUrl: String,
    downloadProgress: DownloadProgress?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit
) {
    val isDownloading = downloadProgress?.state == DownloadState.DOWNLOADING ||
            episode.downloadState == DownloadState.DOWNLOADING
    val isDownloaded = episode.isDownloaded || episode.downloadState == DownloadState.DOWNLOADED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box {
                AsyncImage(
                    model = episode.artworkUrl ?: podcastArtworkUrl,
                    contentDescription = episode.title,
                    modifier = Modifier
                        .size(56.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                // Show downloaded indicator
                if (isDownloaded) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = "Downloaded",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (episode.isPlayed) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = episode.pubDateFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    if (episode.duration > 0) {
                        Text(
                            text = " • ${formatDuration(episode.duration)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    if (isDownloaded) {
                        Text(
                            text = " • Downloaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Show download progress
                if (isDownloading && downloadProgress != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { downloadProgress.progress / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${downloadProgress.progress}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (episode.description.isNotBlank() && !isDownloading) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = episode.description.replace(Regex("<[^>]*>"), ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Download/Delete button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    isDownloading -> {
                        // Cancel download button
                        IconButton(
                            onClick = onCancelDownload,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel download",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    isDownloaded -> {
                        // Delete download button
                        IconButton(
                            onClick = onDeleteDownload,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete download",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    else -> {
                        // Download button
                        IconButton(
                            onClick = onDownload,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Play button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddPodcastDialog(
    onDismiss: () -> Unit,
    onAddRssFeed: (String) -> Unit,
    onImportOpml: (List<String>) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    var feedUrl by remember { mutableStateOf("") }
    var opmlFeeds by remember { mutableStateOf<List<OPMLParser.OPMLFeed>>(emptyList()) }
    var opmlError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val opmlParser = remember { OPMLParser() }

    val opmlFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { inputStream ->
                    val feeds = opmlParser.parseOPML(inputStream)
                    if (feeds.isNotEmpty()) {
                        opmlFeeds = feeds
                        opmlError = null
                    } else {
                        opmlError = "No podcast feeds found in file"
                    }
                } ?: run {
                    opmlError = "Could not read file"
                }
            } catch (e: Exception) {
                opmlError = "Error reading file: ${e.message}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add Podcasts",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                // Tab row
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("RSS Feed") },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.RssFeed,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("OPML") },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> {
                        // RSS Feed tab
                        Text(
                            text = "Enter the podcast RSS feed URL",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = feedUrl,
                            onValueChange = { feedUrl = it },
                            placeholder = { Text("https://example.com/feed.xml") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                cursorColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    1 -> {
                        // OPML Import tab
                        Text(
                            text = "Import podcasts from an OPML file (exported from another podcast app)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { opmlFilePicker.launch(arrayOf("text/xml", "application/xml", "text/x-opml", "*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select OPML File")
                        }

                        opmlError?.let { error ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (opmlFeeds.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Found ${opmlFeeds.size} podcasts:",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    opmlFeeds.take(5).forEach { feed ->
                                        Text(
                                            text = "• ${feed.title}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (opmlFeeds.size > 5) {
                                        Text(
                                            text = "... and ${opmlFeeds.size - 5} more",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selectedTab) {
                        0 -> onAddRssFeed(feedUrl)
                        1 -> onImportOpml(opmlFeeds.map { it.xmlUrl })
                    }
                },
                enabled = when (selectedTab) {
                    0 -> feedUrl.isNotBlank()
                    1 -> opmlFeeds.isNotEmpty()
                    else -> false
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    when (selectedTab) {
                        0 -> "Add Feed"
                        1 -> "Import ${opmlFeeds.size} Podcasts"
                        else -> "Add"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "Cancel",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    )
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
        hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
        else -> String.format("%d:%02d", minutes, secs)
    }
}
