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
import android.app.ActivityManager
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

        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val SCAN_DURATION_MS = 10000L

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
    private var shouldReconnect = true
    private var reconnectAttempts = 0
    private var lastConnectedDevice: BluetoothDevice? = null
    private var foregroundServiceRunning = false

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
            startBluetoothOperations()
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
                            startBluetoothOperations()
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
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
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

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            logCallback("Bluetooth turned on")
                            if (!isScanning && shouldReconnect) {
                                startBluetoothOperations()
                            }
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            logCallback("Bluetooth turned off")
                            disconnectInternal()
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device?.address == lastConnectedDevice?.address) {
                        logCallback("Device disconnected unexpectedly")
                        if (isApplicationInForeground()) {
                            scheduleReconnect()
                        } else {
                            // Alkalmazás háttérben - késleltetett újracsatlakozás
                            mainHandler.postDelayed({
                                if (!isApplicationInForeground()) {
                                    startBluetoothOperations()
                                }
                            }, RECONNECT_DELAY_MS * 2)
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Képernyő bekapcsolásakor próbáljunk újracsatlakozni
                    if (!isScanning && shouldReconnect && connectedGatt == null) {
                        logCallback("Screen turned on - attempting reconnect")
                        startBluetoothOperations()
                    }
                }
            }
        }
    }

    private fun stopForegroundService() {
        if (foregroundServiceRunning) {
            val intent = Intent(context, BLEService::class.java)
            context.stopService(intent)
            foregroundServiceRunning = false
        }
    }

    private fun isApplicationInForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false

        return appProcesses.any {
            it.processName == context.packageName &&
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
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
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        stopScan() // Fontos: állítsd le a scan-t
                        reconnectAttempts = 0
                        connectedGatt = gatt
                        logCallback("Connected to device: ${gatt.device.address}")
                        statusCallback("Connected")

                        mainHandler.postDelayed({ gatt.requestMtu(517) }, 500)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        logCallback("Disconnected from device")
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
            scheduleReconnect()
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

    private fun startBluetoothOperations() {
        logCallback("Starting BLE operations")
        startScan()

        mainHandler.postDelayed({
            if (isScanning) {
                logCallback("Scan timeout reached")
                stopScan()
                scheduleReconnect()
            }
        }, SCAN_DURATION_MS)
    }

    /*private fun scheduleReconnect() {
        if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            val delay = minOf(RECONNECT_DELAY_MS * (1 shl reconnectAttempts), 30000L)
            logCallback("Scheduling reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delay}ms")
            mainHandler.postDelayed({ startScan() }, delay)
        }
    }*/

    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return

        reconnectAttempts++
        val delay = minOf(RECONNECT_DELAY_MS * (1 shl reconnectAttempts), 30000L) // Exponential backoff

        logCallback("Scheduling reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delay}ms")
        mainHandler.postDelayed({
            if (shouldReconnect && connectedGatt == null) {
                startScan()
            }
        }, delay)
    }

    private fun disconnectInternal() {
        shouldReconnect = false
        stopScan()
        stopRssiUpdates()
        connectedGatt?.disconnect()
        connectedGatt?.close()
        connectedGatt = null
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

    /*private fun startScan() {
        if (!isScanning && bluetoothAdapter?.isEnabled == true) {
            isScanning = true
            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            logCallback("BLE scan started")
            statusCallback("Scanning")
        }
    }*/

    private fun startScan() {
        if (isScanning) return // Ne indítsunk új scan-t, ha már fut egy

        isScanning = true
        bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        logCallback("BLE scan started")

        // Automatikus leállítás időzítője
        mainHandler.postDelayed({
            if (isScanning) {
                logCallback("Scan timeout reached")
                stopScan()
            }
        }, SCAN_DURATION_MS)
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

    fun release() {
        disconnectInternal()
        unregisterBluetoothReceiver()
        stopForegroundService()
        permissionLauncher = null
        enableBtLauncher = null
        logCallback("BluetoothManager released")
    }

    fun disconnect() {
        shouldReconnect = false // Kikapcsoljuk az automatikus újracsatlakozást
        disconnectInternal()
        logCallback("Disconnected by user request")
        statusCallback("Disconnected")
    }

    fun reconnect() {
        disconnectInternal() // Mindig szakítsd meg a régi kapcsolatot!
        shouldReconnect = true
        startScan()
    }

    fun sendControl(filter: Float, control: UShort) {
        val buffer = ByteArray(20) { 0 }
        ByteBuffer.wrap(buffer)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(filter)
            .putShort(control.toShort())

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

    private fun initialize(activity: ComponentActivity) {

        if (initState != InitState.NOT_STARTED) return

        startBLEService()

        activityWeakRef = WeakReference(activity)
        setupPermissionLaunchers(activity)
        setupBluetoothAdapter()
        registerBluetoothReceiver()

        startInitializationFlow()
    }

    private fun handleDisconnection(gatt: BluetoothGatt) {
        gatt.close() // Kötelező!
        connectedGatt = null
        if (shouldReconnect) {
            mainHandler.postDelayed({ reconnect() }, 2000) // 2s késleltetés
        }
    }

    fun onAppBackgrounded() {
        val intent = Intent(context, BLEService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun startBLEService() {
        val intent = Intent(context, BLEService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}



/*
@SuppressLint("MissingPermission")
class BLEManager private constructor(
    private val context: Context,
    private val logCallback: (String) -> Unit,
    private val statusCallback: (String) -> Unit,
    private val onDataReceived: (UUID, ByteArray) -> Unit,
    private val onRssiUpdated: (Int) -> Unit,
    private val onDisconnect: () -> Unit
) {
    companion object {

        private const val TAG = "BLEManager"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val SCAN_DURATION_MS = 10000L

        private val SERVICE_UUID = UUID.fromString("96540000-d6a3-4d5b-8145-e5855fd090a7")
        private val CONTROL_CHARACTERISTIC_UUID = UUID.fromString("96540001-d6a3-4d5b-8145-e5855fd090a7")
        private val STATUS_CHARACTERISTIC_UUID = UUID.fromString("96540002-d6a3-4d5b-8145-e5855fd090a7")
        private val DATA_CHARACTERISTIC_UUID = UUID.fromString("96540003-d6a3-4d5b-8145-e5855fd090a7")
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun create(
            activity: ComponentActivity,
            logCallback: (String) -> Unit = { Log.d(TAG, it) },
            statusCallback: (String) -> Unit = {},
            onDataReceived: (UUID, ByteArray) -> Unit = { _, _ -> },
            onRssiUpdated: (Int) -> Unit = {},
            onDisconnect: () -> Unit = {}
        ): BLEManager {
            return BLEManager(
                context = activity,
                logCallback = logCallback,
                statusCallback = statusCallback,
                onDataReceived = onDataReceived,
                onRssiUpdated = onRssiUpdated,
                onDisconnect = onDisconnect
            ).apply {
                initialize(activity)
            }
        }
    }

    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var enableBtLauncher: ActivityResultLauncher<Intent>? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var connectedGatt: BluetoothGatt? = null
    private var isScanning = false
    private var shouldReconnect = true
    private var reconnectAttempts = 0
    private var lastConnectedDevice: BluetoothDevice? = null
    private var foregroundServiceRunning = false

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
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver(this))
        if (initState != InitState.NOT_STARTED) return

        activityWeakRef = WeakReference(activity)
        setupPermissionLaunchers(activity)
        setupBluetoothAdapter()
        registerBluetoothReceiver()

        startInitializationFlow()
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
            startBluetoothOperations()
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
                            startBluetoothOperations()
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
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
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

    // Háttérben történő újracsatlakozás kezelése
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            logCallback("Bluetooth turned on")
                            if (!isScanning && shouldReconnect) {
                                startBluetoothOperations()
                            }
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            logCallback("Bluetooth turned off")
                            disconnectInternal()
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device?.address == lastConnectedDevice?.address) {
                        logCallback("Device disconnected unexpectedly")
                        if (isApplicationInForeground()) {
                            scheduleReconnect()
                        } else {
                            // Alkalmazás háttérben - késleltetett újracsatlakozás
                            mainHandler.postDelayed({
                                if (!isApplicationInForeground()) {
                                    startBluetoothOperations()
                                }
                            }, RECONNECT_DELAY_MS * 2)
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Képernyő bekapcsolásakor próbáljunk újracsatlakozni
                    if (!isScanning && shouldReconnect && connectedGatt == null) {
                        logCallback("Screen turned on - attempting reconnect")
                        startBluetoothOperations()
                    }
                }
            }
        }
    }

    /*private fun isApplicationInForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = activityManager.runningAppProcesses ?: return false
        val processInfo = runningProcesses.firstOrNull { it.processName == context.packageName }
        return processInfo?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }*/

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !foregroundServiceRunning) {
            val intent = Intent(context, BLEService::class.java)
            context.startForegroundService(intent)
            foregroundServiceRunning = true
        }
    }

    private fun stopForegroundService() {
        if (foregroundServiceRunning) {
            val intent = Intent(context, BLEService::class.java)
            context.stopService(intent)
            foregroundServiceRunning = false
        }
    }

    private fun isApplicationInForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false

        return appProcesses.any {
            it.processName == context.packageName &&
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
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
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        reconnectAttempts = 0
                        connectedGatt = gatt
                        logCallback("Connected to device: ${gatt.device.address}")
                        statusCallback("Connected")

                        mainHandler.postDelayed({ gatt.requestMtu(517) }, 500)
                        /*
                        mainHandler.postDelayed({ gatt.discoverServices() }, 2000)
                        */
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        logCallback("Disconnected from device")
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

        /*MTU változások hibakezelése:
         Most a requestMtu után fix időzítéssel történik a discoverServices().
         Érdemes lenne csak akkor hívni, ha az onMtuChanged pozitív visszajelzést ad:
         */
         /*override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
             logCallback("MTU changed to $mtu, success: ${status == BluetoothGatt.GATT_SUCCESS}")
         }*/
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
        /*
        Bluetooth LE scanner ellenőrzés:
        bleScanner = bluetoothAdapter?.bluetoothLeScanner néha null lehet,
        ha a rendszer épp nem engedi elindítani.
        Ezt érdemes logolni vagy késleltetve újrapróbálni.
         */
        if (bleScanner == null) {
            logCallback("BLE scanner not available")
            scheduleReconnect()
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

    private fun startBluetoothOperations() {
        logCallback("Starting BLE operations")
        startScan()

        mainHandler.postDelayed({
            if (isScanning) {
                logCallback("Scan timeout reached")
                stopScan()
                scheduleReconnect()
            }
        }, SCAN_DURATION_MS)
    }

    /*private fun scheduleReconnect() {
        if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            logCallback("Scheduling reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
            mainHandler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
        } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logCallback("Max reconnect attempts reached")
            statusCallback("Connection failed")
        }
    }*/

    private fun scheduleReconnect() {
        if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            val delay = minOf(RECONNECT_DELAY_MS * (1 shl reconnectAttempts), 30000L)
            logCallback("Scheduling reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delay}ms")
            mainHandler.postDelayed({ startScan() }, delay)
        }
    }

    /*private fun handleDisconnection(gatt: BluetoothGatt) {
        stopRssiUpdates()
        gatt.close()
        connectedGatt = null
        statusCallback("Disconnected")
        onDisconnect()

        if (shouldReconnect) {
            scheduleReconnect()
        }
    }*/

    private fun handleDisconnection(gatt: BluetoothGatt) {
        stopRssiUpdates()
        gatt.close()
        connectedGatt = null
        statusCallback("Disconnected")
        onDisconnect()

        if (shouldReconnect) {
            if (isApplicationInForeground()) {
                scheduleReconnect()
            } else {
                // If in background, wait for app to come to foreground
                logCallback("App in background - will reconnect when foregrounded")
                // Register for app state changes
                registerAppStateReceiver()
            }
        }
    }

    private val appStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    if (!isScanning && shouldReconnect && connectedGatt == null) {
                        logCallback("Screen turned on - attempting reconnect")
                        startBluetoothOperations()
                    }
                }
                "android.intent.action.APP_TRANSITIONED_TO_FOREGROUND" -> {
                    if (shouldReconnect && connectedGatt == null) {
                        logCallback("App came to foreground - attempting reconnect")
                        startBluetoothOperations()
                    }
                }
            }
        }
    }

    private fun registerAppStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction("android.intent.action.APP_TRANSITIONED_TO_FOREGROUND")
        }
        context.registerReceiver(appStateReceiver, filter)
    }

    // Add these methods
    fun onAppForegrounded() {
        if (shouldReconnect && connectedGatt == null) {
            logCallback("App came to foreground - attempting reconnect")
            startBluetoothOperations()
        }
    }

    fun onAppBackgrounded() {
        // Handle background state if needed
    }

    private fun unregisterAppStateReceiver() {
        try {
            context.unregisterReceiver(appStateReceiver)
        } catch (e: Exception) {
            logCallback("Error unregistering app state receiver: ${e.message}")
        }
    }

    private fun disconnectInternal() {
        shouldReconnect = false
        stopScan()
        stopRssiUpdates()
        connectedGatt?.disconnect()
        connectedGatt?.close()
        connectedGatt = null
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
        if (!isScanning && bluetoothAdapter?.isEnabled == true) {
            isScanning = true
            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            logCallback("BLE scan started")
            statusCallback("Scanning")
        }
    }

    private fun stopScan() {
        if (isScanning) {
            isScanning = false
            bleScanner?.stopScan(scanCallback)
            logCallback("BLE scan stopped")
        }
    }

    private fun startRssiUpdates() {
        rssiHandler.post(rssiUpdateTask)
    }

    private fun stopRssiUpdates() {
        rssiHandler.removeCallbacks(rssiUpdateTask)
    }

    /*private fun scheduleBackgroundReconnect() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(false)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<BLEReconnectWorker>()
            .setConstraints(constraints)
            .setInitialDelay(RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
    }*/

    private fun scheduleBackgroundReconnect() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val workRequest = OneTimeWorkRequest.from(
            object : ListenableWorker(context, WorkerParameters.EMPTY) {
                override fun startWork(): ListenableFuture<Result> {
                    return try {
                        // Reconnect logika
                        Futures.immediateFuture(Result.success())
                    } catch (e: Exception) {
                        Futures.immediateFuture(Result.failure())
                    }
                }
            }
        ).build()

        WorkManager.getInstance(context).enqueue(workRequest)
    }

    /*fun release() {
        disconnectInternal()
        unregisterBluetoothReceiver()
        permissionLauncher = null
        enableBtLauncher = null
        logCallback("BluetoothManager released")
    }*/

    fun release() {
        disconnectInternal()
        unregisterBluetoothReceiver()
        unregisterAppStateReceiver()
        stopForegroundService()
        permissionLauncher = null
        enableBtLauncher = null
        logCallback("BluetoothManager released")
    }

    fun disconnect() {
        disconnectInternal()
        logCallback("Disconnected by user request")
        statusCallback("Disconnected")
    }

    fun reconnect() {
        shouldReconnect = true
        reconnectAttempts = 0
        startScan()
    }

    fun sendControl(filter: Float, control: UShort) {
        val buffer = ByteArray(20) { 0 }
        ByteBuffer.wrap(buffer)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(filter)
            .putShort(control.toShort())

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

/*
    private fun checkPermissionsAndStart() {
        if (hasRequiredPermissions()) {
            startBluetoothOperations()
        } else {
            requestPermissions()
        }
    }


    private fun checkBluetoothAndStart() {
        if (bluetoothAdapter == null) {
            logCallback("Bluetooth not supported")
            statusCallback("Bluetooth not supported")
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            if (hasBluetoothConnectPermission()) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBtLauncher?.launch(enableBtIntent)
            } else {
                logCallback("BLUETOOTH_CONNECT permission required to enable Bluetooth")
                checkPermissionsAndStart()
            }
        } else {
            checkPermissionsAndStart()
        }
    }

* */

/*
@SuppressLint("MissingPermission")
class BLEManager private constructor(
    private val context: Context,
    private val logCallback: (String) -> Unit,
    private val statusCallback: (String) -> Unit,
    private val onDataReceived: (UUID, ByteArray) -> Unit,
    private val onRssiUpdated: (Int) -> Unit,
    private val onDisconnect: () -> Unit
) {
    companion object {

        private const val TAG = "BLEManager"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val SCAN_DURATION_MS = 10000L

        private val SERVICE_UUID = UUID.fromString("96540000-d6a3-4d5b-8145-e5855fd090a7")
        private val CONTROL_CHARACTERISTIC_UUID = UUID.fromString("96540001-d6a3-4d5b-8145-e5855fd090a7")
        private val STATUS_CHARACTERISTIC_UUID = UUID.fromString("96540002-d6a3-4d5b-8145-e5855fd090a7")
        private val DATA_CHARACTERISTIC_UUID = UUID.fromString("96540003-d6a3-4d5b-8145-e5855fd090a7")
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun create(
            activity: ComponentActivity,
            logCallback: (String) -> Unit = { Log.d(TAG, it) },
            statusCallback: (String) -> Unit = {},
            onDataReceived: (UUID, ByteArray) -> Unit = { _, _ -> },
            onRssiUpdated: (Int) -> Unit = {},
            onDisconnect: () -> Unit = {}
        ): BLEManager {
            return BLEManager(
                context = activity,
                logCallback = logCallback,
                statusCallback = statusCallback,
                onDataReceived = onDataReceived,
                onRssiUpdated = onRssiUpdated,
                onDisconnect = onDisconnect
            ).apply {
                initialize(activity)
            }
        }
    }

    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var enableBtLauncher: ActivityResultLauncher<Intent>? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var connectedGatt: BluetoothGatt? = null
    private var isScanning = false
    private var shouldReconnect = true
    private var reconnectAttempts = 0
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
            startBluetoothOperations()
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
                            startBluetoothOperations()
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
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
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

    // Háttérben történő újracsatlakozás kezelése
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            logCallback("Bluetooth turned on")
                            if (!isScanning && shouldReconnect) {
                                startBluetoothOperations()
                            }
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            logCallback("Bluetooth turned off")
                            disconnectInternal()
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device?.address == lastConnectedDevice?.address) {
                        logCallback("Device disconnected unexpectedly")
                        if (isApplicationInForeground()) {
                            scheduleReconnect()
                        } else {
                            // Alkalmazás háttérben - késleltetett újracsatlakozás
                            mainHandler.postDelayed({
                                if (!isApplicationInForeground()) {
                                    startBluetoothOperations()
                                }
                            }, RECONNECT_DELAY_MS * 2)
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Képernyő bekapcsolásakor próbáljunk újracsatlakozni
                    if (!isScanning && shouldReconnect && connectedGatt == null) {
                        logCallback("Screen turned on - attempting reconnect")
                        startBluetoothOperations()
                    }
                }
            }
        }
    }

    private fun isApplicationInForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningProcesses = activityManager.runningAppProcesses ?: return false
        val processInfo = runningProcesses.firstOrNull { it.processName == context.packageName }
        return processInfo?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
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
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        reconnectAttempts = 0
                        connectedGatt = gatt
                        logCallback("Connected to device: ${gatt.device.address}")
                        statusCallback("Connected")

                        mainHandler.postDelayed({ gatt.requestMtu(517) }, 500)
                        /*
                        mainHandler.postDelayed({ gatt.discoverServices() }, 2000)
                        */
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        logCallback("Disconnected from device")
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

        /*MTU változások hibakezelése:
         Most a requestMtu után fix időzítéssel történik a discoverServices().
         Érdemes lenne csak akkor hívni, ha az onMtuChanged pozitív visszajelzést ad:
         */
         /*override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
             logCallback("MTU changed to $mtu, success: ${status == BluetoothGatt.GATT_SUCCESS}")
         }*/
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
        /*
        Bluetooth LE scanner ellenőrzés:
        bleScanner = bluetoothAdapter?.bluetoothLeScanner néha null lehet,
        ha a rendszer épp nem engedi elindítani.
        Ezt érdemes logolni vagy késleltetve újrapróbálni.
         */
        if (bleScanner == null) {
            logCallback("BLE scanner not available")
            scheduleReconnect()
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

    private fun startBluetoothOperations() {
        logCallback("Starting BLE operations")
        startScan()

        mainHandler.postDelayed({
            if (isScanning) {
                logCallback("Scan timeout reached")
                stopScan()
                scheduleReconnect()
            }
        }, SCAN_DURATION_MS)
    }

    private fun scheduleReconnect() {
        if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            logCallback("Scheduling reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS")
            mainHandler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
        } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logCallback("Max reconnect attempts reached")
            statusCallback("Connection failed")
        }
    }

    private fun handleDisconnection(gatt: BluetoothGatt) {
        stopRssiUpdates()
        gatt.close()
        connectedGatt = null
        statusCallback("Disconnected")
        onDisconnect()

        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun disconnectInternal() {
        shouldReconnect = false
        stopScan()
        stopRssiUpdates()
        connectedGatt?.disconnect()
        connectedGatt?.close()
        connectedGatt = null
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
        if (!isScanning && bluetoothAdapter?.isEnabled == true) {
            isScanning = true
            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            logCallback("BLE scan started")
            statusCallback("Scanning")
        }
    }

    private fun stopScan() {
        if (isScanning) {
            isScanning = false
            bleScanner?.stopScan(scanCallback)
            logCallback("BLE scan stopped")
        }
    }

    private fun startRssiUpdates() {
        rssiHandler.post(rssiUpdateTask)
    }

    private fun stopRssiUpdates() {
        rssiHandler.removeCallbacks(rssiUpdateTask)
    }

    fun release() {
        disconnectInternal()
        unregisterBluetoothReceiver()
        permissionLauncher = null
        enableBtLauncher = null
        logCallback("BluetoothManager released")
    }

    fun disconnect() {
        disconnectInternal()
        logCallback("Disconnected by user request")
        statusCallback("Disconnected")
    }

    fun reconnect() {
        shouldReconnect = true
        reconnectAttempts = 0
        startScan()
    }

    fun sendControl(filter: Float, control: UShort) {
        val buffer = ByteArray(20) { 0 }
        ByteBuffer.wrap(buffer)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(filter)
            .putShort(control.toShort())

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
 */