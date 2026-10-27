package com.pl0t3r.jarvis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantEchoTest {
    @Test
    fun ownSentenceIsRecognized() {
        assertTrue(Assistant.isOwnEcho("Jarvis gotowy, sir", "JARVIS gotowy, sir."))
        assertTrue(Assistant.isOwnEcho("Jest czternasta trzydzieści", "Jest czternasta trzydzieści, sir."))
        assertTrue(Assistant.isOwnEcho("wszystkie systemy działają prawidłowo", "Dzień dobry, sir. Wszystkie systemy działają prawidłowo."))
    }

    @Test
    fun userIsNotMistakenForEcho() {
        assertFalse(Assistant.isOwnEcho("Jarvis", "JARVIS gotowy, sir."))
        assertFalse(Assistant.isOwnEcho("Jarvis, uruchom Spotify", "JARVIS gotowy, sir."))
        assertFalse(Assistant.isOwnEcho("Jarvis, która godzina?", "Jest czternasta trzydzieści, sir."))
        assertFalse(Assistant.isOwnEcho("tak", "Tak, sir?"))
    }
}
