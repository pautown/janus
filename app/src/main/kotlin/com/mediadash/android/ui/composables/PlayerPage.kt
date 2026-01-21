package com.mediadash.android.ui.composables

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mediadash.android.R
import com.mediadash.android.ui.MainUiEvent
import com.mediadash.android.ui.MainUiState

/**
 * Visual-only display of what's currently playing.
 * No interactive playback controls - those are on the PodcastPlayerPage (page 3).
 */
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
