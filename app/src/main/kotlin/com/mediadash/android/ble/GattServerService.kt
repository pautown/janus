package com.mediadash.android.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mediadash.android.R
import com.mediadash.android.data.media.LyricsManager
import com.mediadash.android.data.repository.MediaRepository
import com.mediadash.android.domain.model.LyricsRequest
import com.mediadash.android.domain.model.PlaybackCommand
import com.mediadash.android.domain.usecase.ProcessPlaybackCommandUseCase
import com.mediadash.android.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Foreground service that hosts the BLE GATT server.
 * Required for maintaining BLE connections in the background.
 */
@AndroidEntryPoint
class GattServerService : Service() {

    companion object {
        private const val TAG = "GattServerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "mediadash_ble_channel"
    }

    @Inject
    lateinit var gattServerManager: GattServerManager

    @Inject
    lateinit var mediaRepository: MediaRepository

    @Inject
    lateinit var processPlaybackCommand: ProcessPlaybackCommandUseCase

    @Inject
    lateinit var albumArtTransmitter: AlbumArtTransmitter

    @Inject
    lateinit var lyricsManager: LyricsManager

    @Inject
    lateinit var settingsManager: com.mediadash.android.data.local.SettingsManager

    @Inject
    lateinit var mediaControllerManager: com.mediadash.android.data.media.MediaControllerManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaStateJob: Job? = null
    private var commandJob: Job? = null
    private var albumArtRequestJob: Job? = null
    private var podcastInfoRequestJob: Job? = null
    private var lyricsRequestJob: Job? = null
    private var settingsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        createNotificationChannel()

