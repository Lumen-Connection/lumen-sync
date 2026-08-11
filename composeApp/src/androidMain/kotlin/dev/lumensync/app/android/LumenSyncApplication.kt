package dev.lumensync.app.android

import android.app.Application

class LumenSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidAppGraph.create(this)
    }
}

