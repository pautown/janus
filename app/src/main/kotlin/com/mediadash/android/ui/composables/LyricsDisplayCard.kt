package com.mediadash.android.ui.composables

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediadash.android.domain.model.LyricsLine
import com.mediadash.android.domain.model.LyricsState

/**
 * Displays synced lyrics with the current line highlighted.
 */
@Composable
fun LyricsDisplayCard(
    lyrics: LyricsState?,
    currentLineIndex: Int,
    modifier: Modifier = Modifier
) {
    if (lyrics == null || lyrics.lines.isEmpty()) {
        return
    }

    val listState = rememberLazyListState()

    // Auto-scroll to keep current line visible
    LaunchedEffect(currentLineIndex) {
        if (currentLineIndex >= 0 && currentLineIndex < lyrics.lines.size) {
            // Scroll to show the current line in the center
            val targetIndex = (currentLineIndex - 1).coerceAtLeast(0)
            listState.animateScrollToItem(targetIndex)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header
            Text(
                text = if (lyrics.synced) "Lyrics" else "Lyrics (unsynced)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Lyrics display area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            ) {
                if (lyrics.synced) {
                    // Synced lyrics - show scrolling list with highlighted current line
                    SyncedLyricsView(
                        lines = lyrics.lines,
                        currentLineIndex = currentLineIndex,
                        listState = listState
                    )
                } else {
                    // Unsynced lyrics - just show static text
                    UnsyncedLyricsView(lines = lyrics.lines)
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricsView(
    lines: List<LyricsLine>,
    currentLineIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(lines) { index, line ->
            LyricsLineItem(
                text = line.l,
                isCurrentLine = index == currentLineIndex,
                isPastLine = index < currentLineIndex
            )
        }
    }
}

@Composable
private fun LyricsLineItem(
    text: String,
    isCurrentLine: Boolean,
    isPastLine: Boolean
) {
    val textColor by animateColorAsState(
        targetValue = when {
            isCurrentLine -> MaterialTheme.colorScheme.primary
            isPastLine -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        },
        animationSpec = tween(durationMillis = 300),
        label = "lyricsLineColor"
    )

    val fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal
    val fontSize = if (isCurrentLine) 16.sp else 14.sp

    Text(
        text = text,
        color = textColor,
        fontWeight = fontWeight,
        fontSize = fontSize,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun UnsyncedLyricsView(
    lines: List<LyricsLine>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(lines) { _, line ->
            Text(
                text = line.l,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }
    }
}

/**
 * Compact lyrics display showing only the current and next lines.
 * Use this for a minimal inline display.
 */
@Composable
fun LyricsCompactDisplay(
    lyrics: LyricsState?,
    currentLineIndex: Int,
    modifier: Modifier = Modifier
) {
    if (lyrics == null || lyrics.lines.isEmpty() || !lyrics.synced) {
        return
    }

    val currentLine = lyrics.lines.getOrNull(currentLineIndex)
    val nextLine = lyrics.lines.getOrNull(currentLineIndex + 1)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Current line
            if (currentLine != null) {
                Text(
                    text = currentLine.l,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Next line (dimmed)
            if (nextLine != null) {
                Text(
                    text = nextLine.l,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
            }
        }
    }
}
