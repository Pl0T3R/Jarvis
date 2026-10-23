package com.pl0t3r.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

class FakeDevice : Device {
    val launched = mutableListOf<String>()
    var volume = 40
    var muted = false
    override fun apps() = AppMatcherTest.APPS
    override fun launch(app: InstalledApp): Boolean { launched += app.label; return true }
    override fun volumeStep(up: Boolean): Int { volume = (volume + if (up) 13 else -13).coerceIn(0, 100); return volume }
    override fun setVolume(percent: Int): Int { volume = percent; return volume }
    override fun mute(on: Boolean) { muted = on }
    override fun battery(): Int? = 76
    override fun now(): LocalDateTime = LocalDateTime.of(2026, 10, 23, 14, 5)
    override fun goHome() = true
}

class FakeChat(override val available: Boolean) : Chat {
    val asked = mutableListOf<String>()
    var forgotten = false
    override fun ask(text: String): String { asked += text; return "Odpowiedź AI." }
    override fun forget() { forgotten = true }
}

class BrainTest {
    private val device = FakeDevice()

    @Before fun fixedPhrases() { Phrases.random = Random(1) }

    @Test
    fun launchesApps() {
        val brain = Brain(device, FakeChat(available = false))
        val reply = brain.handle("uruchom spotifaj")
        assertEquals(listOf("Spotify"), device.launched)
        assertTrue(reply, reply.contains("Spotify"))
    }

    @Test
    fun unknownAppGoesToAiWhenAvailable() {
        val chat = FakeChat(available = true)
        val reply = Brain(device, chat).handle("otwórz coś do ćwiczeń")
        assertEquals(listOf("otwórz coś do ćwiczeń"), chat.asked)
        assertEquals("Odpowiedź AI.", reply)
    }

    @Test
    fun unknownAppWithoutAi() {
        val reply = Brain(device, FakeChat(available = false)).handle("otwórz kalkulator")
        assertTrue(reply, reply.startsWith("Nie widzę aplikacji kalkulator"))
    }

    @Test
    fun localAnswers() {
        val brain = Brain(device, FakeChat(available = true))
        assertEquals("Jest czternasta zero pięć, sir.", brain.handle("która jest godzina"))
        assertEquals("Dzisiaj jest piątek, dwudziesty trzeci października.", brain.handle("jaki dziś dzień"))
        assertEquals("Bateria gogli: siedemdziesiąt sześć procent.", brain.handle("ile baterii"))
        assertEquals("Głośność pięćdziesiąt procent.", brain.handle("głośność na 50 procent"))
        assertEquals(50, device.volume)
    }

    @Test
    fun questionsWithoutKeyExplainCommands() {
        assertEquals(Phrases.needsKey(), Brain(device, FakeChat(available = false)).handle("jaka będzie pogoda"))
    }

    @Test
    fun justTheNameAsksBack() {
        val reply = Brain(device, FakeChat(available = true)).handle("")
        assertTrue(reply, reply in listOf("Tak, sir?", "Słucham, sir.", "Do usług."))
    }

    @Test
    fun forgetClearsChat() {
        val chat = FakeChat(available = true)
        Brain(device, chat).handle("zapomnij rozmowę")
        assertTrue(chat.forgotten)
    }
}

class PolishWordsTest {
    @Test
    fun numbers() {
        assertEquals("zero", PolishWords.number(0))
        assertEquals("piętnaście", PolishWords.number(15))
        assertEquals("dwadzieścia", PolishWords.number(20))
        assertEquals("dziewięćdziesiąt dziewięć", PolishWords.number(99))
        assertEquals("sto", PolishWords.number(100))
    }

    @Test
    fun times() {
        assertEquals("Jest równo dziewiąta", PolishWords.time(LocalDateTime.of(2026, 1, 1, 9, 0)))
        assertEquals("Jest dwudziesta pierwsza trzydzieści pięć", PolishWords.time(LocalDateTime.of(2026, 1, 1, 21, 35)))
        assertEquals("Jest północ", PolishWords.time(LocalDateTime.of(2026, 1, 1, 0, 0)))
        assertEquals("Dzisiaj jest czwartek, pierwszy stycznia", PolishWords.date(LocalDateTime.of(2026, 1, 1, 0, 0)))
    }

    @Test
    fun parsesSpokenNumbers() {
        assertEquals(55, PolishWords.parseNumber("na piecdziesiat piec procent"))
        assertEquals(20, PolishWords.parseNumber("dwadziescia"))
        assertEquals(50, PolishWords.parseNumber("na pol"))
        assertEquals(null, PolishWords.parseNumber("bez liczby"))
    }
}
