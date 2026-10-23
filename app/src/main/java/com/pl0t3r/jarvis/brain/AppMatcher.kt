package com.pl0t3r.jarvis.brain

data class InstalledApp(val label: String, val packageName: String)

data class AppMatch(val app: InstalledApp, val score: Double)

/**
 * Matches a spoken app name against the apps installed on the headset. Tolerates Polish endings
 * ("Stopklatkę"), Polish spellings of English names ("spotifaj", "jutub") and Whisper's mistakes.
 */
object AppMatcher {
    /** A match must be at least this close (0..1). */
    const val THRESHOLD = 0.74

    // Extra spoken names. Keys are matched against the normalized label or package name.
    private val aliases = mapOf(
        "youtube" to listOf("jutub", "ju tub", "jutuba", "youtuba", "jutjub"),
        "spotify" to listOf("spotifaj", "spotifi", "spotyfaj", "spotifaja"),
        "browser" to listOf("przegladarka", "przegladarke", "przegladarki", "internet", "chrome"),
        "beat saber" to listOf("bit sejber", "bitsejber", "beat sabera", "bit sabera"),
        "netflix" to listOf("netfliks", "netfliksa"),
        "store" to listOf("sklep", "sklepik"),
        "settings" to listOf("ustawienia"),
        "files" to listOf("pliki"),
        "camera" to listOf("aparat", "kamera"),
        "gallery" to listOf("galeria", "zdjecia"),
        "music" to listOf("muzyka", "muzyke"),
        "stopklatka" to listOf("stop klatka", "stop klatke", "gra stopklatka"),
        "horizon worlds" to listOf("horyzont", "horizon", "swiaty"),
        "supernatural" to listOf("super natural"),
        "superhot" to listOf("super hot", "superhota"),
    )

    // Words that are part of how people ask, not of the name.
    private val fillers = setOf("mi", "prosze", "aplikacje", "aplikacja", "apke", "gre", "gra", "w", "z", "na", "ta", "te", "to", "sir", "teraz")

    fun best(spoken: String, apps: List<InstalledApp>): AppMatch? {
        val words = Text.normalize(spoken).split(' ').filter { it.isNotEmpty() && it !in fillers }
        if (words.isEmpty()) return null
        // the name can be followed by more words ("spotify i puść coś"): also try the first 1..3 words
        val queries = (1..minOf(3, words.size)).map { words.take(it).joinToString(" ") } + words.joinToString(" ")
        val foldedQueries = queries.distinct().map { Text.fold(it) }.filter { it.length >= 2 }

        var best: AppMatch? = null
        for (app in apps) {
            val score = names(app).maxOf { name -> foldedQueries.maxOf { q -> score(q, name) } }
            val b = best
            if (score >= THRESHOLD && (b == null || score > b.score + 1e-9 ||
                    (score > b.score - 1e-9 && app.label.length > b.app.label.length))) {
                best = AppMatch(app, score)
            }
        }
        return best
    }

    /** Spoken forms of an app: its label, the meaningful part of its package name and aliases. */
    private fun names(app: InstalledApp): List<String> {
        val label = Text.normalize(app.label)
        val pkg = app.packageName.lowercase().split('.')
            .filter { it.length >= 4 && it !in setOf("com", "org", "android", "oculus", "meta", "app", "apps", "game", "games", "vr", "quest", "horizon") }
        val out = mutableListOf(label)
        out += pkg
        for ((key, spoken) in aliases) {
            if (label == key || label.startsWith("$key ") || label.endsWith(" $key") || key in pkg || label.replace(" ", "") == key.replace(" ", "")) {
                out += spoken
                out += key
            }
        }
        return out.map { Text.fold(it) }.filter { it.length >= 3 }.distinct()
    }

    private fun score(query: String, name: String): Double {
        var s = Text.similarity(query, name)
        // part of a longer name: "klatka" in "stopklatka", "worlds" in "horizon worlds"
        if (query.length >= 5 && name.length > query.length && name.contains(query)) {
            s = maxOf(s, 0.6 + 0.4 * query.length / name.length)
        }
        // Polish endings: "stopklatke" vs "stopklatka", "spotifaja" vs "spotifaj"
        if (query.length > 4 && name.length > 4) {
            val n = minOf(query.length, name.length)
            if (n >= 5 && kotlin.math.abs(query.length - name.length) <= 2) {
                s = maxOf(s, Text.similarity(query.take(n - 1), name.take(n - 1)) - 0.05)
            }
        }
        return s
    }
}
