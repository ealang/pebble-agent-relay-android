package io.github.ealang.pebbletelegramagent.server

import io.github.ealang.pebbletelegramagent.pipeline.Meta
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Boots the real server on an ephemeral loopback port and speaks HTTP to it —
 * NanoHTTPD is plain JVM code, so the whole request path runs in a unit test.
 */
class WebhookServerTest {

    private val token = "test-token"
    private val enqueued = mutableListOf<Meta>()
    private var enqueueError: Exception? = null
    private val logged = mutableListOf<String>()

    private var port = 0
    private lateinit var server: WebhookServer

    @Before
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = WebhookServer(
            port = port,
            token = token,
            onDied = {},
            log = { logged += it },
            enqueue = { meta ->
                enqueueError?.let { throw it }
                enqueued += meta
            },
        )
        server.start()
    }

    @After
    fun stop() {
        server.stop()
    }

    private fun request(
        path: String,
        method: String = "POST",
        auth: String? = "Bearer $token",
        form: Map<String, String>? = null,
    ): Pair<Int, JSONObject> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        auth?.let { conn.setRequestProperty("Authorization", it) }
        if (form != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form.entries.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return code to JSONObject(text)
    }

    @Test
    fun `health is open, no token needed`() {
        val (code, body) = request("/health", method = "GET", auth = null)
        assertEquals(200, code)
        assertTrue(body.getBoolean("ok"))
    }

    @Test
    fun `unknown path is 404 even without a token`() {
        val (code, _) = request("/nope", auth = null)
        assertEquals(404, code)
    }

    @Test
    fun `missing token is rejected`() {
        val (code, _) = request("/webhook", auth = null, form = mapOf("transcription" to "hi"))
        assertEquals(401, code)
        assertEquals(0, enqueued.size)
    }

    @Test
    fun `wrong token is rejected`() {
        val (code, _) = request("/webhook", auth = "Bearer wrong", form = mapOf("transcription" to "hi"))
        assertEquals(401, code)
        assertEquals(0, enqueued.size)
    }

    @Test
    fun `GET on the webhook is method not allowed`() {
        val (code, _) = request("/webhook", method = "GET")
        assertEquals(405, code)
    }

    @Test
    fun `no transcription field is a 400`() {
        val (code, body) = request("/webhook", form = mapOf("other" to "x"))
        assertEquals(400, code)
        assertTrue(body.getString("error").contains("transcription"))
        assertEquals(0, enqueued.size)
    }

    @Test
    fun `blank transcription is a 400`() {
        val (code, _) = request("/webhook", form = mapOf("transcription" to "   "))
        assertEquals(400, code)
        assertEquals(0, enqueued.size)
    }

    @Test
    fun `a good message is queued with its fields parsed`() {
        val (code, body) = request(
            "/webhook",
            form = mapOf(
                "transcription" to "  hello world ",
                "recordedAt" to "2026-08-31T22:14:03Z",
                "client" to "pebble",
            ),
        )
        assertEquals(200, code)
        assertEquals("queued", body.getString("status"))
        assertEquals(1, enqueued.size)
        val meta = enqueued[0]
        assertEquals("hello world", meta.text)
        assertEquals(1_788_214_443_000L, meta.recordedAtMs)
        assertEquals("pebble", meta.client)
        assertTrue(meta.receivedAtMs > 0)
    }

    @Test
    fun `recordedAt also accepts epoch milliseconds`() {
        val (code, _) = request(
            "/webhook",
            form = mapOf("transcription" to "hi", "recordedAt" to "1756678443000"),
        )
        assertEquals(200, code)
        assertEquals(1_756_678_443_000L, enqueued[0].recordedAtMs)
    }

    @Test
    fun `unparseable recordedAt falls back to zero rather than failing`() {
        val (code, _) = request(
            "/webhook",
            form = mapOf("transcription" to "hi", "recordedAt" to "yesterday-ish"),
        )
        assertEquals(200, code)
        assertEquals(0L, enqueued[0].recordedAtMs)
    }

    @Test
    fun `enqueue failure surfaces as a 500 so the sender retries`() {
        enqueueError = RuntimeException("disk full")
        val (code, body) = request("/webhook", form = mapOf("transcription" to "hi"))
        assertEquals(500, code)
        assertTrue(body.getString("error").contains("queue"))
    }

    @Test
    fun `every request lands one line in the debug log`() {
        request("/health", method = "GET", auth = null)
        request("/webhook", form = mapOf("transcription" to "hi"))
        assertEquals(2, logged.size)
        assertTrue(logged[0].startsWith("GET /health"))
        assertTrue(logged[1].startsWith("POST /webhook"))
    }
}
