package com.pl0t3r.jarvis.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.util.Log
import com.pl0t3r.jarvis.brain.Device
import com.pl0t3r.jarvis.brain.InstalledApp
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * Launches apps and controls the headset. Launching from the background service needs the
 * SYSTEM_ALERT_WINDOW app-op (install.ps1 grants it), otherwise Android blocks the start.
 */
class AndroidDevice(private val context: Context) : Device {
    private val pm: PackageManager = context.packageManager
    private val audio = context.getSystemService(AudioManager::class.java)
    private var cache: List<InstalledApp> = emptyList()
    private var cacheAt = 0L

    override fun apps(): List<InstalledApp> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cacheAt < 60_000) return cache
        val found = LinkedHashMap<String, InstalledApp>()
        // regular Android (2D) apps and Quest VR apps, which declare their own launcher category
        for (category in listOf(Intent.CATEGORY_LAUNCHER, VR_CATEGORY)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || pkg in found) continue
                found[pkg] = InstalledApp(ri.loadLabel(pm).toString().trim(), pkg)
            }
        }
        cache = found.values.toList()
        cacheAt = now
        return cache
    }

    override fun launch(app: InstalledApp): Boolean {
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: vrLaunchIntent(app.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "launch ${app.packageName} failed", e)
            false
        }
    }

    private fun vrLaunchIntent(pkg: String): Intent? {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY).setPackage(pkg)
        val ri = pm.queryIntentActivities(probe, 0).firstOrNull() ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY).setClassName(pkg, ri.activityInfo.name)
    }

    private fun percent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    override fun volumeStep(up: Boolean): Int {
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        // two steps feel like one clear change on Quest's 15-step scale
        repeat(2) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
        return percent()
    }

    override fun setVolume(percent: Int): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) / 100f * max).roundToInt(), 0)
        return percent()
    }

    override fun mute(on: Boolean) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0,
        )
    }

    override fun battery(): Int? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (v in 0..100) v else null
    }

    override fun now(): LocalDateTime = LocalDateTime.now()

    override fun goHome(): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "home failed", e)
        false
    }

    companion object {
        private const val TAG = "Jarvis"
        const val VR_CATEGORY = "com.oculus.intent.category.VR"
    }
}
