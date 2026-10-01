package com.ankiminer.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ankiminer.android.MainActivity
import com.ankiminer.android.R
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.isTerminal
import com.ankiminer.android.mining.runId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal enum class MiningCompletionKind { NOTES_ADDED, STOPPED }

internal data class MiningCompletionNotice(
    val runId: String,
    val kind: MiningCompletionKind,
    val notesAdded: Long,
)

/**
 * What a run's end owes a user who is not looking, or null. Only a run that was in flight counts (a
 * lane restored into a terminal state did not just finish); a cancel is the user's own act.
 */
internal fun miningCompletionNotice(
    previous: MiningRunState,
    current: MiningRunState,
    appInForeground: Boolean,
): MiningCompletionNotice? {
    if (appInForeground || previous == MiningRunState.Idle || previous.isTerminal) return null
    val runId = current.runId ?: return null
    return when (current) {
        is MiningRunState.Success ->
            MiningCompletionNotice(runId, MiningCompletionKind.NOTES_ADDED, current.result.cardsCreated)
        is MiningRunState.Failed ->
            MiningCompletionNotice(runId, MiningCompletionKind.STOPPED, current.result?.cardsCreated ?: 0)
        else -> null
    }
}

/** Started-activity count: the app is in the foreground while any activity is started. */
internal class AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger()

    val inForeground: Boolean get() = started.get() > 0

    override fun onActivityStarted(activity: Activity) {
        started.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        started.decrementAndGet()
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * One silent, self-dismissing notice when a run ends with the app in the background: the
 * foreground-service notification goes with the service and nothing else says the run is done.
 */
internal class MiningCompletionNotifier(
    private val context: Context,
    private val foreground: AppForegroundTracker,
) {
    private val watching = AtomicBoolean()

    /** Idempotent: MainActivity calls it on every creation. */
    fun watch(
        scope: CoroutineScope,
        lanes: List<StateFlow<MiningRunState>>,
    ) {
        if (!watching.compareAndSet(false, true)) return
        lanes.forEach { states ->
            scope.launch {
                var previous = states.value
                states.collect { current ->
                    miningCompletionNotice(previous, current, foreground.inForeground)?.let(::post)
                    previous = current
                }
            }
        }
    }

    /** POST_NOTIFICATIONS is checked on API 33+ before notify; below 33 it is not a runtime permission. */
    @SuppressLint("MissingPermission")
    private fun post(notice: MiningCompletionNotice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.i(
                LogComponent.SERVICE,
                "completion.notify",
                "outcome" to "skip",
                "reason" to "permission_denied",
                "runId" to notice.runId,
            )
            return
        }
        ensureMiningNotificationChannel(context)
        val added = notice.kind == MiningCompletionKind.NOTES_ADDED
        val text =
            if (added) {
                context.resources.getQuantityString(
                    R.plurals.result_notes_added,
                    notice.notesAdded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    notice.notesAdded,
                )
            } else {
                context.getString(R.string.mining_notification_tap_for_details)
            }
        val open =
            PendingIntent.getActivity(
                context,
                notice.runId.hashCode(),
                openRunIntent(context, notice.runId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, MINING_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mining)
                .setContentTitle(context.getString(if (added) R.string.success_title else R.string.failed_title))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        NotificationManagerCompat.from(context).notify(COMPLETION_NOTIFICATION_ID, notification)
        AppLog.i(
            LogComponent.SERVICE,
            "completion.notify",
            "outcome" to "ok",
            "kind" to notice.kind.name.lowercase(Locale.ROOT),
            "runId" to notice.runId,
        )
    }

    companion object {
        private const val COMPLETION_NOTIFICATION_ID = 1002
        private const val ACTION_OPEN_RUN = "com.ankiminer.android.service.OPEN_RUN"
        private const val EXTRA_RUN_ID = "run_id"

        internal fun openRunIntent(
            context: Context,
            runId: String,
        ): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_RUN
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_RUN_ID, runId)
            }

        /** The run a completion notice opens, removed so Activity recreation cannot replay it. */
        internal fun consumeOpenedRunId(intent: Intent?): String? {
            if (intent?.action != ACTION_OPEN_RUN) return null
            val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: return null
            intent.action = null
            intent.removeExtra(EXTRA_RUN_ID)
            return runId
        }
    }
}
