package com.mediadash.android.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mediadash.android.domain.model.ConnectionStatus

// Ethereal color palette
private val EtherealMint = Color(0xFFA5D6A7)  // Connected - soft mint
private val EtherealAmber = Color(0xFFFFE082)  // Advertising - soft amber
private val EtherealGray = Color(0xFFB0BEC5)  // Disconnected - soft blue-gray
private val EtherealViolet = Color(0xFFB39DDB)  // Error - soft lavender

@Composable
fun ConnectionStatusCard(
    status: ConnectionStatus,
    modifier: Modifier = Modifier
) {
    val (statusColor, statusText) = when (status) {
        is ConnectionStatus.Connected -> EtherealMint to "Connected"
        is ConnectionStatus.Advertising -> EtherealAmber to "Advertising"
        is ConnectionStatus.Disconnected -> EtherealGray to "Disconnected"
        is ConnectionStatus.Error -> EtherealViolet to "Error"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator dot
            Icon(
                painter = painterResource(android.R.drawable.presence_online),
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor
                )

                when (status) {
                    is ConnectionStatus.Connected -> {
                        Text(
                            text = "${status.deviceName} (${status.deviceAddress})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    is ConnectionStatus.Error -> {
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = EtherealViolet.copy(alpha = 0.8f)
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}
