package com.mediadash.android.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mediadash.android.R
import com.mediadash.android.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PodcastPlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    companion object {
        const val CHANNEL_ID = "podcast_playback_channel"
        const val NOTIFICATION_ID = 1001
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                playWhenReady = false
                addListener(playerListener)
            }

        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(createSessionActivityPendingIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Podcast Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows podcast playback controls"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createSessionActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    // Handle episode end - could auto-play next
                }
                Player.STATE_IDLE -> {
                    // Player is idle
                }
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Update notification or send state updates
        }
    }
}

/**
 * Helper class to manage podcast playback state and interact with the service.
 */
data class PodcastPlaybackState(
    val isPlaying: Boolean = false,
    val currentEpisodeId: String? = null,
    val currentEpisodeTitle: String = "",
    val currentPodcastTitle: String = "",
    val artworkUrl: String? = null,
    val duration: Long = 0L,
    val position: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val playlist: List<PlaylistItem> = emptyList(),
    val currentIndex: Int = -1
)

data class PlaylistItem(
    val episodeId: String,
    val episodeTitle: String,
    val podcastTitle: String,
    val audioUrl: String,
    val artworkUrl: String?,
    val duration: Long
)
