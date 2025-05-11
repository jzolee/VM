package com.jzolee.vibrationmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleObserver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.app.Activity
import java.lang.ref.WeakReference
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

@SuppressLint("MissingPermission")
class BLEManager private constructor(
    private val context: Context,
    private val logCallback: (String) -> Unit,
    private val statusCallback: (String) -> Unit,
    private val onDataReceived: (UUID, ByteArray) -> Unit,
    private val onRssiUpdated: (Int) -> Unit,
    private val onDisconnect: () -> Unit
) : LifecycleObserver {

    companion object {
        @Volatile
        var instance: BLEManager? = null
            private set

        private val SERVICE_UUID = UUID.fromString("96540000-d6a3-4d5b-8145-e5855fd090a7")
        private val CONTROL_CHARACTERISTIC_UUID = UUID.fromString("96540001-d6a3-4d5b-8145-e5855fd090a7")
        private val STATUS_CHARACTERISTIC_UUID = UUID.fromString("96540002-d6a3-4d5b-8145-e5855fd090a7")
        private val DATA_CHARACTERISTIC_UUID = UUID.fromString("96540003-d6a3-4d5b-8145-e5855fd090a7")
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun create(
            activity: ComponentActivity,
            logCallback: (String) -> Unit = { Log.d("BLEManager", it) },
            statusCallback: (String) -> Unit = {},
            onDataReceived: (UUID, ByteArray) -> Unit = { _, _ -> },
            onRssiUpdated: (Int) -> Unit = {},
            onDisconnect: () -> Unit = {}
        ): BLEManager {
            return instance ?: synchronized(this) {
                instance ?: BLEManager(
                    activity,
                    logCallback,
                    statusCallback,
                    onDataReceived,
                    onRssiUpdated,
                    onDisconnect
                ).also {
                    instance = it
                    it.initialize(activity)
                }
            }
        }
    }

    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var enableBtLauncher: ActivityResultLauncher<Intent>? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var connectedGatt: BluetoothGatt? = null
    private var isScanning = false
    private var lastConnectedDevice: BluetoothDevice? = null

    private val characteristicsToEnable = mutableListOf<BluetoothGattCharacteristic>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rssiHandler = Handler(Looper.getMainLooper())
    private val rssiUpdateInterval = 2000L

    private val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()

    private val scanSettings: ScanSettings
        get() = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                }
            }
            .setReportDelay(0)
            .build()

    private enum class InitState {
        NOT_STARTED,
        CHECKING_BLUETOOTH,
        REQUESTING_BLUETOOTH,
        CHECKING_PERMISSIONS,
        REQUESTING_PERMISSIONS,
        READY
    }

    private var initState = InitState.NOT_STARTED

    private var activityWeakRef: WeakReference<ComponentActivity>? = null

    private fun initialize(activity: ComponentActivity) {

        if (initState != InitState.NOT_STARTED) return

        activityWeakRef = WeakReference(activity)
        setupPermissionLaunchers(activity)
        setupBluetoothAdapter()
        registerBluetoothReceiver()

        startInitializationFlow()

        if (initState == InitState.READY) {
            startBLEService()
        }
    }

    private fun startInitializationFlow() {
        when {
            bluetoothAdapter == null -> {
                logCallback("Bluetooth not supported")
                statusCallback("Bluetooth not supported")
            }
            !bluetoothAdapter!!.isEnabled -> {
                initState = InitState.CHECKING_BLUETOOTH
                requestBluetoothEnable()
            }
            else -> {
                initState = InitState.CHECKING_PERMISSIONS
                checkAndRequestPermissions()
            }
        }
    }

    private fun requestBluetoothEnable() {
        initState = InitState.REQUESTING_BLUETOOTH
        if (hasBluetoothConnectPermission()) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher?.launch(enableBtIntent)
        } else {
            logCallback("Need BLUETOOTH_CONNECT permission to enable Bluetooth")
            checkAndRequestPermissions()
        }
    }

    private fun checkAndRequestPermissions() {
        if (hasRequiredPermissions()) {
            initState = InitState.READY
            startScan() //startBluetoothOperations()
        } else {
            initState = InitState.REQUESTING_PERMISSIONS
            requestPermissions()
        }
    }

    private fun setupPermissionLaunchers(activity: ComponentActivity) {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.all { it.value }

            if (allGranted) {
                logCallback("All permissions granted")
                when (initState) {
                    InitState.REQUESTING_PERMISSIONS -> {
                        if (bluetoothAdapter?.isEnabled == true) {
                            initState = InitState.READY
                            startScan() //startBluetoothOperations()
                        } else {
                            initState = InitState.CHECKING_BLUETOOTH
                            requestBluetoothEnable()
                        }
                    }
                    else -> logCallback("Unexpected state after permission grant")
                }
            } else {
                logCallback("Missing permissions: ${permissions.filter { !it.value }.keys}")
                statusCallback("Permissions required")
                initState = InitState.NOT_STARTED
            }
        }

        enableBtLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                logCallback("Bluetooth enabled")
                when (initState) {
                    InitState.REQUESTING_BLUETOOTH -> {
                        initState = InitState.CHECKING_PERMISSIONS
                        checkAndRequestPermissions()
                    }
                    else -> logCallback("Unexpected state after Bluetooth enable")
                }
            } else {
                logCallback("Bluetooth enable request denied")
                statusCallback("Bluetooth required")
                initState = InitState.NOT_STARTED
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        return mutableListOf<String>().apply {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.toTypedArray()
    }

    private fun requestPermissions() {
        permissionLauncher?.launch(getRequiredPermissions())
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            logCallback("Bluetooth turned on")
                            /*if (!isScanning && shouldReconnect) {
                                startScan() //startBluetoothOperations()
                            }*/
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            logCallback("Bluetooth turned off")
                            disconnect()
                        }
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            logCallback("Found device: ${result.device?.name ?: "Unknown"} (${result.device?.address})")
            stopScan()
            lastConnectedDevice = result.device
            result.device?.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }

        override fun onScanFailed(errorCode: Int) {
            logCallback("Scan failed: $errorCode")
            stopScan()
            //scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        stopScan()
                        connectedGatt = gatt
                        logCallback("Connected to device: ${gatt.device.address}")
                        statusCallback("Connected")
                        mainHandler.postDelayed({ gatt.requestMtu(517) }, 500)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        logCallback("Disconnected from device")
                        statusCallback("Disconnected")
                        handleDisconnection(gatt)
                    }
                }
            } else {
                logCallback("Connection failed: $status")
                handleDisconnection(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    logCallback("Services discovered")
                    enableNotifications(service)
                } else {
                    logCallback("Service not found: $SERVICE_UUID")
                    gatt.disconnect()
                }
            } else {
                logCallback("Service discovery failed: $status")
                gatt.disconnect()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val uuid = characteristic.uuid
            val value = characteristic.value
            if (value != null) {
                onDataReceived(uuid, value)
            } else {
                logCallback("Characteristic value is null: $uuid")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logCallback("Descriptor write successful: ${descriptor.uuid}")
                enableNextNotification()
            } else {
                logCallback("Descriptor write failed: $status")
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onRssiUpdated(rssi)
            } else {
                logCallback("Failed to read RSSI: $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                logCallback("MTU changed to $mtu")
                gatt.discoverServices()
            } else {
                logCallback("MTU change failed: $status")
                gatt.discoverServices() // még így is próbálkozhatsz
            }
        }
    }

    private val rssiUpdateTask = object : Runnable {
        override fun run() {
            connectedGatt?.readRemoteRssi()
            rssiHandler.postDelayed(this, rssiUpdateInterval)
        }
    }

    private fun setupBluetoothAdapter() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            logCallback("BLE scanner not available")
            //scheduleReconnect()
            return
        }
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(bluetoothStateReceiver, filter)
    }

    private fun unregisterBluetoothReceiver() {
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
            logCallback("Error unregistering receiver: ${e.message}")
        }
    }

    private fun enableNotifications(service: BluetoothGattService) {
        characteristicsToEnable.clear()
        listOf(STATUS_CHARACTERISTIC_UUID, DATA_CHARACTERISTIC_UUID)
            .mapNotNull { service.getCharacteristic(it) }
            .let { characteristicsToEnable.addAll(it) }

        if (characteristicsToEnable.isNotEmpty()) {
            enableNextNotification()
        } else {
            logCallback("No characteristics found for notifications")
        }
    }

    private fun enableNextNotification() {
        if (characteristicsToEnable.isNotEmpty()) {
            val characteristic = characteristicsToEnable.removeAt(0)
            enableNotificationForCharacteristic(characteristic)
        } else {
            logCallback("All notifications enabled")
            startRssiUpdates()
        }
    }

    private fun enableNotificationForCharacteristic(characteristic: BluetoothGattCharacteristic) {
        try {
            connectedGatt?.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                connectedGatt?.writeDescriptor(descriptor)
            } else {
                logCallback("Descriptor not found for characteristic: ${characteristic.uuid}")
            }
        } catch (e: Exception) {
            logCallback("Error enabling notification: ${e.message}")
        }
    }

    private fun startScan() {
        if (isScanning) return // Ne indítsunk új scan-t, ha már fut egy

        isScanning = true
        bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        logCallback("BLE scan started")
        statusCallback("Scanning")
    }

    private fun stopScan() {
        if (!isScanning) return
        bleScanner?.stopScan(scanCallback)
        isScanning = false
        logCallback("BLE scan stopped")
    }

    private fun startRssiUpdates() {
        rssiHandler.post(rssiUpdateTask)
    }

    private fun stopRssiUpdates() {
        rssiHandler.removeCallbacks(rssiUpdateTask)
    }

    private fun handleDisconnection(gatt: BluetoothGatt) {
        gatt.close()
        connectedGatt = null
        onDisconnect()
        mainHandler.postDelayed({ reconnect() }, 2000) // 2s késleltetés
    }

    private fun startBLEService() {
        val intent = Intent(context, BLEService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun disconnect() {
        stopScan()
        stopRssiUpdates()
        connectedGatt?.disconnect()
        connectedGatt?.close()
        connectedGatt = null
    }

    private fun reconnect() {
        disconnect()
        startScan()
    }

    fun release() {
        disconnect()
        unregisterBluetoothReceiver()
        permissionLauncher = null
        enableBtLauncher = null
        logCallback("BLEManager released")
    }

    fun sendControl(filter: Float, control: UShort, rpm: FloatArray) {
        val buffer = ByteArray(30) { 0 }
        ByteBuffer.wrap(buffer)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(filter)
            .putShort(control.toShort())
            .putFloat(rpm[1] / 60.0F)
            .putFloat(rpm[2] / 60.0F)
            .putFloat(rpm[3] / 60.0F)
            .putFloat(rpm[4] / 60.0F)
            .putFloat(rpm[5] / 60.0F)
            .putFloat(rpm[6] / 60.0F)

        val gatt = connectedGatt ?: run {
            logCallback("Not connected to any device")
            return
        }

        val service = gatt.getService(SERVICE_UUID) ?: run {
            logCallback("Service not found: $SERVICE_UUID")
            return
        }

        val controlCharacteristic = service.getCharacteristic(CONTROL_CHARACTERISTIC_UUID)
            ?: run {
                logCallback("Control characteristic not found")
                return
            }

        controlCharacteristic.value = buffer
        if (!gatt.writeCharacteristic(controlCharacteristic)) {
            logCallback("Failed to write control characteristic")
        }
    }
}
