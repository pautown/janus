package com.mediadash.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.mediadash.android.di.ApplicationScope
import com.mediadash.android.domain.model.AlbumArtChunk
import com.mediadash.android.domain.model.AlbumArtRequest
import com.mediadash.android.domain.model.CompactLyricsResponse
import com.mediadash.android.domain.model.ConnectionStatus
import com.mediadash.android.domain.model.LyricsRequest
import com.mediadash.android.domain.model.MediaState
import com.mediadash.android.domain.model.PlaybackCommand
import com.mediadash.android.domain.model.PodcastListResponse
import com.mediadash.android.domain.model.RecentEpisodesResponse
import com.mediadash.android.domain.model.PodcastEpisodesResponse
import com.mediadash.android.domain.model.toCompact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the BLE GATT server lifecycle, including advertising,
 * connection handling, and characteristic operations.
 */
@Singleton
class GattServerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val albumArtTransmitter: AlbumArtTransmitter,
    private val throttler: NotificationThrottler,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "GattServerManager"
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    // Characteristics
    private var mediaStateCharacteristic: BluetoothGattCharacteristic? = null
    private var playbackControlCharacteristic: BluetoothGattCharacteristic? = null
    private var albumArtRequestCharacteristic: BluetoothGattCharacteristic? = null
    private var albumArtDataCharacteristic: BluetoothGattCharacteristic? = null
    private var podcastInfoCharacteristic: BluetoothGattCharacteristic? = null
    private var lyricsRequestCharacteristic: BluetoothGattCharacteristic? = null
    private var lyricsDataCharacteristic: BluetoothGattCharacteristic? = null
    private var settingsCharacteristic: BluetoothGattCharacteristic? = null
    private var timeSyncCharacteristic: BluetoothGattCharacteristic? = null

    // State flows
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connectedDevices = MutableStateFlow<Set<BluetoothDevice>>(emptySet())
    val connectedDevices: StateFlow<Set<BluetoothDevice>> = _connectedDevices.asStateFlow()

    // Events
    private val _commandReceived = MutableSharedFlow<PlaybackCommand>()
    val commandReceived = _commandReceived.asSharedFlow()

    private val _albumArtRequested = MutableSharedFlow<AlbumArtRequest>()
    val albumArtRequested = _albumArtRequested.asSharedFlow()

    private val _podcastInfoRequested = MutableSharedFlow<Unit>()
    val podcastInfoRequested = _podcastInfoRequested.asSharedFlow()

    private val _lyricsRequested = MutableSharedFlow<LyricsRequest>()
    val lyricsRequested = _lyricsRequested.asSharedFlow()

    // Album art request indicator state (for UI feedback)
    private val _albumArtRequestActive = MutableStateFlow(false)
    val albumArtRequestActive: StateFlow<Boolean> = _albumArtRequestActive.asStateFlow()

    private val _lastAlbumArtRequestHash = MutableStateFlow<String?>(null)
    val lastAlbumArtRequestHash: StateFlow<String?> = _lastAlbumArtRequestHash.asStateFlow()

    // Devices that have enabled notifications
    private val notificationEnabledDevices = mutableSetOf<BluetoothDevice>()

    // Last known media state for read requests
    private var lastMediaState: MediaState? = null

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: device=${device.address}, status=$status, newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectedDevices.update { it + device }
                    _connectionStatus.value = ConnectionStatus.Connected(
                        deviceName = device.name ?: "Unknown",
                        deviceAddress = device.address
                    )
                    Log.i(TAG, "Device connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectedDevices.update { it - device }
                    notificationEnabledDevices.remove(device)
                    albumArtTransmitter.cancelTransfer(device)

                    if (_connectedDevices.value.isEmpty()) {
                        _connectionStatus.value = ConnectionStatus.Advertising
                    }
                    Log.i(TAG, "Device disconnected: ${device.address}")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "Characteristic read request: ${characteristic.uuid}")

            when (characteristic.uuid) {
                BleConstants.MEDIA_STATE_UUID -> {
                    val state = lastMediaState ?: MediaState.EMPTY
                    val jsonData = json.encodeToString(state).toByteArray(Charsets.UTF_8)
                    val responseData = if (offset < jsonData.size) {
                        jsonData.copyOfRange(offset, jsonData.size)
                    } else {
                        ByteArray(0)
                    }
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseData)
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "Characteristic write request: ${characteristic.uuid}")

            when (characteristic.uuid) {
                BleConstants.PLAYBACK_CONTROL_UUID -> {
                    handlePlaybackCommand(value)
                }
                BleConstants.ALBUM_ART_REQUEST_UUID -> {
                    handleAlbumArtRequest(value)
                }
                BleConstants.LYRICS_REQUEST_UUID -> {
                    handleLyricsRequest(value)
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "Descriptor write request: ${descriptor.uuid}")

            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                // Client is enabling/disabling notifications
                val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabled) {
                    notificationEnabledDevices.add(device)
                    Log.d(TAG, "Notifications enabled for ${device.address}")

                    // Check if this is for the media state characteristic
                    val parentCharacteristic = descriptor.characteristic
                    if (parentCharacteristic?.uuid == BleConstants.MEDIA_STATE_UUID) {
                        // Send current media state immediately upon notification subscription
                        lastMediaState?.let { state ->
                            Log.i(TAG, "Sending initial media state to newly connected device: ${device.address}")
                            scope.launch {
                                sendMediaStateToDevice(device, state)
                            }
                        }
                        // Send time sync after a short delay to ensure connection is stable
                        scope.launch {
                            kotlinx.coroutines.delay(2000) // Wait 2 seconds
                            sendTimeSync(device)
                        }
                    }
                } else {
                    notificationEnabledDevices.remove(device)
                    Log.d(TAG, "Notifications disabled for ${device.address}")
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            albumArtTransmitter.onNotificationSent(device, status)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started successfully")
            _connectionStatus.value = ConnectionStatus.Advertising
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed with error code: $errorCode")
            _connectionStatus.value = ConnectionStatus.Error("Advertising failed: $errorCode")
        }
    }

    /**
     * Starts the GATT server and begins advertising.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (_isRunning.value) {
            Log.w(TAG, "GATT server already running")
            return true
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            _connectionStatus.value = ConnectionStatus.Error("Bluetooth not available")
            return false
        }

        // Open GATT server
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            _connectionStatus.value = ConnectionStatus.Error("Failed to open GATT server")
            return false
        }

        // Set up service and characteristics
        if (!setupService()) {
            stop()
            return false
        }

        // Start advertising
        if (!startAdvertising()) {
            stop()
            return false
        }

        _isRunning.value = true
        Log.i(TAG, "GATT server started")
        return true
    }

    /**
     * Stops the GATT server and advertising.
     */
    @SuppressLint("MissingPermission")
    fun stop() {
        Log.i(TAG, "Stopping GATT server")

        // Stop advertising
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising", e)
        }
        advertiser = null

        // Disconnect all devices and close server
        try {
            _connectedDevices.value.forEach { device ->
                gattServer?.cancelConnection(device)
            }
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server", e)
        }
        gattServer = null

        // Reset state
        _connectedDevices.value = emptySet()
        notificationEnabledDevices.clear()
        throttler.reset()

        _isRunning.value = false
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    @SuppressLint("MissingPermission")
    private fun setupService(): Boolean {
        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Media State characteristic (Read, Notify)
        mediaStateCharacteristic = BluetoothGattCharacteristic(
            BleConstants.MEDIA_STATE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(createCCCD())
        }
        service.addCharacteristic(mediaStateCharacteristic)

        // Playback Control characteristic (Write)
        playbackControlCharacteristic = BluetoothGattCharacteristic(
            BleConstants.PLAYBACK_CONTROL_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(playbackControlCharacteristic)

        // Album Art Request characteristic (Write)
        albumArtRequestCharacteristic = BluetoothGattCharacteristic(
            BleConstants.ALBUM_ART_REQUEST_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(albumArtRequestCharacteristic)

        // Album Art Data characteristic (Read, Notify)
        albumArtDataCharacteristic = BluetoothGattCharacteristic(
            BleConstants.ALBUM_ART_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(createCCCD())
        }
        service.addCharacteristic(albumArtDataCharacteristic)

        // Podcast Info characteristic (Read, Notify)
        podcastInfoCharacteristic = BluetoothGattCharacteristic(
            BleConstants.PODCAST_INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(createCCCD())
        }
        service.addCharacteristic(podcastInfoCharacteristic)

        // Lyrics Request characteristic (Write)
        lyricsRequestCharacteristic = BluetoothGattCharacteristic(
            BleConstants.LYRICS_REQUEST_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(lyricsRequestCharacteristic)

        // Lyrics Data characteristic (Read, Notify)
        lyricsDataCharacteristic = BluetoothGattCharacteristic(
            BleConstants.LYRICS_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(createCCCD())
        }
        service.addCharacteristic(lyricsDataCharacteristic)

        // Settings characteristic (Read, Notify) - for syncing settings like lyrics enabled
        settingsCharacteristic = BluetoothGattCharacteristic(
            BleConstants.SETTINGS_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(createCCCD())
        }
        service.addCharacteristic(settingsCharacteristic)

        // Time Sync characteristic (Notify) - for syncing device time from phone
        timeSyncCharacteristic = BluetoothGattCharacteristic(
            BleConstants.TIME_SYNC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(createCCCD())
        }
        service.addCharacteristic(timeSyncCharacteristic)

        // Add service to server
        val added = gattServer?.addService(service) ?: false
        if (!added) {
            Log.e(TAG, "Failed to add service to GATT server")
            return false
        }

        Log.d(TAG, "GATT service configured with 9 characteristics")
        return true
    }

    private fun createCCCD(): BluetoothGattDescriptor {
        return BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ).apply {
            value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(): Boolean {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported")
            _connectionStatus.value = ConnectionStatus.Error("BLE advertising not supported")
            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)  // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
            _connectionStatus.value = ConnectionStatus.Error("Failed to start advertising")
            return false
        }
    }

    private fun handlePlaybackCommand(data: ByteArray) {
        try {
            val jsonString = String(data, Charsets.UTF_8)
            val command = json.decodeFromString<PlaybackCommand>(jsonString)

            if (command.isValid()) {
                Log.d(TAG, "Received playback command: ${command.action}")
                scope.launch {
                    // Handle podcast info request separately
                    if (command.action == PlaybackCommand.ACTION_PODCAST_INFO_REQUEST) {
                        _podcastInfoRequested.emit(Unit)
                    } else {
                        _commandReceived.emit(command)
                    }
                }
            } else {
                Log.w(TAG, "Invalid playback command: ${command.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse playback command", e)
        }
    }

    private fun handleAlbumArtRequest(data: ByteArray) {
        try {
            val jsonString = String(data, Charsets.UTF_8)
            Log.i("ALBUMART", "═══════════════════════════════════════════════")
            Log.i("ALBUMART", "📥 ALBUM ART REQUEST RECEIVED via BLE")
            Log.i("ALBUMART", "   Raw JSON: $jsonString")

            val request = json.decodeFromString<AlbumArtRequest>(jsonString)

            Log.i("ALBUMART", "   Parsed hash: ${request.hash}")
            Log.d(TAG, "Received album art request: ${request.hash}")

            // Update UI indicator state
            _albumArtRequestActive.value = true
            _lastAlbumArtRequestHash.value = request.hash

            scope.launch {
                Log.i("ALBUMART", "   Emitting request to observers...")
                _albumArtRequested.emit(request)

                // Clear the indicator after 3 seconds
                kotlinx.coroutines.delay(3000)
                _albumArtRequestActive.value = false
            }
        } catch (e: Exception) {
            Log.e("ALBUMART", "❌ Failed to parse album art request: ${e.message}")
            Log.e(TAG, "Failed to parse album art request", e)
        }
    }

    private fun handleLyricsRequest(data: ByteArray) {
        try {
            val jsonString = String(data, Charsets.UTF_8)
            Log.i("LYRICS", "📥 LYRICS REQUEST RECEIVED via BLE")
            Log.i("LYRICS", "   Raw JSON: $jsonString")

            val request = json.decodeFromString<LyricsRequest>(jsonString)
            Log.d(TAG, "Received lyrics request: action=${request.action}, hash=${request.hash}")

            scope.launch {
                _lyricsRequested.emit(request)
            }
        } catch (e: Exception) {
            Log.e("LYRICS", "❌ Failed to parse lyrics request: ${e.message}")
            Log.e(TAG, "Failed to parse lyrics request", e)
        }
    }

    /**
     * Notifies all connected devices of a media state change.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyMediaStateChanged(state: MediaState) {
        lastMediaState = state
        val characteristic = mediaStateCharacteristic ?: return
        val server = gattServer ?: return

        val jsonData = json.encodeToString(state).toByteArray(Charsets.UTF_8)
        characteristic.value = jsonData

        for (device in notificationEnabledDevices) {
            throttler.throttle()
            @Suppress("DEPRECATION")
            val sent = server.notifyCharacteristicChanged(device, characteristic, false)
            if (!sent) {
                Log.w(TAG, "Failed to notify media state to ${device.address}")
            }
        }
    }

    /**
     * Sends media state to a specific device.
     * Used for initial state sync when a device first enables notifications.
     */
    @SuppressLint("MissingPermission")
    private suspend fun sendMediaStateToDevice(device: BluetoothDevice, state: MediaState) {
        val characteristic = mediaStateCharacteristic ?: return
        val server = gattServer ?: return

        val jsonData = json.encodeToString(state).toByteArray(Charsets.UTF_8)
        characteristic.value = jsonData

        throttler.throttle()
        @Suppress("DEPRECATION")
        val sent = server.notifyCharacteristicChanged(device, characteristic, false)
        if (sent) {
            Log.i(TAG, "Initial media state sent to ${device.address}: ${state.trackTitle} by ${state.artist}")
        } else {
            Log.w(TAG, "Failed to send initial media state to ${device.address}")
        }
    }

    /**
     * Sends current time to a specific device for time synchronization.
     * Format: "timestamp|offset_minutes|timezone_id"
     *   - timestamp: Unix timestamp in seconds (UTC)
     *   - offset_minutes: Current timezone offset from UTC in minutes (includes DST)
     *   - timezone_id: IANA timezone ID (e.g., "America/New_York")
     * Example: "1737590400|-300|America/New_York" (EST) or "1737590400|-240|America/New_York" (EDT)
     */
    @SuppressLint("MissingPermission")
    private suspend fun sendTimeSync(device: BluetoothDevice) {
        val characteristic = timeSyncCharacteristic ?: return
        val server = gattServer ?: return

        // Get current time in milliseconds
        val currentTimeMs = System.currentTimeMillis()
        val timestamp = currentTimeMs / 1000

        // Get timezone information - use getOffset() to include DST adjustment
        val timeZone = java.util.TimeZone.getDefault()
        val offsetMinutes = timeZone.getOffset(currentTimeMs) / 60000  // Includes DST
        val timezoneId = timeZone.id

        // Format: "timestamp|offset_minutes|timezone_id"
        val timeSyncData = "$timestamp|$offsetMinutes|$timezoneId"
        val timeData = timeSyncData.toByteArray(Charsets.UTF_8)
        characteristic.value = timeData

        throttler.throttle()
        @Suppress("DEPRECATION")
        val sent = server.notifyCharacteristicChanged(device, characteristic, false)
        if (sent) {
            Log.i(TAG, "Time sync sent to ${device.address}: $timeSyncData (${java.util.Date(timestamp * 1000)})")
        } else {
            Log.w(TAG, "Failed to send time sync to ${device.address}")
        }
    }

    /**
     * Transmits album art chunks to all connected devices.
     */
    suspend fun transmitAlbumArt(chunks: List<AlbumArtChunk>) {
        val characteristic = albumArtDataCharacteristic ?: run {
            Log.w("ALBUMART", "❌ Cannot transmit: albumArtDataCharacteristic is null")
            return
        }
        val server = gattServer ?: run {
            Log.w("ALBUMART", "❌ Cannot transmit: gattServer is null")
            return
        }

        val hash = chunks.firstOrNull()?.hash?.toString() ?: "unknown"
        val totalBytes = chunks.sumOf { it.data.size }

        Log.i("ALBUMART", "═══════════════════════════════════════════════")
        Log.i("ALBUMART", "📤 STARTING ALBUM ART TRANSMISSION (binary)")
        Log.i("ALBUMART", "   Hash: $hash")
        Log.i("ALBUMART", "   Chunks: ${chunks.size}")
        Log.i("ALBUMART", "   Total raw size: ${totalBytes} bytes")
        Log.i("ALBUMART", "   Target devices: ${notificationEnabledDevices.size}")

        for (device in notificationEnabledDevices.toList()) {
            Log.i("ALBUMART", "   → Transmitting to device: ${device.address}")
            val success = albumArtTransmitter.transmit(server, device, characteristic, chunks)
            if (success) {
                Log.i("ALBUMART", "   ✅ Transmission complete to ${device.address}")
            } else {
                Log.e("ALBUMART", "   ❌ Transmission failed to ${device.address}")
            }
        }
        Log.i("ALBUMART", "═══════════════════════════════════════════════")
    }

    /**
     * Notifies all connected devices with podcast information.
     * Sends data in chunks if it exceeds BLE MTU limit.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyPodcastInfo(podcastInfo: com.mediadash.android.domain.model.PodcastInfoResponse) {
        val characteristic = podcastInfoCharacteristic ?: return
        val server = gattServer ?: return

        // Limit episodes per podcast and total podcasts to prevent exceeding BLE limits
        val maxPodcasts = 20
        val maxEpisodesPerPodcast = 15

        val limitedPodcasts = podcastInfo.podcasts.take(maxPodcasts).map { podcast ->
            if (podcast.episodes.size > maxEpisodesPerPodcast) {
                podcast.copy(
                    episodes = podcast.episodes.take(maxEpisodesPerPodcast)
                )
            } else {
                podcast
            }
        }

        val limitedInfo = podcastInfo.copy(podcasts = limitedPodcasts)

        val jsonData = json.encodeToString(limitedInfo).toByteArray(Charsets.UTF_8)

        // BLE max notification size is typically 512 bytes
        // Chunk the data if needed
        val maxChunkSize = 500
        val chunks = jsonData.toList().chunked(maxChunkSize)

        Log.d(TAG, "Sending podcast info: ${limitedPodcasts.size} podcasts, ${jsonData.size} bytes in ${chunks.size} chunks")

        for (device in notificationEnabledDevices) {
            for ((index, chunk) in chunks.withIndex()) {
                throttler.throttle()

                // Prepend chunk header: [chunkIndex][totalChunks][data...]
                val header = byteArrayOf(index.toByte(), chunks.size.toByte())
                val chunkData = header + chunk.toByteArray()

                characteristic.value = chunkData
                @Suppress("DEPRECATION")
                val sent = server.notifyCharacteristicChanged(device, characteristic, false)
                if (!sent) {
                    Log.w(TAG, "Failed to notify podcast info chunk $index to ${device.address}")
                    break
                } else {
                    Log.d(TAG, "Podcast info chunk ${index + 1}/${chunks.size} sent to ${device.address}")
                }
            }
        }
    }

    /**
     * Notifies all connected devices with podcast channel list (A-Z).
     * Uses compact format to minimize BLE bandwidth.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyPodcastList(response: PodcastListResponse) {
        val characteristic = podcastInfoCharacteristic ?: return
        val server = gattServer ?: return

        // Convert to compact format for BLE transfer
        val compactResponse = response.toCompact()
        val jsonData = json.encodeToString(compactResponse).toByteArray(Charsets.UTF_8)
        val maxChunkSize = 500
        val chunks = jsonData.toList().chunked(maxChunkSize)

        Log.d(TAG, "Sending podcast list: ${response.podcasts.size} channels, ${jsonData.size} bytes (compact) in ${chunks.size} chunks")
        Log.i("PODCAST", "   BLE transmission: ${jsonData.size} bytes (compact) in ${chunks.size} chunks")

        for (device in notificationEnabledDevices) {
            for ((index, chunk) in chunks.withIndex()) {
                throttler.throttle()

                // Header: [type=1][chunkIndex][totalChunks][data...]
                // type=1 indicates podcast list response
                val header = byteArrayOf(1, index.toByte(), chunks.size.toByte())
                val chunkData = header + chunk.toByteArray()

                characteristic.value = chunkData
                @Suppress("DEPRECATION")
                val sent = server.notifyCharacteristicChanged(device, characteristic, false)
                if (!sent) {
                    Log.w(TAG, "Failed to notify podcast list chunk $index to ${device.address}")
                    Log.w("PODCAST", "   ⚠️ Failed to send chunk ${index + 1}/${chunks.size}")
                    break
                }
            }
        }
    }

    /**
     * Notifies all connected devices with recent episodes across all podcasts.
     * Uses compact format to minimize BLE bandwidth.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyRecentEpisodes(response: RecentEpisodesResponse) {
        val characteristic = podcastInfoCharacteristic ?: return
        val server = gattServer ?: return

        // Convert to compact format for BLE transfer
        val compactResponse = response.toCompact()
        val jsonData = json.encodeToString(compactResponse).toByteArray(Charsets.UTF_8)
        val maxChunkSize = 500
        val chunks = jsonData.toList().chunked(maxChunkSize)

        Log.d(TAG, "Sending recent episodes: ${response.episodes.size} episodes, ${jsonData.size} bytes (compact) in ${chunks.size} chunks")
        Log.i("PODCAST", "   BLE transmission: ${jsonData.size} bytes (compact) in ${chunks.size} chunks")

        for (device in notificationEnabledDevices) {
            for ((index, chunk) in chunks.withIndex()) {
                throttler.throttle()

                // Header: [type=2][chunkIndex][totalChunks][data...]
                // type=2 indicates recent episodes response
                val header = byteArrayOf(2, index.toByte(), chunks.size.toByte())
                val chunkData = header + chunk.toByteArray()

                characteristic.value = chunkData
                @Suppress("DEPRECATION")
                val sent = server.notifyCharacteristicChanged(device, characteristic, false)
                if (!sent) {
                    Log.w(TAG, "Failed to notify recent episodes chunk $index to ${device.address}")
                    Log.w("PODCAST", "   ⚠️ Failed to send chunk ${index + 1}/${chunks.size}")
                    break
                }
            }
        }
    }

    /**
     * Notifies all connected devices with episodes for a specific podcast.
     * Uses compact format to minimize BLE bandwidth.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyPodcastEpisodes(response: PodcastEpisodesResponse) {
        val characteristic = podcastInfoCharacteristic ?: return
        val server = gattServer ?: return

        // Convert to compact format for BLE transfer
        val compactResponse = response.toCompact()
        val jsonData = json.encodeToString(compactResponse).toByteArray(Charsets.UTF_8)
        val maxChunkSize = 500
        val chunks = jsonData.toList().chunked(maxChunkSize)

        Log.d(TAG, "Sending podcast episodes: ${response.episodes.size} episodes for ${response.podcastTitle}, ${jsonData.size} bytes (compact) in ${chunks.size} chunks")
        Log.i("PODCAST", "   BLE transmission: ${jsonData.size} bytes (compact) in ${chunks.size} chunks")

        for (device in notificationEnabledDevices) {
            for ((index, chunk) in chunks.withIndex()) {
                throttler.throttle()

                // Header: [type=3][chunkIndex][totalChunks][data...]
                // type=3 indicates podcast episodes response
                val header = byteArrayOf(3, index.toByte(), chunks.size.toByte())
                val chunkData = header + chunk.toByteArray()

                characteristic.value = chunkData
                @Suppress("DEPRECATION")
                val sent = server.notifyCharacteristicChanged(device, characteristic, false)
                if (!sent) {
                    Log.w(TAG, "Failed to notify podcast episodes chunk $index to ${device.address}")
                    Log.w("PODCAST", "   ⚠️ Failed to send chunk ${index + 1}/${chunks.size}")
                    break
                }
            }
        }
    }

    /**
     * Notifies all connected devices with media channel list (audio apps with active sessions).
     * Uses binary format: 2-byte count (big-endian) + for each channel: 1-byte len + N-byte name
     * Header type=4 indicates media channels response.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyMediaChannels(channels: List<String>) {
        val characteristic = podcastInfoCharacteristic ?: return
        val server = gattServer ?: return

        Log.i("MEDIA_CHANNELS", "═══════════════════════════════════════════════")
        Log.i("MEDIA_CHANNELS", "📤 SENDING MEDIA CHANNELS")
        Log.i("MEDIA_CHANNELS", "   Channels: ${channels.size}")
        channels.forEach { channel ->
            Log.i("MEDIA_CHANNELS", "   - $channel")
        }

        // Build binary payload: 2-byte count + (1-byte len + name bytes) for each channel
        val binaryData = buildMediaChannelsBinary(channels)
        Log.i("MEDIA_CHANNELS", "   Binary size: ${binaryData.size} bytes")

        // Split into 500-byte chunks if needed
        val maxChunkSize = 500
        val chunks = binaryData.toList().chunked(maxChunkSize)

        Log.i("MEDIA_CHANNELS", "   BLE chunks: ${chunks.size}")

        for (device in notificationEnabledDevices) {
            for ((index, chunk) in chunks.withIndex()) {
                throttler.throttle()

                // Header: [type=4][chunkIndex][totalChunks][data...]
                // type=4 indicates media channels response
                val header = byteArrayOf(4, index.toByte(), chunks.size.toByte())
                val chunkData = header + chunk.toByteArray()

                characteristic.value = chunkData
                @Suppress("DEPRECATION")
                val sent = server.notifyCharacteristicChanged(device, characteristic, false)
                if (!sent) {
                    Log.w(TAG, "Failed to notify media channels chunk $index to ${device.address}")
                    Log.w("MEDIA_CHANNELS", "   ⚠️ Failed to send chunk ${index + 1}/${chunks.size}")
                    break
                }
            }
            Log.i("MEDIA_CHANNELS", "   ✅ Sent to ${device.address}")
        }
        Log.i("MEDIA_CHANNELS", "═══════════════════════════════════════════════")
    }

    /**
     * Builds binary payload for media channels.
     * Format: 2-byte uint16 count (big-endian) + for each channel: 1-byte len + N-byte UTF-8 name
     */
    private fun buildMediaChannelsBinary(channels: List<String>): ByteArray {
        val output = mutableListOf<Byte>()

        // 2-byte count (big-endian)
        val count = channels.size.coerceAtMost(65535)
        output.add((count shr 8).toByte())
        output.add((count and 0xFF).toByte())

        // For each channel: 1-byte length + UTF-8 name bytes
        for (channel in channels) {
            val nameBytes = channel.toByteArray(Charsets.UTF_8)
            val len = nameBytes.size.coerceAtMost(255)
            output.add(len.toByte())
            output.addAll(nameBytes.take(len).toList())
        }

        return output.toByteArray()
    }

    /**
     * Notifies all connected devices with lyrics data.
     * Sends data in chunks to accommodate BLE MTU limits.
     * Each CompactLyricsResponse is serialized to JSON, then split into 500-byte BLE packets.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyLyricsData(chunks: List<CompactLyricsResponse>) {
        val characteristic = lyricsDataCharacteristic ?: return
        val server = gattServer ?: return

        if (chunks.isEmpty()) {
            Log.w("LYRICS", "No lyrics chunks to send")
            return
        }

        val hash = chunks.firstOrNull()?.h ?: "unknown"
        val totalLines = chunks.firstOrNull()?.n ?: 0

        Log.i("LYRICS", "═══════════════════════════════════════════════")
        Log.i("LYRICS", "📤 STARTING LYRICS TRANSMISSION")
        Log.i("LYRICS", "   Hash: $hash")
        Log.i("LYRICS", "   Total lines: $totalLines")
        Log.i("LYRICS", "   Lyrics chunks: ${chunks.size}")
        Log.i("LYRICS", "   Target devices: ${notificationEnabledDevices.size}")

        // Max BLE notification payload size (conservative for compatibility)
        val maxBleChunkSize = 500

        for (device in notificationEnabledDevices) {
            for ((chunkIndex, chunk) in chunks.withIndex()) {
                // Serialize the lyrics chunk to JSON
                val jsonData = json.encodeToString(chunk).toByteArray(Charsets.UTF_8)

                // Split JSON into smaller BLE packets if needed
                val blePackets = jsonData.toList().chunked(maxBleChunkSize)
                val totalBlePackets = blePackets.size

                Log.d("LYRICS", "   Chunk ${chunkIndex + 1}/${chunks.size}: ${jsonData.size} bytes -> $totalBlePackets BLE packets")

                for ((packetIndex, packetData) in blePackets.withIndex()) {
                    throttler.throttle()

                    // Header: [lyricsChunkIndex, blePacketIndex, totalBlePackets]
                    val header = byteArrayOf(
                        chunkIndex.toByte(),
                        packetIndex.toByte(),
                        totalBlePackets.toByte()
                    )
                    val packet = header + packetData.toByteArray()
                    characteristic.value = packet

                    @Suppress("DEPRECATION")
                    val sent = server.notifyCharacteristicChanged(device, characteristic, false)
                    if (!sent) {
                        Log.w(TAG, "Failed to notify lyrics packet to ${device.address}")
                        Log.w("LYRICS", "   ⚠️ Failed to send packet ${packetIndex + 1}/$totalBlePackets of chunk ${chunkIndex + 1}")
                        break
                    }
                }
            }
            Log.i("LYRICS", "   ✅ Transmission complete to ${device.address}")
        }
        Log.i("LYRICS", "═══════════════════════════════════════════════")
    }

    /**
     * Notifies all connected devices to clear their cached lyrics.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyLyricsClear(hash: String? = null) {
        Log.i("LYRICS", "───────────────────────────────────────────────")
        Log.i("LYRICS", "🧹 NOTIFY CLEAR - Sending clear command")
        Log.i("LYRICS", "   Hash: ${hash ?: "(all)"}")
        Log.i("LYRICS", "   Target devices: ${notificationEnabledDevices.size}")

        val characteristic = lyricsDataCharacteristic ?: run {
            Log.w("LYRICS", "   ⚠️ Cannot send: lyricsDataCharacteristic is null")
            return
        }
        val server = gattServer ?: run {
            Log.w("LYRICS", "   ⚠️ Cannot send: gattServer is null")
            return
        }

        // Send empty response to indicate no lyrics / clear
        val clearResponse = CompactLyricsResponse(
            h = hash ?: "",
            s = false,
            n = 0,
            c = 0,
            m = 1,
            l = emptyList()
        )

        val jsonData = json.encodeToString(clearResponse).toByteArray(Charsets.UTF_8)
        characteristic.value = jsonData

        for (device in notificationEnabledDevices) {
            throttler.throttle()
            @Suppress("DEPRECATION")
            val sent = server.notifyCharacteristicChanged(device, characteristic, false)
            if (!sent) {
                Log.w("LYRICS", "   ⚠️ Failed to notify clear to ${device.address}")
                Log.w(TAG, "Failed to notify lyrics clear to ${device.address}")
            } else {
                Log.d("LYRICS", "   ✅ Clear sent to ${device.address}")
            }
        }
        Log.d(TAG, "Lyrics clear notification sent")
    }

    /**
     * Notifies connected devices of a settings change.
     * Settings are sent as a JSON object.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifySettings(settings: Map<String, Any>) {
        val characteristic = settingsCharacteristic ?: return
        val server = gattServer ?: return

        // Manually build JSON since kotlinx.serialization can't serialize Map<String, Any>
        val jsonString = buildString {
            append("{")
            settings.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"$key\":")
                when (value) {
                    is Boolean -> append(value)
                    is Number -> append(value)
                    is String -> append("\"$value\"")
                    else -> append("\"$value\"")
                }
            }
            append("}")
        }
        val jsonData = jsonString.toByteArray(Charsets.UTF_8)
        characteristic.value = jsonData

        for (device in notificationEnabledDevices) {
            throttler.throttle()
            @Suppress("DEPRECATION")
            val sent = server.notifyCharacteristicChanged(device, characteristic, false)
            if (!sent) {
                Log.w(TAG, "Failed to notify settings to ${device.address}")
            } else {
                Log.d(TAG, "Settings notification sent to ${device.address}")
            }
        }
        Log.i(TAG, "Settings notification sent: $jsonString")
    }

    /**
     * Notifies connected devices of lyrics enabled setting change.
     */
    @SuppressLint("MissingPermission")
    suspend fun notifyLyricsEnabled(enabled: Boolean) {
        Log.i("LYRICS", "📢 NOTIFY SETTING - lyricsEnabled: $enabled")
        notifySettings(mapOf("lyricsEnabled" to enabled))
    }
}
