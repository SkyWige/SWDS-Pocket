package ru.skywige.swds

import android.app.Application

class SwdsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        MediaHub.createChannel(this)
        if (Prefs(this).paired) {
            runCatching { SyncWorker.schedule(this) }
        }
    }
}
