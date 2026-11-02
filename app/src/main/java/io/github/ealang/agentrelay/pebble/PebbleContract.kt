package io.github.ealang.agentrelay.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import java.util.UUID

/**
 * The contract with the watch side: who may talk to us and what the words
 * mean. Values here must match the Agent Relay watchapp build.
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

    /**
     * When the dictation was captured, seconds since the epoch by the watch
     * clock, fourth in the list. Every attempt to send the same transcript
     * carries the same value — the companion's handle for recognising a
     * repeat after a lost Ack.
     */
    val KEY_RECORDED_AT: UInt = 10003u

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

/**
 * The capture time as epoch milliseconds, or 0 when the watch sent none
 * (an older watchapp build) — 0 is what [Meta.recordedAtMs] means by absent.
 * The watch writes a uint32; a signed 32-bit reading is tolerated too.
 */
internal fun extractRecordedAtMs(data: Map<UInt, PebbleDictionaryItem>): Long =
    when (val item = data[PebbleContract.KEY_RECORDED_AT]) {
        is PebbleDictionaryItem.UInt32 -> item.value.toLong() * 1000
        is PebbleDictionaryItem.Int32 -> item.value.toLong() * 1000
        else -> 0
    }
