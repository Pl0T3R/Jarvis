package com.pl0t3r.jarvis.brain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * ClaudeBrain against a local fake of the Messages API: checks the requests the real SDK sends
 * (model, tools, thinking, fallbacks, betas) and the tool loop, without an API key or network.
 */
class ClaudeBrainTest {
    private val json = ObjectMapper()
    private lateinit var server: ServerSocket
    private val responses = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val requests = mutableListOf<Pair<Map<String, String>, JsonNode>>()
    private val device = FakeDevice()

    /** A tiny HTTP/1.1 server: one request per connection, answers from [responses]. */
    @Before
    fun start() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: java.io.IOException) { break }
                socket.use { s ->
                    val input = BufferedInputStream(s.getInputStream())
                    val headers = HashMap<String, String>()
                    readLine(input)
                    while (true) {
                        val line = readLine(input)
                        if (line.isEmpty()) break
                        val i = line.indexOf(':')
                        if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                    }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var read = 0
                    while (read < body.size) read += input.read(body, read, body.size - read)
                    synchronized(requests) { requests += headers to json.readTree(body) }
                    val (code, text) = responses.poll() ?: (500 to "{}")
                    val out = text.toByteArray()
                    s.getOutputStream().apply {
                        write(("HTTP/1.1 $code X\r\ncontent-type: application/json\r\ncontent-length: ${out.size}\r\n" +
                            "connection: close\r\n\r\n").toByteArray())
                        write(out)
                        flush()
                    }
                }
            }
        }
    }

    private fun readLine(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString(Charsets.UTF_8)
    }

    @After
    fun stop() = server.close()

    private fun brain(model: String = ClaudeBrain.DEFAULT_MODEL) = ClaudeBrain(
        { ClaudeBrain.Config("sk-ant-test", model, webSearch = true) },
        device,
        baseUrl = "http://127.0.0.1:${server.localPort}",
    )

    private fun message(id: String, stop: String, content: String) =
        """{"id":"$id","type":"message","role":"assistant","model":"claude-opus-5-5","content":$content,
           "stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":100,"output_tokens":20}}"""

    @Test
    fun toolLoopAndRequestShape() {
        responses += 200 to message("msg_1", "tool_use",
            """[{"type":"thinking","thinking":"","signature":"sig1"},
                {"type":"tool_use","id":"toolu_1","name":"launch_app","input":{"name":"Spotify"}}]""")
        responses += 200 to message("msg_2", "end_turn", """[{"type":"text","text":"Oczywiście, sir. **Spotify** już gra."}]""")

        val reply = brain().ask("puść mi muzykę")
        assertEquals("Oczywiście, sir. Spotify już gra.", reply)
        assertEquals(listOf("Spotify"), device.launched)
        assertEquals(2, requests.size)

        val (headers, first) = requests[0]
        assertEquals("sk-ant-test", headers["x-api-key"])
        val betas = headers["anthropic-beta"].orEmpty()
        assertTrue(betas, "server-side-fallback-2026-07-01" in betas)
        assertTrue(betas, "thinking-binding-controls-2026-08-01" in betas)
        assertEquals("claude-sonnet-5-5", first["model"].asText())
        assertEquals("default", first["fallbacks"].asText())
        assertEquals("adaptive", first["thinking"]["type"].asText())
        assertEquals("drop_block", first["thinking"]["block_binding"]["prefix_mismatch_behavior"].asText())
        assertEquals("low", first["output_config"]["effort"].asText())
        assertFalse(first.has("temperature"))
        val tools = first["tools"].map { it["name"].asText() to (it["type"]?.asText() ?: "custom") }
        assertTrue(tools.toString(), ("launch_app" to "custom") in tools || tools.any { it.first == "launch_app" })
        assertTrue(tools.toString(), tools.any { it.first == "web_search" && it.second == "web_search_20260209" })
        val system = first["system"].asText()
        assertTrue(system.contains("JARVIS") && system.contains("Spotify") && system.contains("Beat Saber"))

        // second request: the same frozen system prompt and tools, history appended with the tool result
        val second = requests[1].second
        assertEquals(first["system"], second["system"])
        assertEquals(first["tools"], second["tools"])
        val msgs = second["messages"]
        assertEquals(3, msgs.size())
        assertEquals("assistant", msgs[1]["role"].asText())
        assertEquals("thinking", msgs[1]["content"][0]["type"].asText())
        assertEquals("sig1", msgs[1]["content"][0]["signature"].asText())
        val result = msgs[2]["content"][0]
        assertEquals("tool_result", result["type"].asText())
        assertEquals("toolu_1", result["tool_use_id"].asText())
        assertTrue(result.toString(), result.toString().contains("Uruchomiono Spotify"))
    }

    @Test
    fun conversationIsAppendOnly() {
        val b = brain()
        responses += 200 to message("m1", "end_turn", """[{"type":"thinking","thinking":"","signature":"s1"},{"type":"text","text":"Dzień dobry, sir."}]""")
        b.ask("dzień dobry")
        responses += 200 to message("m2", "end_turn", """[{"type":"text","text":"Świetnie, sir."}]""")
        b.ask("jak się masz")
        val firstMsgs = requests[0].second["messages"]
        val secondMsgs = requests[1].second["messages"]
        assertEquals(requests[0].second["system"], requests[1].second["system"])
        assertEquals(3, secondMsgs.size())
        assertEquals(firstMsgs[0], secondMsgs[0])
        assertEquals("s1", secondMsgs[1]["content"][0]["signature"].asText())
    }

    @Test
    fun refusalIsRolledBack() {
        val b = brain()
        responses += 200 to message("m1", "refusal", "[]")
        assertEquals(Phrases.refused(), b.ask("coś niedozwolonego"))
        responses += 200 to message("m2", "end_turn", """[{"type":"text","text":"Proszę bardzo."}]""")
        b.ask("inne pytanie")
        val msgs = requests[1].second["messages"]
        assertEquals(1, msgs.size())
        assertEquals("inne pytanie", msgs[0]["content"].asText().ifEmpty { msgs[0]["content"][0]["text"].asText() })
    }

    @Test
    fun haikuHasNoFallbackAndBasicSearch() {
        responses += 200 to message("m1", "end_turn", """[{"type":"text","text":"Jasne."}]""")
        brain("claude-haiku-5-5").ask("cześć")
        val (headers, req) = requests[0]
        assertFalse(req.has("fallbacks"))
        assertFalse(headers["anthropic-beta"].orEmpty().contains("server-side-fallback"))
        assertTrue(req["tools"].any { it["type"]?.asText() == "web_search_20250305" })
    }

    @Test
    fun serverErrorSaysSo() {
        // the client retries once
        repeat(2) { responses += 529 to """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""" }
        assertEquals(Phrases.noConnection(), brain().ask("hej"))
    }
}
