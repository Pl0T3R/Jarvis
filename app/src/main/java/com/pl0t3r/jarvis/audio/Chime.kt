package com.pl0t3r.jarvis.audio

import kotlin.math.PI
import kotlin.math.sin

/** Short synthesized UI sounds, so no audio files ship with the app. */
object Chime {
    const val RATE = 22050

    /** Two soft rising notes: "I'm listening". */
    fun listening(): FloatArray = notes(listOf(1046.5f to 0.07f, 1568f to 0.11f), 0.35f)

    /** Two falling notes: "something went wrong". */
    fun error(): FloatArray = notes(listOf(440f to 0.09f, 330f to 0.14f), 0.3f)

    private fun notes(seq: List<Pair<Float, Float>>, gain: Float): FloatArray {
        val total = seq.sumOf { (it.second * RATE).toInt() }
        val out = FloatArray(total)
        var offset = 0
        for ((freq, seconds) in seq) {
            val n = (seconds * RATE).toInt()
            for (i in 0 until n) {
                val t = i.toFloat() / RATE
                val env = VoiceFx.envelope(t, 0.004f, seconds / 3f)
                // a sine with a little octave above it sounds like a clean UI "glass" tone
                val s = sin(2 * PI * freq * t).toFloat() + 0.25f * sin(4 * PI * freq * t).toFloat()
                out[offset + i] = gain * env * s / 1.25f
            }
            offset += n
        }
        VoiceFx.fadeEdges(out, RATE, 3f)
        return out
    }
}
