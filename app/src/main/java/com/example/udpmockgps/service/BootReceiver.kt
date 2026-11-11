package com.example.udpmockgps.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.udpmockgps.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Optional auto-start. Best effort: Android may refuse to start a location-type foreground service
 * from the background (e.g. missing runtime location permission); that failure is logged, not fatal.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(appContext).settings.first()
                if (settings.autoStartOnBoot) MockGpsService.start(appContext)
            } catch (e: Exception) {
                Log.w("BootReceiver", "Auto-start failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
