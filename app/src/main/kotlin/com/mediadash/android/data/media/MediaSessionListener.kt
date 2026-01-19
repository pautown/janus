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
     * Prioritizes playing sessions, then falls back to the most recently active.
     */
    private fun updateActiveSession(controllers: List<MediaController>) {
        if (controllers.isEmpty()) {
            Log.d(TAG, "No active media sessions")
            mediaControllerManager.clearActiveController()
            return
        }

        // Find a playing session first
        val playingController = controllers.find { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        }

        // Use playing session, or fall back to first available
        val selectedController = playingController ?: controllers.first()

        Log.d(TAG, "Selected media session: ${selectedController.packageName}")
        mediaControllerManager.setActiveController(selectedController)
    }
}
