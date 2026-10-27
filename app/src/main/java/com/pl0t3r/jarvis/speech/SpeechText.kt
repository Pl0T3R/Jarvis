package com.pl0t3r.jarvis.speech

/**
 * Prepares text for the speech synthesizer: removes what would be read out literally (markdown,
 * emoji, links), turns symbols into words, and splits the text into sentences, so each one is
 * synthesized on its own with a natural pause after it. Numbers stay as digits: Piper's Polish
 * phonemizer (espeak-ng) reads them, with the right endings more often than a word list would.
 */
object SpeechText {
    private val url = Regex("https?://\\S+|www\\.\\S+")
    private val markup = Regex("[*_#`>|~]|\\[|\\]")
    private val listMarker = Regex("(?m)^\\s*(?:[-•]|\\d+[.)])\\s+")
    // emoji and pictographs, variation selectors, zero-width joiners
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")
    private val spaces = Regex("\\s+")
    private val symbols = listOf(
        Regex("(\\d)\\s*°\\s*C\\b") to "$1 stopni Celsjusza",
        Regex("(\\d)\\s*°") to "$1 stopni",
        Regex("(\\d)\\s*%") to "$1 procent",
        Regex("(\\d)\\s*km/h") to "$1 kilometrów na godzinę",
        Regex("\\s&\\s") to " i ",
        Regex("\\s\\+\\s") to " plus ",
        Regex("\\s=\\s") to " równa się ",
        Regex("\\s[-–—]\\s") to ", ",
    )
    // a sentence ends at . ! ? … followed by a space (or the end); numbers like 3.5 stay whole
    private val sentenceEnd = Regex("(?<=[.!?…])\\s+")

    fun clean(text: String): String {
        var s = text.replace(url, "").replace(listMarker, "").replace(emoji, "").replace(markup, "")
        for ((pattern, words) in symbols) s = s.replace(pattern, words)
        return s.replace(spaces, " ").trim()
    }

    /** Sentences of the cleaned text; very long ones are also split at ; and : so none runs on. */
    fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        for (sentence in clean(text).split(sentenceEnd)) {
            val t = sentence.trim()
            if (t.isEmpty()) continue
            if (t.length > 220) t.split(Regex("(?<=[;:])\\s+")).map { it.trim() }.filterTo(out) { it.isNotEmpty() }
            else out += t
        }
        return out
    }
}
