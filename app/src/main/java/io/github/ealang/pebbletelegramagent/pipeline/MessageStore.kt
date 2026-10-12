package io.github.ealang.pebbletelegramagent.pipeline

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/**
 * The queue is the filesystem; this class is its accessor layer, and the
 * only code allowed to touch `files/message-queue/`. It keeps no in-memory
 * cache — every answer is read from disk, so it can never fall out of sync
 * and never needs invalidating. Threading is single-writer: any thread may
 * [enqueue] (the rename is the handover) and any thread may read;
 * everything else is called from the worker's thread only.
 *
 * Takes a plain root directory rather than a Context so it can be exercised
 * on the JVM against a temp dir.
 */
class MessageStore(root: File) {

    private val queueDir = File(root, "message-queue")
    private val tmpDir = File(queueDir, "tmp")
    private val failedDir = File(queueDir, "failed")
    private val seenDir = File(queueDir, "seen")
    private val seq: AtomicInteger

    init {
        queueDir.mkdirs()
        failedDir.mkdirs()
        seenDir.mkdirs()

        // Leftovers in tmp/ are items half-written when the process died.
        // The sender never got its 200 for them, so deleting is safe — the
        // watch re-sends what was never acknowledged.
        tmpDir.listFiles()?.forEach { it.deleteRecursively() }
        tmpDir.mkdirs()

        // Re-seed the sequence from the largest existing name so a restart
        // cannot mint a name that sorts before items already queued.
        seq = AtomicInteger(
            (itemDirs(queueDir) + itemDirs(failedDir))
                .maxOfOrNull { it.name.substringAfterLast('-').toIntOrNull() ?: 0 }
                ?: 0,
        )
    }

    /** One queued message. State is derived from which files exist, fresh per call. */
    inner class Item internal constructor(internal val dir: File) {
        val name: String get() = dir.name

        val meta: Meta by lazy {
            Meta.fromJson(JSONObject(File(dir, META).readText()))
        }

        /**
         * The named processor's own state file, opaque to everyone else —
         * `telegram.json` today. Present means the item was handed off and
         * no longer blocks the queue; it waits only for confirmation.
         */
        fun processorState(processor: String): JSONObject? =
            File(dir, "$processor.json").takeIf { it.exists() }
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
    }

    // ------------------------------ enqueue ------------------------------

    /**
     * Durably admit one message. Built in `tmp/`, fsynced, then renamed
     * into the queue in one atomic step — a reader can never see a
     * half-written item. Only after this returns may the webhook answer 200.
     */
    @Throws(IOException::class)
    fun enqueue(meta: Meta): Item {
        val name = newName(meta.receivedAtMs)
        val staging = File(tmpDir, name)
        if (!staging.mkdirs()) throw IOException("could not create $staging")

        writeSynced(File(staging, META), meta.toJson().toString(2).toByteArray())

        val home = File(queueDir, name)
        if (!staging.renameTo(home)) throw IOException("could not commit $name")
        return Item(home)
    }

    // ----------------------------- receipts -----------------------------

    /**
     * Admit a message once. The watch resends when an Ack is lost, with the
     * same [Meta.recordedAtMs]; by then the original may already be
     * delivered and gone from the queue, so the memory of it has to live
     * somewhere else: a marker file in `seen/`, named for the sender, the
     * timestamp and a hash of the text (same stamp with different text is a
     * different message). Creating the marker is the atomic claim — no lock,
     * no read-modify-write, and thread-safe like [enqueue]. Returns null for
     * a repeat. Markers older than [SEEN_RETENTION_MS] are pruned here.
     */
    @Throws(IOException::class)
    fun enqueueOnce(meta: Meta): Item? {
        pruneSeen()
        val marker = File(seenDir, receiptName(meta))
        if (!marker.createNewFile()) return null
        return try {
            enqueue(meta)
        } catch (e: IOException) {
            marker.delete() // not admitted, so a resend must get through
            throw e
        }
    }

    private fun receiptName(meta: Meta): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(meta.text.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${meta.client ?: "unknown"}-${meta.recordedAtMs}-$digest"
    }

    private fun pruneSeen() {
        val now = System.currentTimeMillis() // same clock that stamps the files
        seenDir.listFiles()?.forEach {
            if (now - it.lastModified() > SEEN_RETENTION_MS) it.delete()
        }
    }

    // ------------------------------ reads ------------------------------

    /** Every queued item, oldest first — names are UTC stamps, so sorted = FIFO. */
    fun pending(): List<Item> = itemDirs(queueDir).sortedBy { it.name }.map { Item(it) }

    fun failed(): List<Item> = itemDirs(failedDir).sortedBy { it.name }.map { Item(it) }

    // ------------------------- steps (worker only) -------------------------

    @Throws(IOException::class)
    fun writeProcessorState(item: Item, processor: String, state: JSONObject) =
        writeSynced(File(item.dir, "$processor.json"), state.toString(2).toByteArray())

    /** The item is delivered; forget it. */
    fun complete(item: Item) {
        if (!item.dir.deleteRecursively()) {
            Log.w(TAG, "Could not delete completed item ${item.name}")
        }
    }

    /**
     * Permanent failure: park the item, whole, where Retry can reach it.
     * The reason is not kept here; the activity log's "failed" line has it.
     */
    fun fail(item: Item) {
        if (!item.dir.renameTo(File(failedDir, item.name))) {
            Log.w(TAG, "Could not move ${item.name} to failed/")
        }
    }

    /**
     * Back into the queue under a fresh name — fresh so it sorts last:
     * retrying an old failure must not block today's traffic behind it.
     */
    fun retry(item: Item) {
        if (!item.dir.renameTo(File(queueDir, newName(System.currentTimeMillis())))) {
            Log.w(TAG, "Could not requeue ${item.name}")
        }
    }

    fun discard(item: Item) {
        if (!item.dir.deleteRecursively()) {
            Log.w(TAG, "Could not discard ${item.name}")
        }
    }

    // ------------------------------ internals ------------------------------

    private fun itemDirs(parent: File): List<File> =
        parent.listFiles()?.filter {
            it.isDirectory && it.name != "tmp" && it.name != "failed" && it.name != "seen"
        } ?: emptyList()

    private fun newName(atMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(atMs))
        return "%s-%03d".format(Locale.US, stamp, seq.incrementAndGet())
    }

    /** Write + fsync, so "enqueued" means "on flash", not "in a page cache". */
    @Throws(IOException::class)
    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use {
            it.write(bytes)
            it.fd.sync()
        }
    }

    companion object {
        private const val TAG = "pebble-telegram-agent"

        /** The watch retries within seconds or minutes; a day is generous. */
        private const val SEEN_RETENTION_MS = 24L * 60 * 60 * 1000
        private const val META = "meta.json"
    }
}
