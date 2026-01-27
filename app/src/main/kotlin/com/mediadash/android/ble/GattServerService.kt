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
import com.mediadash.android.data.spotify.LibraryAlbumArtManager
import com.mediadash.android.data.spotify.SpotifyConnectionChecker
import com.mediadash.android.data.spotify.SpotifyLibraryManager
import com.mediadash.android.data.spotify.SpotifyPlaybackController
import com.mediadash.android.data.spotify.SpotifyQueueManager
import com.mediadash.android.domain.model.ConnectionStatusResponse
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

    @Inject
    lateinit var spotifyConnectionChecker: SpotifyConnectionChecker

    @Inject
    lateinit var spotifyQueueManager: SpotifyQueueManager

    @Inject
    lateinit var spotifyPlaybackController: SpotifyPlaybackController

    @Inject
    lateinit var spotifyLibraryManager: SpotifyLibraryManager

    @Inject
    lateinit var libraryAlbumArtManager: LibraryAlbumArtManager

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
                    PlaybackCommand.ACTION_CHECK_CONNECTION -> {
                        val serviceName = command.service ?: ""
                        Log.i("CONNECTIONS", "═══════════════════════════════════════════════════════")
                        Log.i("CONNECTIONS", "🔌 CHECK: Connection status")
                        Log.i("CONNECTIONS", "   Service: $serviceName")
                        Log.i("CONNECTIONS", "   Source: golang_ble_client via BLE")
                        handleCheckConnectionRequest(serviceName)
                    }
                    PlaybackCommand.ACTION_CHECK_ALL_CONNECTIONS -> {
                        Log.i("CONNECTIONS", "═══════════════════════════════════════════════════════")
                        Log.i("CONNECTIONS", "🔌 CHECK: All connections status")
                        Log.i("CONNECTIONS", "   Source: golang_ble_client via BLE")
                        handleCheckAllConnectionsRequest()
                    }
                    PlaybackCommand.ACTION_REQUEST_QUEUE -> {
                        Log.i("QUEUE", "═══════════════════════════════════════════════════════")
                        Log.i("QUEUE", "📋 REQUEST: Playback queue")
                        Log.i("QUEUE", "   Source: golang_ble_client via BLE")
                        handleQueueRequest()
                    }
                    PlaybackCommand.ACTION_QUEUE_SHIFT -> {
                        val queueIndex = command.queueIndex
                        Log.i("QUEUE", "═══════════════════════════════════════════════════════")
                        Log.i("QUEUE", "⏭️ QUEUE SHIFT: Skip to position")
                        Log.i("QUEUE", "   Index: $queueIndex")
                        Log.i("QUEUE", "   Source: golang_ble_client via BLE")
                        handleQueueShift(queueIndex)
                    }
                    // Spotify playback controls (shuffle, repeat, like/unlike)
                    PlaybackCommand.ACTION_SHUFFLE_ON -> {
                        Log.i("SPOTIFY", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY", "🔀 SHUFFLE: Enable")
                        handleSpotifyShuffle(true)
                    }
                    PlaybackCommand.ACTION_SHUFFLE_OFF -> {
                        Log.i("SPOTIFY", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY", "🔀 SHUFFLE: Disable")
                        handleSpotifyShuffle(false)
                    }
                    PlaybackCommand.ACTION_REPEAT_OFF -> {
                        Log.i("SPOTIFY", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY", "🔁 REPEAT: Off")
                        handleSpotifyRepeat("off")
                    }
                    PlaybackCommand.ACTION_REPEAT_TRACK -> {
                        Log.i("SPOTIFY", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY", "🔁 REPEAT: Track")
                        handleSpotifyRepeat("track")
                    }
                    PlaybackCommand.ACTION_REPEAT_CONTEXT -> {
                        Log.i("SPOTIFY", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY", "🔁 REPEAT: Context")
                        handleSpotifyRepeat("context")
                    }
                    PlaybackCommand.ACTION_LIKE_TRACK -> {
                        val trackId = command.trackId
                        Log.i("SPOTIFY", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY", "❤️ LIKE: Track")
                        Log.i("SPOTIFY", "   Track ID: ${trackId ?: "(current)"}")
                        handleSpotifyLikeTrack(trackId)
                    }
                    PlaybackCommand.ACTION_UNLIKE_TRACK -> {
                        val trackId = command.trackId
                        Log.i("SPOTIFY", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY", "💔 UNLIKE: Track")
                        Log.i("SPOTIFY", "   Track ID: ${trackId ?: "(current)"}")
                        handleSpotifyUnlikeTrack(trackId)
                    }
                    PlaybackCommand.ACTION_REQUEST_SPOTIFY_STATE -> {
                        Log.i("SPOTIFY", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY", "📊 REQUEST: Spotify playback state")
                        handleSpotifyStateRequest()
                    }
                    // Spotify library browsing
                    PlaybackCommand.ACTION_REQUEST_LIBRARY_OVERVIEW -> {
                        Log.i("SPOTIFY_LIB", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY_LIB", "📊 REQUEST: Library overview")
                        handleLibraryOverviewRequest()
                    }
                    PlaybackCommand.ACTION_REQUEST_LIBRARY_RECENT -> {
                        val limit = if (command.limit > 0) command.limit else 20
                        Log.i("SPOTIFY_LIB", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY_LIB", "🕐 REQUEST: Recent tracks (limit=$limit)")
                        handleLibraryRecentRequest(limit)
                    }
                    PlaybackCommand.ACTION_REQUEST_LIBRARY_LIKED -> {
                        val offset = command.offset
                        val limit = if (command.limit > 0) command.limit else 20
                        Log.i("SPOTIFY_LIB", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY_LIB", "❤️ REQUEST: Liked tracks (offset=$offset, limit=$limit)")
                        handleLibraryLikedRequest(offset, limit)
                    }
                    PlaybackCommand.ACTION_REQUEST_LIBRARY_ALBUMS -> {
                        val offset = command.offset
                        val limit = if (command.limit > 0) command.limit else 20
                        Log.i("SPOTIFY_LIB", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY_LIB", "💿 REQUEST: Saved albums (offset=$offset, limit=$limit)")
                        handleLibraryAlbumsRequest(offset, limit)
                    }
                    PlaybackCommand.ACTION_REQUEST_LIBRARY_PLAYLISTS -> {
                        val offset = command.offset
                        val limit = if (command.limit > 0) command.limit else 20
                        Log.i("SPOTIFY_LIB", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY_LIB", "📋 REQUEST: Playlists (offset=$offset, limit=$limit)")
                        handleLibraryPlaylistsRequest(offset, limit)
                    }
                    PlaybackCommand.ACTION_REQUEST_LIBRARY_ARTISTS -> {
                        val limit = if (command.limit > 0) command.limit else 20
                        val afterCursor = command.after
                        Log.i("SPOTIFY_LIB", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY_LIB", "👤 REQUEST: Followed artists (limit=$limit, after=$afterCursor)")
                        handleLibraryArtistsRequest(limit, afterCursor)
                    }
                    PlaybackCommand.ACTION_PLAY_SPOTIFY_URI -> {
                        val uri = command.uri ?: ""
                        Log.i("SPOTIFY_LIB", "═══════════════════════════════════════════════════════")
                        Log.i("SPOTIFY_LIB", "▶️ PLAY: URI $uri")
                        handlePlaySpotifyUri(uri)
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

    private fun handleCheckConnectionRequest(serviceName: String) {
        serviceScope.launch {
            try {
                if (serviceName.isEmpty()) {
                    Log.w("CONNECTIONS", "⚠️ Empty service name in check request")
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                val services = mutableMapOf<String, String>()

                when (serviceName.lowercase()) {
                    ConnectionStatusResponse.SERVICE_SPOTIFY -> {
                        val status = spotifyConnectionChecker.getConnectionStatus()
                        services[ConnectionStatusResponse.SERVICE_SPOTIFY] = status
                        Log.i("CONNECTIONS", "   Spotify status: $status")
                    }
                    else -> {
                        Log.w("CONNECTIONS", "⚠️ Unknown service: $serviceName")
                        services[serviceName] = "error:unknown_service"
                    }
                }

                val response = ConnectionStatusResponse(
                    services = services,
                    timestamp = System.currentTimeMillis()
                )

                val elapsed = System.currentTimeMillis() - startTime
                Log.i("CONNECTIONS", "📤 RESPONSE: Connection status")
                Log.i("CONNECTIONS", "   Services checked: ${services.size}")
                Log.i("CONNECTIONS", "   Processing time: ${elapsed}ms")

                gattServerManager.notifyConnectionStatus(response)
                Log.i("CONNECTIONS", "✅ Connection status transmitted via BLE")
            } catch (e: Exception) {
                Log.e("CONNECTIONS", "❌ Error checking connection", e)
            }
        }
    }

    private fun handleCheckAllConnectionsRequest() {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val services = mutableMapOf<String, String>()

                // Check Spotify
                val spotifyStatus = spotifyConnectionChecker.getConnectionStatus()
                services[ConnectionStatusResponse.SERVICE_SPOTIFY] = spotifyStatus
                Log.i("CONNECTIONS", "   Spotify status: $spotifyStatus")

                // Add more services here as they are implemented
                // services[ConnectionStatusResponse.SERVICE_YOUTUBE] = youtubeChecker.getConnectionStatus()
                // etc.

                val response = ConnectionStatusResponse(
                    services = services,
                    timestamp = System.currentTimeMillis()
                )

                val elapsed = System.currentTimeMillis() - startTime
                Log.i("CONNECTIONS", "📤 RESPONSE: All connections status")
                Log.i("CONNECTIONS", "   Services checked: ${services.size}")
                services.forEach { (service, status) ->
                    Log.i("CONNECTIONS", "   - $service: $status")
                }
                Log.i("CONNECTIONS", "   Processing time: ${elapsed}ms")

                gattServerManager.notifyConnectionStatus(response)
                Log.i("CONNECTIONS", "✅ All connections status transmitted via BLE")
            } catch (e: Exception) {
                Log.e("CONNECTIONS", "❌ Error checking all connections", e)
            }
        }
    }

    private fun handleQueueRequest() {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                Log.i("QUEUE", "   Starting queue request handling...")

                // Check if Spotify is connected
                if (!spotifyQueueManager.isConnected()) {
                    Log.w("QUEUE", "⚠️ Spotify not connected, sending empty queue response")
                    // Send empty queue response so client knows we received the request
                    val emptyResponse = com.mediadash.android.domain.model.QueueResponse(
                        service = "spotify",
                        tracks = emptyList(),
                        currentlyPlaying = null,
                        timestamp = System.currentTimeMillis()
                    )
                    gattServerManager.notifyQueue(emptyResponse)
                    Log.i("QUEUE", "✅ Empty queue response sent (Spotify not connected)")
                    return@launch
                }

                Log.i("QUEUE", "   Spotify is connected, fetching queue...")
                val response = spotifyQueueManager.fetchQueue()

                if (response != null) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i("QUEUE", "📤 RESPONSE: Playback queue")
                    Log.i("QUEUE", "   Service: ${response.service}")
                    Log.i("QUEUE", "   Currently playing: ${response.currentlyPlaying?.title ?: "(none)"}")
                    Log.i("QUEUE", "   Queue tracks: ${response.tracks.size}")
                    response.tracks.take(3).forEach { track ->
                        Log.d("QUEUE", "   - ${track.title} - ${track.artist}")
                    }
                    if (response.tracks.size > 3) {
                        Log.d("QUEUE", "   ... and ${response.tracks.size - 3} more")
                    }
                    Log.i("QUEUE", "   Processing time: ${elapsed}ms")

                    gattServerManager.notifyQueue(response)
                    Log.i("QUEUE", "✅ Queue transmitted via BLE")
                } else {
                    Log.w("QUEUE", "⚠️ Failed to fetch queue from Spotify API")
                    // Send empty response so client knows we tried
                    val emptyResponse = com.mediadash.android.domain.model.QueueResponse(
                        service = "spotify",
                        tracks = emptyList(),
                        currentlyPlaying = null,
                        timestamp = System.currentTimeMillis()
                    )
                    gattServerManager.notifyQueue(emptyResponse)
                    Log.i("QUEUE", "✅ Empty queue response sent (API fetch failed)")
                }
            } catch (e: Exception) {
                Log.e("QUEUE", "❌ Error handling queue request", e)
            }
        }
    }

    private fun handleQueueShift(queueIndex: Int) {
        serviceScope.launch {
            try {
                if (queueIndex < 0) {
                    Log.w("QUEUE", "⚠️ Invalid queue index: $queueIndex")
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                var success = false

                // Try local skip first (faster, no network required)
                val mediaController = mediaControllerManager.getActiveController()
                if (mediaController != null) {
                    Log.i("QUEUE", "🎵 Using local transport controls for skip")

                    // Use callback to suppress media updates during intermediate skips
                    // This prevents sending track info for tracks we're skipping past
                    success = spotifyQueueManager.skipToPositionLocal(
                        queueIndex,
                        mediaController
                    ) { skipNumber, totalSkips, isFinalSkip ->
                        if (!isFinalSkip) {
                            // Suppress updates for intermediate skips
                            mediaControllerManager.setSuppressMediaUpdates(true)
                            Log.d("QUEUE", "   Suppressing media updates for skip $skipNumber/$totalSkips")
                        } else {
                            // Enable updates for the final skip so we get the actual track info
                            mediaControllerManager.setSuppressMediaUpdates(false)
                            Log.d("QUEUE", "   Enabling media updates for final skip $skipNumber/$totalSkips")
                        }
                    }

                    // Ensure updates are re-enabled even if skip failed partway through
                    mediaControllerManager.setSuppressMediaUpdates(false)
                }

                // Fallback to Spotify API if local skip failed or no controller
                if (!success && spotifyQueueManager.isConnected()) {
                    Log.i("QUEUE", "🌐 Falling back to Spotify API for skip")
                    // For API skips, we can't easily suppress updates since they're async
                    // The API is slower anyway, so less of an issue
                    success = spotifyQueueManager.skipToPositionApi(queueIndex)
                }

                val elapsed = System.currentTimeMillis() - startTime

                if (success) {
                    Log.i("QUEUE", "✅ Skipped to queue position $queueIndex in ${elapsed}ms")

                    // Wait a moment for playback to update, then fetch and send new queue
                    kotlinx.coroutines.delay(500)
                    fetchAndSendQueueUpdate()
                } else {
                    Log.w("QUEUE", "⚠️ Failed to skip to queue position $queueIndex")
                }
            } catch (e: Exception) {
                // Ensure updates are re-enabled on error
                mediaControllerManager.setSuppressMediaUpdates(false)
                Log.e("QUEUE", "❌ Error handling queue shift", e)
            }
        }
    }

    /**
     * Fetches the current queue from Spotify and sends it via BLE automatically.
     * This is called after queue operations to keep the client in sync.
     */
    private suspend fun fetchAndSendQueueUpdate() {
        try {
            if (!spotifyQueueManager.isConnected()) {
                Log.w("QUEUE", "⚠️ Cannot fetch queue update: Spotify not connected")
                return
            }

            Log.i("QUEUE", "🔄 Fetching queue update after skip...")
            val queueResponse = spotifyQueueManager.fetchQueue()

            if (queueResponse != null) {
                Log.i("QUEUE", "📤 Sending queue update via BLE (${queueResponse.tracks.size} tracks)")
                gattServerManager.notifyQueue(queueResponse)
                Log.i("QUEUE", "✅ Queue update sent successfully")
            } else {
                Log.w("QUEUE", "⚠️ Failed to fetch queue update")
            }
        } catch (e: Exception) {
            Log.e("QUEUE", "❌ Error fetching/sending queue update", e)
        }
    }

    // ============================================================================
    // Spotify Playback Controls (shuffle, repeat, like/unlike)
    // ============================================================================

    private fun handleSpotifyShuffle(enabled: Boolean) {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyPlaybackController.isConnected()) {
                    Log.w("SPOTIFY", "⚠️ Spotify not connected, cannot set shuffle")
                    return@launch
                }

                val success = spotifyPlaybackController.setShuffle(enabled)
                val elapsed = System.currentTimeMillis() - startTime

                if (success) {
                    Log.i("SPOTIFY", "✅ Shuffle set to $enabled in ${elapsed}ms")
                    // Send updated state via BLE
                    sendSpotifyPlaybackState()
                } else {
                    Log.w("SPOTIFY", "⚠️ Failed to set shuffle")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY", "❌ Error setting shuffle", e)
            }
        }
    }

    private fun handleSpotifyRepeat(mode: String) {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyPlaybackController.isConnected()) {
                    Log.w("SPOTIFY", "⚠️ Spotify not connected, cannot set repeat")
                    return@launch
                }

                val success = spotifyPlaybackController.setRepeat(mode)
                val elapsed = System.currentTimeMillis() - startTime

                if (success) {
                    Log.i("SPOTIFY", "✅ Repeat set to $mode in ${elapsed}ms")
                    // Send updated state via BLE
                    sendSpotifyPlaybackState()
                } else {
                    Log.w("SPOTIFY", "⚠️ Failed to set repeat")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY", "❌ Error setting repeat", e)
            }
        }
    }

    private fun handleSpotifyLikeTrack(trackId: String?) {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyPlaybackController.isConnected()) {
                    Log.w("SPOTIFY", "⚠️ Spotify not connected, cannot like track")
                    return@launch
                }

                val success = spotifyPlaybackController.saveTrack(trackId)
                val elapsed = System.currentTimeMillis() - startTime

                if (success) {
                    Log.i("SPOTIFY", "✅ Track liked in ${elapsed}ms")
                    // Send updated state via BLE
                    sendSpotifyPlaybackState()
                } else {
                    Log.w("SPOTIFY", "⚠️ Failed to like track")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY", "❌ Error liking track", e)
            }
        }
    }

    private fun handleSpotifyUnlikeTrack(trackId: String?) {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyPlaybackController.isConnected()) {
                    Log.w("SPOTIFY", "⚠️ Spotify not connected, cannot unlike track")
                    return@launch
                }

                val success = spotifyPlaybackController.removeTrack(trackId)
                val elapsed = System.currentTimeMillis() - startTime

                if (success) {
                    Log.i("SPOTIFY", "✅ Track unliked in ${elapsed}ms")
                    // Send updated state via BLE
                    sendSpotifyPlaybackState()
                } else {
                    Log.w("SPOTIFY", "⚠️ Failed to unlike track")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY", "❌ Error unliking track", e)
            }
        }
    }

    private fun handleSpotifyStateRequest() {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyPlaybackController.isConnected()) {
                    Log.w("SPOTIFY", "⚠️ Spotify not connected")
                    return@launch
                }

                val state = spotifyPlaybackController.fetchPlaybackState()
                val elapsed = System.currentTimeMillis() - startTime

                if (state != null) {
                    Log.i("SPOTIFY", "📤 RESPONSE: Spotify playback state")
                    Log.i("SPOTIFY", "   Shuffle: ${state.shuffleEnabled}")
                    Log.i("SPOTIFY", "   Repeat: ${state.repeatMode}")
                    Log.i("SPOTIFY", "   Track ID: ${state.currentTrackId ?: "(none)"}")
                    Log.i("SPOTIFY", "   Processing time: ${elapsed}ms")

                    // Check if current track is liked
                    val isLiked = if (!state.currentTrackId.isNullOrBlank()) {
                        spotifyPlaybackController.isTrackSaved(state.currentTrackId)
                    } else {
                        false
                    }

                    val stateWithLiked = state.copy(isTrackLiked = isLiked)
                    gattServerManager.notifySpotifyPlaybackState(stateWithLiked)
                    Log.i("SPOTIFY", "✅ Spotify playback state transmitted via BLE")
                } else {
                    Log.w("SPOTIFY", "⚠️ Failed to fetch playback state (no active session)")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY", "❌ Error fetching playback state", e)
            }
        }
    }

    /**
     * Helper to send current Spotify playback state via BLE after a change.
     */
    private suspend fun sendSpotifyPlaybackState() {
        try {
            val state = spotifyPlaybackController.fetchPlaybackState()
            if (state != null) {
                // Check if current track is liked
                val isLiked = if (!state.currentTrackId.isNullOrBlank()) {
                    spotifyPlaybackController.isTrackSaved(state.currentTrackId)
                } else {
                    false
                }

                val stateWithLiked = state.copy(isTrackLiked = isLiked)
                gattServerManager.notifySpotifyPlaybackState(stateWithLiked)
                Log.d("SPOTIFY", "📤 Sent updated playback state via BLE")
            }
        } catch (e: Exception) {
            Log.e("SPOTIFY", "❌ Error sending playback state", e)
        }
    }

    // ============================================================================
    // Spotify Library Browsing Handlers
    // ============================================================================

    private fun handleLibraryOverviewRequest() {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyLibraryManager.isConnected()) {
                    Log.w("SPOTIFY_LIB", "⚠️ Spotify not connected")
                    return@launch
                }

                val overview = spotifyLibraryManager.fetchOverview()
                val elapsed = System.currentTimeMillis() - startTime

                if (overview != null) {
                    Log.i("SPOTIFY_LIB", "📤 RESPONSE: Library overview")
                    Log.i("SPOTIFY_LIB", "   User: ${overview.userName}")
                    Log.i("SPOTIFY_LIB", "   Liked: ${overview.likedCount}, Albums: ${overview.albumsCount}")
                    Log.i("SPOTIFY_LIB", "   Playlists: ${overview.playlistsCount}, Artists: ${overview.artistsCount}")
                    Log.i("SPOTIFY_LIB", "   Processing time: ${elapsed}ms")

                    gattServerManager.notifySpotifyLibraryOverview(overview)
                    Log.i("SPOTIFY_LIB", "✅ Library overview transmitted via BLE")
                } else {
                    Log.w("SPOTIFY_LIB", "⚠️ Failed to fetch library overview")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY_LIB", "❌ Error fetching library overview", e)
            }
        }
    }

    private fun handleLibraryRecentRequest(limit: Int) {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyLibraryManager.isConnected()) {
                    Log.w("SPOTIFY_LIB", "⚠️ Spotify not connected")
                    return@launch
                }

                val response = spotifyLibraryManager.fetchRecentTracks(limit)
                val elapsed = System.currentTimeMillis() - startTime

                if (response != null) {
                    Log.i("SPOTIFY_LIB", "📤 RESPONSE: Recent tracks")
                    Log.i("SPOTIFY_LIB", "   Tracks: ${response.items.size}")
                    Log.i("SPOTIFY_LIB", "   Processing time: ${elapsed}ms")

                    gattServerManager.notifySpotifyTrackList(response)
                    Log.i("SPOTIFY_LIB", "✅ Recent tracks transmitted via BLE")
                } else {
                    Log.w("SPOTIFY_LIB", "⚠️ Failed to fetch recent tracks")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY_LIB", "❌ Error fetching recent tracks", e)
            }
        }
    }

    private fun handleLibraryLikedRequest(offset: Int, limit: Int) {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyLibraryManager.isConnected()) {
                    Log.w("SPOTIFY_LIB", "⚠️ Spotify not connected")
                    return@launch
                }

                val response = spotifyLibraryManager.fetchLikedTracks(offset, limit)
                val elapsed = System.currentTimeMillis() - startTime

                if (response != null) {
                    Log.i("SPOTIFY_LIB", "📤 RESPONSE: Liked tracks")
                    Log.i("SPOTIFY_LIB", "   Tracks: ${response.items.size} (total: ${response.total})")
                    Log.i("SPOTIFY_LIB", "   Processing time: ${elapsed}ms")

                    gattServerManager.notifySpotifyTrackList(response)
                    Log.i("SPOTIFY_LIB", "✅ Liked tracks transmitted via BLE")
                } else {
                    Log.w("SPOTIFY_LIB", "⚠️ Failed to fetch liked tracks")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY_LIB", "❌ Error fetching liked tracks", e)
            }
        }
    }

    private fun handleLibraryAlbumsRequest(offset: Int, limit: Int) {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyLibraryManager.isConnected()) {
                    Log.w("SPOTIFY_LIB", "⚠️ Spotify not connected")
                    return@launch
                }

                val response = spotifyLibraryManager.fetchSavedAlbums(offset, limit)
                val elapsed = System.currentTimeMillis() - startTime

                if (response != null) {
                    Log.i("SPOTIFY_LIB", "📤 RESPONSE: Saved albums")
                    Log.i("SPOTIFY_LIB", "   Albums: ${response.items.size} (total: ${response.total})")
                    Log.i("SPOTIFY_LIB", "   Processing time: ${elapsed}ms")

                    // Register albums with the library art manager for on-demand fetching
                    // Each album has an artHash that the CarThing can use to request art
                    libraryAlbumArtManager.registerAlbums(response.items)
                    Log.i("SPOTIFY_LIB", "   📷 Registered ${response.items.size} albums for art requests")

                    gattServerManager.notifySpotifyAlbumList(response)
                    Log.i("SPOTIFY_LIB", "✅ Saved albums transmitted via BLE")

                    // Pre-fetch album art for the first few visible albums in background
                    serviceScope.launch(Dispatchers.IO) {
                        libraryAlbumArtManager.prefetchAlbumArt(response.items.take(5))
                    }
                } else {
                    Log.w("SPOTIFY_LIB", "⚠️ Failed to fetch saved albums")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY_LIB", "❌ Error fetching saved albums", e)
            }
        }
    }

    private fun handleLibraryPlaylistsRequest(offset: Int, limit: Int) {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyLibraryManager.isConnected()) {
                    Log.w("SPOTIFY_LIB", "⚠️ Spotify not connected")
                    return@launch
                }

                val response = spotifyLibraryManager.fetchPlaylists(offset, limit)
                val elapsed = System.currentTimeMillis() - startTime

                if (response != null) {
                    Log.i("SPOTIFY_LIB", "📤 RESPONSE: Playlists")
                    Log.i("SPOTIFY_LIB", "   Playlists: ${response.items.size} (total: ${response.total})")
                    Log.i("SPOTIFY_LIB", "   Processing time: ${elapsed}ms")

                    gattServerManager.notifySpotifyPlaylistList(response)
                    Log.i("SPOTIFY_LIB", "✅ Playlists transmitted via BLE")
                } else {
                    Log.w("SPOTIFY_LIB", "⚠️ Failed to fetch playlists")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY_LIB", "❌ Error fetching playlists", e)
            }
        }
    }

    private fun handleLibraryArtistsRequest(limit: Int, afterCursor: String?) {
        serviceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                if (!spotifyLibraryManager.isConnected()) {
                    Log.w("SPOTIFY_LIB", "⚠️ Spotify not connected")
                    return@launch
                }

                val response = spotifyLibraryManager.fetchFollowedArtists(limit, afterCursor)
                val elapsed = System.currentTimeMillis() - startTime

                if (response != null) {
                    Log.i("SPOTIFY_LIB", "📤 RESPONSE: Followed artists")
                    Log.i("SPOTIFY_LIB", "   Artists: ${response.items.size} (total: ${response.total})")
                    Log.i("SPOTIFY_LIB", "   Has more: ${response.hasMore}, next cursor: ${response.nextCursor}")
                    Log.i("SPOTIFY_LIB", "   Processing time: ${elapsed}ms")

                    gattServerManager.notifySpotifyArtistList(response)
                    Log.i("SPOTIFY_LIB", "✅ Artists transmitted via BLE")
                } else {
                    Log.w("SPOTIFY_LIB", "⚠️ Failed to fetch followed artists")
                }
            } catch (e: Exception) {
                Log.e("SPOTIFY_LIB", "❌ Error fetching followed artists", e)
            }
        }
    }

    private fun handlePlaySpotifyUri(uri: String) {
        serviceScope.launch {
            try {
                Log.i("SPOTIFY_LIB", "═══════════════════════════════════════════════════════")
                Log.i("SPOTIFY_LIB", "▶️ PLAY URI REQUEST: $uri")

                if (uri.isBlank()) {
                    Log.w("SPOTIFY_LIB", "⚠️ Empty URI - ignoring request")
                    return@launch
                }

                Log.d("SPOTIFY_LIB", "Checking Spotify connection (will auto-refresh if needed)...")
                val isConnected = spotifyLibraryManager.isConnected()

                if (!isConnected) {
                    Log.w("SPOTIFY_LIB", "⚠️ Spotify not connected - no valid token or refresh failed")
                    Log.w("SPOTIFY_LIB", "   Please open the Spotify page in the app to authenticate")
                    return@launch
                }

                Log.d("SPOTIFY_LIB", "✓ Spotify connected, sending play command...")
                val success = spotifyLibraryManager.playUri(uri)

                if (success) {
                    Log.i("SPOTIFY_LIB", "✅ SUCCESS: Now playing URI: $uri")
                } else {
                    Log.w("SPOTIFY_LIB", "⚠️ Play command failed (API returned error)")
                }
                Log.i("SPOTIFY_LIB", "═══════════════════════════════════════════════════════")
            } catch (e: Exception) {
                Log.e("SPOTIFY_LIB", "❌ Exception playing URI: ${e.message}", e)
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

                    // Case 3: Request doesn't match expected hash - check library art cache
                    else -> {
                        Log.i("ALBUMART", "   📚 Hash doesn't match current track, checking library art cache...")
                        Log.d("ALBUMART", "      Requested: ${request.hash}")
                        Log.d("ALBUMART", "      Expected:  $expectedHash")

                        // Try to get from library album art cache (for saved albums browsing)
                        val libraryChunks = libraryAlbumArtManager.getCachedChunks(request.hash)
                        if (libraryChunks != null) {
                            Log.i("ALBUMART", "   ✅ Found in library cache! (${libraryChunks.size} chunks)")
                            gattServerManager.transmitAlbumArt(libraryChunks)
                        } else {
                            // Try to fetch on-demand from registered album URLs
                            Log.i("ALBUMART", "   🔄 Not in cache, attempting on-demand fetch...")
                            val fetchedChunks = libraryAlbumArtManager.fetchOnDemand(request.hash)
                            if (fetchedChunks != null) {
                                Log.i("ALBUMART", "   ✅ Fetched on-demand! (${fetchedChunks.size} chunks)")
                                gattServerManager.transmitAlbumArt(fetchedChunks)
                            } else {
                                Log.w("ALBUMART", "   ⚠️ No art available for hash: ${request.hash}")
                            }
                        }
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
