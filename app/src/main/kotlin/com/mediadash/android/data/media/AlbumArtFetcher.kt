package com.mediadash.android.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.util.Log
import com.mediadash.android.ble.BleConstants
import com.mediadash.android.domain.model.AlbumArtChunk
import com.mediadash.android.util.BitmapUtil
import com.mediadash.android.util.CRC32Util
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches and processes album art from MediaMetadata.
 * Handles resizing, compression, and chunking for BLE transmission.
 * Uses binary protocol for maximum efficiency.
 */
@Singleton
class AlbumArtFetcher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AlbumArtFetcher"
    }

    /**
     * Fetches album art from MediaMetadata.
     * Tries multiple sources: bitmap, then URI.
     *
     * @param metadata The media metadata
     * @return Processed JPEG image data, or null if not available
     */
    fun fetch(metadata: MediaMetadata): ByteArray? {
        // Try direct bitmap first (most common for local media)
        var bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        // Try URI fallback (common for streaming services)
        if (bitmap == null) {
            val uriString = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)

            if (uriString != null) {
                bitmap = loadBitmapFromUri(uriString)
            }
        }

        if (bitmap == null) {
            Log.d(TAG, "No album art available")
            return null
        }

        return processAlbumArt(bitmap)
    }

    /**
     * Loads a bitmap from a URI string.
     */
    private fun loadBitmapFromUri(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            var inputStream: InputStream? = null

            try {
                inputStream = context.contentResolver.openInputStream(uri)
                inputStream?.let { BitmapFactory.decodeStream(it) }
            } finally {
                inputStream?.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load album art from URI: $uriString", e)
            null
        }
    }

    /**
     * Fetches album art from a URL (e.g., podcast artwork URL).
     * Used for podcast episode artwork which is typically a remote URL.
     *
     * @param url The URL of the artwork image
     * @return Processed image data, or null if not available
     */
    fun fetchFromUrl(url: String): ByteArray? {
        if (url.isBlank()) {
            Log.d(TAG, "No artwork URL provided")
            return null
        }

        val bitmap = loadBitmapFromUrl(url)
        if (bitmap == null) {
            Log.d(TAG, "Failed to load artwork from URL: $url")
            return null
        }

        return processAlbumArt(bitmap)
    }

    /**
     * Loads a bitmap from a remote HTTP/HTTPS URL.
     */
    private fun loadBitmapFromUrl(urlString: String): Bitmap? {
        return try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doInput = true
            connection.connect()

            val inputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            connection.disconnect()

            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load album art from URL: $urlString", e)
            null
        }
    }

    /**
     * Processes a bitmap: resize and compress to WebP.
     */
    private fun processAlbumArt(bitmap: Bitmap): ByteArray {
        // Resize to max dimensions (250x250)
        val resized = BitmapUtil.resizeBitmap(bitmap, BleConstants.ALBUM_ART_MAX_SIZE)

        // Compress to WebP for better compression
        val compressed = BitmapUtil.compressToWebP(resized, BleConstants.ALBUM_ART_QUALITY)

        Log.d(TAG, "Processed album art: ${compressed.size} bytes (WebP)")
        return compressed
    }

    /**
     * Prepares album art data for BLE transmission by chunking.
     * Uses binary protocol - no base64 encoding, just raw bytes with header.
     *
     * @param hashLong The album art hash as unsigned 32-bit value
     * @param imageData Raw JPEG image data
     * @return List of chunks ready for transmission
     */
    fun prepareChunks(hashLong: Long, imageData: ByteArray): List<AlbumArtChunk> {
        val chunkSize = BleConstants.CHUNK_SIZE
        val totalChunks = (imageData.size + chunkSize - 1) / chunkSize

        if (totalChunks > 8192) {
            Log.w(TAG, "Image too large: ${imageData.size} bytes, ${totalChunks} chunks")
            return emptyList()
        }

        Log.d(TAG, "Preparing $totalChunks binary chunks for ${imageData.size} bytes (hash: $hashLong)")

        return (0 until totalChunks).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, imageData.size)
            val chunkData = imageData.copyOfRange(start, end)

            AlbumArtChunk(
                hash = hashLong,
                chunkIndex = index,
                totalChunks = totalChunks,
                data = chunkData,  // Raw bytes, no base64
                crc32 = CRC32Util.computeCRC32(chunkData)
            )
        }
    }
}
