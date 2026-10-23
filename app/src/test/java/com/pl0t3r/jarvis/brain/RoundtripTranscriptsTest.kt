package com.pl0t3r.jarvis.brain

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Reads what Whisper heard in tools/roundtrip.py (synthetic Polish speech with noise) and runs it
 * through the same steps as Assistant.kt: the wake check, then the command. Run with
 *   ./gradlew test -PjarvisTranscripts=<out>/transcripts.tsv
 * Writes <out>/understanding.txt.
 */
class RoundtripTranscriptsTest {
    private val expectedApp = mapOf("spotify" to "Spotify", "youtube" to "YouTube", "beatsaber" to "Beat Saber", "stopklatka" to "Stopklatka")

    @Test
    fun understoodDespiteMishearing() {
        val path = System.getProperty("jarvis.transcripts")
        assumeTrue("set -PjarvisTranscripts to check round-trip transcripts", path != null)
        val rows = File(path!!).readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        val stats = HashMap<String, IntArray>() // mode -> [cases, woke, understood]
        val report = StringBuilder()
        for (r in rows) {
            val id = r[0]
            val expected = r[1]
            val wakeText = r.getOrElse(2) { "" }
            val commandText = r.getOrElse(3) { "" }
            val mode = id.substringBefore('-')
            val case = id.substringAfter('-').substringBefore('@')

            // accurate: the gate must find the name; economy: the spotter already fired if there is text
            val wake = if (mode == "economy") (if (wakeText.isBlank()) null else WakeMatcher.find(wakeText) ?: WakeMatcher.Hit(wakeText, 0.0))
            else WakeMatcher.find(wakeText)
            val s = stats.getOrPut(mode) { IntArray(3) }
            s[0]++
            if (expected == "NONE") {
                if (wake != null) s[1]++ // a false alarm
                report.append("%-24s %s  \"%s\"\n".format(id, if (wake == null) "quiet" else "FALSE ALARM", wakeText))
                continue
            }
            if (wake != null) s[1]++
            val command = WakeMatcher.find(commandText, 0.6)?.command ?: commandText
            val cmd = if (wake == null) null else LocalCommands.parse(command)
            val kind = if (wake == null) "-" else cmd?.kind?.name ?: "AI"
            val appOk = cmd?.kind != LocalCommands.Kind.LAUNCH ||
                AppMatcher.best(cmd.target, AppMatcherTest.APPS)?.app?.label == expectedApp[case]
            val ok = wake != null && kind == expected && appOk
            if (ok) s[2]++
            report.append("%-24s wake %-3s %-10s want %-10s %-4s \"%s\"\n".format(
                id, if (wake != null) "yes" else "NO", kind, expected, if (ok) "ok" else "MISS", commandText))
        }
        for ((mode, s) in stats.toSortedMap()) {
            report.append(if (mode == "negative") "$mode: ${s[1]}/${s[0]} false alarms\n"
            else "$mode: woke ${s[1]}/${s[0]}, understood ${s[2]}/${s[0]}\n")
        }
        println(report)
        File(File(path).parentFile, "understanding.txt").writeText(report.toString())
        val acc = stats["accurate"] ?: IntArray(3)
        assertTrue("accurate mode should wake on most phrases", acc[1] * 3 >= acc[0] * 2)
        assertTrue("no false alarms on phrases without the name", (stats["negative"]?.get(1) ?: 0) == 0)
    }
}
