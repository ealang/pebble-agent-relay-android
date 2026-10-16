package io.github.ealang.operator.pipeline

import android.os.Handler

/**
 * The debounced retry: one pending retry at a time, backoff owned here and
 * nowhere else. Armed by the orchestrator when a drain reports a blocked
 * head; immediate wakes (a new item, network back, a Telegram event) bypass
 * it entirely — if the head is still blocked, the next report just re-arms it.
 *
 * Main-thread confined, like the rest of the orchestrator's state.
 */
class RetryGate(private val handler: Handler, private val onFire: () -> Unit) {

    private var armed = false
    private var delayMs = INITIAL_MS

    /** Called on a Transient report. Arming an armed gate is a no-op. */
    fun arm() {
        if (armed) return
        armed = true
        handler.postDelayed(fire, delayMs)
        delayMs = (delayMs * 2).coerceAtMost(MAX_MS)
    }

    /** Called on any success: the world works again, so back to 1 minute. */
    fun reset() {
        delayMs = INITIAL_MS
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        armed = false
    }

    private val fire = Runnable {
        armed = false
        onFire()
    }

    companion object {
        private const val INITIAL_MS = 60_000L
        private const val MAX_MS = 1_800_000L
    }
}
