package com.pl0t3r.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts listening after the headset boots (or the app is updated). The microphone may only be used
 * by a service started from the foreground, so the panel opens for a moment and hides itself;
 * that needs the SYSTEM_ALERT_WINDOW app-op from install.ps1.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Settings(context).listening) return
        try {
            if (android.provider.Settings.canDrawOverlays(context)) {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true))
            } else {
                JarvisService.start(context)
            }
        } catch (e: RuntimeException) {
            Log.w("Jarvis", "autostart failed", e)
        }
    }
}
