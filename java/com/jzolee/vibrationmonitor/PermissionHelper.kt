// PermissionHelper.kt - külön osztály az engedélykérésekhez és BT bekapcsoláshoz

package com.jzolee.vibrationmonitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class PermissionHelper(
    private val activity: ComponentActivity,
    private val context: Context,
    private val onGranted: () -> Unit,
    private val onDenied: () -> Unit,
    private val onBluetoothEnabled: () -> Unit,
    private val onBluetoothNotEnabled: () -> Unit
) {
    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.all { it.value }) {
                onGranted()
            } else {
                onDenied()
            }
        }

    private val bluetoothEnableLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter?.isEnabled == true) {
                onBluetoothEnabled()
            } else {
                onBluetoothNotEnabled()
            }
        }

    fun requestPermissions() {
        if (checkPermissions()) {
            onGranted()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    fun requestEnableBluetooth() {
        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        bluetoothEnableLauncher.launch(enableIntent)
    }

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
