package com.mediadash.android.domain.model

import com.mediadash.android.ble.BleConstants
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents a chunk of album art data for BLE transmission.
 * Uses binary protocol for maximum efficiency (no base64, no JSON).
 *
 * Binary format (16-byte header + raw data):
 * Offset  Size   Type     Field
 * ------  ----   ----     -----
 * 0       4      uint32   hash (CRC32 as uint32, little-endian)
 * 4       2      uint16   chunkIndex (little-endian)
 * 6       2      uint16   totalChunks (little-endian)
 * 8       2      uint16   dataLength (little-endian)
 * 10      4      uint32   dataCRC32 (little-endian)
 * 14      2      uint16   reserved (0)
 * 16+     N      bytes    raw image data (max 496 bytes)
 */
data class AlbumArtChunk(
    val hash: Long,               // CRC32 as unsigned 32-bit (stored as Long for Kotlin compatibility)
    val chunkIndex: Int,          // 0-based index
    val totalChunks: Int,         // Total number of chunks for this image
    val data: ByteArray,          // Raw chunk data (NOT base64 encoded)
    val crc32: Long               // CRC32 of the chunk data
) {
    /**
     * Serializes this chunk to binary format for BLE transmission.
     * @return ByteArray ready for BLE notification
     */
    fun toBinary(): ByteArray {
        val buffer = ByteBuffer.allocate(BleConstants.BINARY_HEADER_SIZE + data.size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Header (16 bytes)
        buffer.putInt(hash.toInt())                 // 0-3: hash as uint32
        buffer.putShort(chunkIndex.toShort())       // 4-5: chunkIndex as uint16
        buffer.putShort(totalChunks.toShort())      // 6-7: totalChunks as uint16
        buffer.putShort(data.size.toShort())        // 8-9: dataLength as uint16
        buffer.putInt(crc32.toInt())                // 10-13: dataCRC32 as uint32
        buffer.putShort(0)                          // 14-15: reserved

        // Raw data
        buffer.put(data)

        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AlbumArtChunk
        return hash == other.hash &&
               chunkIndex == other.chunkIndex &&
               totalChunks == other.totalChunks &&
               data.contentEquals(other.data) &&
               crc32 == other.crc32
    }

    override fun hashCode(): Int {
        var result = hash.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + totalChunks
        result = 31 * result + data.contentHashCode()
        result = 31 * result + crc32.hashCode()
        return result
    }

    companion object {
        /**
         * Parses a binary chunk from BLE data.
         * @param bytes Raw BLE notification data
         * @return Parsed AlbumArtChunk or null if invalid
         */
        fun fromBinary(bytes: ByteArray): AlbumArtChunk? {
            if (bytes.size < BleConstants.BINARY_HEADER_SIZE) return null

            val buffer = ByteBuffer.wrap(bytes)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            val hash = buffer.int.toLong() and 0xFFFFFFFFL      // unsigned
            val chunkIndex = buffer.short.toInt() and 0xFFFF    // unsigned
            val totalChunks = buffer.short.toInt() and 0xFFFF   // unsigned
            val dataLength = buffer.short.toInt() and 0xFFFF    // unsigned
            val crc32 = buffer.int.toLong() and 0xFFFFFFFFL     // unsigned
            buffer.short // reserved

            if (bytes.size < BleConstants.BINARY_HEADER_SIZE + dataLength) return null

            val data = ByteArray(dataLength)
            buffer.get(data)

            return AlbumArtChunk(
                hash = hash,
                chunkIndex = chunkIndex,
                totalChunks = totalChunks,
                data = data,
                crc32 = crc32
            )
        }
    }
}

/**
 * Request from Go client for album art by hash.
 * Used for error recovery when transfer fails.
 */
@Serializable
data class AlbumArtRequest(
    val hash: String              // CRC32 as decimal string
)
