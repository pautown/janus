package com.mediadash.android.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Utility for processing album art bitmaps.
 */
object BitmapUtil {

    /**
     * Resizes a bitmap to fit within the specified max dimension while maintaining aspect ratio.
     * @param bitmap Source bitmap
     * @param maxDimension Maximum width or height
     * @return Resized bitmap
     */
    fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        val scale = minOf(
            maxDimension.toFloat() / width,
            maxDimension.toFloat() / height
        )

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Compresses a bitmap to WebP format (lossy).
     * WebP provides better compression than JPEG at equivalent quality.
     * @param bitmap Source bitmap
     * @param quality WebP quality (0-100)
     * @return Compressed image as byte array
     */
    fun compressToWebP(bitmap: Bitmap, quality: Int): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            // Use WEBP_LOSSY for smaller file sizes (API 30+)
            // Falls back to WEBP on older APIs
            val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, quality, stream)
            stream.toByteArray()
        }
    }

    /**
     * Decodes a byte array to a Bitmap.
     * @param data Image data
     * @return Decoded bitmap, or null if decoding fails
     */
    fun decodeBitmap(data: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (e: Exception) {
            null
        }
    }
}
