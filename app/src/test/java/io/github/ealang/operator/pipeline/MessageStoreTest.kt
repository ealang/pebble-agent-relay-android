package io.github.ealang.operator.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MessageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun meta(receivedAt: Long = 1_700_000_000_000L, text: String = "hello") = Meta(
        text = text,
        recordedAtMs = receivedAt - 5_000,
        client = "ring",
        receivedAtMs = receivedAt,
    )

    @Test
    fun `enqueue then pending round-trips the meta`() {
        val store = MessageStore(temp.root)
        store.enqueue(meta())

        val items = store.pending()
        assertEquals(1, items.size)
        assertEquals("hello", items[0].meta.text)
        assertEquals("ring", items[0].meta.client)
        assertEquals(1_700_000_000_000L, items[0].meta.receivedAtMs)
    }

    @Test
    fun `pending is FIFO even across a restart`() {
        var store = MessageStore(temp.root)
        store.enqueue(meta(1_700_000_000_000L))
        store.enqueue(meta(1_700_000_001_000L))

        // A new instance over the same root: the sequence re-seeds, so the
        // third item must still sort after the first two.
        store = MessageStore(temp.root)
        store.enqueue(meta(1_700_000_002_000L))

        val names = store.pending().map { it.name }
        assertEquals(3, names.size)
        assertEquals(names.sorted(), names)
        assertEquals(1_700_000_002_000L, store.pending().last().meta.receivedAtMs)
    }

    @Test
    fun `an item is invisible until its meta is committed`() {
        val store = MessageStore(temp.root)
        val dir = File(temp.root, "message-queue/20231114-221320-000-001").apply { mkdirs() }
        File(dir, "meta.json.part").writeText("{")

        assertEquals(0, store.pending().size)
        assertEquals(0, store.failed().size)
    }

    @Test
    fun `sweep drops uncommitted items only once they are a day old`() {
        val store = MessageStore(temp.root)
        val committed = store.enqueue(meta())
        val fresh = File(temp.root, "message-queue/20231114-221320-000-002").apply { mkdirs() }
        val stale = File(temp.root, "message-queue/20231114-221320-000-003").apply { mkdirs() }
        val staleFailed = File(temp.root, "message-queue/failed/20231114-221320-000-004").apply { mkdirs() }
        val dayLater = System.currentTimeMillis() + 25L * 60 * 60 * 1000
        assertTrue(fresh.setLastModified(dayLater))

        store.sweep(now = dayLater)

        assertEquals(committed.name, store.pending().single().name)
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
        assertFalse(staleFailed.exists())
    }

    @Test
    fun `complete removes the item`() {
        val store = MessageStore(temp.root)
        val item = store.enqueue(meta())

        store.complete(item)

        assertEquals(0, store.pending().size)
    }

    @Test
    fun `fail parks the item, retry re-queues it last`() {
        val store = MessageStore(temp.root)
        val first = store.enqueue(meta(1_700_000_000_000L, text = "older"))
        store.fail(first)
        store.enqueue(meta(1_700_000_001_000L, text = "newer"))

        assertEquals(1, store.pending().size)
        val failed = store.failed()
        assertEquals(1, failed.size)

        store.retry(failed[0])

        assertEquals(0, store.failed().size)
        val names = store.pending().map { it.name }
        assertEquals(2, names.size)
        // The retried item sorts last, so it cannot block newer traffic.
        assertEquals("older", store.pending().last().meta.text)
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `discard removes a failed item for good`() {
        val store = MessageStore(temp.root)
        val item = store.enqueue(meta())
        store.fail(item)

        store.discard(store.failed()[0])

        assertEquals(0, store.failed().size)
        assertEquals(0, store.pending().size)
    }

    @Test
    fun `processor state is absent until written, then round-trips`() {
        val store = MessageStore(temp.root)
        val item = store.enqueue(meta())

        assertNull(item.processorState("telegram"))

        store.writeProcessorState(item, "telegram", JSONObject().put("message_id", 42))

        val state = store.pending()[0].processorState("telegram")
        assertNotNull(state)
        assertEquals(42, state!!.getInt("message_id"))
    }

    @Test
    fun `enqueueOnce admits a stamped message once`() {
        val store = MessageStore(temp.root)
        val first = store.enqueueOnce(meta(text = "hello"))

        assertNotNull(first)
        // Same stamp, same text: the watch resending after a lost Ack.
        assertNull(store.enqueueOnce(meta(text = "hello")))
        // Same stamp, different text: a different message.
        assertNotNull(store.enqueueOnce(meta(text = "other")))
        // The receipt outlives delivery, which is the whole point.
        store.complete(first!!)
        assertNull(store.enqueueOnce(meta(text = "hello")))
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `sweep forgets receipts after a day`() {
        val store = MessageStore(temp.root)
        val stamped = meta().copy(recordedAtMs = 1_700_000_000_000L)
        assertNotNull(store.enqueueOnce(stamped))
        assertNull(store.enqueueOnce(stamped))

        store.sweep()
        assertNull(store.enqueueOnce(stamped)) // still fresh

        store.sweep(now = System.currentTimeMillis() + 25L * 60 * 60 * 1000)
        assertNotNull(store.enqueueOnce(stamped))
    }

    @Test
    fun `the seen folder is not mistaken for a queued item`() {
        val store = MessageStore(temp.root)
        store.enqueueOnce(meta())
        assertEquals(1, store.pending().size)
        assertEquals(0, store.failed().size)
    }

    @Test
    fun `two enqueues in the same millisecond get distinct names`() {
        val store = MessageStore(temp.root)
        val a = store.enqueue(meta(1_700_000_000_000L))
        val b = store.enqueue(meta(1_700_000_000_000L))
        assertTrue(a.name != b.name)
    }
}
