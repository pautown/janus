package com.mediadash.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.os.Build
import android.util.Log
import com.mediadash.android.domain.model.AlbumArtChunk
import com.mediadash.android.util.CRC32Util
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles chunking and transmission of album art over BLE.
 * Uses binary protocol for maximum efficiency (no base64, no JSON).
 *
 * Binary format per chunk (16-byte header + raw data):
 * - 4 bytes: hash (uint32, little-endian)
 * - 2 bytes: chunkIndex (uint16, little-endian)
 * - 2 bytes: totalChunks (uint16, little-endian)
 * - 2 bytes: dataLength (uint16, little-endian)
 * - 4 bytes: dataCRC32 (uint32, little-endian)
 * - 2 bytes: reserved (0)
 * - N bytes: raw image data (max 496 bytes)
 */
@Singleton
class AlbumArtTransmitter @Inject constructor(
    private val throttler: NotificationThrottler
) {
    companion object {
        private const val TAG = "AlbumArtTransmitter"
        private const val NOTIFICATION_TIMEOUT_MS = 200L  // Short timeout for flow control
    }

    // Track in-flight transfers per device
    private val activeTransfers = ConcurrentHashMap<String, Boolean>()

    // Channel to receive notification sent confirmations
    private val notificationSentChannel = Channel<Boolean>(Channel.CONFLATED)

    /**
     * Prepares album art data for transmission by chunking.
     * Uses binary protocol - no base64 encoding, just raw bytes with header.
     * @param hashLong The album art hash as unsigned 32-bit value
     * @param imageData Raw JPEG image data
     * @return List of chunks ready for transmission
     */
    fun prepareChunks(hashLong: Long, imageData: ByteArray): List<AlbumArtChunk> {
        val chunkSize = BleConstants.CHUNK_SIZE
        val totalChunks = (imageData.size + chunkSize - 1) / chunkSize

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

    /**
     * Transmits album art chunks to a connected device using binary protocol.
     * @param gattServer The GATT server
     * @param device The target device
     * @param characteristic The album art data characteristic
     * @param chunks The prepared chunks to transmit
     * @return true if all chunks were sent successfully
     */
    suspend fun transmit(
        gattServer: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        chunks: List<AlbumArtChunk>
    ): Boolean {
        val deviceAddress = device.address

        // Check if transfer already in progress for this device
        if (activeTransfers[deviceAddress] == true) {
            Log.w(TAG, "Transfer already in progress for $deviceAddress")
            return false
        }

        activeTransfers[deviceAddress] = true

        try {
            val hash = chunks.firstOrNull()?.hash ?: 0L
            val totalBytes = chunks.sumOf { it.data.size }
            val binaryBytes = chunks.size * BleConstants.BINARY_HEADER_SIZE + totalBytes

            Log.d(TAG, "Starting binary transmission of ${chunks.size} chunks to $deviceAddress")
            Log.i("ALBUMART", "   📡 Transmitter: Starting ${chunks.size} binary chunks to $deviceAddress")
            Log.i("ALBUMART", "      Hash: $hash, Total: $totalBytes bytes raw, $binaryBytes bytes BLE")

            var successCount = 0
            var failCount = 0

            for ((index, chunk) in chunks.withIndex()) {
                // Rate limit notifications
                throttler.throttle()

                // Serialize chunk to binary format
                val binaryData = chunk.toBinary()

                // Log first and last chunks, and every 50th chunk
                if (index == 0 || index == chunks.size - 1 || (index + 1) % 50 == 0) {
                    Log.i("ALBUMART", "      Chunk ${index + 1}/${chunks.size}: ${binaryData.size} bytes (${chunk.data.size} data)")
                }

                // Send notification
                val sent = safeNotifyCharacteristicChanged(gattServer, device, characteristic, binaryData)

                if (!sent) {
                    Log.e("ALBUMART", "      ❌ Failed to send chunk ${index + 1}/${chunks.size}")
                    Log.e(TAG, "Failed to send chunk $index/${chunks.size}")
                    failCount++
                    return false
                }
                successCount++

                // Wait for notification sent callback for flow control
                try {
                    withTimeout(NOTIFICATION_TIMEOUT_MS) {
                        notificationSentChannel.receive()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Continue anyway - timeout just means we proceed without confirmation
                }

                if ((index + 1) % 10 == 0) {
                    Log.d(TAG, "Sent chunk ${index + 1}/${chunks.size}")
                }
            }

            Log.d(TAG, "Completed binary transmission of ${chunks.size} chunks")
            Log.i("ALBUMART", "   ✅ Transmitter: Completed! Sent $successCount/${chunks.size} chunks")
            return true

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("ALBUMART", "   ❌ Transmitter error: ${e.message}")
            Log.e(TAG, "Error during transmission", e)
            return false
        } finally {
            activeTransfers.remove(deviceAddress)
        }
    }

    /**
     * Sends a BLE notification using the appropriate API for the running Android version.
     */
    @SuppressLint("MissingPermission")
    private fun safeNotifyCharacteristicChanged(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = server.notifyCharacteristicChanged(device, characteristic, false, value)
            result == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.setValue(value)
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    /**
     * Called when a notification has been sent.
     * Used for flow control pacing.
     */
    fun onNotificationSent(device: BluetoothDevice, status: Int) {
        notificationSentChannel.trySend(status == 0)
    }

    /**
     * Cancels any in-progress transfer for the specified device.
     */
    fun cancelTransfer(device: BluetoothDevice) {
        activeTransfers.remove(device.address)
        Log.d(TAG, "Cancelled transfer for ${device.address}")
    }

    /**
     * Checks if a transfer is in progress for the specified device.
     */
    fun isTransferInProgress(device: BluetoothDevice): Boolean {
        return activeTransfers[device.address] == true
    }
}
