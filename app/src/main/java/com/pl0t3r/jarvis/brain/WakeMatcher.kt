package com.pl0t3r.jarvis.brain

/**
 * Finds "JARVIS" at the start of a transcript, the way Whisper actually writes it after a Polish
 * speaker: "Jarvis", "Dżarwis", "żar wys", "Dziary wys", "Zdziarwis", "i żarwis"...
 * Returns what was said after it (possibly empty), or null when the transcript doesn't start with it.
 */
object WakeMatcher {
    private val targets = listOf(Text.fold("jarvis"))
    private val fillers = setOf("i", "w", "z", "a", "o", "hej", "hey", "ej", "ok", "okej", "no", "halo", "sluchaj", "prosze")

    /** How close a window must be to "jarvis" (0..1). */
    const val THRESHOLD = 0.76

    data class Hit(val command: String, val score: Double)

    fun find(transcript: String, threshold: Double = THRESHOLD): Hit? {
        val words = Text.normalize(transcript).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        var best: Hit? = null
        // the name must be among the first four words, and can be split by Whisper into up to three
        for (start in 0 until minOf(4, words.size)) {
            for (len in 1..3) {
                val end = start + len
                if (end > words.size) break
                val window = Text.fold(words.subList(start, end).joinToString(" "))
                if (window.length < 4) continue
                // a long single word can hide the name inside ("zdrowizarwiz"): look at its pieces
                val score = if (window.length <= 11) score(window)
                else if (len == 1) inside(window)
                else continue
                if (score >= threshold && (best == null || score > best.score)) {
                    best = Hit(words.drop(end).dropWhile { it in fillers }.joinToString(" "), score)
                }
            }
        }
        return best
    }

    private fun inside(word: String): Double {
        var s = 0.0
        for (n in 5..8) for (i in 0..word.length - n) s = maxOf(s, Text.similarity(word.substring(i, i + n), targets[0]) - 0.06)
        return s
    }

    private fun score(window: String): Double {
        var s = 0.0
        for (t in targets) {
            s = maxOf(s, Text.similarity(window, t))
            // a stray first sound ("zdziarwis", "wzarwis"): drop it, with a small penalty
            if (window.length > t.length) s = maxOf(s, Text.similarity(window.drop(1), t) - 0.04)
            // a stray last sound ("jarwisk", "zarwisi")
            if (window.length > t.length) s = maxOf(s, Text.similarity(window.dropLast(1), t) - 0.04)
        }
        return s
    }
}
