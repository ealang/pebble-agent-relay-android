package io.github.ealang.operator.telegram

import android.content.Context
import io.github.ealang.operator.Config
import io.github.ealang.operator.pipeline.MessageStore
import io.github.ealang.operator.pipeline.PipelineWorker
import io.github.ealang.operator.pipeline.ReconcileResult
import io.github.ealang.operator.pipeline.StepResult
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Telegram side of the pipeline. Handoff and confirmation are separate:
 * [send] records the accepted message id in the item's `telegram.json` and
 * returns — the item stops blocking the queue but *stays* as the visible
 * record that confirmation is owed — and [reconcile], run on every drain, is
 * what finally deletes it. The engine's events carry no payload; the drain
 * discovers what changed by asking TDLib about each handed-off item. Never
 * re-send on a gap: TDLib's own persistent outbox is retrying underneath,
 * and a timeout that re-sends is how duplicates are made. The one retry we
 * ever issue is ResendMessages, which retries the same message and cannot
 * duplicate it.
 */
class TelegramDelivery(
    context: Context,
    private val store: MessageStore,
) : PipelineWorker.TelegramSender {

    private val config = Config(context)

    /** Worker thread. Blocks on TDLib's synchronous answer — local and quick. */
    override fun send(item: MessageStore.Item, text: String): StepResult {
        // Read at pickup, not at receipt: changing the chat id redirects
        // queued items — the rescue path for a wrong or missing setting.
        val chatId = config.telegramChatId
            ?: return StepResult.Transient("no Telegram chat id is set — save one in the app")

        // Signature read at pickup like the chat id, so editing it applies to
        // anything still queued. Its own line: it marks provenance, not prose.
        val signature = config.telegramSignature
        val message = if (signature.isEmpty()) text else "$text\n$signature"

        var messageId: Long? = null
        var problem: String? = null
        val latch = CountDownLatch(1)
        TelegramEngine.sendText(chatId, message) { id, why ->
            messageId = id
            problem = why
            latch.countDown()
        }
        if (!latch.await(ANSWER_TIMEOUT_S, TimeUnit.SECONDS)) {
            // TDLib answers a SendMessage locally even when offline, so a
            // silent client is broken, not slow. Nothing was recorded, so a
            // later retry cannot duplicate — the send never got an id.
            return StepResult.Transient("Telegram did not answer")
        }
        val id = messageId
            ?: return StepResult.Transient(problem ?: "Telegram send failed")

        store.writeProcessorState(
            item,
            PipelineWorker.PROCESSOR_TELEGRAM,
            JSONObject()
                .put("chatId", chatId)
                .put("messageId", id)
                .put("handedOffAtMs", System.currentTimeMillis()),
        )
        return StepResult.Success
    }

    /**
     * Ask TDLib what became of a handed-off item. Worker thread, blocking
     * like [send]. The recorded id is the *temporary* one; on success TDLib
     * reassigns it, so GetMessage answering "not found" means delivered —
     * exactly the trace a missed UpdateMessageSendSucceeded leaves behind.
     * A retryable failure is resent here (ResendMessages retries the same
     * message, so it cannot duplicate) and the replacement id recorded;
     * everything unclear is Pending, which costs nothing but another look.
     */
    override fun reconcile(item: MessageStore.Item): ReconcileResult {
        val state = item.processorState(PipelineWorker.PROCESSOR_TELEGRAM)
            ?: return ReconcileResult.Pending // caller's bug; stay harmless
        val chatId = state.optLong("chatId")
        val messageId = state.optLong("messageId")

        var outcome: TelegramEngine.SendState = TelegramEngine.SendState.Unknown
        val latch = CountDownLatch(1)
        TelegramEngine.checkSend(chatId, messageId) { answer ->
            outcome = answer
            latch.countDown()
        }
        if (!latch.await(ANSWER_TIMEOUT_S, TimeUnit.SECONDS)) {
            return ReconcileResult.Pending
        }

        return when (val seen = outcome) {
            is TelegramEngine.SendState.Delivered -> ReconcileResult.Delivered
            is TelegramEngine.SendState.Pending,
            is TelegramEngine.SendState.Unknown,
            -> ReconcileResult.Pending

            is TelegramEngine.SendState.Failed -> {
                if (!seen.canRetry) return ReconcileResult.Failed(seen.reason)
                resend(item, chatId, messageId, seen.reason)
            }
        }
    }

    /** TDLib gave up but says a retry can work: resend and record the new id. */
    private fun resend(
        item: MessageStore.Item,
        chatId: Long,
        messageId: Long,
        reason: String,
    ): ReconcileResult {
        var newId: Long? = null
        var problem: String? = null
        val latch = CountDownLatch(1)
        TelegramEngine.resend(chatId, messageId) { id, why ->
            newId = id
            problem = why
            latch.countDown()
        }
        if (!latch.await(ANSWER_TIMEOUT_S, TimeUnit.SECONDS)) {
            return ReconcileResult.Pending
        }
        val id = newId
        // A refused resend on a message TDLib already gave up on is terminal:
        // nothing left in the outbox will ever move this item again.
            ?: return ReconcileResult.Failed("$reason; resend refused: ${problem ?: "no answer"}")

        store.writeProcessorState(
            item,
            PipelineWorker.PROCESSOR_TELEGRAM,
            JSONObject()
                .put("chatId", chatId)
                .put("messageId", id)
                .put("handedOffAtMs", System.currentTimeMillis()),
        )
        return ReconcileResult.Pending
    }

    companion object {
        /** Generous — the answer is a local round-trip into TDLib, not the network. */
        private const val ANSWER_TIMEOUT_S = 20L
    }
}
