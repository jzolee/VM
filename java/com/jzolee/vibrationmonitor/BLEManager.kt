// BLEManager.kt - Koordinátor osztály, amely BLEConnection és PermissionHelper példányt kezel

package com.jzolee.vibrationmonitor

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import java.lang.ref.WeakReference
import java.util.UUID

class BLEManager private constructor(
    private val context: Context,
    private val logCallback: (String) -> Unit,
    private val statusCallback: (String) -> Unit,
    private val onDataReceived: (UUID, ByteArray) -> Unit,
    private val onRssiUpdated: (Int) -> Unit,
    private val onDisconnect: () -> Unit
) {

    companion object {
        @Volatile
        var instance: BLEManager? = null
            private set

        fun create(
            context: Context,
            logCallback: (String) -> Unit,
            statusCallback: (String) -> Unit,
            onDataReceived: (UUID, ByteArray) -> Unit,
            onRssiUpdated: (Int) -> Unit,
            onDisconnect: () -> Unit
        ): BLEManager {
            return instance ?: synchronized(this) {
                instance ?: BLEManager(
                    context,
                    logCallback,
                    statusCallback,
                    onDataReceived,
                    onRssiUpdated,
                    onDisconnect
                ).also { instance = it }
            }
        }
    }

    private lateinit var activityWeakRef: WeakReference<ComponentActivity>
    private var isInitialized = false
    private var connection: BLEConnection? = null
    private var permissionHelper: PermissionHelper? = null

    fun initialize(activity: ComponentActivity) {
        if (isInitialized) return
        isInitialized = true
        activityWeakRef = WeakReference(activity)

        permissionHelper = PermissionHelper(
            activity = activity,
            context = context,
            onGranted = {
                logCallback("Permissions granted")
                startBLEService()
                startConnection()
            },
            onDenied = {
                statusCallback("Bluetooth permission denied")
                logCallback("Bluetooth permissions not granted")
            },
            onBluetoothEnabled = {
                logCallback("Bluetooth enabled by user")
                startConnection()
            },
            onBluetoothNotEnabled = {
                statusCallback("Bluetooth not enabled")
                logCallback("Bluetooth enable request denied or failed")
            }
        )

        permissionHelper?.requestPermissions()
    }

    private fun startBLEService() {
        val serviceIntent = Intent(context, BLEService::class.java)
        context.startForegroundService(serviceIntent)
        logCallback("BLEService started")
    }

    private fun startConnection() {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            logCallback("Bluetooth is not enabled or not available")
            statusCallback("Bluetooth unavailable")
            permissionHelper?.requestEnableBluetooth()
            return
        }

        connection = BLEConnection(
            context = context,
            logCallback = logCallback,
            statusCallback = statusCallback,
            onDataReceived = onDataReceived,
            onRssiUpdated = onRssiUpdated,
            onDisconnect = onDisconnect
        )
        connection?.startScan()
    }

    fun sendControl(filter: Float, control: UShort, rpm: FloatArray) {
        connection?.sendControl(filter, control, rpm)
    }

    fun release() {
        logCallback("Releasing BLEManager resources")
        connection?.release()
        connection = null
        val serviceIntent = Intent(context, BLEService::class.java)
        context.stopService(serviceIntent)
        logCallback("BLEService stopped")
        instance = null
    }
}
