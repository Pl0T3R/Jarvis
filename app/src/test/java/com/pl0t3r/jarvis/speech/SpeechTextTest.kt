package com.pl0t3r.jarvis.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun removesWhatWouldBeReadLiterally() {
        val s = SpeechText.clean("**Jutro** będzie _słonecznie_ 😀 – zobacz https://pogoda.pl #pogoda")
        assertFalse(s.contains("*") || s.contains("_") || s.contains("http") || s.contains("#") || s.contains("😀"))
        assertTrue(s.startsWith("Jutro będzie słonecznie"))
    }

    @Test
    fun symbolsBecomeWords() {
        assertEquals("Jest 15 stopni Celsjusza i 80 procent wilgotności.", SpeechText.clean("Jest 15°C i 80% wilgotności."))
        assertEquals("Wiatr 20 kilometrów na godzinę.", SpeechText.clean("Wiatr 20 km/h."))
    }

    @Test
    fun splitsSentencesButNotDecimals() {
        val parts = SpeechText.sentences("Dzień dobry, sir. Mam 3.5 minuty! Czy coś jeszcze?")
        assertEquals(listOf("Dzień dobry, sir.", "Mam 3.5 minuty!", "Czy coś jeszcze?"), parts)
        assertEquals(emptyList<String>(), SpeechText.sentences("  **  "))
    }

    @Test
    fun listsBecomeSentences() {
        val parts = SpeechText.sentences("Mam dwie rzeczy:\n- pierwszą.\n- drugą.")
        assertTrue(parts.none { it.startsWith("-") })
    }
}
