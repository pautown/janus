package com.mediadash.android.ui.composables

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.PhoneAndroid
import com.mediadash.android.ui.MediaSource
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun NowPlayingCard(
    title: String,
    artist: String,
    album: String,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    albumArt: Bitmap? = null,
    albumArtRequestActive: Boolean = false,
    mediaSource: MediaSource? = null,
    onSeek: (Long) -> Unit = {},
    isInteractive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    var isSeeking by remember { mutableFloatStateOf(-1f) }
    val displayProgress = if (isSeeking >= 0) isSeeking else progress
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Now Playing label with media source indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isPlaying) "Now Playing" else "Paused",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPlaying) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    }
                )

                // Media source indicator
                mediaSource?.let { source ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when (source) {
                                MediaSource.INTERNAL_PODCAST -> Icons.Default.Podcasts
                                MediaSource.EXTERNAL_APP -> Icons.Default.PhoneAndroid
                            },
                            contentDescription = when (source) {
                                MediaSource.INTERNAL_PODCAST -> "Internal podcast player"
                                MediaSource.EXTERNAL_APP -> "External app"
                            },
                            modifier = Modifier.size(14.dp),
                            tint = when (source) {
                                MediaSource.INTERNAL_PODCAST -> MaterialTheme.colorScheme.primary
                                MediaSource.EXTERNAL_APP -> MaterialTheme.colorScheme.tertiary
                            }
                        )
                        Text(
                            text = when (source) {
                                MediaSource.INTERNAL_PODCAST -> "Janus"
                                MediaSource.EXTERNAL_APP -> "External"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (source) {
                                MediaSource.INTERNAL_PODCAST -> MaterialTheme.colorScheme.primary
                                MediaSource.EXTERNAL_APP -> MaterialTheme.colorScheme.tertiary
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art with request indicator
                val infiniteTransition = rememberInfiniteTransition(label = "albumArtRequest")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(500, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseAlpha"
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            if (albumArtRequestActive) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (albumArt != null) {
                        Image(
                            bitmap = albumArt.asImageBitmap(),
                            contentDescription = "Album Art",
                            modifier = Modifier.size(80.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Album art request overlay indicator
                    if (albumArtRequestActive) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "Requesting album art",
                                modifier = Modifier
                                    .size(32.dp)
                                    .alpha(pulseAlpha),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Track info
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Track title
                    Text(
                        text = title.ifEmpty { "Unknown Track" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Artist
                    Text(
                        text = artist.ifEmpty { "Unknown Artist" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Album
                    if (album.isNotEmpty()) {
                        Text(
                            text = album,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Seek slider (interactive or display-only based on isInteractive)
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = displayProgress.coerceIn(0f, 1f),
                    onValueChange = { newValue ->
                        if (isInteractive) {
                            isSeeking = newValue
                        }
                    },
                    onValueChangeFinished = {
                        if (isInteractive && isSeeking >= 0 && durationMs > 0) {
                            val seekPosition = (isSeeking * durationMs).toLong()
                            onSeek(seekPosition)
                        }
                        isSeeking = -1f
                    },
                    enabled = isInteractive,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        disabledThumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        disabledActiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Time labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val currentDisplayMs = if (isSeeking >= 0 && durationMs > 0) {
                        (isSeeking * durationMs).toLong()
                    } else {
                        positionMs
                    }
                    Text(
                        text = formatTime(currentDisplayMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatTime(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
