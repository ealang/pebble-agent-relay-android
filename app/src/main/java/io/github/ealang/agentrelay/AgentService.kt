package io.github.ealang.agentrelay

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.ealang.agentrelay.log.DebugLog
import io.github.ealang.agentrelay.pipeline.DrainReport
import io.github.ealang.agentrelay.pipeline.MessageStore
import io.github.ealang.agentrelay.pipeline.Meta
import io.github.ealang.agentrelay.pipeline.PipelineWorker
import io.github.ealang.agentrelay.pipeline.RetryGate
import io.github.ealang.agentrelay.server.WebhookServer
import io.github.ealang.agentrelay.telegram.TelegramDelivery
import io.github.ealang.agentrelay.telegram.TelegramEngine

/**
 * The agent: the Telegram delivery pipeline plus the optional webhook
 * listener. The two have independent reasons to run: the listener is up
 * while the user's toggle ([Config.enabled]) says so, and the pipeline is up
 * while either the listener is or the queue still holds work — a Pebble
 * dictation starts the service just to deliver, and once the queue is empty
 * (and the listener is off) the service stops itself. A foreground service is the only way to
 * keep either alive on a modern Android — a plain background service is
 * killed within minutes.
 *
 * The service is the orchestrator: it builds the components and owns every
 * edge between them; none of them holds a reference to another. All state is
 * main-thread confined except where noted.
 */
class AgentService : Service() {

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

    /**
     * The newest start Android has delivered. Stops quote it so that a start
     * already queued behind the stop wins (see [Service.stopSelf]): a plain
     * stopSelf() would let that start bind the socket and then tear the
     * service down underneath it.
     */
    private var latestStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            // "Stop" turns the listener off; delivery is not the user's to
            // stop. If the queue still holds work the service stays up just
            // long enough to finish it, then stops itself in onDrainFinished.
            Config(this).enabled = false
            debugLog?.record("stopped by user")
            main.removeCallbacks(retry)
            server?.stop()
            server = null
            running = false
            if (queueIsEmpty()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            baseText = DELIVERING_TEXT
            notify(baseText)
            sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
            worker?.wake()
            return START_STICKY
        }

