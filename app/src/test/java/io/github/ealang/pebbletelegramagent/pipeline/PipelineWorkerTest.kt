package io.github.ealang.pebbletelegramagent.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the drain loop directly on the calling thread — the
 * HandlerThread is never started, so the single-writer rule holds.
 */
class PipelineWorkerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val silentLog = object : PipelineWorker.PipelineLog {
        override fun record(item: String, event: String, detail: String?) {}
    }

    private class FakeSender : PipelineWorker.TelegramSender {
        var sendResult: StepResult = StepResult.Success
        var reconcileResult: ReconcileResult = ReconcileResult.Delivered
        val sent = mutableListOf<String>()
        var markStoreOnSend: MessageStore? = null

        override fun send(item: MessageStore.Item, text: String): StepResult {
            sent += text
            if (sendResult is StepResult.Success) {
                // A real sender records the hand-off before reporting Success.
                markStoreOnSend?.writeProcessorState(
                    item,
                    PipelineWorker.PROCESSOR_TELEGRAM,
                    JSONObject().put("message_id", 1),
                )
            }
            return sendResult
        }

        override fun reconcile(item: MessageStore.Item): ReconcileResult = reconcileResult
    }

    private fun meta(receivedAt: Long = 1_700_000_000_000L, text: String = "hello") = Meta(
        text = text,
        recordedAtMs = receivedAt - 5_000,
        client = null,
        receivedAtMs = receivedAt,
    )

    private fun worker(
        store: MessageStore,
        sender: FakeSender,
        onReport: (DrainReport) -> Unit = {},
    ) = PipelineWorker(store, silentLog, sender, onReport)

    @Test
    fun `sends, reconciles delivered, deletes`() {
        val store = MessageStore(temp.root)
        val sender = FakeSender().apply { markStoreOnSend = store }
        store.enqueue(meta())

        val report = worker(store, sender).drain()

        assertEquals(listOf("hello"), sender.sent)
        assertEquals(1, report.completed)
        assertNull(report.blockedOn)
        assertEquals(0, store.pending().size)
    }

    @Test
    fun `pending reconciliation keeps the item but does not block the queue`() {
        val store = MessageStore(temp.root)
        val sender = FakeSender().apply {
            markStoreOnSend = store
            reconcileResult = ReconcileResult.Pending
        }
        store.enqueue(meta(1_700_000_000_000L, text = "first"))
        store.enqueue(meta(1_700_000_001_000L, text = "second"))

        val report = worker(store, sender).drain()

        // Both were sent — the unconfirmed first item did not stop the second.
        assertEquals(listOf("first", "second"), sender.sent)
        assertEquals(0, report.completed)
        assertNull(report.blockedOn)
        assertEquals(2, store.pending().size)

        // Once confirmation arrives, the next drain deletes without resending.
        sender.reconcileResult = ReconcileResult.Delivered
        val second = worker(store, sender).drain()
        assertEquals(2, second.completed)
        assertEquals(listOf("first", "second"), sender.sent)
        assertEquals(0, store.pending().size)
    }

    @Test
    fun `transient failure blocks the head and everything behind it`() {
        val store = MessageStore(temp.root)
        val sender = FakeSender().apply { sendResult = StepResult.Transient("offline") }
        store.enqueue(meta(1_700_000_000_000L, text = "first"))
        store.enqueue(meta(1_700_000_001_000L, text = "second"))

        val report = worker(store, sender).drain()

        assertEquals(listOf("first"), sender.sent)
        assertNotNull(report.blockedOn)
        assertTrue(report.blockedOn!!.contains("offline"))
        assertEquals(2, store.pending().size)
    }

    @Test
    fun `permanent failure moves the item to failed and the queue moves on`() {
        val store = MessageStore(temp.root)
        val sender = FakeSender().apply { sendResult = StepResult.Permanent("rejected") }
        store.enqueue(meta(1_700_000_000_000L, text = "first"))
        store.enqueue(meta(1_700_000_001_000L, text = "second"))

        val report = worker(store, sender).drain()

        assertEquals(listOf("first", "second"), sender.sent)
        assertEquals(2, report.newlyFailed)
        assertNull(report.blockedOn)
        assertEquals(0, store.pending().size)
        assertEquals(2, store.failed().size)
    }

    @Test
    fun `blank text completes quietly without a send`() {
        val store = MessageStore(temp.root)
        val sender = FakeSender().apply { markStoreOnSend = store }
        store.enqueue(meta(text = "   "))

        val report = worker(store, sender).drain()

        assertEquals(emptyList<String>(), sender.sent)
        assertEquals(1, report.completed)
        assertEquals(0, store.pending().size)
    }

    @Test
    fun `failed reconciliation parks the item`() {
        val store = MessageStore(temp.root)
        val sender = FakeSender().apply {
            markStoreOnSend = store
            reconcileResult = ReconcileResult.Failed("gave up")
        }
        store.enqueue(meta())

        val report = worker(store, sender).drain()

        assertEquals(1, report.newlyFailed)
        assertEquals(1, store.failed().size)
    }

    @Test
    fun `a sender exception is a permanent failure, not a dead queue`() {
        val store = MessageStore(temp.root)
        val sender = object : PipelineWorker.TelegramSender {
            override fun send(item: MessageStore.Item, text: String): StepResult =
                throw IllegalStateException("boom")
            override fun reconcile(item: MessageStore.Item): ReconcileResult =
                ReconcileResult.Pending
        }
        store.enqueue(meta(1_700_000_000_000L))
        store.enqueue(meta(1_700_000_001_000L))

        val report = PipelineWorker(store, silentLog, sender) {}.drain()

        assertEquals(2, report.newlyFailed)
        assertEquals(2, store.failed().size)
    }
}
