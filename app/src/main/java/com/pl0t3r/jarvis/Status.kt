package com.pl0t3r.jarvis

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** What JARVIS is doing, and the recent conversation, for the panel and the notification. */
object Status {
    enum class State(val label: String) {
        OFF("Wyłączony"),
        STARTING("Uruchamiam"),
        IDLE("Słucham"),
        LISTENING("Słucham polecenia"),
        THINKING("Myślę"),
        SPEAKING("Mówię"),
        MIC_BLOCKED("Mikrofon zajęty"),
        ERROR("Błąd"),
    }

    fun interface Listener { fun changed() }

    @Volatile var state: State = State.OFF; private set
    @Volatile var detail: String = ""; private set

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    /** Set by the service so the assistant can ask for the panel to be brought up (mic withheld in background). */
    @Volatile var foregroundRequest: (() -> Unit)? = null

    fun set(s: State, d: String) {
        state = s
        detail = d
        notifyListeners()
    }

    fun heard(text: String) = add("Ty: $text")
    fun answered(text: String) = add("JARVIS: $text")

    fun history(): List<String> = synchronized(lines) { lines.toList() }

    private fun add(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 40) lines.removeFirst()
        }
        notifyListeners()
    }

    fun requestForeground() { foregroundRequest?.let { main.post(it) } }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    private fun notifyListeners() {
        main.post { for (l in listeners) l.changed() }
    }
}
