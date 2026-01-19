package com.mediadash.android.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediadash.android.ui.MainUiEvent
import com.mediadash.android.ui.MainUiState
import com.mediadash.android.ui.MediaSource

@Composable
fun PlayerPage(
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
        // Title
        Text(
            text = "Janus",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
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

        // Now Playing
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
                onSeek = { positionMs -> onEvent(MainUiEvent.SeekTo(positionMs)) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Playback Controls - only show for internal podcast playback
            // External media (Spotify, YouTube Music, etc.) is visual-only
            // Podcast controls are now on the Podcast page mini-player
            if (uiState.mediaSource == MediaSource.INTERNAL_PODCAST) {
                PlaybackControlsRow(
                    isPlaying = mediaState.isPlaying,
                    onPrevious = { onEvent(MainUiEvent.Previous) },
                    onPlayPause = { onEvent(MainUiEvent.PlayPause) },
                    onNext = { onEvent(MainUiEvent.Next) },
                    onSkipBack30 = { onEvent(MainUiEvent.SkipBack30) },
                    onSkipForward30 = { onEvent(MainUiEvent.SkipForward30) }
                )
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
}
