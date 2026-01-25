package com.mediadash.android.domain.model

import kotlinx.serialization.Serializable

/**
 * Response containing connection status for external services.
 * Sent back via BLE when a check_connection or check_all_connections command is received.
 */
@Serializable
data class ConnectionStatusResponse(
    val services: Map<String, String>,  // service name -> status ("connected", "disconnected", "error:...")
    val timestamp: Long                  // Unix timestamp when status was checked
) {
    companion object {
        // Service names
        const val SERVICE_SPOTIFY = "spotify"

        // Status values
        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISCONNECTED = "disconnected"

        fun errorStatus(message: String): String = "error:$message"
    }
}
