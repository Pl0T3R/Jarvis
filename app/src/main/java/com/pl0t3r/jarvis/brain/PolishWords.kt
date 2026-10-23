package com.pl0t3r.jarvis.brain

import java.time.LocalDateTime

/** Numbers, times and dates written out in Polish, so the voice reads them naturally. */
object PolishWords {
    private val units = listOf("zero", "jeden", "dwa", "trzy", "cztery", "pięć", "sześć", "siedem", "osiem", "dziewięć")
    private val teens = listOf(
        "dziesięć", "jedenaście", "dwanaście", "trzynaście", "czternaście", "piętnaście",
        "szesnaście", "siedemnaście", "osiemnaście", "dziewiętnaście",
    )
    private val tens = listOf("", "", "dwadzieścia", "trzydzieści", "czterdzieści", "pięćdziesiąt", "sześćdziesiąt", "siedemdziesiąt", "osiemdziesiąt", "dziewięćdziesiąt")

    private val hours = listOf(
        "północ", "pierwsza", "druga", "trzecia", "czwarta", "piąta", "szósta", "siódma", "ósma", "dziewiąta",
        "dziesiąta", "jedenasta", "dwunasta", "trzynasta", "czternasta", "piętnasta", "szesnasta", "siedemnasta",
        "osiemnasta", "dziewiętnasta", "dwudziesta", "dwudziesta pierwsza", "dwudziesta druga", "dwudziesta trzecia",
    )
    private val dayOrdinals = listOf(
        "", "pierwszy", "drugi", "trzeci", "czwarty", "piąty", "szósty", "siódmy", "ósmy", "dziewiąty", "dziesiąty",
        "jedenasty", "dwunasty", "trzynasty", "czternasty", "piętnasty", "szesnasty", "siedemnasty", "osiemnasty",
        "dziewiętnasty", "dwudziesty", "dwudziesty pierwszy", "dwudziesty drugi", "dwudziesty trzeci",
        "dwudziesty czwarty", "dwudziesty piąty", "dwudziesty szósty", "dwudziesty siódmy", "dwudziesty ósmy",
        "dwudziesty dziewiąty", "trzydziesty", "trzydziesty pierwszy",
    )
    private val months = listOf(
        "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca", "lipca", "sierpnia",
        "września", "października", "listopada", "grudnia",
    )
    private val weekdays = listOf("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela")

    /** 0..100 in words: 76 -> "siedemdziesiąt sześć". */
    fun number(n: Int): String = when {
        n < 0 -> "minus " + number(-n)
        n < 10 -> units[n]
        n < 20 -> teens[n - 10]
        n < 100 -> if (n % 10 == 0) tens[n / 10] else tens[n / 10] + " " + units[n % 10]
        n == 100 -> "sto"
        else -> n.toString()
    }

    /** "Jest czternasta piętnaście." / "Jest równo dziewiąta." */
    fun time(t: LocalDateTime): String {
        val h = hours[t.hour]
        return when {
            t.minute == 0 && t.hour == 0 -> "Jest północ"
            t.minute == 0 -> "Jest równo $h"
            t.minute < 10 -> "Jest $h zero ${number(t.minute)}"
            else -> "Jest $h ${number(t.minute)}"
        }
    }

    /** "Dzisiaj jest piątek, dwudziesty trzeci października." */
    fun date(t: LocalDateTime): String =
        "Dzisiaj jest ${weekdays[t.dayOfWeek.value - 1]}, ${dayOrdinals[t.dayOfMonth]} ${months[t.monthValue - 1]}"

    private val wordValues: Map<String, Int> by lazy {
        val m = HashMap<String, Int>()
        units.forEachIndexed { i, w -> m[Text.normalize(w)] = i }
        teens.forEachIndexed { i, w -> m[Text.normalize(w)] = 10 + i }
        tens.forEachIndexed { i, w -> if (w.isNotEmpty()) m[Text.normalize(w)] = i * 10 }
        m["sto"] = 100
        m["pol"] = 50
        m["polowe"] = 50
        m["maksimum"] = 100
        m["maks"] = 100
        m["max"] = 100
        m
    }

    /** First number written in words in normalized text: "na piecdziesiat piec procent" -> 55. */
    fun parseNumber(normalized: String): Int? {
        val words = normalized.split(' ')
        for (i in words.indices) {
            val v = wordValues[words[i]] ?: continue
            val next = words.getOrNull(i + 1)?.let { wordValues[it] }
            return if (v >= 20 && v % 10 == 0 && v < 100 && next != null && next < 10) v + next else v
        }
        return null
    }
}
