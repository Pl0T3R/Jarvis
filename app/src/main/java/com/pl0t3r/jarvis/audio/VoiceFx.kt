package com.pl0t3r.jarvis.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The "AI assistant" colour on top of the synthesized voice, made only of clean filters (no delays,
 * no chorus, no reverb: those sound metallic and like an echo on the headset's small speakers):
 * - rumble and boom removed (high-pass at 90 Hz, a small dip around 300 Hz),
 * - a crisp, present voice (a lift around 3.2 kHz and a little "air" above 8 kHz),
 * - softened "s" and "sz" sounds (a de-esser), so the lift never gets sharp,
 * - even loudness (a gentle 2:1 compressor) and a soft limiter against clipping.
 * [amount] 0..1 sets how strong the colour is; 0 leaves the voice clean (only normalized).
 */
object VoiceFx {

    fun process(input: FloatArray, rate: Int, amount: Float): FloatArray {
        val a = amount.coerceIn(0f, 1f)
        if (input.isEmpty()) return input
        val x = input.copyOf()
        // remove DC and sub-bass rumble always: they only cost loudness on small speakers
        Biquad.highPass(rate, 70f, 0.707f).run(x)
        if (a <= 0f) return normalize(x)

        Biquad.highPass(rate, 90f, 0.54f).run(x)
        Biquad.highPass(rate, 90f, 1.31f).run(x)
        Biquad.peak(rate, 300f, 1.0f, -2.5f * a).run(x)
        Biquad.peak(rate, 3200f, 0.9f, 3.5f * a).run(x)
        if (rate > 18_000) Biquad.highShelf(rate, 8000f, 2.5f * a).run(x)
        deEss(x, rate, 0.45f - 0.1f * a)
        compress(x, rate, ratio = 1f + 1.2f * a)
        return normalize(x)
    }

    /**
     * Lowers the band above 5.5 kHz when it gets loud compared to the whole voice (sibilants), so
     * "s", "ś", "sz" stay smooth. [limit] is the high-band share where reduction starts.
     */
    private fun deEss(x: FloatArray, rate: Int, limit: Float) {
        val high = x.copyOf()
        val hp = Biquad.highPass(rate, 5500f, 0.707f)
        hp.run(high)
        val attack = coeff(rate, 0.002f)
        val release = coeff(rate, 0.06f)
        var envH = 0f
        var envF = 0f
        for (i in x.indices) {
            val h = abs(high[i])
            val f = abs(x[i])
            envH = if (h > envH) h + (envH - h) * attack else h + (envH - h) * release
            envF = if (f > envF) f + (envF - f) * attack else f + (envF - f) * release
            val share = envH / (envF + 1e-6f)
            val g = if (share > limit) (limit / share).coerceAtLeast(0.4f) else 1f
            x[i] = (x[i] - high[i]) + high[i] * g
        }
    }

    /** RMS compressor above -20 dB of the peak, with make-up left to [normalize]. */
    private fun compress(x: FloatArray, rate: Int, ratio: Float) {
        var peak = 1e-6f
        for (v in x) peak = maxOf(peak, abs(v))
        val threshold = peak * 0.1f   // -20 dB below the loudest moment
        val attack = coeff(rate, 0.008f)
        val release = coeff(rate, 0.15f)
        var env = 0f
        for (i in x.indices) {
            val p = x[i] * x[i]
            env = if (p > env) p + (env - p) * attack else p + (env - p) * release
            val rms = sqrt(env)
            if (rms > threshold) x[i] *= (rms / threshold).pow(1f / ratio - 1f)
        }
    }

    private fun coeff(rate: Int, seconds: Float) = exp(-1f / (seconds * rate))

    /** Peak to about -0.8 dB with a soft limiter, so nothing clips on the headset speakers. */
    private fun normalize(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = maxOf(peak, abs(v))
        if (peak < 1e-6f) return x
        val g = 0.85f / peak   // ends at about -0.8 dBFS after the soft limiter
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

/** A second-order IIR filter (RBJ audio EQ cookbook), direct form I. */
class Biquad private constructor(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float,
) {
    fun run(x: FloatArray) {
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val v = x[i]
            val y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = v
            y2 = y1; y1 = y
            x[i] = y
        }
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

        fun highPass(rate: Int, f: Float, q: Float): Biquad {
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun peak(rate: Int, f: Float, q: Float, gainDb: Float): Biquad {
            val amp = 10.0.pow(gainDb / 40.0)
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make(1 + alpha * amp, -2 * c, 1 - alpha * amp, 1 + alpha / amp, -2 * c, 1 - alpha / amp)
        }

        fun highShelf(rate: Int, f: Float, gainDb: Float): Biquad {
            val amp = 10.0.pow(gainDb / 40.0)
            val w = 2 * PI * f / rate
            val c = cos(w)
            val alpha = sin(w) / 2 * sqrt(2.0)   // shelf slope 1
            val s = 2 * sqrt(amp) * alpha
            return make(
                amp * ((amp + 1) + (amp - 1) * c + s),
                -2 * amp * ((amp - 1) + (amp + 1) * c),
                amp * ((amp + 1) + (amp - 1) * c - s),
                (amp + 1) - (amp - 1) * c + s,
                2 * ((amp - 1) - (amp + 1) * c),
                (amp + 1) - (amp - 1) * c - s,
            )
        }
    }
}
