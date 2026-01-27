package com.mediadash.android.util

import java.util.Locale
import java.util.zip.CRC32

/**
 * Utility for generating album art hashes.
 * The hash algorithm must match the Go client exactly:
 * CRC32(artist.lowercase() + "|" + album.lowercase()) -> decimal string
 */
object CRC32Util {

    /**
     * Generates the album art hash matching the Go client algorithm.
     * Uses English locale for consistent lowercase conversion across all devices.
     * Trims whitespace to prevent hash mismatches from metadata variations.
     * @param artist The artist name
     * @param album The album name
     * @return CRC32 hash as a decimal string (e.g., "3462671303")
     */
    fun generateAlbumArtHash(artist: String, album: String): String {
        return generateAlbumArtHashLong(artist, album).toString()
    }

    /**
     * Generates the album art hash as a Long for binary protocol.
     * @param artist The artist name
     * @param album The album name
     * @return CRC32 hash as unsigned 32-bit value (stored as Long)
     */
    fun generateAlbumArtHashLong(artist: String, album: String): Long {
        // Trim and use English locale for consistent hashing across all devices
        // (Turkish locale 'I'.lowercase() = 'ı' which would cause mismatches)
        val composite = "${artist.trim().lowercase(Locale.ENGLISH)}|${album.trim().lowercase(Locale.ENGLISH)}"
        val crc = CRC32()
        crc.update(composite.toByteArray(Charsets.UTF_8))
        return crc.value
    }

    /**
     * Generates the artist art hash for artist images.
     * Uses just the artist name (no album).
     * @param artist The artist name
     * @return CRC32 hash as a decimal string
     */
    fun generateArtistArtHash(artist: String): String {
        val normalized = artist.trim().lowercase(Locale.ENGLISH)
        val crc = CRC32()
        crc.update(normalized.toByteArray(Charsets.UTF_8))
        return crc.value.toString()
    }

    /**
     * Computes CRC32 checksum of a byte array.
     * @param data The data to checksum
     * @return CRC32 value as a Long
     */
    fun computeCRC32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    /**
     * Calculates CRC32 checksum and returns as decimal string.
     * Used for lyrics hash generation.
     * @param data The data to checksum
     * @return CRC32 hash as a decimal string
     */
    fun calculateCRC32(data: ByteArray): String {
        return computeCRC32(data).toString()
    }
}
