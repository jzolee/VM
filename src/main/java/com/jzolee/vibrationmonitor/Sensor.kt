package com.jzolee.vibrationmonitor

import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val CONTROL_CHARACTERISTIC_UUID  = "96540001-d6a3-4d5b-8145-e5855fd090a7"
private const val STATUS_CHARACTERISTIC_UUID   = "96540002-d6a3-4d5b-8145-e5855fd090a7"
private const val DATA_CHARACTERISTIC_UUID = "96540003-d6a3-4d5b-8145-e5855fd090a7"
//private const val RMS_DATA_CHARACTERISTIC_UUID = "96540004-d6a3-4d5b-8145-e5855fd090a7"

object ControlMode {
    const val OFF = 0b0000000000000000
    const val XEN = 0b0000000000000001
    const val YEN = 0b0000000000000010
    const val ZEN = 0b0000000000000100
}

@SuppressLint("MissingPermission", "DefaultLocale")
class Sensor(
    private val logCallback: (String) -> Unit,
    private val updateRms: (String) -> Unit,
    private val updateBattery: (String) -> Unit,
    private val updateMajorPeak: (String) -> Unit,
    private val updateFFT: (fftData: Map<Float, Float>) -> Unit,
    private val updateControls: () -> Unit
) {
    var control  = ControlMode.OFF
    var filter : Float = 0.0f

    fun processData(uuid: UUID, data: ByteArray) {
        when (uuid.toString()) {
            DATA_CHARACTERISTIC_UUID -> {
                if (data.size == 225) {
                    var floatValue = ByteBuffer.wrap(data, 0, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                    updateRms(String.format("%.2f", floatValue))
                    floatValue = ByteBuffer.wrap(data, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                    updateMajorPeak(String.format("%.0f", floatValue))
                    floatValue = ByteBuffer.wrap(data, 8, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                    filter = floatValue
                    val shortValue = ByteBuffer.wrap(data, 12, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short
                    control = shortValue.toInt()
                    updateControls()
                    val intValue = data[14].toInt() // Byte -> Int konverzió
                    updateBattery("battery: $intValue %")

                    /////////////////////////////////////////////////////////////////
                    val newData = mutableMapOf<Float, Float>()
                    // Az első bin frekvenciájának kinyerése (1 bájt)
                    val startFreq = data[15].toInt() and 0xFF
                    // A bin-ek számának kinyerése (1 bájt)
                    val numBins = data[16].toInt() and 0xFF
                        // Bin-ek amplitúdó értékeinek kinyerése (2 bájt unsigned értékekként)
                    for (i in 0 until numBins) {
                        val amp = ByteBuffer.wrap(data, 17 + i * 2, 2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .short.toInt() and 0xFFFF
                        val freq = startFreq + i
                        newData[freq.toFloat()] = amp.toFloat() / 100f
                    }
                    updateFFT(newData)

                } else {
                    logCallback("Invalid DATA size: ${data.size}")
                }
            }

            /*RMS_DATA_CHARACTERISTIC_UUID -> {
                if (data.size == 20) {
                    var floatValue = ByteBuffer.wrap(data, 0, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                    updateRms(String.format("%.2f", floatValue))
                    floatValue = ByteBuffer.wrap(data, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                    updateMajorPeak(String.format("%.0f", floatValue))
                    floatValue = ByteBuffer.wrap(data, 8, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                    filter = floatValue
                    val shortValue = ByteBuffer.wrap(data, 12, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short
                    control = shortValue.toInt()
                    updateControls()
                    val intValue = data[14].toInt() // Byte -> Int konverzió
                    updateBattery("battery: $intValue %")
                } else {
                    logCallback("Invalid RMS data size: ${data.size}")
                }
            }*/

            STATUS_CHARACTERISTIC_UUID -> {
                if (data.size == 20) {
                    val floatValue = ByteBuffer.wrap(data, 0, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                    filter = floatValue
                    val shortValue = ByteBuffer.wrap(data, 4, 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .short
                    control = shortValue.toInt()
                    updateControls()
                } else {
                    logCallback("Invalid STATUS data size: ${data.size}")
                }
            }

            /*FFT_DATA_CHARACTERISTIC_UUID -> {
                if (data.size == 20) {
                    val fftData = mutableMapOf<Float, Float>()
                    for (i in data.indices step 4) {
                        val freq = ByteBuffer.wrap(data, i, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        val amp = ByteBuffer.wrap(data, i + 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        fftData[freq.toFloat() /** 60f*/ / 100f] = amp.toFloat() / 100f
                    }
                    updateFFT(fftData)
                } else {
                    logCallback("Invalid FFT data size: ${data.size}")
                }
            }*/

            /*FFT_DATA_CHARACTERISTIC_UUID -> {
                if (data.size == 20) {
                        val newData = mutableMapOf<Float, Float>()
                        // Az első bin frekvenciájának kinyerése (1 bájt)
                        val startFreq = data[0].toInt() and 0xFF
                        // A bin-ek számának kinyerése (1 bájt)
                        val numBins = data[1].toInt() and 0xFF
                        // Bin-ek amplitúdó értékeinek kinyerése (2 bájt unsigned értékekként)
                        for (i in 0 until numBins) {
                            val amp = ByteBuffer.wrap(data, 2 + i * 2, 2)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .short.toInt() and 0xFFFF
                            val freq = startFreq + i
                            newData[freq.toFloat()] = amp.toFloat() /100f // / 3937.00787f // Átalakítás inch/s-ből
                        }
                        updateFFT(newData)
                } else {
                    logCallback("Invalid FFT data size: ${data.size}")
                }
            }*/

            /*FFT_DATA_CHARACTERISTIC_UUID -> {
                if (data.size > 2) {
                    val newData = mutableMapOf<Float, Float>()
                    // Az első bin frekvenciájának kinyerése (1 bájt)
                    val startFreq = data[0].toInt() and 0xFF
                    // A bin-ek számának kinyerése (1 bájt)
                    val numBins = data[1].toInt() and 0xFF

                    if (data.size >= 2 + numBins * 2) {
                        // Bin-ek amplitúdó értékeinek kinyerése (2 bájt unsigned értékekként)
                        for (i in 0 until numBins) {
                            val amp = ByteBuffer.wrap(data, 2 + i * 2, 2)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .short.toInt() and 0xFFFF
                            val freq = startFreq + i
                            newData[freq.toFloat()] = amp.toFloat() / 100f
                        }
                        updateFFT(newData)
                    }
                } else {
                    logCallback("Invalid FFT data size: ${data.size}")
                }
            }*/
            else -> {
                logCallback("Unknown characteristic data: $uuid")
            }
        }
    }
}
