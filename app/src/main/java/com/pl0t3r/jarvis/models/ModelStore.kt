package com.pl0t3r.jarvis.models

import android.content.Context
import android.util.Log
import com.pl0t3r.jarvis.Settings
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speech models, downloaded once over Wi-Fi into the app's folder on the headset
 * (/sdcard/Android/data/com.pl0t3r.jarvis/files/models, so they can also be copied in with adb).
 */
class ModelStore(context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    /** One downloadable piece. [files] must all exist (and be at least [minBytes] for the first) when it's ready. */
    class Pack(
        val id: String,
        val title: String,
        val megabytes: Int,
        val dir: String,
        val files: List<String>,
        val minBytes: Long,
        val archiveUrl: String? = null,
        val fileUrls: List<String> = emptyList(),
    )

    companion object {
        private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
        private const val HF = "https://huggingface.co/csukuangfj"
        const val KWS_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        const val TTS_DIR = "vits-piper-pl_PL-darkman-medium"

        val KWS = Pack(
            "kws", "słowo „JARVIS”", 18, KWS_DIR,
            listOf("encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "tokens.txt"),
            4_000_000, archiveUrl = "$GH/kws-models/$KWS_DIR.tar.bz2",
        )
        val VAD = Pack(
            "vad", "wykrywanie mowy", 1, "vad", listOf("silero_vad.onnx"), 600_000,
            fileUrls = listOf("$GH/asr-models/silero_vad.onnx"),
        )
        val TTS = Pack(
            "tts", "głos JARVISA", 67, TTS_DIR,
            listOf("pl_PL-darkman-medium.onnx", "tokens.txt", "espeak-ng-data/phontab"),
            60_000_000, archiveUrl = "$GH/tts-models/$TTS_DIR.tar.bz2",
        )
        val WHISPER_BASE = whisper("base", 160, 25_000_000)
        val WHISPER_SMALL = whisper("small", 375, 100_000_000)

        private fun whisper(size: String, mb: Int, minBytes: Long): Pack {
            val names = listOf("$size-encoder.int8.onnx", "$size-decoder.int8.onnx", "$size-tokens.txt")
            return Pack(
                "whisper-$size", "rozpoznawanie mowy ($size)", mb, "whisper-$size", names, minBytes,
                fileUrls = names.map { "$HF/sherpa-onnx-whisper-$size/resolve/main/$it" },
            )
        }

        fun whisperPack(size: String) = if (size == "base") WHISPER_BASE else WHISPER_SMALL
        private const val TAG = "Jarvis"
    }

    /** The packs the current settings need. */
    fun required(settings: Settings): List<Pack> {
        val out = mutableListOf(VAD, TTS, whisperPack(settings.whisper))
        if (settings.wakeMode == Settings.WakeMode.ACCURATE) out += WHISPER_BASE
        else out += KWS
        return out.distinctBy { it.id }
    }

    fun dir(p: Pack) = File(root, p.dir)

    fun ready(p: Pack): Boolean {
        val d = dir(p)
        if (!p.files.all { File(d, it).exists() }) return false
        return File(d, p.files.first()).length() >= p.minBytes
    }

    fun missing(settings: Settings) = required(settings).filterNot { ready(it) }

    /** Downloads what's missing. [progress] gets (pack, bytes done, bytes total or -1). Throws IOException on failure. */
    fun download(packs: List<Pack>, progress: (Pack, Long, Long) -> Unit) {
        for (p in packs) {
            if (ready(p)) continue
            Log.i(TAG, "downloading ${p.id}")
            if (p.archiveUrl != null) {
                val tmp = File(root, "${p.id}.tar.bz2.part")
                fetch(p.archiveUrl, tmp) { done, total -> progress(p, done, total) }
                extract(tmp, root)
                tmp.delete()
            } else {
                val d = dir(p).apply { mkdirs() }
                var before = 0L
                for ((i, url) in p.fileUrls.withIndex()) {
                    val target = File(d, p.files[i])
                    if (target.exists() && target.length() > 0) continue
                    val tmp = File(d, p.files[i] + ".part")
                    fetch(url, tmp) { done, _ -> progress(p, before + done, p.megabytes * 1_000_000L) }
                    before += tmp.length()
                    if (!tmp.renameTo(target)) throw IOException("cannot move $tmp")
                }
            }
            if (!ready(p)) throw IOException("${p.id} incomplete after download")
        }
    }

    private fun fetch(url: String, out: File, progress: (Long, Long) -> Unit) {
        var conn = URL(url).openConnection() as HttpURLConnection
        var hops = 0
        // GitHub and Hugging Face redirect to their storage hosts
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(URL(url), next).openConnection() as HttpURLConnection
        }
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 1_000_000) { progress(done, total); lastReport = done }
                }
                progress(done, total)
            }
        }
        conn.disconnect()
        if (total > 0 && out.length() != total) throw IOException("download of $url cut short")
    }

    private fun extract(archive: File, into: File) {
        val base = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val target = File(into, e.name)
                if (!target.canonicalPath.startsWith(base)) throw IOException("bad path in archive: ${e.name}")
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it) }
            }
        }
    }
}
