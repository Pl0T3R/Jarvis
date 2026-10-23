package com.pl0t3r.jarvis.brain

import kotlin.random.Random

/** What JARVIS says. Short, calm, a little formal. */
object Phrases {
    var random: Random = Random.Default

    private fun pick(vararg options: String) = options[random.nextInt(options.size)]

    fun launching(label: String) = pick(
        "Oczywiście, sir. Uruchamiam $label.",
        "Już się robi. $label.",
        "Uruchamiam $label, sir.",
    )

    fun launchFailed(label: String) = "Nie udało się uruchomić aplikacji $label, sir."
    fun notInstalled(name: String) = "Nie widzę aplikacji $name wśród zainstalowanych, sir."

    fun volume(percent: Int) = "Głośność ${PolishWords.number(percent)} procent."
    fun muted() = pick("Wyciszam.", "Cisza, sir.")
    fun unmuted() = "Dźwięk przywrócony, sir."

    fun time(text: String) = "$text, sir."
    fun date(text: String) = "$text."

    fun battery(percent: Int?) = when {
        percent == null -> "Nie mogę odczytać stanu baterii, sir."
        percent < 20 -> "Bateria gogli: ${PolishWords.number(percent)} procent. Sugeruję ładowanie, sir."
        else -> "Bateria gogli: ${PolishWords.number(percent)} procent."
    }

    fun stopped() = pick("Do usług, sir.", "Oczywiście.", "Jak sobie życzysz, sir.")
    fun forgot() = "Pamięć rozmowy wyczyszczona, sir."
    fun goingHome() = "Wracam do menu."
    fun homeFailed() = "Nie mogę stąd wrócić do menu, sir."

    fun yesSir() = pick("Tak, sir?", "Słucham, sir.", "Do usług.")
    fun didNotHear() = "Nie dosłyszałem, sir."
    fun needsKey() = "Bez klucza API znam tylko komendy, sir. Na przykład: uruchom Spotify albo która godzina."
    fun noConnection() = "Przepraszam, sir, nie mogę teraz połączyć się z serwerem."
    fun refused() = "Przepraszam, sir, w tym nie mogę pomóc."
    fun ready() = "JARVIS gotowy, sir."
}
