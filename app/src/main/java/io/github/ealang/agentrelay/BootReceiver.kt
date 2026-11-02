package io.github.ealang.agentrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.ealang.agentrelay.pipeline.MessageStore

/**
 * Brings the service back after a reboot or an app update — because the
 * listener should be up (the user's toggle), or because a reboot stranded
 * undelivered messages on disk and a delivery-only run should finish them.
 *
 * BOOT_COMPLETED only reaches an app that the user has launched at least once
 * since install, and OEM battery managers can suppress it — so this is a best
 * effort, and the UI is the authority on whether the server is actually up.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("agent-relay", "Received ${intent.action}")

        if (Config(context).enabled ||
            MessageStore(context.filesDir).pending().isNotEmpty()
        ) {
            AgentService.start(context)
        }
    }
}
