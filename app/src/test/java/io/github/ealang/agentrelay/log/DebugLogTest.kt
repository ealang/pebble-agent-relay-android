package io.github.ealang.agentrelay.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DebugLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun current() = File(temp.root, "debug-log.jsonl")
    private fun previous() = File(temp.root, "debug-log.jsonl.1")

    @Test
    fun `records land newest first in the tail`() {
        val log = DebugLog(temp.root)
        log.record("received", item = "20260913-alpha")
        log.record("sent", detail = "message_id 42", item = "20260913-alpha")

        val tail = log.tail(10)
        assertEquals(2, tail.size)
        assertTrue(tail[0].contains("sent"))
        assertTrue(tail[0].contains("message_id 42"))
        assertTrue(tail[1].contains("received"))
        assertTrue(tail[1].contains("20260913-alpha"))
    }

    @Test
    fun `tail respects its limit`() {
        val log = DebugLog(temp.root)
        repeat(5) { log.record("event-$it") }
        assertEquals(listOf("event-4", "event-3"), log.tail(2).map { it.substringAfterLast("  ") })
    }

    @Test
    fun `rotation keeps exactly one previous generation`() {
        val log = DebugLog(temp.root)
        // Blow past the cap twice; the oldest generation must be dropped.
        val bulk = "x".repeat(4096)
        repeat(150) { log.record("fill", detail = bulk) }

        assertTrue(previous().exists())
        assertTrue(current().length() <= 300 * 1024)
        assertTrue(previous().length() <= 300 * 1024)
        assertFalse(File(temp.root, "debug-log.jsonl.2").exists())
    }

    @Test
    fun `tail spans into the previous generation after rotation`() {
        val log = DebugLog(temp.root)
        // ~3 KB lines against the ~5 KB cap: two per generation, so the
        // current file ends up with fewer lines than the tail asks for.
        val bulk = "x".repeat(3 * 1024)
        repeat(5) { log.record("old-$it", detail = bulk) } // forces rotations
        log.record("fresh")

        val tail = log.tail(3)
        assertEquals(3, tail.size)
        assertTrue(tail[0].contains("fresh"))
    }

    @Test
    fun `clear removes both generations`() {
        val log = DebugLog(temp.root)
        val bulk = "x".repeat(64 * 1024)
        repeat(5) { log.record("fill", detail = bulk) }
        log.clear()

        assertFalse(current().exists())
        assertFalse(previous().exists())
        assertEquals(emptyList<String>(), log.tail(10))

        log.record("after clear")
        assertEquals(1, log.tail(10).size)
    }

    @Test
    fun `a corrupt line comes back raw instead of vanishing`() {
        val log = DebugLog(temp.root)
        log.record("good")
        current().appendText("not json\n")

        val tail = log.tail(10)
        assertEquals(2, tail.size)
        assertEquals("not json", tail[0])
    }
}
