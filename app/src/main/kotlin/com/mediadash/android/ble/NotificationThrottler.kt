package com.mediadash.android.ble

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rate limiter for BLE notifications.
 * Enforces a minimum interval between notifications to prevent
 * ATT error 0x0e (Insufficient Resources) on the Go client.
 */
@Singleton
class NotificationThrottler @Inject constructor() {

    private val mutex = Mutex()
    private var lastNotificationTime = 0L

    /**
     * Throttles notifications to ensure minimum interval.
     * Suspends if called too soon after the previous notification.
     */
    suspend fun throttle() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastNotificationTime
            val remaining = BleConstants.NOTIFICATION_INTERVAL_MS - elapsed

            if (remaining > 0) {
                delay(remaining)
            }

            lastNotificationTime = System.currentTimeMillis()
        }
    }

    /**
     * Resets the throttler state.
     * Call when connection is lost or service is stopped.
     */
    fun reset() {
        lastNotificationTime = 0L
    }
}
