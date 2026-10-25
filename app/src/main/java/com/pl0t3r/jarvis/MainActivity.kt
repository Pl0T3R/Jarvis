package com.pl0t3r.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.pl0t3r.jarvis.brain.ClaudeBrain
import com.pl0t3r.jarvis.models.ModelStore
import java.io.IOException

/**
 * The JARVIS panel: status, model download, microphone permission, Claude API key, settings and
 * the recent conversation. Listening itself runs in [JarvisService], also with the panel closed.
 */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        const val EXTRA_API_KEY = "api_key"
        private const val ACCENT = 0xFF4FC3F7.toInt()
        private const val TEXT = 0xFFE6F1FF.toInt()
        private const val DIM = 0xFF8BA3B8.toInt()
        private const val BG = 0xFF0B1016.toInt()
    }

    private lateinit var settings: Settings
    private lateinit var store: ModelStore
    private val main = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var toggle: Button
    private lateinit var modelsView: TextView
    private lateinit var downloadButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var micButton: Button
    private lateinit var keyView: TextView
    private lateinit var historyView: TextView
    @Volatile private var downloading = false

    private val listener = Status.Listener { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = ModelStore(this)
        setContentView(build())
        handle(intent)
        if (!micGranted()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        else if (store.missing(settings).isNotEmpty()) download()
        else if (settings.listening) JarvisService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_API_KEY)?.let {
            settings.apiKey = it
            intent.removeExtra(EXTRA_API_KEY)
            refresh()
        }
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) {
            intent.removeExtra(EXTRA_AUTOSTART)
            if (micGranted() && store.missing(settings).isEmpty()) {
                JarvisService.start(this)
                // started from the foreground, so the microphone is allowed; now get out of the way
                main.postDelayed({ moveTaskToBack(true) }, 800)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Status.addListener(listener)
        refresh()
    }

    override fun onPause() {
        Status.removeListener(listener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (micGranted()) {
            if (store.missing(settings).isNotEmpty()) download() else if (settings.listening) JarvisService.start(this)
        }
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // --- UI -------------------------------------------------------------------------------------

    private fun build(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 48)
            setBackgroundColor(BG)
        }
        col.addView(text("J.A.R.V.I.S.", 34f, ACCENT, bold = true).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(text("Asystent głosowy. Powiedz „JARVIS, uruchom Spotify”.", 15f, DIM).apply { gravity = Gravity.CENTER_HORIZONTAL })

        statusView = text("", 18f, TEXT).also { col.addView(it, margin(top = 24)) }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = button("") {
            if (JarvisService.instance != null) {
                settings.listening = false
                JarvisService.stop(this)
            } else {
                settings.listening = true
                if (store.missing(settings).isNotEmpty()) download() else JarvisService.start(this)
            }
            main.postDelayed({ refresh() }, 300)
        }
        buttons.addView(toggle)
        buttons.addView(button("Test głosu") {
            JarvisService.instance?.speakTest("Dzień dobry, sir. Wszystkie systemy działają prawidłowo.")
        }, margin(left = 16))
        col.addView(buttons, margin(top = 12))

        micButton = button("Zezwól na mikrofon") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        col.addView(micButton, margin(top = 12))

        col.addView(header("Modele mowy"))
        modelsView = text("", 15f, TEXT).also { col.addView(it) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        col.addView(progress, fill(top = 8))
        downloadButton = button("Pobierz modele") { download() }
        col.addView(downloadButton, margin(top = 8))

        col.addView(header("Rozmowa z AI (Claude)"))
        keyView = text("", 15f, TEXT).also { col.addView(it) }
        val keyEdit = EditText(this).apply {
            hint = "Wklej klucz API z platform.claude.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(TEXT)
            setHintTextColor(DIM)
        }
        col.addView(keyEdit, fill(top = 8))
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyRow.addView(button("Zapisz klucz") {
            settings.apiKey = keyEdit.text.toString()
            keyEdit.setText("")
            refresh()
        })
        keyRow.addView(button("Usuń klucz") { settings.apiKey = ""; refresh() }, margin(left = 16))
        col.addView(keyRow, margin(top = 8))
        col.addView(radio("Model", ClaudeBrain.MODELS, settings.model) { settings.model = it })
        col.addView(CheckBox(this).apply {
            text = "Wyszukiwanie w internecie (pogoda, wiadomości)"
            setTextColor(TEXT)
            isChecked = settings.webSearch
            setOnCheckedChangeListener { _, v -> settings.webSearch = v }
        }, margin(top = 8))

        col.addView(header("Słuchanie"))
        col.addView(radio(
            "Wykrywanie słowa „JARVIS”",
            listOf(
                Settings.WakeMode.ACCURATE.name to "Dokładne (Whisper sprawdza każdą wypowiedź)",
                Settings.WakeMode.ECONOMY.name to "Oszczędne (mniej obciąża gogle, czasem nie usłyszy)",
            ),
            settings.wakeMode.name,
        ) { settings.wakeMode = Settings.WakeMode.valueOf(it); restartService() })
        col.addView(radio(
            "Rozpoznawanie poleceń",
            listOf("small" to "Dokładniejsze (Whisper small)", "base" to "Szybsze (Whisper base)"),
            settings.whisper,
        ) { settings.whisper = it; restartService() })
        col.addView(slider("Czułość na „JARVIS”", settings.sensitivity) { settings.sensitivity = it; restartService() })
        col.addView(slider("Efekt głosu", settings.voiceFx) { settings.voiceFx = it })

        col.addView(header("Ostatnia rozmowa"))
        historyView = text("", 14f, TEXT).also { col.addView(it) }

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(col)
        }
    }

    private fun refresh() {
        if (!::statusView.isInitialized) return
        val running = JarvisService.instance != null
        statusView.text = if (running) "● ${Status.state.label}. ${Status.detail}" else "○ Nasłuch wyłączony."
        statusView.setTextColor(if (running && Status.state != Status.State.ERROR) ACCENT else TEXT)
        toggle.text = if (running) "Wyłącz nasłuch" else "Włącz nasłuch"
        micButton.visibility = if (micGranted()) View.GONE else View.VISIBLE

        val missing = store.missing(settings)
        modelsView.text = if (missing.isEmpty()) "Wszystkie modele są na goglach."
        else "Do pobrania (Wi-Fi): " + missing.joinToString(", ") { "${it.title} ${it.megabytes} MB" } +
            ". Razem około ${missing.sumOf { it.megabytes }} MB."
        downloadButton.visibility = if (missing.isEmpty() || downloading) View.GONE else View.VISIBLE

        val key = settings.apiKey
        keyView.text = if (key.isBlank()) "Brak klucza: działają tylko komendy (uruchom, głośność, godzina, bateria)."
        else "Klucz zapisany (…${key.takeLast(4)}). JARVIS odpowiada też na dowolne pytania."
        historyView.text = Status.history().takeLast(16).joinToString("\n").ifBlank { "Jeszcze nic." }
    }

    private fun restartService() {
        if (JarvisService.instance == null) return
        JarvisService.stop(this)
        main.postDelayed({
            if (store.missing(settings).isNotEmpty()) download() else JarvisService.start(this)
        }, 1500)
    }

    private fun download() {
        if (downloading) return
        val packs = store.missing(settings)
        if (packs.isEmpty()) { if (settings.listening && micGranted()) JarvisService.start(this); return }
        downloading = true
        progress.visibility = View.VISIBLE
        refresh()
        Thread {
            val error = try {
                store.download(packs) { pack, done, total ->
                    main.post {
                        modelsView.text = "Pobieram: ${pack.title}… ${done / 1_000_000} MB" + if (total > 0) " z ${total / 1_000_000} MB" else ""
                        progress.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                    }
                }
                null
            } catch (e: IOException) {
                e.message ?: "błąd sieci"
            }
            main.post {
                downloading = false
                progress.visibility = View.GONE
                refresh()
                if (error != null) modelsView.text = "Pobieranie przerwane: $error. Spróbuj ponownie."
                else if (settings.listening && micGranted()) JarvisService.start(this)
            }
        }.start()
    }

    // --- small builders -------------------------------------------------------------------------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun header(s: String) = text(s.uppercase(), 13f, ACCENT, bold = true).apply {
        layoutParams = margin(top = 28)
        letterSpacing = 0.12f
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun radio(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(title, 15f, DIM), margin(top = 12))
        val group = RadioGroup(this)
        for ((i, opt) in options.withIndex()) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = opt.second
                setTextColor(TEXT)
                isChecked = opt.first == selected
                tag = i
            })
        }
        group.setOnCheckedChangeListener { g, id ->
            val i = g.findViewById<RadioButton>(id)?.tag as? Int ?: return@setOnCheckedChangeListener
            onPick(options[i].first)
        }
        box.addView(group)
        return box
    }

    private fun slider(title: String, value: Float, onChange: (Float) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(title, 15f, DIM), margin(top = 12))
        box.addView(SeekBar(this).apply {
            layoutParams = fill()
            max = 100
            progress = (value * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) = onChange(s.progress / 100f)
            })
        })
        return box
    }

    private fun margin(top: Int = 0, left: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top; leftMargin = left }

    private fun fill(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }
}
