package com.mediadash.android.data.media

import android.util.LruCache
import com.mediadash.android.domain.model.AlbumArtChunk

/**
 * LRU cache for album art chunks.
 * Caches pre-chunked album art data to avoid re-processing
 * when the same album art is needed again.
 */
class AlbumArtCache(
    maxSize: Int = 10  // Number of album arts to cache
) {
    private val cache = LruCache<String, List<AlbumArtChunk>>(maxSize)

    /**
     * Gets cached chunks for the given hash.
     * @param hash Album art hash (CRC32 decimal string)
     * @return Cached chunks, or null if not found
     */
    fun get(hash: String): List<AlbumArtChunk>? {
        return cache.get(hash)
    }

    /**
     * Caches chunks for the given hash.
     * @param hash Album art hash (CRC32 decimal string)
     * @param chunks The chunks to cache
     */
    fun put(hash: String, chunks: List<AlbumArtChunk>) {
        cache.put(hash, chunks)
    }

    /**
     * Removes cached chunks for the given hash.
     * @param hash Album art hash to remove
     */
    fun remove(hash: String) {
        cache.remove(hash)
    }

    /**
     * Clears the entire cache.
     */
    fun clear() {
        cache.evictAll()
    }

    /**
     * Returns the current size of the cache.
     */
    fun size(): Int = cache.size()

    /**
     * Returns cache hit/miss statistics.
     */
    fun stats(): CacheStats {
        return CacheStats(
            hitCount = cache.hitCount(),
            missCount = cache.missCount(),
            size = cache.size(),
            maxSize = cache.maxSize()
        )
    }

    data class CacheStats(
        val hitCount: Int,
        val missCount: Int,
        val size: Int,
        val maxSize: Int
    ) {
        val hitRate: Float
            get() = if (hitCount + missCount > 0) {
                hitCount.toFloat() / (hitCount + missCount)
            } else 0f
    }
}
