package com.bunny.app

import android.app.Application
import com.bunny.app.playback.CrashRecorder
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BunnyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // First thing after Hilt: make hard crashes leave a readable trace
        // behind (the in-memory diagnostics ring dies with the process).
        CrashRecorder.install(this)
    }
}
