package io.github.ealang.operator

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.ealang.operator.log.DebugLog
import io.github.ealang.operator.pipeline.MessageStore
import java.util.concurrent.TimeUnit

/**
 * Brings the service back when Android has taken it down and not restarted
 * it. START_STICKY is supposed to cover that, but a low-memory kill on
 * 2026-10-22 was followed by no restart at all, and the toggle sat on
 * "Starting…" until the app was opened. So the toggle is re-checked from two
 * more places: a periodic job (the floor Android allows is fifteen minutes)
 * and every time the app comes to the foreground.
 *
 * The job exists only while there is something to watch — the toggle is on,
 * or the queue holds messages — and is cancelled once neither holds, so the
 * webhook-off, nothing-pending state costs nothing.
 *
 * Every catch is logged and, for now, announced on its own notification
 * channel, so that the mechanism can be seen working with and without the
 * phone's battery restrictions on this app.
 */
object Watchdog {

    /** Whether anything wants the service up right now. */
    fun shouldRun(context: Context): Boolean =
        Config(context).enabled || MessageStore(context.filesDir).pending().isNotEmpty()

    /**
     * Start the service if it should be running and is not. [source] names
     * the caller for the log and the notification ("the watchdog", "app
     * open"). Returns true when a start was issued.
     */
    fun check(context: Context, source: String): Boolean {
        if (!shouldRun(context)) {
            cancel(context)
            return false
        }
        if (AgentService.isUp() || AgentService.startRequestedRecently()) return false

        DebugLog(context.filesDir).record("service found down", "by $source")
        val accepted = AgentService.start(context)
        val text = if (accepted) {
            "Service was down; restarted by $source."
        } else {
            "Service was down and Android refused the restart. Tap to open."
        }
        notify(context, text)
        return true
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        // Re-scheduling an existing job restarts its period; leave it be.
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, WatchdogJob::class.java))
            .setPeriodic(PERIOD_MS)
            .setPersisted(true)
            .build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "Could not schedule the watchdog job")
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    private fun notify(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Watchdog", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Reports each time the service was found stopped and brought back."
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Pebble Operator")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_operator)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // A fresh id per catch, so repeats stack instead of replacing each other.
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private const val TAG = "pebble-operator"
    private const val JOB_ID = 1
    private const val CHANNEL_ID = "watchdog"
    private val PERIOD_MS = TimeUnit.MINUTES.toMillis(15)
}

/** The periodic half of [Watchdog]; the check is quick, so it runs inline. */
class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Watchdog.check(this, "the watchdog")
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
