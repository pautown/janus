package com.mediadash.android.ui.composables

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Ethereal color palette
private val EtherealViolet = Color(0xFFB39DDB)  // Soft lavender
private val EtherealCyan = Color(0xFF80DEEA)  // Soft cyan
private val EtherealMint = Color(0xFFA5D6A7)  // Soft mint

@Composable
fun ServiceToggleCard(
    isRunning: Boolean,
    isBluetoothEnabled: Boolean,
    onToggle: () -> Unit,
    onEnableBluetooth: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Graceful breathing animation for disabled Bluetooth
    val infiniteTransition = rememberInfiniteTransition(label = "breathe")
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2500,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheAlpha"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Service toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isRunning) "BLE Service Active" else "BLE Service Inactive",
                    style = MaterialTheme.typography.titleMedium
                )

                Switch(
                    checked = isRunning,
                    onCheckedChange = { onToggle() },
                    enabled = isBluetoothEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = EtherealMint,
                        checkedTrackColor = EtherealMint.copy(alpha = 0.5f),
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledCheckedThumbColor = EtherealViolet.copy(alpha = 0.5f),
                        disabledUncheckedThumbColor = EtherealViolet.copy(alpha = 0.5f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bluetooth status row - always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (!isBluetoothEnabled) {
                            Modifier.clickable { onEnableBluetooth() }
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isBluetoothEnabled) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isBluetoothEnabled) {
                        EtherealMint
                    } else {
                        EtherealViolet.copy(alpha = breatheAlpha)
                    }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = if (isBluetoothEnabled) "Bluetooth Enabled" else "Bluetooth Disabled",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = if (isBluetoothEnabled) {
                            EtherealMint
                        } else {
                            EtherealViolet.copy(alpha = breatheAlpha)
                        }
                    )

                    if (!isBluetoothEnabled) {
                        Text(
                            text = "Tap to open Bluetooth settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = EtherealCyan.copy(alpha = breatheAlpha * 0.85f)
                        )
                    }
                }
            }
        }
    }
}
