package com.example.clockapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.clockapp.MainActivity
import com.example.clockapp.MediaNotificationListener
import com.example.clockapp.R
import com.example.clockapp.ble.BLEConnectionManager
import com.example.clockapp.ble.DataProvider
import com.example.clockapp.protocol.ProtocolField
import com.example.clockapp.state.ConnectionState

class BleExchangeService : Service(), DataProvider {

    private val binder = LocalBinder()
    private lateinit var bleManager: BLEConnectionManager

    inner class LocalBinder : Binder() {
        fun getService(): BleExchangeService = this@BleExchangeService
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        bleManager = BLEConnectionManager(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> bleManager.connect()
            ACTION_DISCONNECT -> bleManager.disconnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }

    fun connect() = bleManager.connect()
    fun disconnect() = bleManager.disconnect()

    fun sendAllData() {
        if (!ConnectionState.isConnected) return
        val timestamp = (System.currentTimeMillis() / 1000).toUInt()
        val vpn = checkVpn()
        val (track, artist, isPlaying) = getCurrentMediaInfo()

        val fields = mutableListOf<ProtocolField>()
        fields.add(ProtocolField.UnixTime(timestamp))
        fields.add(ProtocolField.Vpn(vpn))
        fields.add(ProtocolField.Playback(isPlaying))
        if (artist.isNotEmpty()) fields.add(ProtocolField.Artist(artist))
        if (track.isNotEmpty()) fields.add(ProtocolField.Track(track))

        bleManager.sendFields(fields)
    }

    // --- DataProvider (автоответ на запросы ESP32) ---

    override fun getUnixTime(): UInt = (System.currentTimeMillis() / 1000).toUInt()
    override fun getVpnStatus(): Boolean = checkVpn()
    override fun getPlaybackStatus(): Boolean = getCurrentMediaInfo().third
    override fun getArtist(): String = getCurrentMediaInfo().second
    override fun getTrack(): String = getCurrentMediaInfo().first

    // --- Helpers ---

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

    private fun createNotification(): Notification {
        val channelId = "ble_exchange_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "BLE Обмен",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновое подключение к ESP32"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("BLE-шлюз активен")
            .setContentText("Ожидание подключения ESP32...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.example.clockapp.CONNECT"
        const val ACTION_DISCONNECT = "com.example.clockapp.DISCONNECT"
    }
}