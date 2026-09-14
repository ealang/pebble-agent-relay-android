package io.github.ealang.pebbletelegramwebhook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the server back after a reboot or an app update.
 *
 * BOOT_COMPLETED only reaches an app that the user has launched at least once
 * since install, and OEM battery managers can suppress it — so this is a best
 * effort, and the UI is the authority on whether the server is actually up.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("pebble-telegram-webhook", "Received ${intent.action}")

        if (Config(context).enabled) {
            WebhookService.start(context)
        }
    }
}
