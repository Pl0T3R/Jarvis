package com.pl0t3r.jarvis.brain

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaThinkingBlockBinding
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaThinkingPrefixMismatchBehavior
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.anthropic.models.beta.messages.BetaUserLocation
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/**
 * Conversation with Claude, in JARVIS's voice. Short spoken answers in Polish, with tools to launch
 * apps, change the volume and read the headset's status, plus web search for current information.
 *
 * Each conversation is append-only (system prompt and tools are frozen when it starts), so Claude's
 * thinking from earlier turns stays valid. It starts over after 5 minutes of silence or 20 exchanges.
 */
class ClaudeBrain(
    private val config: () -> Config,
    private val device: Device,
    private val log: (String) -> Unit = {},
    /** Only for tests (a local fake server). */
    private val baseUrl: String? = null,
) : Chat {

    data class Config(val apiKey: String, val model: String, val webSearch: Boolean)

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (najmądrzejszy)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (najszybszy, najtańszy)",
        )
        private const val IDLE_RESET_MS = 5 * 60 * 1000L
        private const val MAX_EXCHANGES = 20
        private const val MAX_ROUNDS = 6
    }

    /** Everything that must stay identical for the whole conversation. */
    private class Session(val model: String, val webSearch: Boolean, val system: String)

    private var client: AnthropicClient? = null
    private var clientKey = ""
    private var session: Session? = null
    private val history = mutableListOf<BetaMessageParam>()
    private var exchanges = 0
    private var lastAt = 0L

    override val available: Boolean get() = config().apiKey.isNotBlank()

    @Synchronized
    override fun forget() {
        session = null
        history.clear()
        exchanges = 0
    }

    @Synchronized
    override fun ask(text: String): String {
        val cfg = config()
        if (cfg.apiKey.isBlank()) return Phrases.needsKey()
        val now = System.currentTimeMillis()
        if (now - lastAt > IDLE_RESET_MS || exchanges >= MAX_EXCHANGES) forget()
        lastAt = now
        val s = session ?: Session(cfg.model, cfg.webSearch, systemPrompt()).also { session = it }

        val mark = history.size
        history += BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(text).build()
        try {
            repeat(MAX_ROUNDS) {
                val msg = client(cfg.apiKey).beta().messages().create(params(s))
                when (msg.stopReason().orElse(null)) {
                    BetaStopReason.REFUSAL -> {
                        log("Claude: refusal ${msg.stopDetails().map { it.category() }.orElse(null)}")
                        rollback(mark)
                        return Phrases.refused()
                    }
                    BetaStopReason.TOOL_USE -> {
                        history += msg.toParam()
                        val results = msg.content().mapNotNull { it.toolUse().orElse(null) }.map { runTool(it) }
                        history += BetaMessageParam.builder().role(BetaMessageParam.Role.USER)
                            .contentOfBetaContentBlockParams(results).build()
                    }
                    // a server tool (web search) paused a long turn: send it back to continue
                    BetaStopReason.PAUSE_TURN -> history += msg.toParam()
                    else -> {
                        history += msg.toParam()
                        exchanges++
                        return spoken(msg).ifBlank { Phrases.stopped() }
                    }
                }
            }
            rollback(mark)
            return Phrases.noConnection()
        } catch (e: AnthropicException) {
            log("Claude error: ${e.javaClass.simpleName}: ${e.message}")
            rollback(mark)
            return Phrases.noConnection()
        } catch (e: RuntimeException) {
            log("Claude error: ${e.javaClass.simpleName}: ${e.message}")
            rollback(mark)
            return Phrases.noConnection()
        }
    }

    /** Drops only what this question appended, so the earlier conversation stays untouched. */
    private fun rollback(mark: Int) {
        while (history.size > mark) history.removeAt(history.size - 1)
    }

    private fun client(key: String): AnthropicClient {
        val c = client
        if (c != null && key == clientKey) return c
        val b = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(40))
            .maxRetries(1)
        baseUrl?.let { b.baseUrl(it) }
        return b.build().also { client = it; clientKey = key }
    }

    private fun params(s: Session): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(s.model)
            .maxTokens(16000L)
            .system(s.system)
            .messages(history.toList())
            // thinking stays on (it can't be turned off on these models); low effort keeps replies quick.
            // If an earlier thinking block ever stops matching, drop it instead of failing the request.
            .thinking(
                BetaThinkingConfigAdaptive.builder()
                    .blockBinding(
                        BetaThinkingBlockBinding.builder()
                            .prefixMismatchBehavior(BetaThinkingPrefixMismatchBehavior.DROP_BLOCK)
                            .build()
                    )
                    .build()
            )
            .addBeta("thinking-binding-controls-2026-08-01")
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .addTool(launchTool)
            .addTool(volumeTool)
            .addTool(statusTool)
        val isHaiku = s.model.startsWith("claude-haiku")
        if (s.webSearch) {
            val location = BetaUserLocation.builder().country("PL").timezone("Europe/Warsaw").build()
            if (isHaiku) b.addTool(BetaWebSearchTool20250305.builder().maxUses(2L).userLocation(location).build())
            else b.addTool(BetaWebSearchTool20260209.builder().maxUses(2L).userLocation(location).build())
        }
        // If a safety classifier declines, retry server-side on the model Anthropic recommends.
        // Not available for Haiku.
        if (!isHaiku) b.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
        return b.build()
    }

    private fun systemPrompt(): String {
        val apps = device.apps().map { it.label }.distinct().sorted().joinToString(", ")
        val today = PolishWords.date(device.now())
        return """
            Jesteś JARVIS, asystent głosowy w goglach Meta Quest 3. Mówisz po polsku, spokojnie i uprzejmie,
            z lekkim brytyjskim poczuciem humoru. Do użytkownika zwracasz się „sir”.

            Twoje odpowiedzi czyta na głos syntezator mowy, dlatego:
            - odpowiadaj krótko: zwykle jedno lub dwa zdania, najwyżej trzy,
            - pisz zwykłym tekstem, bez list, nagłówków, gwiazdek, emotikon i linków,
            - liczby, godziny, daty i jednostki zapisuj słowami, tak jak się je wymawia
              (na przykład „piętnaście stopni”, a nie „15°C”).

            Słowa użytkownika rozpoznaje automat, więc mogą być przekręcone. Domyśl się, o co chodziło;
            dopytaj tylko wtedy, gdy naprawdę nie wiadomo.

            Narzędzia:
            - launch_app uruchamia aplikację na goglach; podaj nazwę z listy zainstalowanych aplikacji,
            - set_volume zmienia głośność gogli,
            - device_status podaje aktualną godzinę, datę i stan baterii gogli.
            Gdy pytanie dotyczy bieżących informacji (pogoda, wiadomości, wyniki, ceny), skorzystaj
            z wyszukiwania w internecie, jeśli jest dostępne. Po użyciu narzędzia powiedz krótko, co zrobiłeś.

            Zainstalowane aplikacje: $apps.
            $today. Strefa czasowa: Europa/Warszawa.
        """.trimIndent()
    }

    private val launchTool: BetaTool = BetaTool.builder()
        .name("launch_app")
        .description("Uruchamia aplikację zainstalowaną na goglach Meta Quest.")
        .inputSchema(
            BetaTool.InputSchema.builder()
                .properties(
                    BetaTool.InputSchema.Properties.builder()
                        .putAdditionalProperty(
                            "name",
                            JsonValue.from(mapOf("type" to "string", "description" to "Nazwa aplikacji z listy zainstalowanych.")),
                        )
                        .build()
                )
                .required(listOf("name"))
                .build()
        )
        .build()

    private val volumeTool: BetaTool = BetaTool.builder()
        .name("set_volume")
        .description("Zmienia głośność gogli: ustawia procent albo robi głośniej, ciszej, wycisza lub przywraca dźwięk.")
        .inputSchema(
            BetaTool.InputSchema.builder()
                .properties(
                    BetaTool.InputSchema.Properties.builder()
                        .putAdditionalProperty(
                            "percent",
                            JsonValue.from(mapOf("type" to "integer", "minimum" to 0, "maximum" to 100, "description" to "Docelowa głośność w procentach.")),
                        )
                        .putAdditionalProperty(
                            "change",
                            JsonValue.from(mapOf("type" to "string", "enum" to listOf("up", "down", "mute", "unmute"), "description" to "Zmiana zamiast procentu.")),
                        )
                        .build()
                )
                .build()
        )
        .build()

    private val statusTool: BetaTool = BetaTool.builder()
        .name("device_status")
        .description("Podaje aktualną godzinę, datę i poziom baterii gogli.")
        .inputSchema(
            BetaTool.InputSchema.builder()
                .properties(BetaTool.InputSchema.Properties.builder().build())
                .build()
        )
        .build()

    private fun runTool(use: BetaToolUseBlock): BetaContentBlockParam {
        val input: Map<*, *> = try {
            use._input().convert(Map::class.java) ?: emptyMap<String, Any>()
        } catch (e: RuntimeException) {
            emptyMap<String, Any>()
        }
        var isError = false
        val result = when (use.name()) {
            "launch_app" -> {
                val name = input["name"]?.toString().orEmpty()
                val match = AppMatcher.best(name, device.apps())
                when {
                    match == null -> { isError = true; "Nie ma zainstalowanej aplikacji o nazwie „$name”." }
                    device.launch(match.app) -> "Uruchomiono ${match.app.label}."
                    else -> { isError = true; "Nie udało się uruchomić ${match.app.label}." }
                }
            }
            "set_volume" -> {
                val percent = (input["percent"] as? Number)?.toInt()
                when (input["change"]?.toString()) {
                    "up" -> "Głośność: ${device.volumeStep(up = true)}%."
                    "down" -> "Głośność: ${device.volumeStep(up = false)}%."
                    "mute" -> { device.mute(true); "Wyciszono." }
                    "unmute" -> { device.mute(false); "Dźwięk włączony." }
                    else -> if (percent != null) "Głośność: ${device.setVolume(percent.coerceIn(0, 100))}%."
                    else { isError = true; "Podaj percent albo change." }
                }
            }
            "device_status" -> {
                val t = device.now()
                val battery = device.battery()?.let { "$it%" } ?: "nieznany"
                "Godzina %02d:%02d, %s. Bateria gogli: %s.".format(t.hour, t.minute, PolishWords.date(t), battery)
            }
            else -> { isError = true; "Nieznane narzędzie ${use.name()}." }
        }
        log("tool ${use.name()} $input -> $result")
        return BetaContentBlockParam.ofToolResult(
            BetaToolResultBlockParam.builder().toolUseId(use.id()).content(result).isError(isError).build()
        )
    }

    /** The reply's text, cleaned of anything a speech synthesizer would read out literally. */
    private fun spoken(msg: BetaMessage): String =
        msg.content().mapNotNull { it.text().orElse(null)?.text() }
            .joinToString(" ")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_#`>\\[\\]]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
