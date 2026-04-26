package com.example.clockapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.clockapp.service.BleExchangeService
import com.example.clockapp.state.ConnectionState
import com.example.clockapp.ui.BleControlScreen
import com.example.clockapp.ui.theme.BleEsp32Theme

class MainActivity : ComponentActivity() {

    private var bleService: BleExchangeService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleExchangeService.LocalBinder
            bleService = binder.getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startAndConnectService()
        } else {
            Toast.makeText(this, "Необходимы разрешения", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BleEsp32Theme {
                BleControlScreen(
                    onConnectClick = { checkPermissionsAndConnect() },
                    onSendAllClick = {
                        bleService?.sendAllData()
                            ?: Toast.makeText(this, "Сервис не запущен", Toast.LENGTH_SHORT).show()
                    },
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

    override fun onStart() {
        super.onStart()
        Intent(this, BleExchangeService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    private fun requestNotificationPermission() {
        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun checkPermissionsAndConnect() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.FOREGROUND_SERVICE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.FOREGROUND_SERVICE
            )
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startAndConnectService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAndConnectService() {
        val intent = Intent(this, BleExchangeService::class.java).apply {
            action = BleExchangeService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(this, intent)
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
}