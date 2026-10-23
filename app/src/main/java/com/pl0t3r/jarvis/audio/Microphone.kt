package com.pl0t3r.jarvis.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log

/**
 * The headset microphone at 16 kHz mono, read in 100 ms chunks.
 * Prefers the voice-call input, whose echo canceller removes the headset's own sound
 * (game audio, JARVIS's voice) from what we hear.
 */
class Microphone(private val audio: AudioManager) {
    companion object {
        const val RATE = 16000
        const val CHUNK = RATE / 10
        private const val TAG = "Jarvis"
    }

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private val pcm = ShortArray(CHUNK)
    private var chunks = 0
    private var silenced = false

    val running: Boolean get() = record != null

    /**
     * True while Android feeds us silence instead of the microphone: another app is recording in the
     * foreground, or the mic was withheld because the service started in the background.
     */
    val blocked: Boolean get() = silenced

    @SuppressLint("MissingPermission") // checked by the caller (JarvisService)
    fun start(): Boolean {
        if (record != null) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 4 s of room, so nothing is lost while Whisper checks a phrase
        val size = maxOf(min, RATE * 2 * 4)
        for (source in listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); continue }
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
                record = r
                chunks = 0
                silenced = false
                Log.i(TAG, "microphone on, source $source, echo canceller ${aec != null}")
                return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "microphone source $source failed", e)
            }
        }
        return false
    }

    fun stop() {
        aec?.release(); aec = null
        record?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        record = null
    }

    /** Blocks for about 100 ms. Null when the microphone failed (caller restarts it). */
    fun read(): FloatArray? {
        val r = record ?: return null
        val n = r.read(pcm, 0, CHUNK, AudioRecord.READ_BLOCKING)
        if (n <= 0) return null
        val out = FloatArray(n) { pcm[it] / 32768f }
        if (++chunks % 10 == 0) {
            silenced = audio.activeRecordingConfigurations.any { it.clientAudioSessionId == r.audioSessionId && it.isClientSilenced }
        }
        return out
    }
}

/** The last few seconds of audio, so speech just before a detection isn't lost. */
class AudioRing(seconds: Float, rate: Int = Microphone.RATE) {
    private val buf = FloatArray((seconds * rate).toInt())
    private var pos = 0
    private var filled = 0

    fun add(x: FloatArray) {
        for (v in x) {
            buf[pos] = v
            pos = (pos + 1) % buf.size
        }
        filled = minOf(buf.size, filled + x.size)
    }

    fun last(samples: Int): FloatArray {
        val n = minOf(samples, filled)
        val out = FloatArray(n)
        var p = (pos - n + buf.size) % buf.size
        for (i in 0 until n) {
            out[i] = buf[p]
            p = (p + 1) % buf.size
        }
        return out
    }

    fun clear() { pos = 0; filled = 0 }
}
