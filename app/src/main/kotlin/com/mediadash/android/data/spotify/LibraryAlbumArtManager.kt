package com.mediadash.android.data.spotify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.mediadash.android.ble.BleConstants
import com.mediadash.android.domain.model.AlbumArtChunk
import com.mediadash.android.domain.model.SpotifyAlbumItem
import com.mediadash.android.util.BitmapUtil
import com.mediadash.android.util.CRC32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for library album art fetching and caching.
 * Fetches album art from Spotify URLs, compresses to 150x150 WebP,
 * and caches chunks ready for BLE transmission.
 */
@Singleton
class LibraryAlbumArtManager @Inject constructor() {

    companion object {
        private const val TAG = "LibraryAlbumArtMgr"
        private const val CACHE_SIZE = 30  // Number of albums to cache
        private const val CONNECTION_TIMEOUT = 10000  // 10 seconds
        private const val READ_TIMEOUT = 10000  // 10 seconds
    }

    // Cache of prepared album art chunks, keyed by hash
    private val artCache = LruCache<String, List<AlbumArtChunk>>(CACHE_SIZE)

    // Track which albums are being fetched to avoid duplicates
    private val fetchingSet = mutableSetOf<String>()
    private val fetchingMutex = Mutex()

    // Track pending album art requests (hash -> imageUrl)
    private val pendingFetches = mutableMapOf<String, String>()
    private val pendingMutex = Mutex()

    /**
     * Get cached album art chunks for a hash.
     * @param hash The album art hash (CRC32 string)
     * @return List of chunks if cached, null otherwise
     */
    fun getCachedChunks(hash: String): List<AlbumArtChunk>? {
        return artCache.get(hash)
    }

    /**
     * Check if album art is cached for the given hash.
     */
    fun isCached(hash: String): Boolean {
        return artCache.get(hash) != null
    }

    /**
     * Register albums for potential album art fetching.
     * This stores the hash -> imageUrl mapping so art can be fetched on demand.
     */
    suspend fun registerAlbums(albums: List<SpotifyAlbumItem>) {
        pendingMutex.withLock {
            for (album in albums) {
                val hash = album.artHash ?: continue
                val imageUrl = album.imageUrl ?: continue
                if (!isCached(hash) && !pendingFetches.containsKey(hash)) {
                    pendingFetches[hash] = imageUrl
                    Log.d(TAG, "Registered pending fetch: $hash -> ${imageUrl.take(50)}...")
                }
            }
        }
        Log.i(TAG, "Registered ${albums.size} albums, ${pendingFetches.size} pending fetches")
    }

    /**
     * Pre-fetch album art for a list of albums in the background.
     * Only fetches art that isn't already cached.
     * @param albums List of albums to pre-fetch art for
     * @param maxConcurrent Maximum concurrent fetches
     */
    suspend fun prefetchAlbumArt(albums: List<SpotifyAlbumItem>, maxConcurrent: Int = 3) {
        // Register all albums first
        registerAlbums(albums)

        // Fetch first few albums immediately (visible ones)
        val toFetch = albums.take(maxConcurrent)
        for (album in toFetch) {
            val hash = album.artHash ?: continue
            val imageUrl = album.imageUrl ?: continue
            if (!isCached(hash)) {
                fetchAndCacheArt(hash, imageUrl)
            }
        }
    }

    /**
     * Fetch album art on demand when requested.
     * First checks cache, then tries to fetch from registered URL.
     * @param hash The album art hash
     * @return List of chunks if successful, null otherwise
     */
    suspend fun fetchOnDemand(hash: String): List<AlbumArtChunk>? {
        // Check cache first
        getCachedChunks(hash)?.let { return it }

        // Check if we have a pending URL for this hash
        val imageUrl = pendingMutex.withLock { pendingFetches[hash] }
        if (imageUrl != null) {
            Log.i(TAG, "Fetching on-demand art for hash: $hash")
            return fetchAndCacheArt(hash, imageUrl)
        }

        Log.w(TAG, "No URL registered for hash: $hash")
        return null
    }

