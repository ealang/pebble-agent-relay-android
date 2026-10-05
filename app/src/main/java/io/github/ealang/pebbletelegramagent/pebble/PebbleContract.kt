package io.github.ealang.pebbletelegramagent.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import java.util.UUID

/**
 * The contract with the watch side: who may talk to us and what the words
 * mean. Values here must match the watchapp build (pebble-voice-hook).
 */
object PebbleContract {

    /**
     * The watchapp UUIDs we accept (each build's package.json `pebble.uuid`).
     * Messages from any other watchapp are Nacked.
     */
    val WATCHAPP_UUIDS: Set<UUID> = setOf(
        UUID.fromString("c0b48501-a0da-4fbb-9013-a4ea68dc6165"),
        UUID.fromString("641826b9-9512-48d3-8354-332fc90568bd"),
    )

    /** The only phone app allowed to deliver watch messages to us. */
    const val COREAPP_PACKAGE = "coredevices.coreapp"

    /** SHA-256 of CoreApp's signing certificate. */
    val COREAPP_CERT_SHA256: ByteArray = hexToBytes(
        "e16246184f6fc8f7a3f27314a43c300854556d11acf5956008a4df4ffe61c39d",
    )

    /**
     * The transcript entry in the AppMessage dictionary. The Pebble SDK
     * auto-assigns `messageKeys` from package.json sequential values starting
     * at 10000; TRANSCRIPT is first in the list. Verify against the generated
     * message_keys header when the watchapp is rebuilt.
     */
    val KEY_TRANSCRIPT: UInt = 10000u

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}

/**
 * The transcript from a watch dictionary, or null when absent, wrongly
 * typed, or blank — anything but a non-empty string is not a dictation
 * worth forwarding.
 */
internal fun extractTranscript(data: Map<UInt, PebbleDictionaryItem>): String? =
    (data[PebbleContract.KEY_TRANSCRIPT] as? PebbleDictionaryItem.Text)
        ?.value?.trim()?.ifEmpty { null }
