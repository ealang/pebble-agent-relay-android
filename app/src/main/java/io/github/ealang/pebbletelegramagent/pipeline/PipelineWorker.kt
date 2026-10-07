package io.github.ealang.pebbletelegramagent.pipeline

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * The one thread that mutates the queue past enqueue. Inbound surface:
 * [wake], from any thread — every reason to work (an enqueue, a Retry, a
 * network change, a Telegram event) raises the same flag. Outbound: one
 * callback per drain, with a report; the worker owns no retry timer and
 * does not know who listens.
 */
class PipelineWorker(
    private val store: MessageStore,
    private val log: PipelineLog,
    private val telegramSender: TelegramSender,
    private val onDrainFinished: (DrainReport) -> Unit,
) {

    /** The Telegram destination, behind an interface so tests can fake it. */
    interface TelegramSender {
        /** Hand one item's text to Telegram. Success means *handed off*, not delivered. */
        fun send(item: MessageStore.Item, text: String): StepResult

        /** What became of an item [send] already handed off. */
        fun reconcile(item: MessageStore.Item): ReconcileResult
    }

    /** Where the worker narrates what it did; the app decides where that goes. */
    interface PipelineLog {
        fun record(item: String, event: String, detail: String? = null)
    }

    private val thread = HandlerThread("pipeline")
    private lateinit var handler: Handler

    /** Collapses a burst of wakes into one queued drain. */
    @Volatile
    private var drainQueued = false

    /**
     * The last "waiting" line written, as item name + reason. A blocked head
     * is re-examined on every retry pass; logging it once per cause keeps
     * the activity log to one line per message plus one when it stalls.
     */
    private var lastWaiting: Pair<String, String>? = null

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
    }

    fun quit() {
        thread.quitSafely()
    }

    /** Thread-safe; call from anywhere. */
    fun wake() {
        if (drainQueued) return
        drainQueued = true
        handler.post {
            drainQueued = false
            onDrainFinished(drain())
        }
    }

    /** UI actions run on the worker too, so even `failed/` has one writer. */
    fun post(action: () -> Unit) {
        handler.post(action)
    }

    internal fun drain(): DrainReport {
        var completed = 0
        var newlyFailed = 0
        var blockedOn: String? = null

        for (item in store.pending()) {
            // process() is idempotent — the marker file makes the done step a
            // cheap skip — so it runs on every pass, even for items already
            // handed off to Telegram; those wait only for reconciliation.
            val result = try {
                process(item)
            } catch (e: Exception) {
                // An unexpected exception is a failure report, never a dead
                // thread — and not knowing what it was, we must not assume
                // it will fix itself.
                Log.e(TAG, "Unexpected failure processing ${item.name}", e)
                StepResult.Permanent("unexpected: ${e::class.java.simpleName}")
            }

            when (result) {
                is StepResult.Success -> {
                    if (item.processorState(PROCESSOR_TELEGRAM) != null) {
                        // Handed off: the item stays as the record that the
                        // outbox owes a confirmation, and does not block the
                        // items behind it. Deletion is the confirmation's job.
                        when (val settled = telegramSender.reconcile(item)) {
                            is ReconcileResult.Delivered -> {
                                completed++
                                log.record(item.name, "completed")
                                store.complete(item)
                            }
                            is ReconcileResult.Failed -> {
                                newlyFailed++
                                log.record(item.name, "failed", settled.why)
                                store.fail(item)
                            }
                            is ReconcileResult.Pending -> {}
                        }
                    } else {
                        completed++
                        log.record(item.name, "completed")
                        store.complete(item)
                    }
                }
                is StepResult.Permanent -> {
                    newlyFailed++
                    log.record(item.name, "failed", result.why)
                    store.fail(item)
                }
                is StepResult.Transient -> {
                    // Ordering: nothing behind this item may pass it. Stop —
                    // don't sleep — and let the orchestrator decide when to
                    // wake us again.
                    val waiting = item.name to result.why
                    if (waiting != lastWaiting) {
                        lastWaiting = waiting
                        log.record(item.name, "waiting", result.why)
                    }
                    blockedOn = "${item.name}: ${result.why}"
                    break
                }
            }
        }

        return DrainReport(completed, blockedOn, newlyFailed)
    }

    /**
     * Advance one item, idempotently. The marker file, not the send, is the
     * record it ran: an item with `telegram.json` present is already in the
     * outbox and only awaits confirmation.
     */
    private fun process(item: MessageStore.Item): StepResult {
        if (item.processorState(PROCESSOR_TELEGRAM) != null) {
            return StepResult.Success // handed off; the caller reconciles
        }

        val text = item.meta.text
        if (text.isBlank()) {
            // A pocket press or silence. Completing it quietly is the
            // designed outcome, not an error.
            log.record(item.name, "skipped", "blank text")
            return StepResult.Success
        }

        return telegramSender.send(item, text)
    }

    companion object {
        private const val TAG = "pebble-telegram-agent"
        const val PROCESSOR_TELEGRAM = "telegram"
    }
}
