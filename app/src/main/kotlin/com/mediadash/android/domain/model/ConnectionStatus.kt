package com.mediadash.android.domain.model

/**
 * Represents the BLE connection status.
 */
sealed class ConnectionStatus {
    data object Disconnected : ConnectionStatus()
    data object Advertising : ConnectionStatus()
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()

    val isConnected: Boolean
        get() = this is Connected

    val displayText: String
        get() = when (this) {
            is Disconnected -> "Disconnected"
            is Advertising -> "Waiting for connection..."
            is Connected -> "Connected to $deviceName"
            is Error -> "Error: $message"
        }
}
