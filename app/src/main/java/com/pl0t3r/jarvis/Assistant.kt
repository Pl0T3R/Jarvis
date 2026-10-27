package com.pl0t3r.jarvis

import android.content.Context
import android.util.Log
import com.pl0t3r.jarvis.audio.AudioRing
import com.pl0t3r.jarvis.audio.Chime
import com.pl0t3r.jarvis.audio.Microphone
import com.pl0t3r.jarvis.audio.Speaker
import com.pl0t3r.jarvis.audio.VoiceFx
import com.pl0t3r.jarvis.brain.Brain
import com.pl0t3r.jarvis.brain.ClaudeBrain
import com.pl0t3r.jarvis.brain.Phrases
import com.pl0t3r.jarvis.brain.Text
import com.pl0t3r.jarvis.brain.WakeMatcher
import com.pl0t3r.jarvis.device.AndroidDevice
import com.pl0t3r.jarvis.models.ModelStore
import com.pl0t3r.jarvis.speech.Segmenter
import com.pl0t3r.jarvis.speech.Synthesizer
import com.pl0t3r.jarvis.speech.Transcriber
import com.pl0t3r.jarvis.speech.WakeWord
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The listening loop (runs on JarvisService's worker thread):
 *
 *   microphone -> "JARVIS"? -> the phrase -> Whisper -> Brain -> Piper -> voice effect -> speakers
 *
 * Accurate mode: silero VAD cuts every phrase, a fast Whisper (base) checks its start for "JARVIS".
 * Economy mode: a tiny keyword spotter listens for "JARVIS", then the phrase is cut and transcribed.
 */
class Assistant(private val context: Context, private val settings: Settings) {
    companion object {
        private const val TAG = "Jarvis"
        private const val RATE = Microphone.RATE
        private const val HEAD_SECONDS = 1.8f
        private val HALLUCINATIONS = listOf("amara", "napisy", "subtitles", "dziekuje za uwage", "dziekuje za obejrzenie")
        /** Nothing is heard this long after JARVIS stops talking (the room's echo of its own voice). */
        private const val QUIET_AFTER_SPEAKING_MS = 1200L
        /** A transcript this similar to what JARVIS said in the last 10 s is its own voice, not the user. */
        private const val ECHO_SIMILARITY = 0.7
        private const val ECHO_WINDOW_MS = 10_000L

        /** The one assistant allowed to listen and speak; a new one takes over from an old one. */
        private val current = AtomicReference<Assistant?>()
        private val ids = AtomicInteger()

        /** True when [heard] is (most likely) JARVIS's own [spoken] sentence picked up by the microphone. */
        internal fun isOwnEcho(heard: String, spoken: String): Boolean {
            val h = Text.normalize(heard)
            val s = Text.normalize(spoken)
            // short phrases ("jarvis", "tak") are left alone: the user may well say them right after JARVIS
            if (h.length < 10 || s.isEmpty()) return false
            if (h.length >= 15 && h in s) return true
            return Text.similarity(h, s) >= ECHO_SIMILARITY
        }
    }

    private val id = ids.incrementAndGet()
    private val finished = CountDownLatch(1)
    private var said = 0
    private var lastSpoken = ""
    private var lastSpokenAt = 0L
    private var quietUntil = 0L

    private val store = ModelStore(context)
    private val mic = Microphone(context.getSystemService(android.media.AudioManager::class.java))
    private val speaker = Speaker(context)
    private val device = AndroidDevice(context)
    private val brain = Brain(device, ClaudeBrain({ settings.chatConfig() }, device, log = { Log.i(TAG, it) }))

    @Volatile private var running = true
    @Volatile private var trimRequested = false
    @Volatile private var pendingSay: String? = null

    private var segmenter: Segmenter? = null
    private var wake: WakeWord? = null
    private var gate: Transcriber? = null
    private var commands: Transcriber? = null
    private var voice: Synthesizer? = null
    private val ring = AudioRing(2.5f)
    private var startedAt = 0L

    fun stop() {
        running = false
        speaker.stop()
    }

    /** Waits until [run] has returned; false if it is still running after [ms]. */
    fun awaitEnd(ms: Long): Boolean = finished.await(ms, TimeUnit.MILLISECONDS)

    private val active: Boolean get() = running && current.get() === this

    /** Low memory: let go of the big Whisper model until it's needed again. */
    fun trim() { trimRequested = true }

    fun run() {
        startedAt = System.currentTimeMillis()
        // never two assistants at once (each would answer, so every reply would be said twice)
        val previous = current.getAndSet(this)
        if (previous != null && previous !== this) {
            Log.w(TAG, "assistant $id: stopping assistant ${previous.id} that was still running")
            previous.stop()
            if (!previous.awaitEnd(5000)) Log.w(TAG, "assistant ${previous.id} did not stop in 5 s; it stays muted")
        }
        Log.i(TAG, "assistant $id started")
        try {
            if (store.missing(settings).isNotEmpty()) {
                Status.set(Status.State.ERROR, "Brak modeli mowy. Otwórz JARVIS i pobierz je.")
                return
            }
            Status.set(Status.State.STARTING, "Wczytuję modele…")
            load()
            say(Phrases.ready())
            loop()
        } catch (e: Throwable) {
            Log.e(TAG, "assistant crashed", e)
            Status.set(Status.State.ERROR, "Błąd: ${e.message}")
        } finally {
            mic.stop()
            release()
            current.compareAndSet(this, null)
            Log.i(TAG, "assistant $id ended")
            finished.countDown()
        }
    }

    private fun load() {
        segmenter = Segmenter(File(store.dir(ModelStore.VAD), "silero_vad.onnx"))
        voice = Synthesizer(store.dir(ModelStore.TTS))
        if (settings.wakeMode == Settings.WakeMode.ACCURATE) {
            gate = Transcriber(store.dir(ModelStore.WHISPER_BASE), "base", threads = 2)
        } else {
            wake = WakeWord(store.dir(ModelStore.KWS), File(context.filesDir, "keywords.txt"), settings.sensitivity)
        }
        commandModel()
    }

    private fun commandModel(): Transcriber {
        commands?.let { return it }
        val size = settings.whisper
        val g = gate
        val t = if (g != null && g.size == size) g else Transcriber(store.dir(ModelStore.whisperPack(size)), size, threads = 4)
        commands = t
        return t
    }

    private fun release() {
        segmenter?.release(); segmenter = null
        wake?.release(); wake = null
        if (commands !== gate) commands?.release()
        commands = null
        gate?.release(); gate = null
        voice?.release(); voice = null
    }

    private fun loop() {
        if (!startMic()) return
        Status.set(Status.State.IDLE, "Słucham. Powiedz „JARVIS”.")
        var blockedShown = false
        var blockedSince = 0L
        while (active) {
            if (trimRequested) {
                trimRequested = false
                if (commands !== gate) { commands?.release(); commands = null; Log.i(TAG, "released command model (low memory)") }
            }
            pendingSay?.let { pendingSay = null; say(it) }
            val chunk = mic.read()
            if (chunk == null) {
                mic.stop()
                Thread.sleep(500)
                if (!startMic()) return
                continue
            }
            if (mic.blocked) {
                val now = System.currentTimeMillis()
                if (!blockedShown) {
                    blockedShown = true
                    blockedSince = now
                    Status.set(Status.State.MIC_BLOCKED, "Mikrofon zajęty przez inną aplikację albo zablokowany.")
                    // right after a restart in the background Android may withhold the mic: bring the panel up once
                    if (now - startedAt < 15_000) Status.requestForeground()
                } else if (now - blockedSince > 10_000) {
                    // try again with a fresh recorder (after the panel came up, or the other app let go)
                    blockedSince = now
                    mic.stop()
                    if (!startMic()) return
                }
                continue
            } else if (blockedShown) {
                blockedShown = false
                Status.set(Status.State.IDLE, "Słucham. Powiedz „JARVIS”.")
            }
            ring.add(chunk)
            if (settings.wakeMode == Settings.WakeMode.ACCURATE) accurate(chunk) else economy(chunk)
        }
    }

    private fun accurate(chunk: FloatArray) {
        val seg = segmenter ?: return
        for (phrase in seg.accept(chunk)) {
            if (phrase.size < RATE * 0.3f) continue
            if (System.currentTimeMillis() < quietUntil) continue
            val head = if (phrase.size > RATE * HEAD_SECONDS) phrase.copyOf((RATE * HEAD_SECONDS).toInt()) else phrase
            val headText = gate?.transcribe(head) ?: continue
            if (heardMyself(headText)) continue
            val hit = WakeMatcher.find(headText, threshold(settings.sensitivity)) ?: continue
            Log.i(TAG, "wake: \"$headText\" (${"%.2f".format(hit.score)})")
            chime()
            val full = if (head.size == phrase.size && commandModel() === gate) headText else transcribe(phrase)
            // the bigger model sometimes drops the name ("Jaka jutro będzie pogoda?"): then all of it is the request
            val command = WakeMatcher.find(full, 0.6)?.command ?: full
            respond(full, command)
        }
    }

    private fun economy(chunk: FloatArray) {
        val ww = wake ?: return
        if (!ww.accept(chunk)) return
        if (System.currentTimeMillis() < quietUntil) return
        Log.i(TAG, "keyword spotter: JARVIS")
        chime()
        val seg = segmenter ?: return
        seg.reset()
        // the phrase began before the spotter fired: start from the last 1.5 s
        val phrase = listen(seg, ring.last((RATE * 1.5f).toInt()), waitForSpeechMs = 4000) ?: return
        val text = transcribe(phrase)
        val norm = Text.normalize(text)
        if (norm.isEmpty() || HALLUCINATIONS.any { it in norm } || heardMyself(text)) return
        respond(text, WakeMatcher.find(text, 0.6)?.command ?: text)
    }

    /** JARVIS's own last sentence came back through the microphone: ignore it. */
    private fun heardMyself(text: String): Boolean {
        if (System.currentTimeMillis() - lastSpokenAt > ECHO_WINDOW_MS) return false
        if (!isOwnEcho(text, lastSpoken)) return false
        Log.i(TAG, "ignored my own voice: \"$text\"")
        return true
    }

    /** Sensitivity 0..1 -> how close to "jarvis" a heard word must be. */
    private fun threshold(sensitivity: Float) = 0.84 - 0.16 * sensitivity

    private fun transcribe(phrase: FloatArray): String {
        Status.set(Status.State.THINKING, "Rozpoznaję…")
        val t0 = System.currentTimeMillis()
        val text = commandModel().transcribe(phrase)
        Log.i(TAG, "whisper ${settings.whisper}: \"$text\" in ${System.currentTimeMillis() - t0} ms")
        return text
    }

    private fun respond(heard: String, command: String) {
        Status.heard(heard)
        if (command.isBlank()) {
            // just "JARVIS": answer and wait a moment for the actual request
            say(Phrases.yesSir())
            val seg = segmenter ?: return
            val phrase = listen(seg, FloatArray(0), waitForSpeechMs = 6000)
            if (phrase == null) { Status.set(Status.State.IDLE, "Słucham. Powiedz „JARVIS”."); return }
            val text = transcribe(phrase)
            if (Text.normalize(text).isEmpty()) { say(Phrases.didNotHear()); return }
            Status.heard(text)
            reply(WakeMatcher.find(text, 0.6)?.command ?: text)
            return
        }
        reply(command)
    }

    private fun reply(command: String) {
        Status.set(Status.State.THINKING, "Myślę…")
        val t0 = System.currentTimeMillis()
        val answer = brain.handle(command)
        Log.i(TAG, "brain: \"$command\" -> \"$answer\" in ${System.currentTimeMillis() - t0} ms")
        say(answer)
    }

    /**
     * Reads the microphone until a phrase ends. Starts by feeding [preroll]. Gives up when nobody
     * speaks for [waitForSpeechMs], and cuts the phrase at 12 s.
     */
    private fun listen(seg: Segmenter, preroll: FloatArray, waitForSpeechMs: Long): FloatArray? {
        Status.set(Status.State.LISTENING, "Słucham polecenia…")
        seg.accept(preroll).firstOrNull()?.let { return it }
        val start = System.currentTimeMillis()
        var heardSpeech = seg.speaking
        while (active) {
            val chunk = mic.read() ?: return null
            ring.add(chunk)
            seg.accept(chunk).firstOrNull()?.let { return it }
            if (seg.speaking) heardSpeech = true
            val elapsed = System.currentTimeMillis() - start
            if (!heardSpeech && elapsed > waitForSpeechMs) return null
            if (elapsed > 12_000) return seg.flush().firstOrNull()
        }
        return null
    }

    private fun chime() {
        // played on its own thread so the microphone keeps listening; the echo canceller removes it
        Thread { speaker.play(Chime.listening(), Chime.RATE) }.start()
    }

    private fun say(text: String) {
        if (text.isBlank()) return
        if (current.get() !== this) {
            Log.w(TAG, "assistant $id is not the current one: not saying \"$text\"")
            return
        }
        said++
        Log.i(TAG, "say#$said [assistant $id]: \"$text\"")
        Status.answered(text)
        Status.set(Status.State.SPEAKING, text)
        val v = voice ?: return
        val raw = v.speak(text)
        val audio = VoiceFx.process(raw, v.sampleRate, settings.voiceFx)
        // half duplex: don't listen to ourselves
        mic.stop()
        speaker.play(audio, v.sampleRate)
        lastSpoken = text
        lastSpokenAt = System.currentTimeMillis()
        quietUntil = lastSpokenAt + QUIET_AFTER_SPEAKING_MS
        if (active) startMic()
        segmenter?.reset()
        wake?.reset()
        ring.clear()
        Status.set(Status.State.IDLE, "Słucham. Powiedz „JARVIS”.")
    }

    private fun startMic(): Boolean {
        repeat(10) {
            if (!active) return false
            if (mic.start()) return true
            Thread.sleep(1000)
        }
        Status.set(Status.State.ERROR, "Nie mogę włączyć mikrofonu.")
        return false
    }

    /** Test button in the panel: spoken by the worker thread at the next chunk. */
    fun speakTest(text: String) { pendingSay = text }
}
