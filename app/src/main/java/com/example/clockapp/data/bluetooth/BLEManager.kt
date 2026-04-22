package com.example.clockapp.data.bluetooth

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID

class BLEManager(private val context: Context) {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionCallback: (() -> Unit)? = null
    private var connected = false

    companion object {
        val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    }

    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    // Проверка наличия всех разрешений
    fun hasPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            // Android 11 и ниже
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Проверка включён ли Bluetooth
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    // Проверка подключения
    fun isConnected(): Boolean {
        return connected
    }

    // Подключение к ESP32
    fun connectToESP32(macAddress: String, onConnected: () -> Unit): Boolean {
        // Проверяем разрешения
        if (!hasPermissions()) {
            println("BLE: Permissions not granted")
            return false
        }

        // Проверяем Bluetooth
        if (!isBluetoothEnabled()) {
            println("BLE: Bluetooth is disabled")
            return false
        }

        connectionCallback = onConnected

        // Приводим MAC к верхнему регистру
        val normalizedMac = macAddress.uppercase()
        println("BLE: Connecting to $normalizedMac")

        val device = bluetoothAdapter?.getRemoteDevice(normalizedMac)

        if (device == null) {
            println("BLE: Device not found for MAC: $normalizedMac")
            return false
        }

        // Проверка разрешений перед подключением (для Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                println("BLE: BLUETOOTH_CONNECT permission denied")
                return false
            }
        }

        try {
            bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            connected = true
                            println("BLE: Connection established")
                            connectionCallback?.invoke()
                            gatt.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            connected = false
                            println("BLE: Disconnected")
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        println("BLE: Services discovered")
                        gatt.services.forEach { service ->
                            println("BLE: Service: ${service.uuid}")
                        }
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val data = characteristic.value
                        println("BLE: Read data: ${data?.joinToString()}")
                    }
                }
            })
            return true
        } catch (e: SecurityException) {
            println("BLE: SecurityException - ${e.message}")
            return false
        } catch (e: Exception) {
            println("BLE: Exception - ${e.message}")
            return false
        }
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            println("BLE: Error disconnecting - ${e.message}")
        }
        bluetoothGatt = null
        connected = false
    }
}