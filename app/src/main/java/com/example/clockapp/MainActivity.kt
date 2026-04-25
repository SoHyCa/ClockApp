package com.example.clockapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.clockapp.ble.BLEConnectionManager
import com.example.clockapp.protocol.ProtocolField
import com.example.clockapp.state.ConnectionState
import com.example.clockapp.ui.BleControlScreen
import com.example.clockapp.ui.theme.BleEsp32Theme

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BLEConnectionManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            bleManager.connect()
        } else {
            Toast.makeText(this, "Необходимы разрешения", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BLEConnectionManager(this)

        setContent {
            BleEsp32Theme {
                BleControlScreen(
                    onConnectClick = { checkPermissionsAndConnect() },
                    onSendAllClick = { sendAllData() },
                    onCheckVpnClick = {
                        val isVpn = checkVpn()
                        Toast.makeText(
                            this,
                            if (isVpn) "VPN подключен" else "VPN отключен",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onGetMediaClick = {
                        val (track, artist, isPlaying) = getCurrentMediaInfo()
                        val message = if (track.isEmpty()) {
                            "Медиа отсутствует"
                        } else {
                            val status = if (isPlaying) "Воспроизводится" else "Пауза"
                            "$artist - $track, $status"
                        }
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    },
                    isConnected = ConnectionState.isConnected
                )
            }
        }

        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun checkPermissionsAndConnect() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            bleManager.connect()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkVpn(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private fun getCurrentMediaInfo(): Triple<String, String, Boolean> {
        return Triple(
            MediaNotificationListener.currentTrack,
            MediaNotificationListener.currentArtist,
            MediaNotificationListener.isPlaying
        )
    }

    private fun sendAllData() {
        val timestamp = (System.currentTimeMillis() / 1000).toUInt()
        val vpn = checkVpn()
        val (track, artist, isPlaying) = getCurrentMediaInfo()

        val fields = mutableListOf<ProtocolField>()
        fields.add(ProtocolField.UnixTime(timestamp))
        fields.add(ProtocolField.Vpn(vpn))
        if (track.isNotEmpty()) fields.add(ProtocolField.Playback(isPlaying))
        if (artist.isNotEmpty()) fields.add(ProtocolField.Artist(artist))
        if (track.isNotEmpty()) fields.add(ProtocolField.Track(track))

        bleManager.sendFields(fields)
        Toast.makeText(this, "Все данные отправлены", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}