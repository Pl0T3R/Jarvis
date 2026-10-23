package com.pl0t3r.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMatcherTest {
    companion object {
        val APPS = listOf(
            InstalledApp("Spotify", "com.spotify.music"),
            InstalledApp("YouTube", "com.google.android.apps.youtube.vr.oculus"),
            InstalledApp("Beat Saber", "com.beatgames.beatsaber"),
            InstalledApp("Browser", "com.oculus.browser"),
            InstalledApp("Stopklatka", "com.pl0t3r.stopklatka"),
            InstalledApp("Meta Quest TV", "com.oculus.tv"),
            InstalledApp("Netflix", "com.netflix.ninja"),
            InstalledApp("Supernatural", "com.within.supernatural"),
            InstalledApp("Horizon Worlds", "com.facebook.arvr.quillplayer"),
            InstalledApp("Files", "com.oculus.fileexplorer"),
            InstalledApp("SUPERHOT VR", "com.superhot.superhotvr"),
        )
    }

    private fun label(q: String) = AppMatcher.best(q, APPS)?.app?.label

    @Test
    fun exactAndInflected() {
        assertEquals("Spotify", label("spotify"))
        assertEquals("Beat Saber", label("beat saber"))
        assertEquals("Stopklatka", label("stopklatke"))
        assertEquals("Netflix", label("netflixa"))
        assertEquals("SUPERHOT VR", label("superhot"))
    }

    @Test
    fun polishSpellingsAndMishearing() {
        assertEquals("Spotify", label("spotifaj"))
        assertEquals("Spotify", label("spotifaja"))
        assertEquals("YouTube", label("jutub"))
        assertEquals("YouTube", label("jutuba"))
        assertEquals("Beat Saber", label("bit sejber"))
        assertEquals("Browser", label("przegladarke"))
        assertEquals("Stopklatka", label("stop klatke"))
        assertEquals("Netflix", label("netfliks"))
        assertEquals("Stopklatka", label("z to klatka"))
        assertEquals("Horizon Worlds", label("worlds"))
    }

    @Test
    fun extraWordsAfterTheName() {
        assertEquals("Spotify", label("spotify i pusc cos spokojnego"))
        assertEquals("YouTube", label("youtube prosze"))
    }

    @Test
    fun musicMeansTheMusicApp() {
        // Spotify's package is com.spotify.music, so "włącz muzykę" opens it
        assertEquals("Spotify", label("muzyke"))
    }

    @Test
    fun unknownStaysUnknown() {
        assertNull(label("lodowke"))
        assertNull(label("kalkulator naukowy"))
        assertNull(label("kalkulator"))
    }
}
