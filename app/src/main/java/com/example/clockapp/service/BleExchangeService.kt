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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.clockapp.MainActivity
import com.example.clockapp.MediaNotificationListener
import com.example.clockapp.R
import com.example.clockapp.ble.BLEConnectionManager
import com.example.clockapp.ble.DataProvider
import com.example.clockapp.state.ConnectionState

class BleExchangeService : Service(), DataProvider {

    private val binder = LocalBinder()
    private var bleManager: BLEConnectionManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    /** true — после успешного ручного подключения автоподключение активно */
    private var autoConnectEnabled = false

    /** Запускается ровно через 64 сек после разрыва */
    private val reconnectRunnable = Runnable {
        if (autoConnectEnabled && !ConnectionState.isConnected && !ConnectionState.isConnecting) {
            ConnectionState.statusText = "Статус: Автопереподключение..."
            doConnect()
        }
    }

    private val disconnectRunnable = Runnable {
        if (ConnectionState.isConnected) {
            ConnectionState.statusText = "Статус: Таймаут запроса, отключение"
            bleManager?.disconnect()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleExchangeService = this@BleExchangeService
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        acquireWakeLock()
        bleManager = BLEConnectionManager(this, this)
        bleManager?.setOnDisconnectedCallback {
            handler.post { onDisconnected() }
        }
        bleManager?.setOnRequestReceivedCallback {
            handler.post { onRequestReceived() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        autoConnectEnabled = false
        handler.removeCallbacks(disconnectRunnable)
        handler.removeCallbacks(reconnectRunnable)
        bleManager?.disconnect()
        releaseWakeLock()
    }

    /** Ручное подключение по кнопке. При успехе включает автоподключение. */
    fun forceConnect() {
        handler.removeCallbacks(reconnectRunnable)
        doConnect()
    }

    private fun doConnect() {
        handler.removeCallbacks(reconnectRunnable)
        if (ConnectionState.isConnected) {
            bleManager?.disconnect()
        }
        ConnectionState.statusText = "Статус: Подключение..."
        bleManager?.connect {
            onConnected()
        }
    }

    private fun onConnected() {
        handler.removeCallbacks(disconnectRunnable)
        handler.removeCallbacks(reconnectRunnable)
        // Таймер НЕ запускаем здесь — он стартует только при первом запросе от ESP32

        // Включаем автоподключение только после УСПЕШНОГО первого соединения
        if (!autoConnectEnabled) {
            autoConnectEnabled = true
            updateNotification("Автоподключение активно")
        }
    }

    /** Вызывается при каждом входящем запросе — сбрасывает таймаут отключения */
    private fun onRequestReceived() {
        handler.removeCallbacks(disconnectRunnable)
        handler.postDelayed(disconnectRunnable, REQUEST_TIMEOUT_MS)
    }

    /** Вызывается при любом разрыве */
    private fun onDisconnected() {
        handler.removeCallbacks(disconnectRunnable)
        if (autoConnectEnabled && !ConnectionState.isConnected && !ConnectionState.isConnecting) {
            ConnectionState.statusText = "Статус: Автоматический реконнект"
            handler.postDelayed(reconnectRunnable, 64_000L)
        } else if (!autoConnectEnabled) {
            ConnectionState.statusText = "Статус: Отключено"
        }
    }

    override fun getUnixTime(): UInt = (System.currentTimeMillis() / 1000).toUInt()
    override fun getVpnStatus(): Boolean = checkVpn()
    override fun getPlaybackStatus(): Boolean = getCurrentMediaInfo().third
    override fun getArtist(): String = getCurrentMediaInfo().second
    override fun getTrack(): String = getCurrentMediaInfo().first

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

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ClockApp::BleExchangeWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(contentText: String = "Нажмите 'Подключиться' для первого соединения"): Notification {
        val channelId = "ble_exchange_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "BLE Обмен",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ожидание запросов от ESP32"
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
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val REQUEST_TIMEOUT_MS = 200_000L
    }
}