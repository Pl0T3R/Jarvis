package com.pl0t3r.jarvis.brain

import com.pl0t3r.jarvis.brain.LocalCommands.Kind

/**
 * Decides what to do with what was said after "JARVIS": a local command first (offline, free),
 * otherwise the AI chat, otherwise a polite "I only know commands". Returns what to say.
 */
class Brain(private val device: Device, private val chat: Chat) {

    fun handle(command: String): String {
        if (command.isBlank()) return Phrases.yesSir()
        val cmd = LocalCommands.parse(command)
            ?: return if (chat.available) chat.ask(command) else Phrases.needsKey()

        return when (cmd.kind) {
            Kind.LAUNCH -> launch(cmd.target, command)
            Kind.VOLUME_UP -> Phrases.volume(device.volumeStep(up = true))
            Kind.VOLUME_DOWN -> Phrases.volume(device.volumeStep(up = false))
            Kind.VOLUME_SET -> Phrases.volume(device.setVolume(cmd.percent))
            Kind.MUTE -> { device.mute(true); Phrases.muted() }
            Kind.UNMUTE -> { device.mute(false); Phrases.unmuted() }
            Kind.TIME -> Phrases.time(PolishWords.time(device.now()))
            Kind.DATE -> Phrases.date(PolishWords.date(device.now()))
            Kind.BATTERY -> Phrases.battery(device.battery())
            Kind.STOP -> Phrases.stopped()
            Kind.FORGET -> { chat.forget(); Phrases.forgot() }
            Kind.HOME -> if (device.goHome()) Phrases.goingHome() else Phrases.homeFailed()
        }
    }

    private fun launch(target: String, original: String): String {
        val match = AppMatcher.best(target, device.apps())
        if (match != null) {
            return if (device.launch(match.app)) Phrases.launching(match.app.label) else Phrases.launchFailed(match.app.label)
        }
        // "włącz muzykę", "otwórz coś do ćwiczeń": the AI can pick an app from the list
        return if (chat.available) chat.ask(original) else Phrases.notInstalled(target)
    }
}
