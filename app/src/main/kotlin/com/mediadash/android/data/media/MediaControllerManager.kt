package com.mediadash.android.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log
import com.mediadash.android.ble.BleConstants
import com.mediadash.android.di.ApplicationScope
import com.mediadash.android.di.IoDispatcher
import com.mediadash.android.domain.model.AlbumArtChunk
import com.mediadash.android.domain.model.MediaState
import com.mediadash.android.util.BitmapUtil
import com.mediadash.android.util.CRC32Util
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the active MediaController and tracks playback state changes.
 * Provides a reactive StateFlow of the current media state.
 * Integrates with PlaybackSourceTracker to track external app playback.
 */
@Singleton
class MediaControllerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioManager: AudioManager,
    private val albumArtFetcher: AlbumArtFetcher,
    private val albumArtCache: AlbumArtCache,
    private val playbackSourceTracker: PlaybackSourceTracker,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "MediaControllerManager"
    }

    private var activeController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null

    private val _currentMediaState = MutableStateFlow<MediaState?>(null)
    val currentMediaState: StateFlow<MediaState?> = _currentMediaState.asStateFlow()

    private val _currentAlbumArtChunks = MutableStateFlow<List<AlbumArtChunk>?>(null)
    val currentAlbumArtChunks: StateFlow<List<AlbumArtChunk>?> = _currentAlbumArtChunks.asStateFlow()

    private val _currentAlbumArtBitmap = MutableStateFlow<Bitmap?>(null)
    val currentAlbumArtBitmap: StateFlow<Bitmap?> = _currentAlbumArtBitmap.asStateFlow()

    // Track which app is being controlled (e.g., "Spotify", "YouTube Music")
    private val _controlledAppName = MutableStateFlow<String?>(null)
    val controlledAppName: StateFlow<String?> = _controlledAppName.asStateFlow()

    private var lastAlbumArtHash: String? = null
    private var isPodcastActive = false

    /**
     * Sets the active media controller and registers callbacks.
     * This is called when an external app (Spotify, YouTube Music, etc.) becomes active.
     *
     * @param controller The MediaController for the active media session
     * @param appName The human-readable app name (e.g., "Spotify", "YouTube Music")
     */
    fun setActiveController(controller: MediaController, appName: String) {
        // Mark external app as active source
        isPodcastActive = false
        _controlledAppName.value = appName
        // Unregister from previous controller
        controllerCallback?.let { callback ->
            activeController?.unregisterCallback(callback)
        }

        activeController = controller

        // Track that an external app is now the active source
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        playbackSourceTracker.onExternalAppStarted(controller.packageName, title)

        // Create and register callback
        controllerCallback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                Log.d(TAG, "Playback state changed: ${state?.state}")

                // Track external app playback state changes
                val isPlaying = state?.state == PlaybackState.STATE_PLAYING
                val currentTitle = activeController?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

                // Update the playing state in tracker
                playbackSourceTracker.onExternalAppPlayingChanged(isPlaying, controller.packageName, currentTitle)

                when (state?.state) {
                    PlaybackState.STATE_PLAYING -> {
                        // External app started playing
                        Log.d(TAG, "External app playing: ${controller.packageName} - $currentTitle")
                    }
                    PlaybackState.STATE_PAUSED -> {
                        // Track pause with position (but don't change active source)
                        Log.d(TAG, "External app paused at ${state.position}ms")
                        playbackSourceTracker.onPaused(state.position)
                    }
                    PlaybackState.STATE_STOPPED -> {
                        Log.d(TAG, "External app stopped")
                        playbackSourceTracker.onStopped()
                    }
                    else -> {}
                }

                updateMediaState()
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                Log.d(TAG, "Metadata changed")

                // Update external app title in tracker
                val newTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                playbackSourceTracker.updateExternalAppTitle(controller.packageName, newTitle)

                // Process album art FIRST to ensure chunks are ready before media state is sent
                // This prevents race condition where Go client requests hash before chunks are prepared
                scope.launch {
                    processAlbumArtAndUpdateState(metadata)
                }
            }
        }

        controller.registerCallback(controllerCallback!!)

        // Get initial state - process album art first, then update state
        scope.launch {
            processAlbumArtAndUpdateState(controller.metadata)
        }

        Log.d(TAG, "Active controller set: ${controller.packageName}")
    }

    /**
     * Clears the active controller.
     */
    fun clearActiveController() {
        controllerCallback?.let { callback ->
            activeController?.unregisterCallback(callback)
        }
        activeController = null
        controllerCallback = null
        _currentMediaState.value = null
        _currentAlbumArtChunks.value = null
        _currentAlbumArtBitmap.value = null
        _controlledAppName.value = null
        lastAlbumArtHash = null

        Log.d(TAG, "Active controller cleared")
    }

    /**
     * Sets the media state from podcast playback.
     * This allows podcast playback to share its state and artwork via BLE,
     * just like external apps (Spotify, YouTube Music, etc.).
     *
     * @param isPlaying Whether the podcast is currently playing
     * @param episodeTitle The title of the current episode
     * @param podcastTitle The title of the podcast (used as "artist")
     * @param artworkUrl The URL of the podcast/episode artwork
     * @param duration Duration in milliseconds
     * @param position Current position in milliseconds
     */
    fun setPodcastState(
        isPlaying: Boolean,
        episodeTitle: String,
        podcastTitle: String,
        artworkUrl: String?,
        duration: Long,
        position: Long
    ) {
        isPodcastActive = true
        Log.d(TAG, "Setting podcast state: $podcastTitle - $episodeTitle (playing: $isPlaying)")

        // Generate album art hash from podcast title + episode title
        // This matches the pattern used for music (artist + album)
        val albumArtHash = if (podcastTitle.isNotEmpty() && episodeTitle.isNotEmpty()) {
            CRC32Util.generateAlbumArtHash(podcastTitle, episodeTitle)
        } else null

        val playbackStateString = if (isPlaying) "playing" else "paused"

        val state = MediaState(
            isPlaying = isPlaying,
            playbackState = playbackStateString,
            trackTitle = episodeTitle,
            artist = podcastTitle,
            album = episodeTitle,  // Use episode title as album for hash consistency
            duration = duration,
            position = position,
            volume = getCurrentVolume(),
            albumArtHash = albumArtHash,
            mediaChannel = "Podcasts"  // Podcast playback is handled internally
        )

        _currentMediaState.value = state
        Log.d(TAG, "Podcast media state updated: $episodeTitle - $podcastTitle [Podcasts] (hash: $albumArtHash)")

        // Process artwork if URL is available and hash is new
        if (albumArtHash != null && albumArtHash != lastAlbumArtHash && !artworkUrl.isNullOrEmpty()) {
            lastAlbumArtHash = albumArtHash
            scope.launch {
                processPodcastArtwork(artworkUrl, albumArtHash)
            }
        }
    }

    /**
     * Processes podcast artwork from URL and prepares chunks for BLE transmission.
     */
    private suspend fun processPodcastArtwork(artworkUrl: String, hash: String) = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "Fetching podcast artwork from: $artworkUrl")

            // Check cache first
            val cachedChunks = albumArtCache.get(hash)
            if (cachedChunks != null) {
                Log.d(TAG, "Using cached podcast artwork for hash: $hash")
                _currentAlbumArtChunks.value = cachedChunks
                return@withContext
            }

            // Fetch artwork from URL
            val imageData = albumArtFetcher.fetchFromUrl(artworkUrl)
            if (imageData != null) {
                val hashLong = hash.toLongOrNull() ?: return@withContext
                val chunks = albumArtFetcher.prepareChunks(hashLong, imageData)

                if (chunks.isNotEmpty()) {
                    albumArtCache.put(hash, chunks)
                    _currentAlbumArtChunks.value = chunks
                    Log.d(TAG, "Podcast artwork prepared: ${chunks.size} chunks for hash: $hash")
                }
            } else {
                Log.w(TAG, "Failed to fetch podcast artwork from: $artworkUrl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing podcast artwork", e)
        }
    }

    /**
     * Returns the current active controller for command dispatch.
     */
    fun getActiveController(): MediaController? = activeController

    /**
     * Gets the current volume level (0-100).
     */
    fun getCurrentVolume(): Int {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (maxVolume > 0) (currentVolume * 100 / maxVolume) else 0
    }

    /**
     * Sets the volume level (0-100).
     */
    fun setVolume(level: Int) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = (level.coerceIn(0, 100) * maxVolume / 100)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
    }

    private fun updateMediaState() {
        val controller = activeController ?: return
        val playbackState = controller.playbackState
        val metadata = controller.metadata

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val playbackStateString = when (playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            else -> "stopped"
        }

        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val albumArtHash = if (artist.isNotEmpty() && album.isNotEmpty()) {
            CRC32Util.generateAlbumArtHash(artist, album)
        } else null

        val state = MediaState(
            isPlaying = isPlaying,
            playbackState = playbackStateString,
            trackTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = artist,
            album = album,
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            position = playbackState?.position ?: 0L,
            volume = getCurrentVolume(),
            albumArtHash = albumArtHash,
            mediaChannel = _controlledAppName.value
        )

        _currentMediaState.value = state
        Log.d(TAG, "Media state updated: ${state.trackTitle} - ${state.artist} [${state.mediaChannel}]")
    }

    /**
     * Processes album art and then updates media state.
     * This ensures chunks are ready BEFORE the media state (with hash) is sent to BLE.
     * This prevents the race condition where Go client requests a hash before chunks are prepared.
     */
    private suspend fun processAlbumArtAndUpdateState(metadata: MediaMetadata?) {
        // First, prepare album art chunks
        if (metadata != null) {
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

            val hash = if (artist.isNotEmpty() && album.isNotEmpty()) {
                CRC32Util.generateAlbumArtHash(artist, album)
            } else null

            // Skip chunk processing if same album art
            val skipChunkProcessing = hash != null && hash == lastAlbumArtHash

            if (hash != null) {
                lastAlbumArtHash = hash
            }

            // Fetch album art bitmap and chunks BEFORE updating media state
            val bitmap = fetchAlbumArtBitmap(metadata)
            _currentAlbumArtBitmap.value = bitmap

            if (!skipChunkProcessing && hash != null) {
                // Check cache first
                val cachedChunks = albumArtCache.get(hash)
                if (cachedChunks != null) {
                    Log.d(TAG, "Using cached album art for hash: $hash")
                    _currentAlbumArtChunks.value = cachedChunks
                } else {
                    val chunks = fetchAndPrepareAlbumArt(metadata, hash)
                    if (chunks != null) {
                        albumArtCache.put(hash, chunks)
                        _currentAlbumArtChunks.value = chunks
                        Log.d(TAG, "Album art prepared: ${chunks.size} chunks for hash: $hash")
                    }
                }
            }
        } else {
            _currentAlbumArtBitmap.value = null
        }

        // NOW update media state - chunks are guaranteed to be ready
        updateMediaState()
    }

    private fun processAlbumArt(metadata: MediaMetadata?) {
        if (metadata == null) {
            _currentAlbumArtBitmap.value = null
            return
        }

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        val hash = if (artist.isNotEmpty() && album.isNotEmpty()) {
            CRC32Util.generateAlbumArtHash(artist, album)
        } else null

        // Skip chunk processing if same album art, but always try to get bitmap
        val skipChunkProcessing = hash != null && hash == lastAlbumArtHash

        if (hash != null) {
            lastAlbumArtHash = hash
        }

        // Fetch album art bitmap and chunks
        scope.launch {
            val bitmap = fetchAlbumArtBitmap(metadata)
            _currentAlbumArtBitmap.value = bitmap

            if (!skipChunkProcessing && hash != null) {
                // Check cache first
                val cachedChunks = albumArtCache.get(hash)
                if (cachedChunks != null) {
                    Log.d(TAG, "Using cached album art for hash: $hash")
                    _currentAlbumArtChunks.value = cachedChunks
                } else {
                    val chunks = fetchAndPrepareAlbumArt(metadata, hash)
                    if (chunks != null) {
                        albumArtCache.put(hash, chunks)
                        _currentAlbumArtChunks.value = chunks
                        Log.d(TAG, "Album art prepared: ${chunks.size} chunks for hash: $hash")
                    }
                }
            }
        }
    }

    private suspend fun fetchAlbumArtBitmap(metadata: MediaMetadata): Bitmap? = withContext(ioDispatcher) {
        try {
            // Try direct bitmap first
            var bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

            // Try URI fallback
            if (bitmap == null) {
                val uriString = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                if (uriString != null) {
                    val uri = android.net.Uri.parse(uriString)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                    }
                }
            }

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch album art bitmap", e)
            null
        }
    }

    private suspend fun fetchAndPrepareAlbumArt(
        metadata: MediaMetadata,
        hash: String
    ): List<AlbumArtChunk>? = withContext(ioDispatcher) {
        try {
            val imageData = albumArtFetcher.fetch(metadata)
            if (imageData != null) {
                // Convert hash string to Long for binary protocol
                val hashLong = hash.toLongOrNull() ?: return@withContext null
                albumArtFetcher.prepareChunks(hashLong, imageData)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch/prepare album art", e)
            null
        }
    }
}
