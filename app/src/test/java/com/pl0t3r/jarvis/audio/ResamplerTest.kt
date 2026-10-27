package com.pl0t3r.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    @Test
    fun voiceRateToHeadsetRate() {
        val from = 22050
        val to = 48000
        val x = FloatArray(from) { 0.5f * sin(2 * PI * 1000 * it / from).toFloat() }
        val y = Resampler.resample(x, from, to)
        assertEquals((x.size * to.toDouble() / from).toInt(), y.size)
        // compare with an ideal 1 kHz sine at 48 kHz, away from the edges
        var err = 0.0
        var ref = 0.0
        for (i in 1000 until y.size - 1000) {
            val ideal = 0.5 * sin(2 * PI * 1000 * i / to)
            err += (y[i] - ideal) * (y[i] - ideal)
            ref += ideal * ideal
        }
        val snrDb = 10 * kotlin.math.log10(ref / err)
        assertTrue("SNR $snrDb dB", snrDb > 50)
    }

    @Test
    fun keepsLoudnessAndSilence() {
        val y = Resampler.resample(FloatArray(2205), 22050, 48000)
        assertTrue(y.all { it == 0f })
        val dc = Resampler.resample(FloatArray(2205) { 0.3f }, 22050, 48000)
        for (i in 200 until dc.size - 200) assertTrue(abs(dc[i] - 0.3f) < 1e-3f)
        assertTrue(sqrt(1.0) == 1.0)
    }
}
