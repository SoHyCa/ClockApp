package com.example.clockapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.clockapp.state.ConnectionState

@Composable
fun BleControlScreen(
    onConnectClick: () -> Unit,
    onSendAllClick: () -> Unit,
    onCheckVpnClick: () -> Unit,
    onGetMediaClick: () -> Unit,
    isConnected: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Button(
            onClick = onConnectClick,
            enabled = !ConnectionState.isConnecting
        ) {
            Text(
                text = when {
                    ConnectionState.isConnecting -> "Подключение..."
                    isConnected -> "Подключено"
                    else -> "Подключиться к ESP32"
                }
            )
        }

        Text(
            text = ConnectionState.statusText,
            style = MaterialTheme.typography.bodyLarge
        )

        Button(onClick = onCheckVpnClick) {
            Text("Статус подключения к VPN")
        }

        Button(onClick = onGetMediaClick) {
            Text("Сведения о медиа")
        }

        if (isConnected) {
            Button(
                onClick = onSendAllClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Отправить всё")
            }
        }
    }
}