package com.pluxy.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = ServerPreferences(context)
        val shouldStart = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> preferences.autoStart
            Intent.ACTION_MY_PACKAGE_REPLACED -> preferences.runningDesired
            else -> false
        }
        if (shouldStart && preferences.hasMediaSource()) {
            runCatching { ServerService.start(context) }
        }
    }
}
