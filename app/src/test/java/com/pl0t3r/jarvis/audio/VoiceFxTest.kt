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
    fun staysInRangeAndAddsTail() {
        val rate = 22050
        val x = tone(1f, rate)
        for (amount in listOf(0f, 0.5f, 1f)) {
            val y = VoiceFx.process(x, rate, amount)
            assertTrue(y.all { it.isFinite() && abs(it) <= 1f })
            if (amount == 0f) assertEquals(x.size, y.size) else assertTrue(y.size > x.size)
        }
    }

    @Test
    fun chimesAreShortAndQuiet() {
        for (c in listOf(Chime.listening(), Chime.error())) {
            assertTrue(c.size < Chime.RATE / 2)
            assertTrue(c.all { abs(it) <= 0.5f })
        }
    }

    /** ./gradlew test -PjarvisSamples=<dir>: renders <dir>/replies/X.wav to <dir>/jarvis/X_fx50.wav and X_fx100.wav. */
    @Test
    fun renderSamples() {
        val dir = System.getProperty("jarvis.samples")
        assumeTrue("set -PjarvisSamples to render voice samples", dir != null)
        val out = File(dir, "jarvis").apply { mkdirs() }
        for (f in File(dir, "replies").listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }) {
            val (samples, rate) = Wav.read(f)
            for (amount in listOf(0.5f, 1f)) {
                val y = VoiceFx.process(samples, rate, amount)
                Wav.write(File(out, "${f.nameWithoutExtension}_fx${(amount * 100).toInt()}.wav"), y, rate)
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
