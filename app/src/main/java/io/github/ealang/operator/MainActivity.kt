package io.github.ealang.operator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.ealang.operator.pipeline.MessageStore
import io.github.ealang.operator.telegram.TelegramEngine
import io.github.ealang.operator.ui.AppTheme
import io.github.ealang.operator.ui.MainScreen
import io.github.ealang.operator.ui.ScreenActions
import io.github.ealang.operator.ui.ScreenState

class MainActivity : ComponentActivity() {

    /**
     * The whole screen re-reads its facts when this changes. Cheaper to
     * reason about than per-fact observation, and everything it reads is
     * fast local state.
     */
    private var tick by mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())

    private val stateChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = bump()
    }

    // Granted or not, the server starts: since Android 13 a foreground
    // service runs fine without notification permission, its notification
    // just never shows in the shade.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startServer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15 imposes edge-to-edge on anything targeting SDK 35 and
        // offers no opt-out; asking for it here just makes every version
        // behave the way the newest one will. The layout handles the insets.
        enableEdgeToEdge()

        // Bring the Telegram client up for the auth flow even when the
        // service isn't running yet — but only once the Keystore has been
        // warmed off the UI thread, because starting the engine reads the
        // database key and the first Keystore contact can take seconds.
        SecretStore.warm(this) {
            TelegramEngine.ensureStarted(this)
            bump()
        }

        val actions = ScreenActions(
            toggleServer = ::toggleServer,
            savePort = ::savePort,
            regenerateToken = ::regenerateToken,
            submitAuthInput = { TelegramEngine.submitAuthInput(it) },
            logOut = { TelegramEngine.logOut() },
            restartLogin = { TelegramEngine.restartLogin() },
            searchChats = { query, onResult -> TelegramEngine.searchChats(query, onResult) },
            pickChat = { hit ->
                Config(this).apply {
                    telegramChatId = hit.id
                    telegramChatTitle = hit.title
                }
                toast("Sending to ${hit.title}")
                bump()
            },
            saveSignature = ::saveSignature,
            retryAllFailed = {
                AgentService.retryAllFailed(this)
                bumpSoon()
            },
            discardAllFailed = {
                AgentService.discardAllFailed(this)
                bumpSoon()
            },
            resetSentTotal = {
                Config(this).sentTotal = 0
                toast("Sent counter reset")
                bump()
            },
        )

        setContent {
            AppTheme {
                MainScreen(
                    state = snapshot(tick),
                    actions = actions,
                    onPoll = ::bump,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Whatever took the service down while the screen was away, the
        // toggle (or a queue left mid-drain) says whether it belongs up.
        Watchdog.check(this, "app open")
        val filter = IntentFilter(AgentService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateChanged, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateChanged, filter)
        }
        TelegramEngine.onChanged = ::bump
        bump()
    }

    override fun onStop() {
        TelegramEngine.onChanged = {}
        unregisterReceiver(stateChanged)
        super.onStop()
    }

    // ------------------------------ snapshot ------------------------------

    /**
     * Every fact the screen renders, read fresh. [refreshTick] is not used
     * for its value: passing it means the read happens during composition,
     * which is what makes Compose recompose when [bump] moves it.
     */
    private fun snapshot(refreshTick: Int): ScreenState {
        val config = Config(this)
        // When the service is down, read the queue directly — the failed
        // items are still on disk and still reviewable.
        val (pending, failed) = AgentService.pipelineSnapshot()
            ?: MessageStore(filesDir).let { it.pending().size to it.failed().size }
        return ScreenState(
            serverWanted = config.enabled,
            serverRunning = AgentService.running,
            port = config.port,
            // Before the Keystore is warm, reading the token would do the
            // expensive first contact right here on the UI thread. Render
            // blank; the warm callback bumps and the real value appears.
            token = if (SecretStore.ready) config.token else "",
            lastStartFailure = config.lastStartFailure,
            bindFailed = AgentService.bindFailed,
            sentTotal = config.sentTotal,
            pendingCount = pending,
            failedCount = failed,
            logTail = AgentService.logTail(this, LOG_DIALOG_LINES),
            telegramStage = TelegramEngine.stage,
            telegramStatus = TelegramEngine.statusLine(),
            telegramHealth = TelegramEngine.health,
            telegramAccount = TelegramEngine.account,
            telegramConfigured = TelegramEngine.configured,
            telegramChatId = config.telegramChatId,
            telegramChatTitle = config.telegramChatTitle,
            telegramSignature = config.telegramSignature,
        )
    }

    private fun bump() {
        tick += 1
    }

    /** For changes that land asynchronously — worker actions, service stops. */
    private fun bumpSoon() {
        main.postDelayed(::bump, 400)
    }

    // ------------------------------ actions ------------------------------

    /**
     * Notification permission is asked for here, the first time the server
     * is switched on, and nowhere else: the notification belongs to the
     * webhook service, and someone using only the Pebble app never needs
     * it.
     */
    private fun toggleServer(wantOn: Boolean) {
        Config(this).enabled = wantOn
        if (!wantOn) {
            AgentService.stop(this)
            bumpSoon()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startServer()
        }
    }

    private fun startServer() {
        AgentService.start(this)
        bumpSoon()
    }

    private fun savePort(text: String) {
        val port = text.trim().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            toast("Port must be a number between 1024 and 65535.")
            bump() // re-seed the field from the saved value
            return
        }
        Config(this).port = port
        toast("Port saved.")
        bounceServerIfRunning()
        bump()
    }

    private fun regenerateToken() {
        Config(this).regenerateToken()
        toast("New token generated.")
        bounceServerIfRunning()
        bump()
    }

    /** The server reads port and token at bind time; a rebind applies them. */
    private fun bounceServerIfRunning() {
        if (!Config(this).enabled) return
        AgentService.rebind(this)
        bumpSoon()
    }

    private fun saveSignature(text: String) {
        Config(this).telegramSignature = text
        toast("Signature saved.")
        bump()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val LOG_DIALOG_LINES = 100
    }
}
