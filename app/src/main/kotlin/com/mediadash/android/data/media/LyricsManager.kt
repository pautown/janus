package com.mediadash.android.data.media

import android.util.Log
import com.mediadash.android.data.local.SettingsManager
import com.mediadash.android.data.remote.LyricsApiService
import com.mediadash.android.domain.model.CompactLyricsLine
import com.mediadash.android.domain.model.CompactLyricsResponse
import com.mediadash.android.domain.model.LyricsLine
import com.mediadash.android.domain.model.LyricsState
import com.mediadash.android.util.CRC32Util
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages lyrics fetching, caching, and BLE transmission.
 */
@Singleton
class LyricsManager @Inject constructor(
    private val lyricsApiService: LyricsApiService,
    private val settingsManager: SettingsManager
) {
    companion object {
        private const val TAG = "LyricsManager"
        private const val MAX_CACHE_SIZE = 50  // Keep up to 50 lyrics in cache
        private const val MAX_LINES_PER_CHUNK = 20
    }

    // In-memory cache of lyrics (hash -> LyricsState)
    private val lyricsCache = LinkedHashMap<String, LyricsState>(MAX_CACHE_SIZE, 0.75f, true)
    private val cacheMutex = Mutex()

    // Currently displayed lyrics
    private val _currentLyrics = MutableStateFlow<LyricsState?>(null)
    val currentLyrics: StateFlow<LyricsState?> = _currentLyrics.asStateFlow()

    // Track currently being fetched to avoid duplicate requests
    private var currentFetchKey: String? = null
    private val fetchMutex = Mutex()

    // Callback for BLE transmission when lyrics are fetched
    private var onLyricsFetchedCallback: (suspend (List<CompactLyricsResponse>) -> Unit)? = null

    /**
     * Sets a callback to be invoked when lyrics are fetched.
     * Use this to transmit lyrics over BLE.
     */
    fun setOnLyricsFetchedCallback(callback: suspend (List<CompactLyricsResponse>) -> Unit) {
        onLyricsFetchedCallback = callback
    }

    /**
     * Fetches lyrics for the given track.
     * Uses cache if available, otherwise fetches from API.
     * Returns null if lyrics are not available or feature is disabled.
     */
    suspend fun fetchLyrics(artist: String, track: String): LyricsState? {
        Log.d("LYRICS", "═══════════════════════════════════════════════")
        Log.d("LYRICS", "📥 FETCH LYRICS REQUEST")
        Log.d("LYRICS", "   Artist: $artist")
        Log.d("LYRICS", "   Track: $track")

        if (!settingsManager.lyricsEnabled.value) {
            Log.d("LYRICS", "   ⚠️ Lyrics feature disabled in settings")
            Log.d(TAG, "Lyrics feature disabled")
            return null
        }

        val hash = generateHash(artist, track)
        Log.d("LYRICS", "   Generated hash: $hash")

        // Check cache first
        val cachedLyrics = cacheMutex.withLock {
            lyricsCache[hash]
        }

        if (cachedLyrics != null) {
            Log.d("LYRICS", "   ✅ CACHE HIT - Lyrics found in cache")
            Log.d("LYRICS", "   Cached lines: ${cachedLyrics.lines.size}, synced: ${cachedLyrics.synced}")
            Log.d(TAG, "Lyrics found in cache for: $artist - $track")
            _currentLyrics.value = cachedLyrics

            // Transmit cached lyrics over BLE if callback is set
            onLyricsFetchedCallback?.let { callback ->
                try {
                    val chunks = lyricsToChunks(cachedLyrics)
                    Log.i("LYRICS", "📤 BLE TRANSMISSION (CACHE HIT) - Sending cached lyrics")
                    Log.i("LYRICS", "   Chunks: ${chunks.size}")
                    Log.i(TAG, "Transmitting ${chunks.size} cached lyrics chunks over BLE")
                    callback(chunks)  // Can call suspend function directly since fetchLyrics is suspend
                    Log.i("LYRICS", "   ✅ BLE transmission callback invoked")
                } catch (e: Exception) {
                    Log.e("LYRICS", "   ❌ BLE TRANSMISSION FAILED: ${e.message}")
                    Log.e(TAG, "Failed to transmit cached lyrics over BLE", e)
                }
            }

            return cachedLyrics
        }
        Log.d("LYRICS", "   CACHE MISS - Fetching from API...")

        // Avoid duplicate fetches
        fetchMutex.withLock {
            if (currentFetchKey == hash) {
                Log.d("LYRICS", "   ⚠️ Duplicate fetch blocked - already fetching this track")
                Log.d(TAG, "Already fetching lyrics for: $artist - $track")
                return null
            }
            currentFetchKey = hash
        }

        try {
            val startTime = System.currentTimeMillis()
            Log.i("LYRICS", "🌐 API FETCH - Calling LRCLIB API")
            Log.i(TAG, "Fetching lyrics for: $artist - $track")
            val lyrics = lyricsApiService.fetchLyrics(artist, track)
            val elapsed = System.currentTimeMillis() - startTime

            if (lyrics != null) {
                Log.i("LYRICS", "   ✅ API SUCCESS")
                Log.i("LYRICS", "   Fetch time: ${elapsed}ms")
                Log.i("LYRICS", "   Lines: ${lyrics.lines.size}")
                Log.i("LYRICS", "   Synced: ${lyrics.synced}")
                Log.i("LYRICS", "   Source: ${lyrics.source}")

                cacheMutex.withLock {
                    // Evict oldest entry if cache is full
                    if (lyricsCache.size >= MAX_CACHE_SIZE) {
                        val oldestKey = lyricsCache.keys.firstOrNull()
                        oldestKey?.let {
                            lyricsCache.remove(it)
                            Log.d("LYRICS", "   📦 CACHE EVICTION - Removed oldest entry: $it")
                        }
                    }
                    lyricsCache[hash] = lyrics
                    Log.d("LYRICS", "   📦 CACHE STORE - Cached with hash: $hash")
                    Log.d("LYRICS", "   Cache size: ${lyricsCache.size}/$MAX_CACHE_SIZE")
                }
                _currentLyrics.value = lyrics
                Log.i(TAG, "Cached lyrics: ${lyrics.lines.size} lines, synced: ${lyrics.synced}")

                // Transmit lyrics over BLE if callback is set
                onLyricsFetchedCallback?.let { callback ->
                    try {
                        val chunks = lyricsToChunks(lyrics)
                        Log.i("LYRICS", "📤 BLE TRANSMISSION - Preparing to send lyrics")
                        Log.i("LYRICS", "   Chunks: ${chunks.size}")
                        Log.i("LYRICS", "   Lines per chunk: $MAX_LINES_PER_CHUNK")
                        Log.i(TAG, "Transmitting ${chunks.size} lyrics chunks over BLE")
                        callback(chunks)
                        Log.i("LYRICS", "   ✅ BLE transmission callback invoked")
                    } catch (e: Exception) {
                        Log.e("LYRICS", "   ❌ BLE TRANSMISSION FAILED: ${e.message}")
                        Log.e(TAG, "Failed to transmit lyrics over BLE", e)
                    }
                }
            } else {
                Log.w("LYRICS", "   ⚠️ API returned no lyrics")
                Log.w("LYRICS", "   Fetch time: ${elapsed}ms")
            }

            return lyrics
        } finally {
            fetchMutex.withLock {
                if (currentFetchKey == hash) {
                    currentFetchKey = null
                }
            }
        }
    }

    /**
     * Gets cached lyrics by hash.
     */
    suspend fun getCachedLyrics(hash: String): LyricsState? {
        Log.d("LYRICS", "📦 CACHE LOOKUP by hash: $hash")
        return cacheMutex.withLock {
            val cached = lyricsCache[hash]
            if (cached != null) {
                Log.d("LYRICS", "   ✅ CACHE HIT - Found ${cached.lines.size} lines")
            } else {
                Log.d("LYRICS", "   ⚠️ CACHE MISS - No lyrics for hash")
            }
            cached
        }
    }

    /**
     * Clears the current lyrics.
     */
    fun clearCurrentLyrics() {
        Log.d("LYRICS", "🧹 CLEAR CURRENT - Clearing displayed lyrics")
        _currentLyrics.value = null
    }

    /**
     * Clears all cached lyrics.
     */
    suspend fun clearCache() {
        Log.d("LYRICS", "🧹 CLEAR CACHE - Removing all cached lyrics")
        cacheMutex.withLock {
            val count = lyricsCache.size
            lyricsCache.clear()
            Log.d("LYRICS", "   Removed $count cached entries")
        }
        _currentLyrics.value = null
    }

    /**
     * Converts lyrics to compact BLE chunks for transmission.
     * Each chunk contains up to MAX_LINES_PER_CHUNK lines.
     */
    fun lyricsToChunks(lyrics: LyricsState): List<CompactLyricsResponse> {
        Log.d("LYRICS", "📦 CHUNKING - Converting lyrics to BLE chunks")
        Log.d("LYRICS", "   Total lines: ${lyrics.lines.size}")
        Log.d("LYRICS", "   Lines per chunk: $MAX_LINES_PER_CHUNK")

        val lineChunks = lyrics.lines.chunked(MAX_LINES_PER_CHUNK)
        val totalChunks = lineChunks.size

        Log.d("LYRICS", "   Total chunks: $totalChunks")

        return lineChunks.mapIndexed { index, lines ->
            CompactLyricsResponse(
                h = lyrics.hash,
                s = lyrics.synced,
                n = lyrics.lines.size,
                c = index,
                m = totalChunks,
                l = lines.map { CompactLyricsLine(t = it.t, l = it.l) }
            )
        }
    }

    /**
     * Generates a hash for lyrics identification.
     */
    private fun generateHash(artist: String, track: String): String {
        val input = "${artist.lowercase().trim()}|${track.lowercase().trim()}"
        return CRC32Util.calculateCRC32(input.toByteArray())
    }
}