    /**
     * Fetch and cache album art from a URL.
     * @param hash The expected hash for this art
     * @param imageUrl The Spotify image URL
     * @return List of chunks if successful, null otherwise
     */
    suspend fun fetchAndCacheArt(hash: String, imageUrl: String): List<AlbumArtChunk>? {
        // Check if already fetching
        fetchingMutex.withLock {
            if (fetchingSet.contains(hash)) {
                Log.d(TAG, "Already fetching art for hash: $hash")
                return null
            }
            fetchingSet.add(hash)
        }

        try {
            Log.i(TAG, "Fetching library album art: $hash")
            Log.d(TAG, "   URL: ${imageUrl.take(80)}...")

            // Fetch bitmap from URL
            val bitmap = withContext(Dispatchers.IO) {
                loadBitmapFromUrl(imageUrl)
            }

            if (bitmap == null) {
                Log.w(TAG, "Failed to load bitmap from URL")
                return null
            }

            Log.d(TAG, "   Loaded bitmap: ${bitmap.width}x${bitmap.height}")

            // Resize to library size (150x150)
            val resized = BitmapUtil.resizeBitmap(bitmap, BleConstants.LIBRARY_ART_MAX_SIZE)
            Log.d(TAG, "   Resized to: ${resized.width}x${resized.height}")

            // Compress to WebP
            val compressed = BitmapUtil.compressToWebP(resized, BleConstants.ALBUM_ART_QUALITY)
            Log.d(TAG, "   Compressed to: ${compressed.size} bytes")

            // Prepare chunks
            val hashLong = hash.toLongOrNull() ?: return null
            val chunks = prepareChunks(hashLong, compressed)

            if (chunks.isEmpty()) {
                Log.w(TAG, "Failed to prepare chunks")
                return null
            }

            // Cache the chunks
            artCache.put(hash, chunks)

            // Remove from pending
            pendingMutex.withLock {
                pendingFetches.remove(hash)
            }

            Log.i(TAG, "✅ Cached library art: $hash (${chunks.size} chunks, ${compressed.size} bytes)")
            return chunks

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching library art: $hash", e)
            return null
        } finally {
            fetchingMutex.withLock {
                fetchingSet.remove(hash)
            }
        }
    }

    /**
     * Load a bitmap from an HTTP/HTTPS URL.
     */
    private fun loadBitmapFromUrl(urlString: String): Bitmap? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECTION_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.doInput = true
            connection.connect()

            val inputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            connection.disconnect()

            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bitmap from URL: $urlString", e)
            null
        }
    }

    /**
     * Prepare album art chunks for BLE transmission.
     * Uses the same binary protocol as regular album art.
     */
    private fun prepareChunks(hashLong: Long, imageData: ByteArray): List<AlbumArtChunk> {
        val chunkSize = BleConstants.CHUNK_SIZE
        val totalChunks = (imageData.size + chunkSize - 1) / chunkSize

        if (totalChunks > 8192) {
            Log.w(TAG, "Image too large: ${imageData.size} bytes, $totalChunks chunks")
            return emptyList()
        }

        return (0 until totalChunks).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, imageData.size)
            val chunkData = imageData.copyOfRange(start, end)

            AlbumArtChunk(
                hash = hashLong,
                chunkIndex = index,
                totalChunks = totalChunks,
                data = chunkData,
                crc32 = CRC32Util.computeCRC32(chunkData)
            )
        }
    }

    /**
     * Clear the cache.
     */
    fun clearCache() {
        artCache.evictAll()
        pendingFetches.clear()
        Log.i(TAG, "Cache cleared")
    }

    /**
     * Get cache stats for debugging.
     */
    fun getCacheStats(): String {
        return "LibraryArtCache: ${artCache.size()}/$CACHE_SIZE cached, ${pendingFetches.size} pending"
    }
}
