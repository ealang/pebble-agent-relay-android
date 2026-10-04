package io.github.ealang.pebbletelegramagent.log

import android.util.Log
import io.github.ealang.pebbletelegramagent.pipeline.stringOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one app-wide debug log: web requests, pipeline events, Telegram and
 * server incidents, all in a single timeline on disk. "Did that message from
 * the car make it?" is a question asked hours later, possibly after a
 * restart, so this is a record rather than an in-memory window.
 *
 * JSONL, appended from whichever thread is doing the work; appends are
 * serialized on the instance. Rotation keeps the current file plus one
 * previous generation, each capped, so retention is bounded by design.
 */
class DebugLog(root: File) {

    private val file = File(root, "debug-log.jsonl")

    /**
     * One event. [item] is a queue item name when the event concerns one;
     * server lines and app-lifecycle events leave it null.
     */
    fun record(event: String, detail: String? = null, item: String? = null) {
        val line = JSONObject()
            .put("ts", STAMP.get()!!.format(Date()))
            .put("event", event)
            .put("detail", detail ?: JSONObject.NULL)
            .put("item", item ?: JSONObject.NULL)
            .toString()
        synchronized(this) {
            try {
                if (file.length() > MAX_BYTES) {
                    file.renameTo(File(file.parentFile, file.name + ".1"))
                }
                file.appendText(line + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Could not append to the debug log", e)
            }
        }
    }

    /** Both generations gone; the next record starts a fresh file. */
    fun clear() = synchronized(this) {
        file.delete()
        File(file.parentFile, file.name + ".1").delete()
    }

    /**
     * The most recent [limit] lines, newest first, rendered for the UI.
     * Spans into the previous generation when the current file is short.
     */
    fun tail(limit: Int): List<String> = synchronized(this) {
        try {
            val current = file.takeIf { it.exists() }?.readLines() ?: emptyList()
            val lines = if (current.size >= limit) {
                current
            } else {
                val previous = File(file.parentFile, file.name + ".1")
                    .takeIf { it.exists() }?.readLines() ?: emptyList()
                previous + current
            }
            lines.takeLast(limit).asReversed().map(::render)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the debug log", e)
            emptyList()
        }
    }

    private fun render(line: String): String = runCatching {
        val json = JSONObject(line)
        val item = json.stringOrNull("item")
        val detail = json.stringOrNull("detail")
        buildString {
            append(json.optString("ts"))
            append("  ")
            append(json.optString("event"))
            item?.let { append("  $it") }
            detail?.let { append(" — $it") }
        }
    }.getOrDefault(line)

    companion object {
        private const val TAG = "pebble-telegram-agent"

        /** Per generation; two generations bound the total at ~512 KB. */
        private const val MAX_BYTES = 256L * 1024

        private val STAMP = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        }
    }
}
