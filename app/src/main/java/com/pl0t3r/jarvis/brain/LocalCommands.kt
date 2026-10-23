package com.pl0t3r.jarvis.brain

/**
 * Polish commands handled on the headset, offline and free. Anything else goes to Claude.
 * Works on Whisper's output, so it accepts digits ("50%") and words ("pięćdziesiąt procent").
 */
object LocalCommands {
    enum class Kind { LAUNCH, VOLUME_UP, VOLUME_DOWN, VOLUME_SET, MUTE, UNMUTE, TIME, DATE, BATTERY, STOP, FORGET, HOME }

    data class Command(val kind: Kind, val target: String = "", val percent: Int = 0)

    private fun rx(p: String) = Regex(p)

    private val stop = rx("^(stop|stoj|dziekuje|dzieki|anuluj|nic|niewazne|nie wazne|cisza|wystarczy|spokojnie|to wszystko|nevermind)( juz)?( to wszystko)?$")
    private val forget = rx("\\b(zapomnij|wyczysc|usun|skasuj)\\b.*\\b(rozmow|pamiec|histori|wszystko)|\\bnowa rozmowa\\b|\\bzacznijmy od nowa\\b")
    private val home = rx("\\b(wroc|idz|przejdz|wyjdz|zabierz mnie)\\b.*\\b(menu|domu|ekran\\w* glown\\w*|home)\\b|\\bekran glowny\\b|\\bzamknij\\b.*\\b(aplikacj\\w*|gre|program)\\b|\\bwyjdz z\\b")
    private val volumeWord = rx("\\b(glosnosc\\w*|glosnosci|dzwiek\\w*)\\b")
    private val percentNumber = rx("(\\d{1,3})\\s*(%|procent\\w*)?")
    private val mute = rx("^(wycisz|wycisz sie|wycisz dzwiek|wycisz wszystko|mute)$|\\bwylacz (dzwiek|glos)\\b|\\bbez dzwieku\\b")
    private val unmute = rx("\\b(wlacz|przywroc|odcisz)\\b.*\\b(dzwiek|glos)\\b|^odcisz$")
    private val up = rx("\\b(glosniej|podglosnij|zglosnij|zwieksz glosnosc|wiecej glosnosci|podkrec)\\b")
    private val down = rx("\\b(ciszej|scisz|przycisz|zmniejsz glosnosc|mniej glosnosci|przykrec)\\b")
    private val time = rx("\\b(ktora|jaka)\\b.*\\bgodzin\\w*|\\b(podaj|powiedz)\\b.*\\bgodzin\\w*|^ktora (jest|teraz)$")
    private val date = rx("\\b(jaki|ktory)\\b.*\\b(dzien|data)\\b|\\bjaka\\b.*\\bdata\\b|\\bktorego (dzis|dzisiaj|mamy|jest)\\b|\\bjaki mamy dzien\\b|\\bjaka jest data\\b")
    private val battery = rx("bateri|baterie|\\bnaladowan\\w*|\\bakumulator\\w*")
    private val launch = rx("\\b(uruchom|uruchomic|otworz|otworzyc|odpal|odpalic|wlacz|wlaczyc|zagraj w|zagrajmy w|graj w|wejdz w|wejdz do|przejdz do|pokaz|daj)\\b\\s*(.*)$")
    private val launchFillers = setOf("mi", "prosze", "aplikacje", "aplikacja", "apke", "apka", "gre", "gra", "program", "teraz", "szybko", "sir")
    private val launchVerbs = listOf("uruchom", "otworz", "odpal", "wlacz")
    private val leadFillers = setOf("i", "a", "no", "to", "prosze", "mozesz", "czy", "sir")

    /** [text] is what came after the wake word. Returns null when nothing local matches. */
    fun parse(text: String): Command? {
        val t = Text.normalize(text)
        if (t.isEmpty()) return null
        if (stop.containsMatchIn(t)) return Command(Kind.STOP)
        if (forget.containsMatchIn(t)) return Command(Kind.FORGET)
        if (home.containsMatchIn(t)) return Command(Kind.HOME)

        if (volumeWord.containsMatchIn(t)) {
            val n = percentNumber.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: PolishWords.parseNumber(t)
            if (n != null) return Command(Kind.VOLUME_SET, percent = n.coerceIn(0, 100))
        }
        if (mute.containsMatchIn(t)) return Command(Kind.MUTE)
        if (unmute.containsMatchIn(t)) return Command(Kind.UNMUTE)
        if (up.containsMatchIn(t)) return Command(Kind.VOLUME_UP)
        if (down.containsMatchIn(t)) return Command(Kind.VOLUME_DOWN)
        if (time.containsMatchIn(t)) return Command(Kind.TIME)
        if (date.containsMatchIn(t)) return Command(Kind.DATE)
        if (battery.containsMatchIn(t)) return Command(Kind.BATTERY)

        launch.find(t)?.let { m ->
            val target = m.groupValues[2].split(' ').dropWhile { it in launchFillers }.joinToString(" ")
            if (target.isNotBlank()) return Command(Kind.LAUNCH, target = target)
        }
        // a misheard verb at the start: "urochom spotify", "uruhom youtube"
        val words = t.split(' ').dropWhile { it in leadFillers }
        if (words.size >= 2 && launchVerbs.any { Text.similarity(words[0], it) >= 0.7 }) {
            val target = words.drop(1).dropWhile { it in launchFillers }.joinToString(" ")
            if (target.isNotBlank()) return Command(Kind.LAUNCH, target = target)
        }
        return null
    }
}
