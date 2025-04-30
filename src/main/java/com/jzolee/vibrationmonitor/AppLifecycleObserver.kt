package com.jzolee.vibrationmonitor

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent

class AppLifecycleObserver(private val bleManager: BLEManager) : LifecycleObserver {

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        bleManager.onAppBackgrounded()
    }
}
