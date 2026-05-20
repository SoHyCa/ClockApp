package com.example.clockapp.ble

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.clockapp.protocol.ProtocolDecoder
import com.example.clockapp.protocol.ProtocolEncoder
import com.example.clockapp.protocol.ProtocolField
import com.example.clockapp.state.ConnectionState
import java.util.UUID

class BLEConnectionManager(
    private val context: Context,
    private val dataProvider: DataProvider
) {
    private val tag = "BLEConnectionManager"
    private val macAddress = "A4:CB:8F:22:74:0A"
    private val serviceUuid = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val characteristicUuid = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var onDisconnectedCallback: (() -> Unit)? = null
    fun setOnDisconnectedCallback(callback: () -> Unit) {
        onDisconnectedCallback = callback
    }

    private var onRequestReceivedCallback: (() -> Unit)? = null
    fun setOnRequestReceivedCallback(callback: () -> Unit) {
        onRequestReceivedCallback = callback
    }
    private var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var onConnectedCallback: (() -> Unit)? = null

    private val sendQueue = mutableListOf<ByteArray>()
    private var isSending = false

    init {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
    }

    fun connect(onConnected: (() -> Unit)? = null) {
        this.onConnectedCallback = onConnected

        if (!bluetoothAdapter.isEnabled) {
            Log.e(tag, "Bluetooth disabled")
            ConnectionState.statusText = "Статус: Bluetooth выключен"
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(tag, "BLUETOOTH_CONNECT permission not granted")
                ConnectionState.statusText = "Статус: Нет разрешения"
                return
            }
        }

        ConnectionState.isConnecting = true
        Log.d(tag, "Connecting to $macAddress")

        try {
            val device = bluetoothAdapter.getRemoteDevice(macAddress)
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: IllegalArgumentException) {
            Log.e(tag, "Invalid MAC address: $macAddress")
            ConnectionState.isConnecting = false
            ConnectionState.statusText = "Статус: Неверный MAC"
        }
    }

    fun disconnect() {
        Log.d(tag, "Disconnecting")
        val wasActive = ConnectionState.isConnected || ConnectionState.isConnecting
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error disconnecting: ${e.message}")
        }
        bluetoothGatt = null
        writeCharacteristic = null
        sendQueue.clear()
        isSending = false
        ConnectionState.isConnected = false
        ConnectionState.isConnecting = false
        ConnectionState.statusText = "Статус: Отключено"
        if (wasActive) {
            onDisconnectedCallback?.invoke()
        }
    }

    fun sendFields(fields: List<ProtocolField>) {
        if (!ConnectionState.isConnected) {
            Log.w(tag, "Not connected, cannot send")
            return
        }
        if (writeCharacteristic == null) {
            Log.w(tag, "Characteristic not available, cannot send")
            return
        }

        val packets = ProtocolEncoder.encodeMessage(fields)
        Log.d(tag, "Sending ${packets.size} packet(s)")
        enqueuePackets(packets)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Handler(Looper.getMainLooper()).post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(tag, "Connected to ESP32")
                        ConnectionState.isConnected = true
                        ConnectionState.isConnecting = false
                        ConnectionState.statusText = "Подключено, ищу сервисы..."
                        // Сначала discoverServices, MTU позже
                        gatt?.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(tag, "Disconnected from ESP32")
                        disconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(tag, "Service discovery failed: $status")
                return
            }

            val service = gatt?.getService(serviceUuid)
            if (service == null) {
                Log.e(tag, "Service $serviceUuid not found")
                return
            }

            val characteristic = service.getCharacteristic(characteristicUuid)
            if (characteristic == null) {
                Log.e(tag, "Characteristic $characteristicUuid not found")
                return
            }

            writeCharacteristic = characteristic
            Log.d(tag, "Characteristic found, enabling notifications")
            enableNotifications(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor?.uuid == cccdUuid) {
                Log.d(tag, "Notifications enabled, requesting MTU 512")
                // MTU запрашиваем ПОСЛЕ включения уведомлений
                bluetoothGatt?.requestMtu(512)
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(tag, "Failed to enable notifications: $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(tag, "MTU changed to $mtu")
                // Теперь всё готово, сообщаем сервису
                onConnectedCallback?.invoke()
            } else {
                Log.e(tag, "MTU request failed: $status")
                // Даже если MTU не поменялся, всё равно работаем
                onConnectedCallback?.invoke()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val data = characteristic?.value ?: return
            Log.d(tag, "Received ${data.size} bytes via notification")

            val result = ProtocolDecoder.processPacket(data, data.size)
            if (result == null) {
                Log.w(tag, "Failed to parse packet")
                return
            }

            if (result.isRequest) {
                Log.i(tag, "Received request with tags: ${result.tags}")
                handleRequest(result.tags)
            } else {
                Log.w(tag, "Received data instead of request")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendNextPacket()
            } else {
                Log.e(tag, "Write failed: $status")
                isSending = false
                sendQueue.clear()
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt?) {
        val char = writeCharacteristic ?: return
        gatt?.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(cccdUuid)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt?.writeDescriptor(descriptor)
        } else {
            Log.w(tag, "CCCD descriptor not found")
            // Нет CCCD — пробуем работать без него (маловероятно, что сработает)
            bluetoothGatt?.requestMtu(512)
        }
    }

    private fun handleRequest(tags: List<Int>) {
        val fields = mutableListOf<ProtocolField>()
        for (tag in tags) {
            when (tag) {
                ProtocolDecoder.REQ_TIME ->
                    fields.add(ProtocolField.UnixTime(dataProvider.getUnixTime()))
                ProtocolDecoder.REQ_VPN ->
                    fields.add(ProtocolField.Vpn(dataProvider.getVpnStatus()))
                ProtocolDecoder.REQ_PLAYBACK ->
                    fields.add(ProtocolField.Playback(dataProvider.getPlaybackStatus()))
                ProtocolDecoder.REQ_ARTIST -> {
                    val artist = dataProvider.getArtist()
                    if (artist.isNotEmpty()) fields.add(ProtocolField.Artist(artist))
                }
                ProtocolDecoder.REQ_TRACK -> {
                    val track = dataProvider.getTrack()
                    if (track.isNotEmpty()) fields.add(ProtocolField.Track(track))
                }
            }
        }

        // Уведомляем сервис о входящем запросе (для сброса таймаута)
        onRequestReceivedCallback?.invoke()

        if (fields.isNotEmpty()) {
            Log.i(tag, "Sending response with ${fields.size} field(s)")
            sendFields(fields)
        } else {
            Log.w(tag, "No fields to send")
        }
    }

    private fun enqueuePackets(packets: List<ByteArray>) {
        sendQueue.addAll(packets)
        if (!isSending) sendNextPacket()
    }

    private fun sendNextPacket() {
        if (sendQueue.isEmpty()) {
            isSending = false
            return
        }
        isSending = true
        val packet = sendQueue.removeAt(0)
        writeCharacteristic?.let { char ->
            char.value = packet
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothGatt?.writeCharacteristic(char)
                } else {
                    isSending = false
                }
            } else {
                bluetoothGatt?.writeCharacteristic(char)
            }
        } ?: run {
            Log.e(tag, "Characteristic null, cannot send")
            isSending = false
        }
    }
}