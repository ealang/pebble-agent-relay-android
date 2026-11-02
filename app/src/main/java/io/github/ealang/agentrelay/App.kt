package io.github.ealang.agentrelay

import android.app.Application
import android.util.Log
import io.github.ealang.agentrelay.pebble.PebbleContract
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Layer 1 of the PebbleKit ingress gate (see PebbleIngressService).
        // By default the library auto-accepts any installed app that merely
        // looks like a Pebble app; pinning CoreApp as the one selected app
        // makes the base class reject everyone else. enableAutoSelect is an
        // in-memory var on a process singleton, so it must be switched off on
        // every process start — onCreate always runs before any service bind.
        val picker = DefaultPebbleAndroidAppPicker.getInstance(this)
        picker.enableAutoSelect = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Persisted, but selectApp throws when the package is not
                // currently installed — hence re-tried, guarded, every start.
                if (PebbleContract.COREAPP_PACKAGE in picker.getAllEligibleApps()) {
                    picker.selectApp(PebbleContract.COREAPP_PACKAGE)
                }
            } catch (e: Exception) {
                // With auto-select off and nothing selected the listener
                // rejects everyone: fail closed, not crash.
                Log.w(TAG, "Could not pin CoreApp in the PK2 app picker", e)
            }
        }
    }

    private companion object {
        const val TAG = "agent-relay"
    }
}
