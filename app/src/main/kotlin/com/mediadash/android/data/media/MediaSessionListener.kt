package com.mediadash.android.data.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * NotificationListenerService that monitors active media sessions.
 * This is required to access MediaSession from other apps like Spotify, YouTube Music, etc.
 *
 * Users must grant notification access permission in Settings for this to work.
 */
@AndroidEntryPoint
class MediaSessionListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaSessionListener"

        @Volatile
        private var instance: MediaSessionListener? = null

        fun getInstance(): MediaSessionListener? = instance

        /**
         * Checks if notification listener permission is granted.
         */
        fun isPermissionGranted(context: Context): Boolean {
            val componentName = ComponentName(context, MediaSessionListener::class.java)
            val enabledListeners = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return enabledListeners?.contains(componentName.flattenToString()) == true
        }
    }

    @Inject
    lateinit var mediaControllerManager: MediaControllerManager

    private var mediaSessionManager: MediaSessionManager? = null
    private var isListening = false

    // Selected channel to control (null = auto-select playing/first)
    private var selectedChannelName: String? = null

    // Last known controllers for channel selection
    private var lastControllers: List<MediaController> = emptyList()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(TAG, "Active sessions changed: ${controllers?.size ?: 0} sessions")
        controllers?.let { updateActiveSession(it) }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MediaSessionListener created")
        instance = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected")

        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        try {
            val componentName = ComponentName(this, MediaSessionListener::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
            isListening = true

            // Get initial sessions
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            controllers?.let { updateActiveSession(it) }

            Log.d(TAG, "Started listening for media sessions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening for media sessions", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Listener disconnected")

        if (isListening) {
            try {
                mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing session listener", e)
            }
            isListening = false
        }

        mediaControllerManager.clearActiveController()
    }

    override fun onDestroy() {
        Log.d(TAG, "MediaSessionListener destroyed")
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // We don't need to handle notifications directly
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // We don't need to handle notifications directly
    }

    /**
     * Updates the active media controller based on the current sessions.
     * Priority: 1. Selected channel if set, 2. New playing session (auto-switch), 3. First available
     */
    private fun updateActiveSession(controllers: List<MediaController>) {
        lastControllers = controllers

        if (controllers.isEmpty()) {
            Log.d(TAG, "No active media sessions")
            mediaControllerManager.clearActiveController()
            return
        }

        // Check if a new session started playing (for auto-switch)
        val playingController = controllers.find { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        }

        // If something new started playing, auto-switch to it
        if (playingController != null) {
            val playingAppName = getAppNameForPackage(playingController.packageName)
            val currentControlledAppName = mediaControllerManager.controlledAppName.value

            // If this is a different app that just started playing, switch to it
            if (playingAppName != currentControlledAppName) {
                Log.i(TAG, "Auto-switching to newly playing app: $playingAppName")
                selectedChannelName = playingAppName
            }
        }

        // Try to find the selected channel
        var targetController: MediaController? = null
        if (!selectedChannelName.isNullOrEmpty()) {
            targetController = controllers.find { controller ->
                getAppNameForPackage(controller.packageName) == selectedChannelName
            }
            if (targetController == null) {
                Log.w(TAG, "Selected channel '$selectedChannelName' not found in active sessions")
            }
        }

        // Fall back to playing session or first available
        val finalController = targetController
            ?: playingController
            ?: controllers.first()

        val appName = getAppNameForPackage(finalController.packageName)
        Log.d(TAG, "Selected media session: ${finalController.packageName} ($appName)")
        mediaControllerManager.setActiveController(finalController, appName)
    }

    /**
     * Converts a package name to a human-readable app name.
     */
    private fun getAppNameForPackage(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Selects a specific media channel by name.
     * The name should match the app's display name (e.g., "Spotify", "YouTube Music").
     * If a different channel is currently playing, it will be paused before switching.
     */
    fun selectChannel(channelName: String) {
        Log.i(TAG, "Selecting channel: $channelName")

        // Before switching, pause any currently playing session that isn't the target
        val currentlyPlaying = lastControllers.find { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (currentlyPlaying != null) {
            val playingAppName = getAppNameForPackage(currentlyPlaying.packageName)
            if (playingAppName != channelName) {
                Log.i(TAG, "Pausing currently playing app: $playingAppName")
                try {
                    currentlyPlaying.transportControls.pause()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to pause $playingAppName", e)
                }
            }
        }

        selectedChannelName = channelName

        // Re-evaluate active session with the new selection
        if (lastControllers.isNotEmpty()) {
            updateActiveSession(lastControllers)
        }
    }

    /**
     * Gets the currently selected channel name.
     */
    fun getSelectedChannel(): String? = selectedChannelName

    /**
     * Gets the list of all active media channel app names (package names converted to display names).
     * Returns empty list if no sessions are active or permission not granted.
     */
    fun getActiveMediaChannels(): List<String> {
        val componentName = ComponentName(this, MediaSessionListener::class.java)
        val controllers = mediaSessionManager?.getActiveSessions(componentName) ?: emptyList()

        return controllers.mapNotNull { controller ->
            // Convert package name to human-readable app name
            try {
                val packageManager = packageManager
                val appInfo = packageManager.getApplicationInfo(controller.packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                Log.w(TAG, "Could not get app name for ${controller.packageName}", e)
                // Fallback to package name if app name not found
                controller.packageName.substringAfterLast('.')
                    .replaceFirstChar { it.uppercase() }
            }
        }.distinct()
    }
}
