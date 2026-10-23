package com.pl0t3r.jarvis.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.pl0t3r.jarvis.audio.Microphone
import java.io.File
import kotlin.math.pow

/**
 * Always-on keyword spotter for "JARVIS" (economy mode). The model is English, so besides the real
 * spelling it listens for the shapes a Polish "Dżarwis" takes in it ("JARVIS IS", "SHARVIS"...).
 * Whisper confirms every hit afterwards.
 */
class WakeWord(modelDir: File, keywordsFile: File, sensitivity: Float) {
    companion object {
        val VARIANTS = listOf(
            "▁JA R VI S", "▁JA R VI S ▁IS", "▁SHA R VI S", "▁CHA R VI S", "▁JA R ▁VI S", "▁JA R VE S",
            "▁JA R ▁W IS", "▁CHA R ▁VI S", "▁SHA R ▁VI S", "▁JA R VI E", "▁JA R VI A",
        )

        /** Keyword file for the given sensitivity (0..1): higher boosts the keyword and lowers its threshold. */
        fun keywords(sensitivity: Float): String {
            val s = sensitivity.coerceIn(0f, 1f)
            val score = 5f + 15f * s
            val threshold = 0.01f * 0.1f.pow(s)
            return VARIANTS.joinToString("\n", postfix = "\n") { "$it :%.1f #%.4f @JARVIS".format(java.util.Locale.ROOT, score, threshold) }
        }
    }

    private val kws: KeywordSpotter
    private var stream: OnlineStream

    init {
        keywordsFile.writeText(keywords(sensitivity))
        val d = modelDir.path
        kws = KeywordSpotter(
            config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = Microphone.RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$d/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        decoder = "$d/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        joiner = "$d/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    ),
                    tokens = "$d/tokens.txt",
                    numThreads = 1,
                    modelType = "zipformer2",
                ),
                maxActivePaths = 4,
                keywordsFile = keywordsFile.path,
                numTrailingBlanks = 1,
            ),
        )
        stream = kws.createStream()
    }

    /** Feeds 100 ms of audio. True when "JARVIS" was heard. */
    fun accept(chunk: FloatArray): Boolean {
        stream.acceptWaveform(chunk, Microphone.RATE)
        var hit = false
        while (kws.isReady(stream)) {
            kws.decode(stream)
            if (kws.getResult(stream).keyword.isNotBlank()) {
                kws.reset(stream)
                hit = true
            }
        }
        return hit
    }

    fun reset() {
        stream.release()
        stream = kws.createStream()
    }

    fun release() {
        stream.release()
        kws.release()
    }
}

/** Silero VAD: turns the microphone stream into finished phrases (speech followed by 0.8 s of silence). */
class Segmenter(model: File) {
    private val window = 512
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.path,
                threshold = 0.5f,
                minSilenceDuration = 0.8f,
                minSpeechDuration = 0.2f,
                windowSize = window,
                maxSpeechDuration = 12f,
            ),
            sampleRate = Microphone.RATE,
            numThreads = 1,
        ),
    )
    private val pending = FloatArray(window)
    private var filled = 0

    /** Someone is talking right now. */
    val speaking: Boolean get() = vad.isSpeechDetected()

    /** Feeds audio, returns the phrases that ended in it. */
    fun accept(chunk: FloatArray): List<FloatArray> {
        val out = ArrayList<FloatArray>(1)
        var i = 0
        while (i < chunk.size) {
            val n = minOf(window - filled, chunk.size - i)
            System.arraycopy(chunk, i, pending, filled, n)
            filled += n
            i += n
            if (filled == window) {
                vad.acceptWaveform(pending.copyOf())
                filled = 0
                while (!vad.empty()) {
                    out += vad.front().samples
                    vad.pop()
                }
            }
        }
        return out
    }

    /** Ends the current phrase now (used when listening times out). */
    fun flush(): List<FloatArray> {
        vad.flush()
        val out = ArrayList<FloatArray>(1)
        while (!vad.empty()) {
            out += vad.front().samples
            vad.pop()
        }
        return out
    }

    fun reset() {
        vad.reset()
        filled = 0
    }

    fun release() = vad.release()
}

/** Whisper (multilingual, int8) transcribing Polish. */
class Transcriber(dir: File, val size: String, threads: Int) {
    private val rec = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = Microphone.RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "${dir.path}/$size-encoder.int8.onnx",
                    decoder = "${dir.path}/$size-decoder.int8.onnx",
                    language = "pl",
                    task = "transcribe",
                    tailPaddings = -1,
                ),
                tokens = "${dir.path}/$size-tokens.txt",
                numThreads = threads,
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    fun transcribe(samples: FloatArray): String {
        val s = rec.createStream()
        try {
            s.acceptWaveform(samples, Microphone.RATE)
            rec.decode(s)
            return rec.getResult(s).text.trim()
        } finally {
            s.release()
        }
    }

    fun release() = rec.release()
}

/** Piper, Polish male voice. */
class Synthesizer(dir: File) {
    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "${dir.path}/pl_PL-darkman-medium.onnx",
                    tokens = "${dir.path}/tokens.txt",
                    dataDir = "${dir.path}/espeak-ng-data",
                ),
                numThreads = 2,
            ),
            maxNumSentences = 1,
        ),
    )

    val sampleRate: Int get() = tts.sampleRate()

    /** [speed] above 1 talks faster. */
    fun speak(text: String, speed: Float = 1f): FloatArray = tts.generate(text, sid = 0, speed = speed).samples

    fun release() = tts.release()
}
