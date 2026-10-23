package com.pl0t3r.jarvis.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The "AI assistant" colour on top of the plain synthesized voice: a thinner low end, a faint
 * doubled voice (chorus), a little presence and a short, small-room reverb. [amount] 0..1 sets how
 * much of it you hear; 0 leaves the voice clean (only normalized).
 */
object VoiceFx {

    fun process(input: FloatArray, rate: Int, amount: Float): FloatArray {
        val a = amount.coerceIn(0f, 1f)
        if (input.isEmpty()) return input
        if (a <= 0f) return normalize(input.copyOf())

        val tail = (0.35f * rate).toInt()
        val n = input.size + tail
        val dry = FloatArray(n).also { System.arraycopy(input, 0, it, 0, input.size) }

        highPass(dry, rate, 110f)
        val voice = dry.copyOf()

        // chorus: one copy of the voice, 14 ms behind, slowly wobbling by +-3 ms
        val chorusMix = 0.32f * a
        val base = 0.014f * rate
        val depth = 0.003f * rate
        for (i in 0 until n) {
            val d = base + depth * sin(2.0 * PI * 0.9 * i / rate).toFloat()
            val pos = i - d
            if (pos >= 1) {
                val j = pos.toInt()
                val f = pos - j
                voice[i] += chorusMix * (dry[j] * (1 - f) + dry[minOf(j + 1, n - 1)] * f)
            }
        }

        // presence: a touch of the upper band on top
        val presence = voice.copyOf()
        highPass(presence, rate, 2800f)
        val presenceMix = 0.22f * a
        for (i in 0 until n) voice[i] += presenceMix * presence[i]

        // short reverb (Schroeder: parallel damped combs into series all-passes)
        val wet = reverb(voice, rate)
        val wetMix = 0.2f * a
        for (i in 0 until n) voice[i] += wetMix * wet[i]

        return normalize(voice)
    }

    private fun highPass(x: FloatArray, rate: Int, cutoff: Float) {
        val rc = 1f / (2f * PI.toFloat() * cutoff)
        val dt = 1f / rate
        val alpha = rc / (rc + dt)
        var prevIn = 0f
        var prevOut = 0f
        for (i in x.indices) {
            val v = x[i]
            prevOut = alpha * (prevOut + v - prevIn)
            prevIn = v
            x[i] = prevOut
        }
    }

    private fun reverb(x: FloatArray, rate: Int): FloatArray {
        val out = FloatArray(x.size)
        val combMs = floatArrayOf(23.3f, 26.9f, 29.9f, 33.1f)
        for (ms in combMs) {
            val d = (ms / 1000f * rate).toInt().coerceAtLeast(1)
            val buf = FloatArray(d)
            var idx = 0
            var lp = 0f
            for (i in x.indices) {
                val y = buf[idx]
                lp = y * 0.7f + lp * 0.3f          // damping: highs fade faster
                buf[idx] = x[i] + lp * 0.62f
                idx = (idx + 1) % d
                out[i] += y * 0.25f
            }
        }
        for (ms in floatArrayOf(5.0f, 1.7f)) {
            val d = (ms / 1000f * rate).toInt().coerceAtLeast(1)
            val buf = FloatArray(d)
            var idx = 0
            val g = 0.6f
            for (i in out.indices) {
                val b = buf[idx]
                val y = -g * out[i] + b
                buf[idx] = out[i] + g * y
                idx = (idx + 1) % d
                out[i] = y
            }
        }
        return out
    }

    /** Peak to about -1 dB with a soft limiter, so nothing clips on the headset speakers. */
    private fun normalize(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = maxOf(peak, abs(v))
        if (peak < 1e-6f) return x
        val g = 0.9f / peak
        for (i in x.indices) x[i] = tanh(1.1f * g * x[i]) / tanh(1.1f)
        return x
    }

    /** Fade the first and last few milliseconds to avoid clicks. */
    fun fadeEdges(x: FloatArray, rate: Int, ms: Float = 6f) {
        val n = minOf((ms / 1000f * rate).toInt(), x.size / 2)
        for (i in 0 until n) {
            val g = i.toFloat() / n
            x[i] *= g
            x[x.size - 1 - i] *= g
        }
    }

    internal fun envelope(t: Float, attack: Float, decay: Float): Float =
        if (t < attack) t / attack else exp(-(t - attack) / decay)
}
