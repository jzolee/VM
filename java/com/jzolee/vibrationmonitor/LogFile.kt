package com.jzolee.vibrationmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SortedMap
import java.util.concurrent.atomic.AtomicInteger

class LogFile private constructor(
    private val activity: AppCompatActivity,
    private val logCallback: (String) -> Unit,
    private val permissionOK: () -> Unit,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val PREF_SAF_URI = "saf_uri"
        fun create(
            activity: AppCompatActivity,
            logCallback: (String) -> Unit,
            permissionOK: () -> Unit,
            onStart: () -> Unit,
            onStop: () -> Unit,
            onError: (String) -> Unit
        ): LogFile {
            return LogFile(activity, logCallback, permissionOK, onStart, onStop, onError).apply {
                initialize()
            }
        }
    }

    private var writer: BufferedWriter? = null
    private var isRecording = false
    private var pendingStart = false
    private var outputUri: Uri? = null

    private val sampleCount = AtomicInteger(0)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val frequencyHeader by lazy { (1..104).joinToString(",") { "${it}Hz" } }
    private val outputDirectory: File by lazy { getOrCreateDocumentsDirectory() }

    private lateinit var permissionLauncher: ActivityResultLauncher<String>
    private lateinit var documentLauncher: ActivityResultLauncher<Intent>

    private fun initialize() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                permissionOK()
            } else {
                logCallback("Storage permission denied - cannot save measurements")
                onError("Permission denied")
            }
        }

        documentLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == AppCompatActivity.RESULT_OK) {
                val treeUri = result.data?.data
                if (treeUri != null) {
                    try {
                        activity.contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        outputUri = treeUri
                        saveUri(treeUri)
                        logCallback("SAF directory selected: $treeUri")
                    } catch (e: Exception) {
                        val msg = "Error taking persistable URI permission: ${e.message}"
                        logCallback(msg)
                        onError(msg)
                        return@registerForActivityResult
                    }

                    if (pendingStart) {
                        pendingStart = false
                        start()
                    }
                } else {
                    val msg = "SAF selection returned null URI"
                    logCallback(msg)
                    onError(msg)
                }
            } else {
                val msg = "No SAF directory selected"
                logCallback(msg)
                onError(msg)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                == PackageManager.PERMISSION_GRANTED
            ) {
                permissionOK()
            } else {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            permissionOK()
        }

        outputUri = loadSavedUri()
    }

    private fun requestSAFDirectory() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        documentLauncher.launch(intent)
    }

    private fun saveUri(uri: Uri) {
        val prefs = activity.getSharedPreferences("logfile_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SAF_URI, uri.toString()).apply()
    }

    private fun loadSavedUri(): Uri? {
        val prefs = activity.getSharedPreferences("logfile_prefs", Context.MODE_PRIVATE)
        val uriString = prefs.getString(PREF_SAF_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    private fun writeHeader() {
        writer?.apply {
            write("# Start time: ${dateFormat.format(Date(System.currentTimeMillis()))}\n")
            write("# Frequency range: 1-104Hz (1Hz step)\n")
            write("Timestamp,RMS,$frequencyHeader\n")
            flush()
        }
    }

    private fun writeData(fftData: SortedMap<Float, Float>) {
        writer?.apply {
            try {
                val timestamp = dateFormat.format(Date(System.currentTimeMillis()))
                val amplitudes = (0..104).joinToString(",") { freq ->
                    fftData[freq.toFloat()]?.toString() ?: "0.0"
                }

                write("$timestamp,$amplitudes\n")
                val currentCount = sampleCount.incrementAndGet()

                if (currentCount % 100 == 0) {
                    flush()
                    logCallback("Flushed data at sample $currentCount")
                }
            } catch (e: Exception) {
                val msg = "Error writing data: ${e.localizedMessage}"
                logCallback(msg)
                onError(msg)
            }
        }
    }

    private fun getOrCreateDocumentsDirectory(): File {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.apply {
                    File(this, "VibrationData").also { it.mkdirs() }
                } ?: activity.filesDir.apply {
                    File(this, "VibrationData").also { it.mkdirs() }
                }
            } else {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "VibrationData"
                ).apply { mkdirs() }
            }
        } else {
            activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.apply {
                File(this, "VibrationData").mkdirs()
            } ?: activity.filesDir.apply {
                File(this, "VibrationData").mkdirs()
            }
        }
    }

    fun start() {
        if (isRecording) return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val fileName = "VM_data_$timestamp.csv"

        try {
            writer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (outputUri == null) {
                    logCallback("Requesting SAF directory before starting recording")
                    pendingStart = true
                    requestSAFDirectory()
                    return
                }

                val pickedDir = DocumentFile.fromTreeUri(activity, outputUri!!)
                if (pickedDir == null || !pickedDir.canWrite()) {
                    val msg = "Selected folder is not writable."
                    logCallback(msg)
                    onError(msg)
                    return
                }

                val newFile = pickedDir.createFile("text/csv", fileName)
                if (newFile == null) {
                    val msg = "Failed to create file in selected directory."
                    logCallback(msg)
                    onError(msg)
                    return
                }

                BufferedWriter(OutputStreamWriter(activity.contentResolver.openOutputStream(newFile.uri)))
            } else {
                val outputFile = File(outputDirectory, fileName).apply {
                    createNewFile()
                }
                logCallback("File created at: ${outputFile.absolutePath}")
                FileWriter(outputFile).buffered()
            }

            isRecording = true
            sampleCount.set(0)
            writeHeader()
            onStart()

        } catch (e: Exception) {
            val msg = "File creation error: ${e.message}"
            logCallback(msg)
            onError(msg)
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
            onStop()
        } catch (e: Exception) {
            val msg = "Error stopping recording: ${e.localizedMessage}"
            logCallback(msg)
            onError(msg)
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
            val msg = "Error writing data: ${e.localizedMessage}"
            logCallback(msg)
            onError(msg)
        }
    }
}