        // A listener restart (port or token changed): drop the socket here
        // and let the normal flow below bind it again with the fresh config.
        if (intent?.action == ACTION_REBIND && server != null) {
            server?.stop()
            server = null
            running = false
            debugLog?.record("listener restarting to apply settings")
        }

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
            if (Config(this).enabled) {
                attemptBind()
            } else {
                // A delivery-only run: the Pebble ingress (or boot, with
                // messages stranded on disk) started us just to drain.
                baseText = DELIVERING_TEXT
                notify(baseText)
                sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
            }
            worker?.wake()
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
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        Log.i(TAG, "Stopped")
        super.onDestroy()
    }

    // ------------------------------ pipeline ------------------------------

    private fun startPipeline() {
        if (worker != null) return

        val store = MessageStore(filesDir).also { store = it }
        val log = DebugLog(filesDir).also { debugLog = it }
        reportProcessExits(log)
        gate = RetryGate(main) { worker?.wake() }

        val workerLog = object : PipelineWorker.PipelineLog {
            override fun record(item: String, event: String, detail: String?) {
                log.record(event, detail, item)
            }
        }
        val delivery = TelegramDelivery(this, store)

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

    /**
     * Android's own record of how this process last ended, copied into the
     * activity log once per exit. The service cannot log its own death, so
     * this is the only account of one. Exits of an idle, cached process are
     * routine housekeeping and skipped; anything that ended the process
     * while it was running the service or the screen gets a line.
     */
    private fun reportProcessExits(log: DebugLog) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val config = Config(this)
        val exits = try {
            getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 10)
        } catch (e: Exception) {
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        exits.filter { it.timestamp > config.lastReportedExit }
            .filter { it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE }
            .sortedBy { it.timestamp }
            .forEach { exit ->
                val detail = listOfNotNull(
                    "at ${stamp.format(Date(exit.timestamp))}",
                    exit.description?.takeIf { it.isNotBlank() },
                ).joinToString(" — ")
                log.record("app ended by ${exitReason(exit.reason)}", detail)
            }
        exits.maxOfOrNull { it.timestamp }?.let { config.lastReportedExit = it }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "a crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "a native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_SIGNALED -> "a signal"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "the user (swiped away or force stopped)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "the user profile stopping"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "an app update"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "a package state change"
        ApplicationExitInfo.REASON_FREEZER -> "the freezer"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency dying"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "a start-up failure"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exiting itself"
        else -> "Android (reason $reason)"
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
            Config(this).let { it.sentTotal += report.completed }
            gate?.reset()
        }
        if (report.blockedOn != null) gate?.arm()
        // A delivery-only run has exactly one job; when the queue is empty
        // (delivered, confirmed, or parked in failed/) the service is done.
        // A racing enqueue is safe: the ingress always calls start() after
        // enqueueing, and that intent recreates the service.
        if (!Config(this).enabled && queueIsEmpty()) {
            stopSelf(latestStartId)
            return
        }
        // Success is silent; the notification only ever gains a failure
        // notice, and re-rendering also clears one whose failed/ emptied.
        notify(baseText)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    /**
     * Empty means nothing pending *and* nothing handed to Telegram still
     * awaiting confirmation — unreconciled items stay in pending() until the
     * engine confirms them, so this cannot stop the service out from under
     * an in-flight send.
     */
    private fun queueIsEmpty(): Boolean = store?.pending()?.isEmpty() ?: true

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
        // The toggle may have gone off between a retry being scheduled and
        // firing; a disabled listener must stay down.
        if (!Config(this).enabled) return

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
            "Agent Relay",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Shown while the agent is listening or delivering."
        manager.createNotificationChannel(channel)
        // The channel used to be registered under the webhook-era id; drop it
        // so upgraded installs don't show a stale second entry in settings.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Agent Relay")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "agent-relay"
        private const val CHANNEL_ID = "agent"
        private const val LEGACY_CHANNEL_ID = "webhook-server"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "io.github.ealang.agentrelay.STOP"

        /** Drop and re-bind the listener socket to pick up a new port/token. */
        const val ACTION_REBIND = "io.github.ealang.agentrelay.REBIND"

        /** The notification text for a run that exists only to drain the queue. */
        private const val DELIVERING_TEXT = "Delivering queued messages…"

        /** Broadcast whenever anything the UI shows may have changed. */
        const val ACTION_STATE_CHANGED = "io.github.ealang.agentrelay.STATE_CHANGED"

        private const val INITIAL_RETRY_MS = 5_000L

        /** A capped once-a-minute retry costs nothing and never wakes a sleeping phone. */
        private const val MAX_RETRY_MS = 60_000L

        /** NanoHTTPD's default read timeout; long enough for a keep-alive client to idle. */
        private const val SOCKET_READ_TIMEOUT_MS = 5000

        /** How long after a start request the service counts as "on its way". */
        private const val START_GRACE_MS = 10_000L

        /** The listener is bound and accepting. */
        @Volatile
        var running: Boolean = false
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
            startRequestedAt = System.currentTimeMillis()
            try {
                context.startForegroundService(Intent(context, AgentService::class.java))
            } catch (e: Exception) {
                val reason = "${e::class.java.simpleName}: ${e.message ?: "no detail"}"
                Log.w(TAG, "Android refused to start the service", e)
                Config(context).lastStartFailure = "start refused — $reason"
            }
        }

        @Volatile
        private var startRequestedAt = 0L

        /**
         * The service is wanted — the toggle is on, or messages are on disk
         * — but no instance is up. Called when the app comes to the
         * foreground: whatever took the service down while the screen was
         * away (a low-memory kill Android never followed with its sticky
         * restart, 2026-10-22), the user looking is the moment to bring it
         * back. A no-op when it is up, on its way — a start is asynchronous,
         * and one just issued must not be counted as down — or unwanted.
         */
        fun ensureUp(context: Context) {
            if (activeWorker != null) return
            if (System.currentTimeMillis() - startRequestedAt < START_GRACE_MS) return
            val wanted = Config(context).enabled ||
                MessageStore(context.filesDir).pending().isNotEmpty()
            if (!wanted) return
            DebugLog(context.filesDir).record("service found down", "on app open")
            start(context)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AgentService::class.java).setAction(ACTION_STOP),
            )
        }

        /** Applies a changed port/token to a live listener without a stop/start. */
        fun rebind(context: Context) {
            context.startService(
                Intent(context, AgentService::class.java).setAction(ACTION_REBIND),
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
            val log = activeLog ?: DebugLog(context.filesDir)
            // A stamped message is admitted once: the watch resends the same
            // stamp when it misses our Ack, and the first copy may already
            // be delivered. Ack the repeat all the same — it *was* accepted.
            val item = if (meta.recordedAtMs != 0L) store.enqueueOnce(meta) else store.enqueue(meta)
            if (item == null) {
                log.record("duplicate", "${meta.client}, recorded at ${meta.recordedAtMs / 1000}")
            } else {
                log.record("received", meta.client, item.name)
                activeWorker?.wake()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Enqueue from the Pebble ingress failed", e)
            false
        }

        /** Pending and failed counts for the UI, or null when stopped. */
        fun pipelineSnapshot(): Pair<Int, Int>? {
            val store = activeStore ?: return null
            return store.pending().size to store.failed().size
        }

        /** The debug log's recent lines for the UI, newest first. */
        fun logTail(context: Context, limit: Int): List<String> =
            (activeLog ?: DebugLog(context.filesDir)).tail(limit)

        fun retryAllFailed(context: Context) {
            onPipeline(context) { store -> store.failed().forEach(store::retry) }
            // With the listener off there is no service to pick the items up:
            // the ingress starts one after each enqueue, and a retry must too.
            // onPipeline ran synchronously on the direct path, so the items
            // are on disk before the service comes up.
            if (activeWorker == null) start(context)
        }

        fun discardAllFailed(context: Context) {
            onPipeline(context) { store -> store.failed().forEach(store::discard) }
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
