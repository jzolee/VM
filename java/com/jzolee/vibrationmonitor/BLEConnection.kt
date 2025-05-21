
// BLEConnection.kt - a GATT kapcsolat és adatforgalom teljes kezelése

package com.jzolee.vibrationmonitor

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

@SuppressLint("MissingPermission")
class BLEConnection(
    private val context: Context,
    private val logCallback: (String) -> Unit,
    private val statusCallback: (String) -> Unit,
    private val onDataReceived: (UUID, ByteArray) -> Unit,
    private val onRssiUpdated: (Int) -> Unit,
    private val onDisconnect: () -> Unit
) {

    private var subscriptionQueue: MutableList<BluetoothGattCharacteristic> = mutableListOf()

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("96540000-d6a3-4d5b-8145-e5855fd090a7")
        val CONTROL_UUID: UUID = UUID.fromString("96540001-d6a3-4d5b-8145-e5855fd090a7")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                if (bluetoothGatt != null) return
                stopScan()
                logCallback("Found device: ${device.address}")
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            logCallback("Scan failed: $errorCode")
            statusCallback("Scan failed")
        }
    }

    fun startScan() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bleScanner = bluetoothManager.adapter?.bluetoothLeScanner
        bleScanner?.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )
        isScanning = true
        logCallback("BLE scan started")
        statusCallback("Scanning")
    }

    private fun stopScan() {
        if (!isScanning) return
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        isScanning = false
    }

    private fun connectToDevice(device: BluetoothDevice) {
        disconnect()
        logCallback("Connecting to ${device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                logCallback("Connected to GATT")
                statusCallback("Connected")
                gatt.requestMtu(512)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                logCallback("Disconnected from GATT")
                statusCallback("Disconnected")
                onDisconnect()
                retryConnection()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logCallback("MTU changed to $mtu")
                gatt.discoverServices()
            } else {
                logCallback("MTU change failed")
                retryConnection()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logCallback("Service discovery failed")
                retryConnection()
                return
            }
            subscriptionQueue = gatt.getService(SERVICE_UUID)?.characteristics?.filter {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            }?.toMutableList() ?: mutableListOf()
            subscribeNext(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logCallback("Descriptor write failed")
                disconnect()
                retryConnection()
                return
            }
            logCallback("Subscribed to ${descriptor.characteristic.uuid}")
            subscribeNext(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onDataReceived(characteristic.uuid, characteristic.value)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onRssiUpdated(rssi)
            }
        }
    }

    private fun subscribeNext(gatt: BluetoothGatt) {
        if (subscriptionQueue.isEmpty()) {
            logCallback("All subscriptions complete")
            startRssiUpdates()
            return
        }
        val char = subscriptionQueue.removeAt(0)
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            logCallback("No CCCD found for ${char.uuid}")
            disconnect()
            retryConnection()
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(descriptor)) {
            logCallback("writeDescriptor() failed for ${char.uuid}")
            disconnect()
            retryConnection()
        }
    }

    private var rssiRunnable: Runnable? = null
    private fun startRssiUpdates() {
        rssiRunnable = object : Runnable {
            override fun run() {
                bluetoothGatt?.readRemoteRssi()
                handler.postDelayed(this, 3000)
            }
        }
        handler.post(rssiRunnable!!)
    }

    private fun stopRssiUpdates() {
        rssiRunnable?.let { handler.removeCallbacks(it) }
        rssiRunnable = null
    }

    fun sendControl(filter: Float, control: UShort, rpm: FloatArray) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CONTROL_UUID) ?: return
        val buffer = ByteBuffer.allocate(2 + 4 + 6 * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(filter)
        buffer.putShort(control.toShort())
        for (i in 1 until 7) {
            val scaled = if (i < rpm.size) rpm[i] / 60f else 0f
            buffer.putFloat(scaled)
        }
        characteristic.value = buffer.array()
        gatt.writeCharacteristic(characteristic)
        logCallback("Sent control packet")
    }

    private fun retryConnection() {
        handler.postDelayed({
            bluetoothGatt = null
            startScan()
        }, 3000)
    }

    fun release() {
        stopScan()
        disconnect()
        stopRssiUpdates()
    }

    private fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
