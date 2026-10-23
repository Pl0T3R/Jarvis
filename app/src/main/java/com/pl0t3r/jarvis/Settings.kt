package com.pl0t3r.jarvis

import android.content.Context
import com.pl0t3r.jarvis.brain.ClaudeBrain

/** User settings, stored privately on the headset. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    enum class WakeMode { ACCURATE, ECONOMY }

    /** Claude API key (from console.anthropic.com). Never leaves the headset except to api.anthropic.com. */
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", ClaudeBrain.DEFAULT_MODEL) ?: ClaudeBrain.DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v).apply()

    /** Whisper size for commands: "small" (better Polish) or "base" (faster). */
    var whisper: String
        get() = prefs.getString("whisper", "small") ?: "small"
        set(v) = prefs.edit().putString("whisper", v).apply()

    /**
     * ACCURATE: every phrase is checked by Whisper for "JARVIS" (reliable, more CPU while people talk).
     * ECONOMY: a tiny always-on keyword spotter wakes Whisper (less CPU, misses more often).
     */
    var wakeMode: WakeMode
        get() = runCatching { WakeMode.valueOf(prefs.getString("wake_mode", "ACCURATE")!!) }.getOrDefault(WakeMode.ACCURATE)
        set(v) = prefs.edit().putString("wake_mode", v.name).apply()

    /** 0..1, how readily "JARVIS" is accepted. */
    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 0.5f)
        set(v) = prefs.edit().putFloat("sensitivity", v.coerceIn(0f, 1f)).apply()

    /** 0..1, strength of the "digital" voice effect. */
    var voiceFx: Float
        get() = prefs.getFloat("voice_fx", 0.5f)
        set(v) = prefs.edit().putFloat("voice_fx", v.coerceIn(0f, 1f)).apply()

    var webSearch: Boolean
        get() = prefs.getBoolean("web_search", true)
        set(v) = prefs.edit().putBoolean("web_search", v).apply()

    /** Whether JARVIS should be listening (also after a reboot). */
    var listening: Boolean
        get() = prefs.getBoolean("listening", true)
        set(v) = prefs.edit().putBoolean("listening", v).apply()

    fun chatConfig() = ClaudeBrain.Config(apiKey, model, webSearch)
}
