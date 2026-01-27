package com.mediadash.android.ui.composables

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mediadash.android.R
import com.mediadash.android.ui.MainUiEvent
import com.mediadash.android.ui.MainUiState
import com.spotsdk.models.Album
import com.spotsdk.models.Artist
import com.spotsdk.models.Track
import java.text.NumberFormat
import java.util.Locale

/**
 * Visual-only display of what's currently playing.
 * No interactive playback controls - those are on the PodcastPlayerPage (page 3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingPage(
    uiState: MainUiState,
    onEvent: (MainUiEvent) -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Janus logo header
        Image(
            painter = painterResource(id = R.drawable.janus_header),
            contentDescription = "Janus",
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Notification Permission Warning
        if (!uiState.hasNotificationPermission) {
            PermissionWarningCard(
                onOpenSettings = onOpenNotificationSettings
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Service Toggle
        ServiceToggleCard(
            isRunning = uiState.isServiceRunning,
            isBluetoothEnabled = uiState.isBluetoothEnabled,
            onToggle = { onEvent(MainUiEvent.ToggleService) },
            onEnableBluetooth = { onEvent(MainUiEvent.EnableBluetooth) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Connection Status
        ConnectionStatusCard(
            status = uiState.connectionStatus
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Lyrics Toggle
        LyricsToggleCard(
            isEnabled = uiState.lyricsEnabled,
            onToggle = { enabled -> onEvent(MainUiEvent.ToggleLyrics(enabled)) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Now Playing (visual-only, no interactive controls)
        uiState.mediaState?.let { mediaState ->
            NowPlayingCard(
                title = mediaState.trackTitle,
                artist = mediaState.artist,
                album = mediaState.album,
                positionMs = mediaState.position,
                durationMs = mediaState.duration,
                isPlaying = mediaState.isPlaying,
                albumArt = uiState.albumArtBitmap,
                albumArtRequestActive = uiState.albumArtRequestActive,
                mediaSource = uiState.mediaSource,
                isInteractive = false  // Visual-only mode
            )

            // View Album / View Artist buttons (Spotify only)
            if (mediaState.spotifyAlbumId != null || mediaState.spotifyArtistId != null) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (mediaState.spotifyAlbumId != null) {
                        OutlinedButton(
                            onClick = { onEvent(MainUiEvent.ViewAlbum(mediaState.spotifyAlbumId)) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("View Album")
                        }
                    }

                    if (mediaState.spotifyAlbumId != null && mediaState.spotifyArtistId != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    if (mediaState.spotifyArtistId != null) {
                        OutlinedButton(
                            onClick = { onEvent(MainUiEvent.ViewArtist(mediaState.spotifyArtistId)) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("View Artist")
                        }
                    }
                }
            }

            // Lyrics Display - show when enabled and lyrics are available
            if (uiState.lyricsEnabled && uiState.currentLyrics != null) {
                Spacer(modifier = Modifier.height(16.dp))

                LyricsCompactDisplay(
                    lyrics = uiState.currentLyrics,
                    currentLineIndex = uiState.currentLyricsLineIndex
                )
            }
        } ?: run {
            NoMediaCard()
        }
    }

    // Album Detail Bottom Sheet
    if (uiState.showAlbumDetail) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { onEvent(MainUiEvent.DismissAlbumDetail) },
            sheetState = sheetState
        ) {
            if (uiState.isLoadingDetail && uiState.albumDetail == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            } else {
                uiState.albumDetail?.let { album ->
                    AlbumDetailContent(album = album)
                }
            }
        }
    }

    // Artist Detail Bottom Sheet
    if (uiState.showArtistDetail) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { onEvent(MainUiEvent.DismissArtistDetail) },
            sheetState = sheetState
        ) {
            if (uiState.isLoadingDetail && uiState.artistDetail == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            } else {
                uiState.artistDetail?.let { artist ->
                    ArtistDetailContent(
                        artist = artist,
                        topTracks = uiState.artistTopTracks ?: emptyList()
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumDetailContent(album: Album) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        // Album header
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val imageUrl = album.images?.firstOrNull()?.url
                if (imageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = album.name,
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(
                    text = album.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                album.artists?.joinToString(", ") { it.name }?.let { artistNames ->
                    Text(
                        text = artistNames,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                val details = buildList {
                    album.albumType?.replaceFirstChar { it.uppercase() }?.let { add(it) }
                    album.releaseDate?.take(4)?.let { add(it) }
                    album.totalTracks?.let { add("$it tracks") }
                }
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" \u2022 "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                album.label?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Track listing
        val tracks = album.tracks?.items ?: emptyList()
        itemsIndexed(tracks) { index, track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${track.trackNumber ?: (index + 1)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    track.artists?.joinToString(", ") { it.name }?.let { names ->
                        Text(
                            text = names,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = formatDuration(track.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ArtistDetailContent(artist: Artist, topTracks: List<Track>) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        // Artist header
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val imageUrl = artist.images?.firstOrNull()?.url
                if (imageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = artist.name,
                        modifier = Modifier
                            .size(200.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                val followerCount = artist.followerCount
                if (followerCount > 0) {
                    Text(
                        text = "${NumberFormat.getNumberInstance(Locale.US).format(followerCount)} followers",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                artist.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = genres.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (topTracks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Top Tracks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                }
            }
        }

        // Top tracks listing
        itemsIndexed(topTracks) { index, track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp)
                )

                val trackImageUrl = track.album?.images?.lastOrNull()?.url
                if (trackImageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(trackImageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    track.album?.name?.let { albumName ->
                        Text(
                            text = albumName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = formatDuration(track.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:%02d".format(seconds)
}
