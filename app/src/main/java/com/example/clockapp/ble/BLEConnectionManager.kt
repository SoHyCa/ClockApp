package com.example.clockapp.ble

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.clockapp.protocol.*
import com.example.clockapp.state.ConnectionState
import java.util.UUID

class BLEConnectionManager(
    private val context: Context,
    private val dataProvider: DataProvider
) {
    private val TAG = "BLEConnectionManager"
    private val macAddress = "94:A9:90:98:02:E6"
    private val serviceUuid = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val characteristicUuid = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val sendQueue = mutableListOf<ByteArray>()
    private var isSending = false

    init {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        Log.d(TAG, "BLEConnectionManager initialized")
    }

    fun connect() {
        Log.d(TAG, "connect() called")
        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            Toast.makeText(context, "Bluetooth выключен", Toast.LENGTH_SHORT).show()
            return
        }

        ConnectionState.isConnecting = true
        ConnectionState.statusText = "Статус: Подключение..."

        try {
            val device = bluetoothAdapter.getRemoteDevice(macAddress)
            Log.d(TAG, "Connecting to device: $macAddress")
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid MAC address", e)
            ConnectionState.isConnecting = false
            ConnectionState.statusText = "Ошибка: неверный MAC"
            Toast.makeText(context, "Ошибка: неверный MAC", Toast.LENGTH_SHORT).show()
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect() called")
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        sendQueue.clear()
        isSending = false
    }

    fun sendFields(fields: List<ProtocolField>) {
        Log.d(TAG, "sendFields() called with ${fields.size} fields")
        if (!ConnectionState.isConnected || writeCharacteristic == null) {
            Log.w(TAG, "Cannot send fields: Not connected or characteristic null")
            Toast.makeText(context, "Нет подключения", Toast.LENGTH_SHORT).show()
            return
        }
        val packets = ProtocolEncoder.encodeMessage(fields)
        enqueuePackets(packets)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status, newState=$newState")
            Handler(Looper.getMainLooper()).post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected to GATT server")
                        ConnectionState.isConnected = true
                        ConnectionState.isConnecting = false
                        ConnectionState.statusText = "Подключение установлено"
                        gatt?.requestMtu(512)
                        gatt?.discoverServices()
                        Toast.makeText(context, "Подключено!", Toast.LENGTH_SHORT).show()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from GATT server")
                        ConnectionState.isConnected = false
                        ConnectionState.isConnecting = false
                        ConnectionState.statusText = "Статус: Отключено"
                        writeCharacteristic = null
                        sendQueue.clear()
                        isSending = false
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(serviceUuid)
                writeCharacteristic = service?.getCharacteristic(characteristicUuid)
                Log.d(TAG, "Service/Characteristic discovered: ${writeCharacteristic != null}")
                enableNotifications(gatt)
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            Log.d(TAG, "onDescriptorWrite status=$status, uuid=${descriptor?.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor?.uuid == cccdUuid) {
                Log.d(TAG, "Notifications enabled")
                // Use a handler to avoid toast issues if called from background
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Уведомления включены", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.e(TAG, "Descriptor write failed with status: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val data = characteristic?.value
            Log.d(TAG, "onCharacteristicChanged data=${data?.joinToString { "%02x".format(it) }}")
            if (data == null) return
            val result = ProtocolDecoder.processPacket(data)
            if (result != null && result.isRequest) {
                handleRequest(result.tags)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            Log.d(TAG, "onCharacteristicWrite status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendNextPacket()
            } else {
                Log.e(TAG, "Characteristic write failed with status: $status")
                isSending = false
                sendQueue.clear()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Ошибка отправки", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt?) {
        Log.d(TAG, "enableNotifications() called")
        val char = writeCharacteristic ?: return
        gatt?.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(cccdUuid)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt?.writeDescriptor(descriptor)
        } else {
            Log.e(TAG, "CCCD descriptor not found")
        }
    }

    private fun handleRequest(tags: List<Int>) {
        Log.d(TAG, "handleRequest() tags=$tags")
        val fields = mutableListOf<ProtocolField>()
        for (tag in tags) {
            when (tag) {
                ProtocolConstants.REQ_TIME ->
                    fields.add(ProtocolField.UnixTime(dataProvider.getUnixTime()))
                ProtocolConstants.REQ_VPN ->
                    fields.add(ProtocolField.Vpn(dataProvider.getVpnStatus()))
                ProtocolConstants.REQ_PLAYBACK ->
                    fields.add(ProtocolField.Playback(dataProvider.getPlaybackStatus()))
                ProtocolConstants.REQ_ARTIST -> {
                    val artist = dataProvider.getArtist()
                    if (artist.isNotEmpty()) fields.add(ProtocolField.Artist(artist))
                }
                ProtocolConstants.REQ_TRACK -> {
                    val track = dataProvider.getTrack()
                    if (track.isNotEmpty()) fields.add(ProtocolField.Track(track))
                }
            }
        }
        if (fields.isNotEmpty()) {
            sendFields(fields)
        }
    }

    private fun enqueuePackets(packets: List<ByteArray>) {
        Log.d(TAG, "enqueuePackets() size=${packets.size}")
        sendQueue.addAll(packets)
        if (!isSending) sendNextPacket()
    }

    private fun sendNextPacket() {
        if (sendQueue.isEmpty()) {
            Log.d(TAG, "sendQueue is empty, stopping send")
            isSending = false
            return
        }
        isSending = true
        val packet = sendQueue.removeAt(0)
        Log.d(TAG, "Sending packet size=${packet.size}")
        writeCharacteristic?.let { char ->
            char.value = packet
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothGatt?.writeCharacteristic(char)
                } else {
                    Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
                    isSending = false
                }
            } else {
                bluetoothGatt?.writeCharacteristic(char)
            }
        } ?: run {
            Log.e(TAG, "Cannot send packet: characteristic is null")
            isSending = false
        }
    }
}
