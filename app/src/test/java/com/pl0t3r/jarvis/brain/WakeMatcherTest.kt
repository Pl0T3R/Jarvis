package com.pl0t3r.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WakeMatcherTest {

    // How Whisper actually wrote "Dżarwis, ..." in tools/roundtrip.py (Polish TTS, base and small).
    @Test
    fun findsGarbledName() {
        val heard = listOf(
            "Jarvis odfuszył udą.",
            "żarwis ustaw woślność na 50%.",
            "Zdziarwis odfuszył ud",
            "Dziary wys urochom spokif.",
            "żar wys o ruchom spotyju.",
            "Jarwisk, która jest godzina.",
            "ziarwisk, która jest godzina.",
            "Zjarwis, która jest godzina.",
            "W żarwis włącz z to klatką",
            "I żarwis włącz z to klatką.",
            "Dżarvis, opowiedz mi krótki dowcip.",
            "z jarwis opowiec mi krótki dowcip.",
            "Zdarwis ustaw błośność na 50%.",
            "Zdżarwiz opowiecmi krótki dowciów.",
            "Hej Jarvis, która godzina?",
            "JARVIS",
        )
        for (h in heard) assertNotNull("should hear JARVIS in: $h", WakeMatcher.find(h))
    }

    @Test
    fun returnsWhatFollows() {
        assertEquals("uruchom spotify", WakeMatcher.find("Jarvis, uruchom Spotify.")!!.command)
        assertEquals("ktora jest godzina", WakeMatcher.find("Dżarwis, która jest godzina?")!!.command)
        assertEquals("", WakeMatcher.find("Jarvis!")!!.command)
        assertEquals("otworz youtube", WakeMatcher.find("Hej, Jarvis, otwórz YouTube")!!.command)
    }

    @Test
    fun ignoresOrdinarySpeech() {
        val ordinary = listOf(
            "Jarek wysłał mi wczoraj wiadomość.",
            "To jest zwykła rozmowa o pogodzie i o szkole.",
            "Darek, podaj mi proszę pilota od telewizora.",
            "Zdaję się, że zaróżar w",
            "Zadzwonię do serwisu jutro.",
            "Darwin napisał o ewolucji.",
            "Jarosław przyjdzie wieczorem.",
            "Czarny kot przebiegł przez ulicę.",
            "Harry Potter i kamień filozoficzny.",
            "Javier gra w piłkę.",
            "Garwolin to miasto na Mazowszu.",
            "Zawsze mówię prawdę.",
            "Jak się masz?",
            "Serwis rowerowy jest zamknięty.",
            "",
        )
        for (o in ordinary) assertNull("false alarm on: $o", WakeMatcher.find(o))
    }

    @Test
    fun nameMustBeNearTheStart() {
        assertNull(WakeMatcher.find("Wczoraj oglądałem film o robocie, który nazywał się Jarvis."))
    }
}