        // Start foreground with notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        // Start GATT server
        if (gattServerManager.start()) {
            observeMediaState()
            observeCommands()
            observeAlbumArtRequests()
            observePodcastInfoRequests()
            observeLyricsRequests()
            setupLyricsTransmission()
            observeSettings()
        } else {
            Log.e(TAG, "Failed to start GATT server, stopping service")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")

        mediaStateJob?.cancel()
        commandJob?.cancel()
        albumArtRequestJob?.cancel()
        podcastInfoRequestJob?.cancel()
        lyricsRequestJob?.cancel()
        settingsJob?.cancel()
        serviceScope.cancel()

        gattServerManager.stop()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun observeMediaState() {
        mediaStateJob = serviceScope.launch {
            mediaRepository.currentMediaState.collectLatest { state ->
                state?.let {
                    gattServerManager.notifyMediaStateChanged(it)
                    // Album art is sent only when requested by the Go client via observeAlbumArtRequests()
                }
            }
        }
    }

    private fun observeCommands() {
        commandJob = serviceScope.launch {
            gattServerManager.commandReceived.collect { command ->
                Log.d(TAG, "Processing command: ${command.action}")

                // Enhanced logging for podcast-related commands
                when (command.action) {
                    PlaybackCommand.ACTION_REQUEST_PODCAST_LIST -> {
                        Log.i("PODCAST", "═══════════════════════════════════════════════════════")
                        Log.i("PODCAST", "📥 REQUEST: Podcast channel list (A-Z)")
                        Log.i("PODCAST", "   Source: golang_ble_client via BLE")
                        handlePodcastListRequest()
                    }
                    PlaybackCommand.ACTION_REQUEST_RECENT_EPISODES -> {
                        val limit = if (command.limit > 0) command.limit else 30
                        Log.i("PODCAST", "═══════════════════════════════════════════════════════")
                        Log.i("PODCAST", "📥 REQUEST: Recent episodes (limit=$limit)")
                        Log.i("PODCAST", "   Source: golang_ble_client via BLE")
                        handleRecentEpisodesRequest(limit)
                    }
                    PlaybackCommand.ACTION_REQUEST_PODCAST_EPISODES -> {
                        val podcastId = command.podcastId ?: ""
                        val offset = command.offset
                        val limit = if (command.limit > 0) command.limit else 15
                        Log.i("PODCAST", "═══════════════════════════════════════════════════════")
                        Log.i("PODCAST", "📥 REQUEST: Episodes for podcast")
                        Log.i("PODCAST", "   podcastId: $podcastId")
                        Log.i("PODCAST", "   offset: $offset, limit: $limit")
                        Log.i("PODCAST", "   Source: golang_ble_client via BLE")
                        handlePodcastEpisodesRequest(podcastId, offset, limit)
                    }
                    PlaybackCommand.ACTION_REQUEST_MEDIA_CHANNELS -> {
                        Log.i("MEDIA_CHANNELS", "═══════════════════════════════════════════════════════")
                        Log.i("MEDIA_CHANNELS", "📥 REQUEST: Media channel apps list")
                        Log.i("MEDIA_CHANNELS", "   Source: golang_ble_client via BLE")
                        handleMediaChannelsRequest()
                    }
                    PlaybackCommand.ACTION_SELECT_MEDIA_CHANNEL -> {
                        val channelName = command.channel ?: ""
                        Log.i("MEDIA_CHANNELS", "═══════════════════════════════════════════════════════")
                        Log.i("MEDIA_CHANNELS", "🎯 SELECT: Media channel")
                        Log.i("MEDIA_CHANNELS", "   Channel: $channelName")
                        Log.i("MEDIA_CHANNELS", "   Source: golang_ble_client via BLE")
                        handleSelectMediaChannel(channelName)
                    }
                    PlaybackCommand.ACTION_PLAY_PODCAST_EPISODE -> {
                        Log.i("PODCAST", "═══════════════════════════════════════════════════════")
                        Log.i("PODCAST", "▶️ PLAY: Podcast episode")
                        Log.i("PODCAST", "   podcastId: ${command.podcastId}")
                        Log.i("PODCAST", "   episodeIndex: ${command.episodeIndex}")
                        Log.i("PODCAST", "   Source: golang_ble_client via BLE")
                        processPlaybackCommand(command)
                    }
                    else -> {
                        // Regular command processing
                        processPlaybackCommand(command)
                    }
                }
            }
        }
    }

    private fun handlePodcastListRequest() {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val response = mediaRepository.getPodcastList()

                if (response != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i("PODCAST", "📤 RESPONSE: Podcast list")
                    Log.i("PODCAST", "   Channels: ${response.podcasts.size}")
                    Log.i("PODCAST", "   Processing time: ${elapsed}ms")
                    response.podcasts.take(3).forEach { channel ->
                        Log.d("PODCAST", "   - ${channel.title} (${channel.episodeCount} episodes)")
                    }
                    if (response.podcasts.size > 3) {
                        Log.d("PODCAST", "   ... and ${response.podcasts.size - 3} more")
                    }
                    gattServerManager.notifyPodcastList(response)
                    Log.i("PODCAST", "✅ Podcast list transmitted via BLE")
                } else {
                    Log.w("PODCAST", "⚠️ No podcast list available")
                }
            } catch (e: Exception) {
                Log.e("PODCAST", "❌ Error handling podcast list request", e)
            }
        }
    }

    private fun handleRecentEpisodesRequest(limit: Int) {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val response = mediaRepository.getRecentEpisodes(limit)

                if (response != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i("PODCAST", "📤 RESPONSE: Recent episodes")
                    Log.i("PODCAST", "   Episodes: ${response.episodes.size} (of ${response.totalCount} total)")
                    Log.i("PODCAST", "   Processing time: ${elapsed}ms")
                    response.episodes.take(3).forEach { ep ->
                        Log.d("PODCAST", "   - ${ep.podcastTitle}: ${ep.title}")
                    }
                    if (response.episodes.size > 3) {
                        Log.d("PODCAST", "   ... and ${response.episodes.size - 3} more")
                    }
                    gattServerManager.notifyRecentEpisodes(response)
                    Log.i("PODCAST", "✅ Recent episodes transmitted via BLE")
                } else {
                    Log.w("PODCAST", "⚠️ No recent episodes available")
                }
            } catch (e: Exception) {
                Log.e("PODCAST", "❌ Error handling recent episodes request", e)
            }
        }
    }

    private fun handlePodcastEpisodesRequest(podcastId: String, offset: Int, limit: Int) {
        serviceScope.launch {
            try {
                if (podcastId.isEmpty()) {
                    Log.w("PODCAST", "⚠️ Missing podcastId in request")
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                val response = mediaRepository.getPodcastEpisodes(podcastId, offset, limit)

                if (response != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i("PODCAST", "📤 RESPONSE: Podcast episodes")
                    Log.i("PODCAST", "   Podcast: ${response.podcastTitle}")
                    Log.i("PODCAST", "   Episodes: ${response.episodes.size} (offset=$offset, total=${response.totalEpisodes})")
                    Log.i("PODCAST", "   hasMore: ${response.hasMore}")
                    Log.i("PODCAST", "   Processing time: ${elapsed}ms")
                    response.episodes.take(3).forEach { ep ->
                        Log.d("PODCAST", "   - ${ep.title}")
                    }
                    if (response.episodes.size > 3) {
                        Log.d("PODCAST", "   ... and ${response.episodes.size - 3} more")
                    }
                    gattServerManager.notifyPodcastEpisodes(response)
                    Log.i("PODCAST", "✅ Podcast episodes transmitted via BLE")
                } else {
                    Log.w("PODCAST", "⚠️ Podcast not found: $podcastId")
                }
            } catch (e: Exception) {
                Log.e("PODCAST", "❌ Error handling podcast episodes request", e)
            }
        }
    }

    private fun handleMediaChannelsRequest() {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                // Get active media channels from MediaSessionListener
                val listener = com.mediadash.android.data.media.MediaSessionListener.getInstance()
                val externalChannels = listener?.getActiveMediaChannels() ?: emptyList()

                // Build channel list - include "Podcasts" if podcast playback is active
                val allChannels = mutableListOf<String>()

                // Add "Podcasts" if podcast is active (put it first for visibility)
                if (mediaControllerManager.isPodcastActive()) {
                    allChannels.add("Podcasts")
                }

                // Add external media apps
                allChannels.addAll(externalChannels)

                val elapsed = System.currentTimeMillis() - startTime
                Log.i("MEDIA_CHANNELS", "📤 RESPONSE: Media channels")
                Log.i("MEDIA_CHANNELS", "   Channels found: ${allChannels.size}")
                allChannels.forEach { channel ->
                    Log.i("MEDIA_CHANNELS", "   - $channel")
                }
                Log.i("MEDIA_CHANNELS", "   Processing time: ${elapsed}ms")

                gattServerManager.notifyMediaChannels(allChannels)
                Log.i("MEDIA_CHANNELS", "✅ Media channels transmitted via BLE")
            } catch (e: Exception) {
                Log.e("MEDIA_CHANNELS", "❌ Error handling media channels request", e)
            }
        }
    }

    private fun handleSelectMediaChannel(channelName: String) {
        serviceScope.launch {
            try {
                if (channelName.isEmpty()) {
                    Log.w("MEDIA_CHANNELS", "⚠️ Empty channel name in select request")
                    return@launch
                }

                if (channelName == "Podcasts") {
                    // Special handling for Podcasts - activate internal podcast player
                    Log.i("MEDIA_CHANNELS", "✅ Selected Podcasts channel (internal)")
                    // The podcast player will set itself as active when it starts playing
                    // For now, just acknowledge the selection - the podcast UI will handle playback
                    return@launch
                }

                val listener = com.mediadash.android.data.media.MediaSessionListener.getInstance()
                if (listener != null) {
                    listener.selectChannel(channelName)
                    Log.i("MEDIA_CHANNELS", "✅ Selected media channel: $channelName")
                } else {
                    Log.w("MEDIA_CHANNELS", "⚠️ MediaSessionListener not available")
                }
            } catch (e: Exception) {
                Log.e("MEDIA_CHANNELS", "❌ Error selecting media channel", e)
            }
        }
    }

    private fun observeAlbumArtRequests() {
        albumArtRequestJob = serviceScope.launch {
            gattServerManager.albumArtRequested.collect { request ->
                Log.i("ALBUMART", "───────────────────────────────────────────────")
                Log.i("ALBUMART", "🔄 SERVICE: Processing album art request")
                Log.i("ALBUMART", "   Requested hash: ${request.hash}")
                Log.d(TAG, "Processing album art request: ${request.hash}")

                // First check if the requested hash matches the expected hash from current media state
                // This tells us if the request is for the CURRENT track (even if chunks aren't ready yet)
                val expectedHash = mediaRepository.currentMediaState.value?.albumArtHash
                Log.i("ALBUMART", "   Expected hash from media state: $expectedHash")

                val chunks = mediaRepository.currentAlbumArtChunks.value
                val currentChunkHash = chunks?.firstOrNull()?.hash?.toString()
                Log.i("ALBUMART", "   Current chunk hash in repo: $currentChunkHash")

                when {
                    // Case 1: Chunks are ready and match the request - transmit immediately
                    chunks != null && chunks.isNotEmpty() && currentChunkHash == request.hash -> {
                        Log.i("ALBUMART", "   ✅ Hash match! Starting transmission...")
                        gattServerManager.transmitAlbumArt(chunks)
                    }

                    // Case 2: Request matches expected hash but chunks aren't ready - wait for them
                    expectedHash == request.hash -> {
                        Log.i("ALBUMART", "   ⏳ Request matches expected hash, waiting for chunks...")

                        // Wait up to 5 seconds for chunks to become available with the correct hash
                        val readyChunks = withTimeoutOrNull(5000L) {
                            mediaRepository.currentAlbumArtChunks
                                .filter { chunkList ->
                                    chunkList != null &&
                                    chunkList.isNotEmpty() &&
                                    chunkList.first().hash.toString() == request.hash
                                }
                                .first()
                        }

                        if (readyChunks != null) {
                            Log.i("ALBUMART", "   ✅ Chunks ready! Starting transmission...")
                            gattServerManager.transmitAlbumArt(readyChunks)
                        } else {
                            Log.w("ALBUMART", "   ⚠️ Timeout waiting for chunks (5s)")
                        }
                    }

                    // Case 3: Request doesn't match expected hash - it's for an old track
                    else -> {
                        Log.w("ALBUMART", "   ⚠️ Hash mismatch - request is for old track")
                        Log.w("ALBUMART", "      Requested: ${request.hash}")
                        Log.w("ALBUMART", "      Expected:  $expectedHash")
                        Log.w("ALBUMART", "      Chunk:     $currentChunkHash")
                    }
                }
            }
        }
    }

    private fun observePodcastInfoRequests() {
        podcastInfoRequestJob = serviceScope.launch {
            gattServerManager.podcastInfoRequested.collect {
                Log.d(TAG, "Processing podcast info request")

                val podcastInfo = mediaRepository.getPodcastInfo()
                if (podcastInfo != null) {
                    Log.d(TAG, "Sending podcast info: ${podcastInfo.podcasts.size} podcasts")
                    gattServerManager.notifyPodcastInfo(podcastInfo)
                } else {
                    Log.w(TAG, "No podcast info available")
                }
            }
        }
    }

    private fun observeLyricsRequests() {
        lyricsRequestJob = serviceScope.launch {
            gattServerManager.lyricsRequested.collect { request ->
                Log.i("LYRICS", "═══════════════════════════════════════════════")
                Log.i("LYRICS", "📥 BLE LYRICS REQUEST RECEIVED")
                Log.i("LYRICS", "   Action: ${request.action}")
                Log.i("LYRICS", "   Hash: ${request.hash ?: "(none)"}")
                Log.i("LYRICS", "   Artist: ${request.artist ?: "(none)"}")
                Log.i("LYRICS", "   Track: ${request.track ?: "(none)"}")
                Log.i(TAG, "───────────────────────────────────────────────")
                Log.i(TAG, "🎵 LYRICS: Processing lyrics request")
                Log.i(TAG, "   Requested hash: ${request.hash}")
                Log.i(TAG, "   Artist: ${request.artist}, Track: ${request.track}")

                // Handle clear action
                if (request.action == "clear") {
                    Log.i("LYRICS", "   🧹 CLEAR action - notifying devices to clear lyrics")
                    gattServerManager.notifyLyricsClear(request.hash)
                    return@collect
                }

                // If request includes artist/track, fetch lyrics
                if (!request.artist.isNullOrBlank() && !request.track.isNullOrBlank()) {
                    Log.i("LYRICS", "   📡 Fetching lyrics by artist/track")
                    try {
                        val startTime = System.currentTimeMillis()
                        val lyrics = lyricsManager.fetchLyrics(request.artist!!, request.track!!)
                        val elapsed = System.currentTimeMillis() - startTime

                        if (lyrics != null) {
                            Log.i("LYRICS", "   ✅ Lyrics fetched successfully")
                            Log.i("LYRICS", "   Lines: ${lyrics.lines.size}, synced: ${lyrics.synced}")
                            Log.i("LYRICS", "   Total processing time: ${elapsed}ms")
                            Log.i(TAG, "   ✅ Lyrics fetched: ${lyrics.lines.size} lines, synced: ${lyrics.synced}")
                            Log.i(TAG, "   Processing time: ${elapsed}ms")
                            // Transmission happens automatically via setupLyricsTransmission callback
                        } else {
                            Log.w("LYRICS", "   ⚠️ No lyrics found for this track")
                            Log.w("LYRICS", "   Processing time: ${elapsed}ms")
                            Log.w(TAG, "   ⚠️ No lyrics available for this track")
                            // Notify that no lyrics are available
                            gattServerManager.notifyLyricsClear(request.hash)
                        }
                    } catch (e: Exception) {
                        Log.e("LYRICS", "   ❌ Error fetching lyrics: ${e.message}")
                        Log.e(TAG, "   ❌ Error fetching lyrics", e)
                    }
                } else if (!request.hash.isNullOrBlank()) {
                    // Request is for cached lyrics by hash
                    Log.i("LYRICS", "   📦 Looking up cached lyrics by hash")
                    val cachedLyrics = lyricsManager.getCachedLyrics(request.hash!!)
                    if (cachedLyrics != null) {
                        Log.i("LYRICS", "   ✅ Cache hit - found ${cachedLyrics.lines.size} lines")
                        Log.i(TAG, "   ✅ Found cached lyrics for hash: ${request.hash}")
                        val chunks = lyricsManager.lyricsToChunks(cachedLyrics)
                        Log.i("LYRICS", "   📤 Transmitting ${chunks.size} chunks over BLE")
                        gattServerManager.notifyLyricsData(chunks)
                    } else {
                        Log.w("LYRICS", "   ⚠️ Cache miss - no lyrics for hash")
                        Log.w(TAG, "   ⚠️ No cached lyrics for hash: ${request.hash}")
                        gattServerManager.notifyLyricsClear(request.hash)
                    }
                } else {
                    Log.w("LYRICS", "   ⚠️ Invalid request - no artist/track or hash provided")
                }
                Log.i("LYRICS", "═══════════════════════════════════════════════")
            }
        }
    }

    private fun setupLyricsTransmission() {
        Log.i("LYRICS", "🔧 SETUP - Registering lyrics transmission callback")
        // Set up callback to transmit lyrics over BLE when they are fetched
        lyricsManager.setOnLyricsFetchedCallback { chunks ->
            Log.i("LYRICS", "═══════════════════════════════════════════════")
            Log.i("LYRICS", "📤 AUTO-TRANSMIT - Lyrics callback triggered")
            Log.i("LYRICS", "   Chunks to send: ${chunks.size}")
            if (chunks.isNotEmpty()) {
                val first = chunks.first()
                Log.i("LYRICS", "   Hash: ${first.h}")
                Log.i("LYRICS", "   Total lines: ${first.n}")
                Log.i("LYRICS", "   Synced: ${first.s}")
            }
            Log.i(TAG, "🎵 LYRICS: Auto-transmitting ${chunks.size} chunks over BLE")
            try {
                gattServerManager.notifyLyricsData(chunks)
                Log.i("LYRICS", "   ✅ Lyrics transmission complete")
                Log.i(TAG, "   ✅ Lyrics transmission complete")
            } catch (e: Exception) {
                Log.e("LYRICS", "   ❌ Failed to transmit lyrics: ${e.message}")
                Log.e(TAG, "   ❌ Failed to transmit lyrics", e)
            }
            Log.i("LYRICS", "═══════════════════════════════════════════════")
        }
    }

    private fun observeSettings() {
        settingsJob = serviceScope.launch {
            // Observe lyrics enabled setting and notify over BLE when it changes
            settingsManager.lyricsEnabled.collectLatest { enabled ->
                Log.i("LYRICS", "═══════════════════════════════════════════════")
                Log.i("LYRICS", "⚙️ SETTINGS CHANGE - lyricsEnabled: $enabled")
                Log.i(TAG, "⚙️ SETTINGS: Lyrics enabled changed to $enabled")
                try {
                    gattServerManager.notifyLyricsEnabled(enabled)
                    Log.i("LYRICS", "   ✅ Setting broadcast to connected devices")
                } catch (e: Exception) {
                    Log.e("LYRICS", "   ❌ Failed to notify setting: ${e.message}")
                    Log.e(TAG, "Failed to notify lyrics enabled setting", e)
                }
                Log.i("LYRICS", "═══════════════════════════════════════════════")
            }
        }
    }
}
