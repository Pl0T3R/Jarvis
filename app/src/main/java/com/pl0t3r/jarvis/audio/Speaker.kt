package com.pl0t3r.jarvis.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/** Plays JARVIS's voice through the headset speakers, lowering other audio (music, games) meanwhile. */
class Speaker(context: Context) {
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
        stopRequested = false
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 4)
                .build()
        } catch (e: RuntimeException) {
            Log.w("Jarvis", "AudioTrack failed", e)
            return
        }
        audio.requestAudioFocus(focus)
        try {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            val endAt = System.currentTimeMillis() + samples.size * 1000L / rate + 500
            while (!stopRequested && track.playbackHeadPosition < samples.size && System.currentTimeMillis() < endAt) {
                Thread.sleep(20)
            }
            track.stop()
        } catch (e: RuntimeException) {
            Log.w("Jarvis", "playback failed", e)
        } finally {
            track.release()
            audio.abandonAudioFocusRequest(focus)
        }
    }
}
