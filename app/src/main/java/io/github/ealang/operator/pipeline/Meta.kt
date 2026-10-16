package io.github.ealang.operator.pipeline

import org.json.JSONObject

/**
 * The facts of one webhook receipt — everything `meta.json` holds. No
 * processing state: that lives in the marker files next to it.
 */
data class Meta(
    /** The message text to forward. Required — the endpoint rejects blank. */
    val text: String,
    /** Epoch milliseconds, as the sender reported it. 0 when absent. */
    val recordedAtMs: Long,
    /** Caller name, when the sender identified itself. */
    val client: String?,
    /** Epoch milliseconds when we received it. */
    val receivedAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text)
        .put("recorded_at_ms", recordedAtMs)
        .put("client", client ?: JSONObject.NULL)
        .put("received_at_ms", receivedAtMs)

    companion object {
        fun fromJson(json: JSONObject): Meta = Meta(
            text = json.optString("text"),
            recordedAtMs = json.optLong("recorded_at_ms"),
            client = json.stringOrNull("client"),
            receivedAtMs = json.optLong("received_at_ms"),
        )
    }
}

/**
 * Read a string field that may be an explicit JSON null. Android's org.json
 * differs from the desktop library here: `optString` on `JSONObject.NULL`
 * returns the *string* "null" on the device (desktop returns ""). `isNull`
 * first is the only read that behaves the same on both.
 */
internal fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).ifBlank { null }
