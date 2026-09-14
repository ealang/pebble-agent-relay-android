package io.github.ealang.pebbletelegramwebhook.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaTest {

    @Test
    fun `round-trips through JSON`() {
        val original = Meta(
            text = "pick up milk",
            recordedAtMs = 1_700_000_000_000L,
            client = "ring",
            receivedAtMs = 1_700_000_005_000L,
        )

        val back = Meta.fromJson(original.toJson())

        assertEquals(original, back)
    }

    @Test
    fun `null client survives the round trip as null, not the string "null"`() {
        val original = Meta(
            text = "hello",
            recordedAtMs = 0,
            client = null,
            receivedAtMs = 1_700_000_000_000L,
        )

        val back = Meta.fromJson(original.toJson())

        assertNull(back.client)
        assertEquals(original, back)
    }
}
