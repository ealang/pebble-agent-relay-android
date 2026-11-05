package io.github.ealang.agentrelay.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class PebbleContractTest {

    @Test
    fun `transcript is read from the TRANSCRIPT key and trimmed`() {
        val data = mapOf<UInt, PebbleDictionaryItem>(
            PebbleContract.KEY_TRANSCRIPT to PebbleDictionaryItem.Text("  hello world "),
        )
        assertEquals("hello world", extractTranscript(data))
    }

    @Test
    fun `missing, blank, or wrongly typed transcripts are rejected`() {
        assertNull(extractTranscript(emptyMap()))
        assertNull(
            extractTranscript(
                mapOf(PebbleContract.KEY_TRANSCRIPT to PebbleDictionaryItem.Text("   ")),
            ),
        )
        assertNull(
            extractTranscript(
                mapOf(PebbleContract.KEY_TRANSCRIPT to PebbleDictionaryItem.Int32(7)),
            ),
        )
        // Right value under the wrong key is still a miss.
        assertNull(
            extractTranscript(
                mapOf(10001u to PebbleDictionaryItem.Text("hello")),
            ),
        )
    }

    @Test
    fun `recorded-at is read as a uint32 of seconds, absent is zero`() {
        assertEquals(
            1_760_000_000_000L,
            extractRecordedAtMs(
                mapOf(PebbleContract.KEY_RECORDED_AT to PebbleDictionaryItem.UInt32(1_760_000_000L)),
            ),
        )
        assertEquals(0L, extractRecordedAtMs(emptyMap()))
        assertEquals(
            0L,
            extractRecordedAtMs(
                mapOf(PebbleContract.KEY_RECORDED_AT to PebbleDictionaryItem.Text("1760000000")),
            ),
        )
    }

    @Test
    fun `the published watchapp is accepted`() {
        assertEquals(
            UUID.fromString("c0b48501-a0da-4fbb-9013-a4ea68dc6165"),
            PebbleContract.WATCHAPP_UUID,
        )
    }

    @Test
    fun `the pinned cert digest decoded to 32 bytes`() {
        assertEquals(32, PebbleContract.COREAPP_CERT_SHA256.size)
    }
}
