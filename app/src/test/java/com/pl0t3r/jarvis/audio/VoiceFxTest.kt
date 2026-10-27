package com.pl0t3r.jarvis.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VoiceFxTest {
    private fun tone(seconds: Float, rate: Int) =
        FloatArray((seconds * rate).toInt()) { 0.5f * sin(2 * PI * 220 * it / rate).toFloat() }

    @Test
    fun staysInRangeAndKeepsLength() {
        val rate = 22050
        val x = tone(1f, rate)
        for (amount in listOf(0f, 0.5f, 1f)) {
            val y = VoiceFx.process(x, rate, amount)
            assertTrue(y.all { it.isFinite() && abs(it) <= 0.92f })
            assertEquals(x.size, y.size)
        }
    }

    /**
     * No echo, chorus or reverb: white noise has no correlation with itself at a delay, so after the
     * effect it must still have none between 8 and 60 ms (the old chorus and reverb lived there and
     * made the voice sound metallic).
     */
    @Test
    fun addsNoEcho() {
        val rate = 22050
        val rng = java.util.Random(1)
        val x = FloatArray(rate) { (rng.nextGaussian() * 0.2).toFloat() }
        for (amount in listOf(0.5f, 1f)) {
            val y = VoiceFx.process(x, rate, amount)
            var energy = 0.0
            for (v in y) energy += v * v
            for (lagMs in 8..60) {
                val lag = lagMs * rate / 1000
                var c = 0.0
                for (i in lag until y.size) c += y[i] * y[i - lag]
                assertTrue("echo at $lagMs ms: ${c / energy}", abs(c / energy) < 0.1)
            }
        }
    }

    @Test
    fun chimesAreShortAndQuiet() {
        for (c in listOf(Chime.listening(), Chime.error())) {
            assertTrue(c.size < Chime.RATE / 2)
            assertTrue(c.all { abs(it) <= 0.5f })
        }
    }

    /**
     * ./gradlew test -PjarvisSamples=<dir>: renders <dir>/replies/X.wav to <dir>/jarvis/X_fx50.wav and
     * X_fx100.wav, at 48 kHz like the headset plays them.
     */
    @Test
    fun renderSamples() {
        val dir = System.getProperty("jarvis.samples")
        assumeTrue("set -PjarvisSamples to render voice samples", dir != null)
        val out = File(dir, "jarvis").apply { mkdirs() }
        for (f in File(dir, "replies").listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }) {
            val (samples, rate) = Wav.read(f)
            for (amount in listOf(0.5f, 1f)) {
                val y = Resampler.resample(VoiceFx.process(samples, rate, amount), rate, Speaker.OUTPUT_RATE)
                Wav.write(File(out, "${f.nameWithoutExtension}_fx${(amount * 100).toInt()}.wav"), y, Speaker.OUTPUT_RATE)
            }
        }
    }
}

/** Minimal mono 16-bit PCM WAV reading and writing for the tests. */
object Wav {
    fun read(f: File): Pair<FloatArray, Int> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 0
        var bits = 16
        var channels = 1
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) })
            val size = b.getInt(pos + 4)
            if (id == "fmt ") {
                channels = b.getShort(pos + 10).toInt()
                rate = b.getInt(pos + 12)
                bits = b.getShort(pos + 22).toInt()
            } else if (id == "data") {
                require(bits == 16 && channels == 1) { "expected mono 16-bit wav" }
                val n = size / 2
                return FloatArray(n) { b.getShort(pos + 8 + it * 2) / 32768f } to rate
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in $f")
    }

    fun write(f: File, x: FloatArray, rate: Int) {
        val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(x.size * 2)
        RandomAccessFile(f, "rw").use { it.setLength(0); it.write(h.array()); it.write(data.array()) }
    }
}
