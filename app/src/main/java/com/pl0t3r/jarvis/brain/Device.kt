package com.pl0t3r.jarvis.brain

import java.time.LocalDateTime

/** What JARVIS can do on the headset. Android implementation: device/AndroidDevice.kt. */
interface Device {
    fun apps(): List<InstalledApp>
    fun launch(app: InstalledApp): Boolean
    /** Returns the new volume in percent. */
    fun volumeStep(up: Boolean): Int
    fun setVolume(percent: Int): Int
    fun mute(on: Boolean)
    fun battery(): Int?
    fun now(): LocalDateTime
    fun goHome(): Boolean
}

/** Free conversation with an AI model. Android implementation: brain/ClaudeBrain.kt. */
interface Chat {
    val available: Boolean
    fun ask(text: String): String
    fun forget()
}
