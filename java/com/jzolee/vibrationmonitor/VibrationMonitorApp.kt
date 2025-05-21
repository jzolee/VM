package com.jzolee.vibrationmonitor

import android.app.Application

class VibrationMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Inicializálások itt (pl. Timber)
        /*if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }*/
    }
}