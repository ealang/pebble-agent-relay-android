package io.github.ealang.pebbletelegramagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.ealang.pebbletelegramagent.log.DebugLog
import io.github.ealang.pebbletelegramagent.pipeline.DrainReport
import io.github.ealang.pebbletelegramagent.pipeline.MessageStore
import io.github.ealang.pebbletelegramagent.pipeline.Meta
import io.github.ealang.pebbletelegramagent.pipeline.PipelineWorker
import io.github.ealang.pebbletelegramagent.pipeline.RetryGate
import io.github.ealang.pebbletelegramagent.server.WebhookServer
import io.github.ealang.pebbletelegramagent.telegram.TelegramDelivery
import io.github.ealang.pebbletelegramagent.telegram.TelegramEngine

/**
 * Holds the webhook listener and the delivery pipeline for as long as the
 * user wants them up. A foreground service is the only way to keep a
 * listening socket alive on a modern Android — a plain background service is
 * killed within minutes, and the socket dies with it.
 *
 * The service is the orchestrator: it builds the components and owns every
 * edge between them; none of them holds a reference to another. All state is
 * main-thread confined except where noted.
 */
class WebhookService : Service() {

    private var server: WebhookServer? = null
    private var store: MessageStore? = null
    private var debugLog: DebugLog? = null
    private var worker: PipelineWorker? = null
    private var gate: RetryGate? = null

    private val main = Handler(Looper.getMainLooper())
    private val retry = Runnable { attemptBind() }
    private var retryDelayMs = INITIAL_RETRY_MS

