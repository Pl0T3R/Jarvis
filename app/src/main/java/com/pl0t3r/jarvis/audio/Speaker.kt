package com.pl0t3r.jarvis.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Plays JARVIS's voice through the headset speakers, lowering other audio (music, games) meanwhile.
 * The sound is resampled to 48 kHz here (the headset's mixer rate) and streamed once through a
 * fresh AudioTrack, which is stopped and released when the last frame has played.
 */
class Speaker(context: Context) {
    companion object {
        const val OUTPUT_RATE = 48_000
        private const val TAG = "Jarvis"
        /** One sound at a time in the whole app, even from two Speaker objects. */
        private val playing = Any()
        private var plays = 0
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { }
        .build()

    @Volatile private var stopRequested = false

    fun stop() { stopRequested = true }

    /** Blocks until the sound has played (or [stop] was called). */
    fun play(samples: FloatArray, rate: Int) {
        if (samples.isEmpty()) return
        synchronized(playing) { playOnce(samples, rate, ++plays) }
    }

    private fun playOnce(samples: FloatArray, rate: Int, n: Int) {
        stopRequested = false
        val pcm = Resampler.resample(samples, rate, OUTPUT_RATE)
        VoiceFx.fadeEdges(pcm, OUTPUT_RATE, 4f)
        val minBytes = AudioTrack.getMinBufferSize(OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(OUTPUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBytes, OUTPUT_RATE / 5 * 4))   // at least 200 ms
                .build()
        } catch (e: RuntimeException) {
            Log.w(TAG, "AudioTrack failed", e)
            return
        }
        audio.requestAudioFocus(focus)
        val t0 = System.currentTimeMillis()
        Log.i(TAG, "speaker #$n: ${pcm.size * 1000L / OUTPUT_RATE} ms, session ${track.audioSessionId}")
        try {
            track.play()
            // stream in 50 ms pieces so stop() takes effect quickly
            val piece = OUTPUT_RATE / 20
            var offset = 0
            while (offset < pcm.size && !stopRequested) {
                val count = minOf(piece, pcm.size - offset)
                val written = track.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            // wait for the last written frame to leave the speaker, then stop (never loop or replay)
            val endAt = System.currentTimeMillis() + pcm.size * 1000L / OUTPUT_RATE + 1000
            while (!stopRequested && track.playbackHeadPosition < offset && System.currentTimeMillis() < endAt) {
                Thread.sleep(15)
            }
            if (stopRequested) track.pause() else track.stop()
            track.flush()
            Log.i(TAG, "speaker #$n: done after ${System.currentTimeMillis() - t0} ms${if (stopRequested) " (stopped)" else ""}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "playback failed", e)
        } finally {
            track.release()
            audio.abandonAudioFocusRequest(focus)
        }
    }
}
