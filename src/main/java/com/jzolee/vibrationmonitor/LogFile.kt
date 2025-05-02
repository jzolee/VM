package com.jzolee.vibrationmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class LogFile private constructor(
    private val activity: AppCompatActivity,
    private val logCallback: (String) -> Unit,
    private val permissionOK: () -> Unit,
    private val permissionNOK: () -> Unit
) {
    companion object {
        fun create(
            activity: AppCompatActivity,
            logCallback: (String) -> Unit,
            permissionOK: () -> Unit,
            permissionNOK: () -> Unit
        ): LogFile {
            return LogFile(activity, logCallback, permissionOK, permissionNOK).apply {
                initPermissionLauncher()
            }
        }
    }

    //private var writer: FileWriter? = null
    private var writer: BufferedWriter? = null
    private var isRecording = false
    private val sampleCount = AtomicInteger(0) // Thread-safe számláló

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val startTime = System.currentTimeMillis()
    private val frequencyHeader by lazy { (0..104).joinToString(",") { "${it}Hz" } }

    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private val outputDirectory: File by lazy { getOrCreateDocumentsDirectory() }

    private fun initPermissionLauncher() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                //startRecording() // ez miért van itt?
                permissionOK()
            } else {
                logCallback("Storage permission denied - cannot save measurements")
                permissionNOK()
            }
        }
    }

    fun start() {
        if (isRecording) return

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startRecording()
                permissionOK()
            }
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                startRecording()
                permissionOK()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) -> {
                logCallback("Permission required to save vibration data")
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    /*private fun startRecording() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val outputFile = File(outputDirectory, "VM_data_$timestamp.csv")

            //writer = FileWriter(outputFile).also {
            writer = FileWriter(outputFile).buffered().also {
                isRecording = true
                sampleCount.set(0)
                writeHeader()
                logCallback("Recording started successfully")
            }
        } catch (e: Exception) {
            logCallback("Error starting recording: ${e.localizedMessage}")
            stop()
        }
    }*/

    fun getFileLocation(): String {
        return outputDirectory.absolutePath
    }

    fun getLastFileName(): String? {
        return outputDirectory.listFiles()?.maxByOrNull { it.lastModified() }?.name
    }

    private fun startRecording() {
        try {
            val outputFile = File(outputDirectory, "VM_data_${System.currentTimeMillis()}.csv").apply {
                createNewFile() // Explicit fájl létrehozás
            }

            writer = FileWriter(outputFile).buffered().also {
                logCallback("File created at: ${outputFile.absolutePath}") // Debug üzenet
                isRecording = true
                sampleCount.set(0)
                writeHeader()
            }
        } catch (e: Exception) {
            logCallback("File creation error: ${e.message}\nPath: ${outputDirectory.absolutePath}")
            stop()
        }
    }

    fun stop() {
        if (!isRecording) return

        try {
            writer?.apply {
                flush()
                close()
                logCallback("Recording stopped. Saved ${sampleCount.get()} samples.")
            }
        } catch (e: Exception) {
            logCallback("Error stopping recording: ${e.localizedMessage}")
        } finally {
            writer = null
            isRecording = false
        }
    }

    fun onDataReceived(fftData: Map<Float, Float>) {
        if (!isRecording) return

        try {
            writeData(fftData.toSortedMap())
        } catch (e: Exception) {
            logCallback("Error writing data: ${e.localizedMessage}")
        }
    }

    private fun writeHeader() {
        writer?.apply {
            write("# BEÁLLÍTÁSOK\n")
            write("# Mérés kezdete: ${dateFormat.format(Date(startTime))}\n")
            write("# Frekvenciatartomány: 1-104Hz (1Hz lépés)\n")
            write("# \n# ADATOK\n")
            write("Időbélyeg,$frequencyHeader\n")
            flush()
        }
    }

    private fun writeData(fftData: SortedMap<Float, Float>) {
        writer?.apply {
            try {
                val timestamp = dateFormat.format(Date(System.currentTimeMillis()))
                val amplitudes = (0..104).joinToString(",") {
                    freq -> fftData[freq.toFloat()]?.toString() ?: "0.0"
                }

                write("$timestamp,$amplitudes\n")
                val currentCount = sampleCount.incrementAndGet()

                // Minden 100. mérésnél flush a teljesítmény érdekében
                if (currentCount % 100 == 0) {
                    flush()
                    logCallback("Flushed data at sample $currentCount")
                }
            } catch (e: Exception) {
                logCallback("Error writing data: ${e.localizedMessage}")
            }
        }
    }

    /*private fun getOrCreateDocumentsDirectory(): File {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.apply {
                    File(this, "VibrationData").mkdirs()
                } ?: activity.filesDir.apply {
                    File(this, "VibrationData").mkdirs()
                }
            } else {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                File(docsDir, "VibrationData").apply { mkdirs() }
            }
        } else {
            activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.apply {
                File(this, "VibrationData").mkdirs()
            } ?: activity.filesDir.apply {
                File(this, "VibrationData").mkdirs()
            }
        }
    }*/

    private fun getOrCreateDocumentsDirectory(): File {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - használjuk az app-specifikus könyvtárat
                activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.apply {
                    File(this, "VibrationData").also { it.mkdirs() }
                } ?: activity.filesDir.apply {
                    File(this, "VibrationData").also { it.mkdirs() }
                }
            } else {
                // Android 9 és alatt - klasszikus módszer
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "VibrationData").apply { mkdirs() }
            }
        } else {
            // Belső tár
            activity.filesDir.resolve("VibrationData").apply { mkdirs() }
        }
    }
}
