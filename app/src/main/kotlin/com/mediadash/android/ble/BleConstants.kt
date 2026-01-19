package com.mediadash.android.ble

import java.util.UUID

/**
 * BLE protocol constants matching the Mercury specification.
 * These UUIDs and values must match exactly for interoperability.
 * See: https://github.com/pautown/mercury
 */
object BleConstants {
    // Janus Service UUID
    val SERVICE_UUID: UUID = UUID.fromString("0000a0d0-0000-1000-8000-00805f9b34fb")

    // Characteristic UUIDs
    val MEDIA_STATE_UUID: UUID = UUID.fromString("0000a0d1-0000-1000-8000-00805f9b34fb")
    val PLAYBACK_CONTROL_UUID: UUID = UUID.fromString("0000a0d2-0000-1000-8000-00805f9b34fb")
    val ALBUM_ART_REQUEST_UUID: UUID = UUID.fromString("0000a0d3-0000-1000-8000-00805f9b34fb")
    val ALBUM_ART_DATA_UUID: UUID = UUID.fromString("0000a0d4-0000-1000-8000-00805f9b34fb")
    val PODCAST_INFO_UUID: UUID = UUID.fromString("0000a0d5-0000-1000-8000-00805f9b34fb")
    val LYRICS_REQUEST_UUID: UUID = UUID.fromString("0000a0d6-0000-1000-8000-00805f9b34fb")
    val LYRICS_DATA_UUID: UUID = UUID.fromString("0000a0d7-0000-1000-8000-00805f9b34fb")
    val SETTINGS_UUID: UUID = UUID.fromString("0000a0d8-0000-1000-8000-00805f9b34fb")

    // Client Characteristic Configuration Descriptor UUID (standard)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Binary protocol constants
    // Binary header is 16 bytes, max BLE notification is 512 bytes
    // So max raw data per chunk is 512 - 16 = 496 bytes
    const val BINARY_HEADER_SIZE = 16             // Fixed header size (bytes)
    const val CHUNK_SIZE = 496                    // Max raw image data per chunk (bytes)
    const val MAX_BLE_NOTIFICATION = 512          // Max BLE notification size (bytes)
    const val NOTIFICATION_INTERVAL_MS = 10L      // Minimum interval between notifications (ms)
    const val ALBUM_ART_MAX_SIZE = 250            // Max album art dimension (pixels)
    const val ALBUM_ART_QUALITY = 75              // WebP compression quality (0-100)
    const val MAX_ALBUM_ART_BYTES = 2 * 1024 * 1024  // 2MB max album art size

    // Device advertisement name
    const val DEVICE_NAME = "Janus"

    // Lyrics constants
    const val LYRICS_CHUNK_SIZE = 450        // Max lyrics text per chunk (leaves room for header)
    const val LYRICS_MAX_LINES_PER_CHUNK = 20  // Max lines per BLE notification
}
