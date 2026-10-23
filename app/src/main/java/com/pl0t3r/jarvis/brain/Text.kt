package com.pl0t3r.jarvis.brain

import java.util.Locale

/** Text helpers for matching what Whisper heard against words we expect. */
object Text {
    private val plain = mapOf(
        'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
        'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
    )
    private val nonWord = Regex("[^a-z0-9% ]")
    private val spaces = Regex("\\s+")

    /** Lowercase, no Polish letters, no punctuation, single spaces: "Uruchom Spotify!" -> "uruchom spotify". */
    fun normalize(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
        val sb = StringBuilder(lower.length)
        for (c in lower) sb.append(plain[c] ?: c)
        return sb.toString().replace(nonWord, " ").replace(spaces, " ").trim()
    }

    // Sound-alike folding (applied to normalized text, in order), so that Polish spellings of English
    // names land near the original: "dziarwis" ~ "jarvis", "spotifaj" ~ "spotify", "jutub" ~ "youtube".
    private val folds = listOf(
        "dzi" to "j", "dz" to "j", "zi" to "j", "zj" to "j", "rz" to "z", "sz" to "s", "cz" to "c", "ch" to "h",
        "ph" to "f", "ck" to "k", "ou" to "u", "oo" to "u", "ee" to "i", "ea" to "i", "aj" to "i",
        "ai" to "i", "ay" to "i", "w" to "v", "y" to "i", "x" to "ks", "q" to "k", "th" to "t",
    )

    /** Normalized text without spaces and with sound-alike letters merged. */
    fun fold(normalized: String): String {
        var t = normalized.replace(" ", "")
        for ((from, to) in folds) t = t.replace(from, to)
        // youtube -> iutube -> iutub: a final silent "e" after a consonant is common in English names
        if (t.length > 4 && t.endsWith("e") && t[t.length - 2] !in "aeiou") t = t.dropLast(1)
        val sb = StringBuilder(t.length)
        for (c in t) if (sb.isEmpty() || sb.last() != c) sb.append(c)
        return sb.toString()
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 1.0 = same, 0.0 = nothing in common (edit distance relative to the longer string). */
    fun similarity(a: String, b: String): Double {
        val n = maxOf(a.length, b.length)
        return if (n == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / n
    }
}
