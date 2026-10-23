package com.pl0t3r.jarvis.brain

import com.pl0t3r.jarvis.brain.LocalCommands.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCommandsTest {
    private fun kind(s: String) = LocalCommands.parse(s)?.kind

    @Test
    fun launch() {
        assertEquals(LocalCommands.Command(Kind.LAUNCH, "spotify"), LocalCommands.parse("Uruchom Spotify."))
        assertEquals("youtube", LocalCommands.parse("otwórz mi YouTube")!!.target)
        assertEquals("beat saber", LocalCommands.parse("Zagraj w Beat Saber")!!.target)
        assertEquals("stopklatke", LocalCommands.parse("włącz grę Stopklatkę")!!.target)
        assertEquals("przegladarke", LocalCommands.parse("odpal przeglądarkę")!!.target)
        assertEquals(Kind.LAUNCH, kind("Możesz otworzyć Netflix?"))
        assertEquals("spotify", LocalCommands.parse("urochom spotify")!!.target)
        assertEquals("youtube", LocalCommands.parse("i uruhom youtube")!!.target)
    }

    @Test
    fun volume() {
        assertEquals(Kind.VOLUME_UP, kind("Głośniej."))
        assertEquals(Kind.VOLUME_UP, kind("podgłośnij trochę"))
        assertEquals(Kind.VOLUME_DOWN, kind("ciszej proszę"))
        assertEquals(Kind.VOLUME_DOWN, kind("Ścisz muzykę"))
        assertEquals(LocalCommands.Command(Kind.VOLUME_SET, percent = 50), LocalCommands.parse("Ustaw głośność na 50%."))
        assertEquals(LocalCommands.Command(Kind.VOLUME_SET, percent = 30), LocalCommands.parse("głośność 30 procent"))
        assertEquals(LocalCommands.Command(Kind.VOLUME_SET, percent = 55), LocalCommands.parse("ustaw głośność na pięćdziesiąt pięć procent"))
        assertEquals(LocalCommands.Command(Kind.VOLUME_SET, percent = 100), LocalCommands.parse("dźwięk na maksimum"))
        assertEquals(Kind.MUTE, kind("Wycisz."))
        assertEquals(Kind.MUTE, kind("wyłącz dźwięk"))
        assertEquals(Kind.UNMUTE, kind("włącz dźwięk"))
    }

    @Test
    fun timeDateBattery() {
        assertEquals(Kind.TIME, kind("Która jest godzina?"))
        assertEquals(Kind.TIME, kind("jaka teraz godzina"))
        assertEquals(Kind.DATE, kind("Jaki dziś dzień?"))
        assertEquals(Kind.DATE, kind("którego dzisiaj mamy"))
        assertEquals(Kind.BATTERY, kind("Ile mam baterii?"))
        assertEquals(Kind.BATTERY, kind("jaki jest stan baterii"))
        assertEquals(Kind.BATTERY, kind("ilo membaterii"))
    }

    @Test
    fun control() {
        assertEquals(Kind.STOP, kind("Dziękuję."))
        assertEquals(Kind.STOP, kind("stop"))
        assertEquals(Kind.FORGET, kind("Zapomnij naszą rozmowę."))
        assertEquals(Kind.HOME, kind("wróć do menu"))
        assertEquals(Kind.HOME, kind("Zamknij aplikację."))
    }

    @Test
    fun questionsGoToAi() {
        assertNull(LocalCommands.parse("Jaka jutro będzie pogoda w Warszawie?"))
        assertNull(LocalCommands.parse("Opowiedz mi krótki dowcip."))
        assertNull(LocalCommands.parse("Ile to jest siedem razy osiem?"))
        assertNull(LocalCommands.parse("Dziękuję, a co słychać na świecie?"))
        assertNull(LocalCommands.parse("Za głośno tu jest, prawda?"))
        assertNull(LocalCommands.parse(""))
    }
}
