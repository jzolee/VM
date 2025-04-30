package com.jzolee.vibrationmonitor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

class BLEReconnectWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            delay(2000) // Wait before reconnecting

            // Get BLEManager instance (you might need to implement this differently)
            BLEManager.instance?.reconnect()

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}