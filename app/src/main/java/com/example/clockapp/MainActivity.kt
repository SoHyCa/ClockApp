package com.example.clockapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.clockapp.ui.theme.BleEsp32Theme
import java.nio.ByteBuffer
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val MAC_ADDRESS = "C8:F0:9E:F1:48:22"
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            connectToDevice()
        } else {
            Toast.makeText(this, "Необходимы разрешения", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            BleEsp32Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BleControlScreen(
                        onConnectClick = { checkPermissionsAndConnect() },
                        onSendTimeClick = { sendCurrentTime() },
                        isConnected = ConnectionState.isConnected
                    )
                }
            }
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

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            connectToDevice()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun connectToDevice() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth выключен", Toast.LENGTH_SHORT).show()
            return
        }

        ConnectionState.isConnecting = true
        ConnectionState.statusText = "Статус: Подключение..."

        try {
            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(MAC_ADDRESS)

            bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    Handler(Looper.getMainLooper()).post {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                ConnectionState.isConnected = true
                                ConnectionState.isConnecting = false
                                ConnectionState.statusText = "Подключение установлено"
                                gatt?.discoverServices()
                                Toast.makeText(this@MainActivity, "Подключено!", Toast.LENGTH_SHORT).show()
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                ConnectionState.isConnected = false
                                ConnectionState.isConnecting = false
                                ConnectionState.statusText = "Статус: Отключено"
                                writeCharacteristic = null
                            }
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val service = gatt?.getService(SERVICE_UUID)
                        writeCharacteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                    }
                }
            })

        } catch (e: IllegalArgumentException) {
            ConnectionState.isConnecting = false
            ConnectionState.statusText = "Ошибка: неверный MAC"
            Toast.makeText(this, "Ошибка: неверный MAC", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendCurrentTime() {
        if (!ConnectionState.isConnected || writeCharacteristic == null) {
            Toast.makeText(this, "Нет подключения", Toast.LENGTH_SHORT).show()
            return
        }

        val currentTimeMillis = System.currentTimeMillis()
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(currentTimeMillis)
        val timeBytes = buffer.array()

        sendBytes(writeCharacteristic!!, timeBytes)
        Toast.makeText(this, "Время отправлено", Toast.LENGTH_SHORT).show()
    }

    private fun sendBytes(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        characteristic.value = data
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
        } else {
            bluetoothGatt?.writeCharacteristic(characteristic)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
    }
}

object ConnectionState {
    var isConnected by mutableStateOf(false)
    var isConnecting by mutableStateOf(false)
    var statusText by mutableStateOf("Статус: Отключено")
}

@Composable
fun BleControlScreen(
    onConnectClick: () -> Unit,
    onSendTimeClick: () -> Unit,
    isConnected: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Button(
            onClick = onConnectClick,
            enabled = !ConnectionState.isConnecting
        ) {
            Text(
                text = if (ConnectionState.isConnecting) "Подключение..."
                else if (isConnected) "Подключено"
                else "Подключиться к ESP32"
            )
        }

        Text(
            text = ConnectionState.statusText,
            style = MaterialTheme.typography.bodyLarge
        )

        if (isConnected) {
            Button(
                onClick = onSendTimeClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Отправить текущее время")
            }
        }
    }
}