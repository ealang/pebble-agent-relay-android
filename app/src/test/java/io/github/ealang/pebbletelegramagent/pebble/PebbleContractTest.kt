package io.github.ealang.pebbletelegramagent.pebble

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
    fun `both watchapp builds are accepted`() {
        assertEquals(
            setOf(
                UUID.fromString("c0b48501-a0da-4fbb-9013-a4ea68dc6165"),
                UUID.fromString("641826b9-9512-48d3-8354-332fc90568bd"),
            ),
            PebbleContract.WATCHAPP_UUIDS,
        )
    }

    @Test
    fun `the pinned cert digest decoded to 32 bytes`() {
        assertEquals(32, PebbleContract.COREAPP_CERT_SHA256.size)
    }
}
