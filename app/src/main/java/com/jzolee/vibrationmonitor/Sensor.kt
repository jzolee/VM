package com.jzolee.vibrationmonitor

import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val CONTROL_CHARACTERISTIC_UUID = "96540001-d6a3-4d5b-8145-e5855fd090a7"
private const val STATUS_CHARACTERISTIC_UUID = "96540002-d6a3-4d5b-8145-e5855fd090a7"
private const val DATA_CHARACTERISTIC_UUID = "96540003-d6a3-4d5b-8145-e5855fd090a7"

object ControlMode {
    const val OFF = 0b0000000000000000
    const val XEN = 0b0000000000000001
    const val YEN = 0b0000000000000010
    const val ZEN = 0b0000000000000100
}

@SuppressLint("MissingPermission", "DefaultLocale")
class Sensor(
    private val logCallback: (String) -> Unit,
    private val updateControls: () -> Unit,
    private val updateData: () -> Unit
) {
    var control = ControlMode.OFF
    var filter: Float = 0.0f
    var rpm = FloatArray(7) // rpm

    var rms = FloatArray(7) // inch/s
    var battery: Int = 0 // %

    fun processData(uuid: UUID, data: ByteArray) {
        when (uuid.toString()) {
            DATA_CHARACTERISTIC_UUID -> {
                if (data.size == 33) {
                    var floatValue = ByteBuffer.wrap(data, 0, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                    rpm[0] = floatValue
                    val intValue = data[4].toInt() // Byte -> Int konverzió
                    battery = intValue
                    for (i in 0 until 7) {
                        floatValue = ByteBuffer.wrap(data, 5 + i * 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .float
                        rms[i] = floatValue
                    }
                    updateData()
                } else {
                    logCallback("Invalid DATA size: ${data.size}")
                }
            }

            STATUS_CHARACTERISTIC_UUID -> {
                if (data.size == 30) {
                    var floatValue = ByteBuffer.wrap(data, 0, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                    filter = floatValue
                    val shortValue = ByteBuffer.wrap(data, 4, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short
                    control = shortValue.toInt()
                    for (i in 0 until 6) {
                        floatValue = ByteBuffer.wrap(data, 6 + i * 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .float
                        rpm[i + 1] = floatValue * 60.0F
                    }
                    updateControls()
                } else {
                    logCallback("Invalid STATUS data size: ${data.size}")
                }
            }

            else -> {
                logCallback("Unknown characteristic data: $uuid")
            }
        }
    }
}