    private val connectivity = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            // Connectivity is the real retry signal; the gate's timer is the
            // fallback. Safe from this binder thread: wake() is thread-safe.
            worker?.wake()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Config(this).enabled = false
            debugLog?.record("stopped by user")
            stopSelf()
            return START_NOT_STICKY
        }

        Config(this).enabled = true

        // Started before the first bind attempt: the five-second foreground
        // deadline must be met regardless of what binding runs into.
        if (!goForeground()) {
            // Being refused foreground status is survivable; crashing is not,
            // because START_STICKY would bring us straight back to the same
            // refusal — the loop the system reports as the app repeatedly
            // stopping. Stop cleanly and stay stopped.
            stopSelf()
            return START_NOT_STICKY
        }

        Config(this).lastStartFailure = null
        retryDelayMs = INITIAL_RETRY_MS
        startPipeline()
        // Everything past the pipeline needs a secret — the engine reads
        // the database key, binding reads the token — so it waits for the
        // Keystore warm-up instead of doing that first contact on the main
        // thread. The callback lands on the main thread, where all of this
        // state lives; re-entry is safe because ensureStarted and
        // attemptBind are both idempotent.
        SecretStore.warm(this) {
            // The service may have been stopped while the Keystore was
            // warming; a destroyed service must not bind a socket.
            if (worker == null) return@warm
            // Started here (not only in the activity) so the connection
            // lives when the service came up on its own — after a reboot,
            // with no screen ever opened.
            TelegramEngine.ensureStarted(this)
            attemptBind()
        }

        // START_STICKY: if Android reclaims us under memory pressure, come back.
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(retry)
        server?.stop()
        server = null
        stopPipeline()
        running = false
        bindFailed = false
        sentSinceUp = 0
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        Log.i(TAG, "Stopped")
        super.onDestroy()
    }

    // ------------------------------ pipeline ------------------------------

    private fun startPipeline() {
        if (worker != null) return

        val store = MessageStore(filesDir).also { store = it }
        val log = DebugLog(filesDir).also { debugLog = it }
        gate = RetryGate(main) { worker?.wake() }

        val workerLog = object : PipelineWorker.PipelineLog {
            override fun record(item: String, event: String, detail: String?) {
                log.record(event, detail, item)
            }
        }
        val delivery = TelegramDelivery(this, store, workerLog)

        worker = PipelineWorker(
            store = store,
            log = workerLog,
            telegramSender = delivery,
            onDrainFinished = { report -> main.post { onDrainFinished(report) } },
        ).also {
            it.start()
            it.wake() // drain anything left from before the restart
        }

        // Every engine event — a delivery confirmed, a send abandoned, the
        // connection back — is the same parameter-free wake; the drain
        // discovers what changed by reconciling.
        TelegramEngine.onDeliveryEvent = { worker?.wake() }

        getSystemService(android.net.ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(connectivity)

        activeStore = store
        activeWorker = worker
        activeLog = log
    }

    private fun stopPipeline() {
        try {
            getSystemService(android.net.ConnectivityManager::class.java)
                .unregisterNetworkCallback(connectivity)
        } catch (e: Exception) {
            // Never registered; nothing to do.
        }
        TelegramEngine.onDeliveryEvent = {}
        gate?.cancel()
        worker?.quit()
        activeWorker = null
        activeStore = null
        activeLog = null
        worker = null
        gate = null
        store = null
        debugLog = null
    }

    private fun onDrainFinished(report: DrainReport) {
        if (report.completed > 0) {
            sentSinceUp += report.completed
            gate?.reset()
        }
        if (report.blockedOn != null) gate?.arm()
        // Success is silent; the notification only ever gains a failure
        // notice, and re-rendering also clears one whose failed/ emptied.
        notify(baseText)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    // ------------------------------ listener ------------------------------

    /** What the notification says apart from failures. */
    private var baseText = "Starting…"

    /**
     * Binds now if possible, otherwise leaves a retry scheduled. Loopback
     * always exists, so the only way this fails is another process squatting
     * on the port — worth retrying, not worth dying over.
     */
    private fun attemptBind() {
        main.removeCallbacks(retry)
        if (server != null) return

        val config = Config(this)
        val port = config.port
        val instance = WebhookServer(
            port = port,
            token = config.token,
            onDied = ::onServerDied,
            log = { line -> debugLog?.record(line) },
            // Runs on NanoHTTPD's request thread. enqueue is thread-safe (it
            // only ever creates), wake is thread-safe by design; a throw here
            // becomes the 500 that makes the sender retry.
            enqueue = { meta ->
                val item = store?.enqueue(meta)
                    ?: throw IllegalStateException("pipeline not started")
                debugLog?.record("received", meta.client, item.name)
                worker?.wake()
            },
        )
        try {
            // daemon=false: the socket should outlive the thread that made it.
            instance.start(SOCKET_READ_TIMEOUT_MS, false)
            server = instance
            running = true
            bindFailed = false
            retryDelayMs = INITIAL_RETRY_MS
            debugLog?.record("listening on ${WebhookServer.BIND}:$port")
            baseText = "Listening on ${WebhookServer.BIND}:$port"
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind port $port; will retry", e)
            debugLog?.record(
                "bind ${WebhookServer.BIND}:$port failed",
                e.message ?: e::class.java.simpleName,
            )
            baseText = "Waiting to bind ${WebhookServer.BIND}:$port"
            bindFailed = true
            main.postDelayed(retry, retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
        notify(baseText)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    /**
     * Called from the listener thread when the socket has failed repeatedly
     * and shut itself. Tears the server down and goes back to binding.
     */
    private fun onServerDied() {
        main.post {
            Log.w(TAG, "Server died underneath us; rebinding")
            debugLog?.record("server restarting after socket death")
            server?.stop()
            server = null
            running = false
            attemptBind()
        }
    }

    // ----------------------------- foreground -----------------------------

    /**
     * Takes foreground status. `specialUse` is the honest type for "keeps a
     * local socket open"; it carries no while-in-use condition, so a start
     * from [BootReceiver]'s background broadcast is granted the same as one
     * from the activity. Returns false only if even that was refused.
     */
    private fun goForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            // Types are not enforced before 34; asking for one is what breaks.
            startForeground(NOTIFICATION_ID, notification("Starting…"))
        }
        true
    } catch (e: Exception) {
        val reason = "${e::class.java.simpleName}: ${e.message ?: "no detail"}"
        Log.w(TAG, "Refused foreground status; not retrying", e)
        Config(this).lastStartFailure = "foreground start refused — $reason"
        false
    }

    private fun notify(text: String) {
        // The one place a pipeline failure is announced: a suffix on the
        // notification the service must show anyway. Never a separate,
        // buzzing notification.
        val failed = store?.failed()?.size ?: 0
        val full = if (failed > 0) "$text — $failed failed, open to review" else text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(full))
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_LOW: the notification is a requirement of running in the
        // foreground, not something the user needs to be alerted about.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Webhook server",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Shown while the webhook server is running."
        manager.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WebhookService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pebble Telegram Agent")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "pebble-telegram-agent"
        private const val CHANNEL_ID = "webhook-server"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "io.github.ealang.pebbletelegramagent.STOP"

        /** Broadcast whenever anything the UI shows may have changed. */
        const val ACTION_STATE_CHANGED = "io.github.ealang.pebbletelegramagent.STATE_CHANGED"

        private const val INITIAL_RETRY_MS = 5_000L

        /** A capped once-a-minute retry costs nothing and never wakes a sleeping phone. */
        private const val MAX_RETRY_MS = 60_000L

        /** NanoHTTPD's default read timeout; long enough for a keep-alive client to idle. */
        private const val SOCKET_READ_TIMEOUT_MS = 5000

        /** The listener is bound and accepting. */
        @Volatile
        var running: Boolean = false
            private set

        /** Messages confirmed done since the service came up — the UI counter. */
        @Volatile
        var sentSinceUp: Int = 0
            private set

        /**
         * The last bind attempt failed and a retry is pending. Distinct from
         * [Config.lastStartFailure], which records the service being refused
         * outright: a bind failure keeps the service alive and retrying, so
         * it lives here with the process rather than in prefs.
         */
        @Volatile
        var bindFailed: Boolean = false
            private set

        /**
         * The running service's pipeline, for the activity's counters and
         * Retry/Discard buttons. Set on the main thread by
         * [startPipeline]/[stopPipeline]; read from the UI, the same thread.
         * Routing UI actions through the worker keeps `failed/` single-writer.
         */
        @Volatile
        private var activeWorker: PipelineWorker? = null

        @Volatile
        private var activeStore: MessageStore? = null

        @Volatile
        private var activeLog: DebugLog? = null

        /**
         * Asking for the service is allowed to fail. Since Android 12 a
         * background app may be refused a foreground-service start outright,
         * and the refusal is an exception thrown at the caller — so an
         * unguarded call here takes down whatever was making it, which
         * includes [BootReceiver] running on a broadcast the app cannot
         * decline. The reason is kept where it can outlive the process.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WebhookService::class.java))
            } catch (e: Exception) {
                val reason = "${e::class.java.simpleName}: ${e.message ?: "no detail"}"
                Log.w(TAG, "Android refused to start the service", e)
                Config(context).lastStartFailure = "start refused — $reason"
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WebhookService::class.java).setAction(ACTION_STOP),
            )
        }

        /**
         * Durable enqueue from the PebbleKit ingress — the second front door
         * into the same pipeline the webhook feeds. Safe whether or not the
         * service is up: enqueue only ever creates files, so a fresh store
         * cannot collide with the running worker (the same reasoning that
         * lets the webhook enqueue from NanoHTTPD's thread). Returns true
         * once the item is on disk — the caller's Ack must mean that much.
         */
        fun enqueueExternal(context: Context, meta: Meta): Boolean = try {
            val store = activeStore ?: MessageStore(context.filesDir)
            val item = store.enqueue(meta)
            (activeLog ?: DebugLog(context.filesDir)).record("received", meta.client, item.name)
            activeWorker?.wake()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Enqueue from the Pebble ingress failed", e)
            false
        }

        /** Queue depth and failed items for the UI, or null when stopped. */
        fun pipelineSnapshot(): Pair<Int, List<MessageStore.Item>>? {
            val store = activeStore ?: return null
            return store.pending().size to store.failed()
        }

        /** The debug log's recent lines for the UI, newest first. */
        fun logTail(context: Context, limit: Int): List<String> =
            (activeLog ?: DebugLog(context.filesDir)).tail(limit)

        fun retryFailed(context: Context, name: String) {
            onPipeline(context) { store -> store.failed().find { it.name == name }?.let(store::retry) }
        }

        fun discardFailed(context: Context, name: String) {
            onPipeline(context) { store -> store.failed().find { it.name == name }?.let(store::discard) }
        }

        /**
         * Runs the action on the worker thread when the service is up, else
         * directly on a fresh store — safe, because no worker means no other
         * writer exists.
         */
        private fun onPipeline(context: Context, action: (MessageStore) -> Unit) {
            val worker = activeWorker
            val store = activeStore
            if (worker != null && store != null) {
                worker.post { action(store) }
                worker.wake()
            } else {
                action(MessageStore(context.filesDir))
            }
        }
    }
}
