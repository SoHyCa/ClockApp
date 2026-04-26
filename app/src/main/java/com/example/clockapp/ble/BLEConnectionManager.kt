package com.example.clockapp.ble

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.clockapp.protocol.*
import com.example.clockapp.state.ConnectionState
import java.util.UUID

class BLEConnectionManager(
    private val context: Context,
    private val dataProvider: DataProvider
) {
    private val macAddress = "C8:F0:9E:F1:48:22"
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
    }

    fun connect() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "Bluetooth выключен", Toast.LENGTH_SHORT).show()
            return
        }

        ConnectionState.isConnecting = true
        ConnectionState.statusText = "Статус: Подключение..."

        try {
            val device = bluetoothAdapter.getRemoteDevice(macAddress)
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: IllegalArgumentException) {
            ConnectionState.isConnecting = false
            ConnectionState.statusText = "Ошибка: неверный MAC"
            Toast.makeText(context, "Ошибка: неверный MAC", Toast.LENGTH_SHORT).show()
        }
    }

    fun disconnect() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        sendQueue.clear()
        isSending = false
    }

    fun sendFields(fields: List<ProtocolField>) {
        if (!ConnectionState.isConnected || writeCharacteristic == null) {
            Toast.makeText(context, "Нет подключения", Toast.LENGTH_SHORT).show()
            return
        }
        val packets = ProtocolEncoder.encodeMessage(fields)
        enqueuePackets(packets)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Handler(Looper.getMainLooper()).post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        ConnectionState.isConnected = true
                        ConnectionState.isConnecting = false
                        ConnectionState.statusText = "Подключение установлено"
                        gatt?.requestMtu(512)
                        gatt?.discoverServices()
                        Toast.makeText(context, "Подключено!", Toast.LENGTH_SHORT).show()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(serviceUuid)
                writeCharacteristic = service?.getCharacteristic(characteristicUuid)
                enableNotifications(gatt)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor?.uuid == cccdUuid) {
                Toast.makeText(context, "Уведомления включены", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val data = characteristic?.value ?: return
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendNextPacket()
            } else {
                isSending = false
                sendQueue.clear()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Ошибка отправки", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt?) {
        val char = writeCharacteristic ?: return
        gatt?.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(cccdUuid)
        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt?.writeDescriptor(descriptor)
    }

    private fun handleRequest(tags: List<Int>) {
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
        } ?: run { isSending = false }
    }
}