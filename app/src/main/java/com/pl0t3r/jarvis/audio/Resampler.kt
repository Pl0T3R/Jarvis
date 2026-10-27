package com.pl0t3r.jarvis.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Band-limited resampling (windowed sinc, Blackman window, [taps] zero crossings per side). Used to
 * bring Piper's 22 050 Hz voice to the headset's 48 kHz mixer ourselves, cleanly, instead of leaving
 * it to the system's fast resampler (which dulls and aliases speech).
 */
object Resampler {
    fun resample(input: FloatArray, from: Int, to: Int, taps: Int = 8): FloatArray {
        if (from == to || input.isEmpty()) return input.copyOf()
        val ratio = to.toDouble() / from
        val n = (input.size * ratio).toInt()
        val out = FloatArray(n)
        // when going down, lower the cutoff to the new Nyquist frequency
        val cutoff = minOf(1.0, ratio)
        val radius = taps / cutoff
        for (i in 0 until n) {
            val center = i / ratio
            val first = maxOf(0, floor(center - radius).toInt() + 1)
            val last = minOf(input.size - 1, floor(center + radius).toInt())
            var acc = 0.0
            var norm = 0.0
            for (j in first..last) {
                val x = (j - center) * cutoff
                val w = blackman((j - center) / radius)
                val k = sinc(x) * w
                acc += input[j] * k
                norm += k
            }
            out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return out
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    /** Blackman window over -1..1. */
    private fun blackman(t: Double): Double {
        if (t <= -1.0 || t >= 1.0) return 0.0
        val p = PI * (t + 1.0)
        return 0.42 - 0.5 * cos(p) + 0.08 * cos(2 * p)
    }
}
