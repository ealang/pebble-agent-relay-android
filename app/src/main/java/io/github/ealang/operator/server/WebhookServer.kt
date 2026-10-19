package io.github.ealang.operator.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import io.github.ealang.operator.pipeline.Meta
import java.io.ByteArrayInputStream
import org.json.JSONObject

/**
 * The webhook receiver, this app's only listener. Its one caller is on this
 * same phone, so it listens on loopback only, and its whole job is to get
 * the message onto flash and answer fast: 200 means "durably queued", never
 * "delivered".
 *
 * The token is a nice-to-have on top of loopback isolation, not the primary
 * defence — but it keeps any other local app from posting into the chat.
 */
class WebhookServer(
    /** Read from config at service start; changing it restarts the service. */
    port: Int,
    private val token: String,
    onDied: () -> Unit,
    /** One line per request/incident, for the app-wide debug log. */
    private val log: (String) -> Unit,
    /**
     * Durably admit one message — the orchestrator's `store.enqueue` +
     * `worker.wake()`, handed in as a lambda so the server knows nothing
     * about the pipeline. Throwing means "could not queue", answered with a
     * 500 so the sender retries.
     */
    private val enqueue: (Meta) -> Unit,
) : NanoHTTPD(BIND, port) {

    init {
        serverSocketFactory = ServerSocketFactory { ResilientServerSocket(onDied, log) }
    }

    final override fun serve(session: IHTTPSession): Response {
        val response = handle(session)
        // One line per request, with the reason when it was turned away:
        // a wrong token, a recording-only payload and a mistyped path are
        // the usual setup mistakes, and the debug log is where they show.
        val why = (response as? Rejection)?.why
        log("${session.method} ${session.uri} → ${response.status.description}" +
            (why?.let { " — $it" } ?: ""))
        return response
    }

    private fun handle(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }

        // Unknown paths 404 before auth is checked — a probe of the wrong
        // port learns "not here" rather than "token needed". This is the
        // only path; there is no unauthenticated endpoint at all.
        if (uri != PATH) {
            return reject(Response.Status.NOT_FOUND, "Not found: $uri", "no such path")
        }

        val header = session.headers["authorization"] // NanoHTTPD lowercases names
        if (header == null) {
            return reject(Response.Status.UNAUTHORIZED, "Unauthorized", "no Authorization header")
        }
        if (!constantTimeEquals(header, "Bearer $token")) {
            Log.w(TAG, "Rejected an unauthorized request from ${session.remoteIpAddress}")
            return reject(Response.Status.UNAUTHORIZED, "Unauthorized", "wrong token")
        }

        return webhook(session)
    }

    private fun webhook(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return reject(Response.Status.METHOD_NOT_ALLOWED, "POST only")
        }

        val length = session.headers["content-length"]?.toLongOrNull() ?: 0
        if (length > MAX_BODY_BYTES) {
            return reject(Response.Status.PAYLOAD_TOO_LARGE, "body too large")
        }

        // For multipart, parseBody spills file parts (an attached recording,
        // which this app ignores) to NanoHTTPD temp files, deleted when the
        // request ends, and leaves text fields in session.parameters.
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable webhook body", e)
            return reject(Response.Status.BAD_REQUEST, "unreadable body")
        }

        val text = session.parameters["transcription"]?.firstOrNull()?.trim()
        if (text.isNullOrBlank()) {
            // This app forwards text; it cannot transcribe. Audio-only
            // payloads are affirmatively rejected so the sender knows.
            return reject(Response.Status.BAD_REQUEST, "no transcription present")
        }

        val meta = Meta(
            text = text,
            // An ISO-8601 instant ("2026-08-31T22:14:03Z"); epoch
            // milliseconds are accepted too for hand-rolled senders.
            recordedAtMs = session.parameters["recordedAt"]?.firstOrNull()?.trim()?.let { raw ->
                raw.toLongOrNull()
                    ?: runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
            } ?: 0,
            client = session.parameters["client"]?.firstOrNull(),
            receivedAtMs = System.currentTimeMillis(),
        )

        return try {
            enqueue(meta)
            json(Response.Status.OK, JSONObject().put("status", "queued"))
        } catch (e: Exception) {
            Log.e(TAG, "Could not enqueue a webhook message", e)
            reject(Response.Status.INTERNAL_ERROR, "could not queue the message")
        }
    }

    /**
     * Compares without an early return, so the time taken doesn't reveal how
     * many leading characters of a guess were right.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) {
            diff = diff or (x[i].toInt() xor y[i].toInt())
        }
        return diff == 0
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    /**
     * An error reply that also carries, for the log only, why it was sent.
     * [error] goes to the sender as `{"error": …}`; [why] defaults to it,
     * and differs where the log can afford to be plainer than the reply
     * (the 401 says "Unauthorized" to the wire, "wrong token" to the log).
     */
    private fun reject(status: Response.Status, error: String, why: String = error): Response {
        val bytes = JSONObject().put("error", error).toString().toByteArray(Charsets.UTF_8)
        return Rejection(status, bytes, why)
    }

    private class Rejection(status: Response.Status, body: ByteArray, val why: String) :
        Response(status, "application/json", ByteArrayInputStream(body), body.size.toLong())

    companion object {
        private const val TAG = "pebble-operator"
        /** Matches the Pebble app's own naming for this feature ("Index"). */
        const val PATH = "/index"

        /**
         * Hardcoded to loopback: the caller is on this same phone, so the
         * listener needs no route from anywhere else. A message inbox open
         * to the network is exactly the mistake this removes; no other bind
         * address will ever be supported.
         */
        const val BIND = "127.0.0.1"

        /** The default port; the config value is what a running listener binds. */
        const val PORT = 8911

        /** Reject request bodies past this; the watch sends ~100 KB. */
        const val MAX_BODY_BYTES = 25L * 1024 * 1024
    }
}
