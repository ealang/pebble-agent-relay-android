package io.github.ealang.pebbletelegramwebhook.server

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * A ServerSocket that refuses to let NanoHTTPD spin.
 *
 * NanoHTTPD's listener loop catches every accept() failure at an invisible log
 * level and retries with no pause. A socket that starts failing persistently
 * turns that loop into a core pegged at 100% for as long as the phone is
 * awake — a real, observed battery killer.
 *
 * accept() here paces each failure and, past a run of consecutive ones, closes
 * the socket. NanoHTTPD's loop exits on isClosed(), and [onDied] tells the
 * service to tear down and rebind rather than leave a dead server standing.
 */
internal class ResilientServerSocket(
    private val onDied: () -> Unit,
    /** One line per noteworthy incident, for the app-wide debug log. */
    private val onEvent: (String) -> Unit,
) : ServerSocket() {

    companion object {
        private const val TAG = "pebble-telegram-webhook"

        /** Pause per failed accept, so a broken socket costs 4 wakeups a second. */
        private const val RETRY_PAUSE_MS = 250L

        /** ~5 seconds of unbroken failure before the socket is declared dead. */
        private const val FAILURE_LIMIT = 20
    }

    private var consecutiveFailures = 0

    init {
        // The service rebinds the same port moments after a failure teardown;
        // without this a lingering connection can hold the port hostage.
        reuseAddress = true
    }

    override fun accept(): Socket {
        try {
            val socket = super.accept()
            consecutiveFailures = 0
            return socket
        } catch (e: IOException) {
            // Closed is the normal shutdown path: stop() closes the socket to
            // break accept(). Not a failure, and not ours to count.
            if (isClosed) throw e

            consecutiveFailures++
            Log.w(TAG, "accept() failed ($consecutiveFailures in a row)", e)
            if (consecutiveFailures == 1) {
                // The first of a streak carries the interesting error; logging
                // all twenty would flood the log with one incident.
                onEvent("accept() failing: ${e.message ?: e::class.java.simpleName}")
            }

            if (consecutiveFailures >= FAILURE_LIMIT) {
                Log.e(TAG, "Listening socket looks dead; closing it for a rebind")
                onEvent("socket dead after $FAILURE_LIMIT failures in a row")
                try {
                    close()
                } catch (closing: IOException) {
                    // The loop only needs isClosed() to flip, which it has.
                }
                onDied()
            } else {
                try {
                    Thread.sleep(RETRY_PAUSE_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            throw e
        }
    }
}
