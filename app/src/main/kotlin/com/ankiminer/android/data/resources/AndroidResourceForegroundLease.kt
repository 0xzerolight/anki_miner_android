package com.ankiminer.android.data.resources

import android.content.Context
import androidx.core.content.ContextCompat
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.service.ResourceImportForegroundService
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds [ResourceImportForegroundService] up for as long as an operation runs.
 *
 * Progress arrives per copied chunk, which is far more often than a notification
 * can usefully change, so a repost is throttled to a phase change or one second —
 * enough for the bar to move, cheap enough not to spend the import's time on
 * binder traffic.
 */
internal class AndroidResourceForegroundLease(
    private val context: Context,
    private val elapsedMillis: () -> Long = System::currentTimeMillis,
) : ResourceForegroundLease {
    private val lastPostedAt = AtomicLong(0)

    @Volatile
    private var lastPhase: ResourceOperationPhase? = null

    override fun start(progress: ResourceOperationProgress) {
        lastPhase = progress.phase
        lastPostedAt.set(elapsedMillis())
        post(progress)
    }

    override fun update(progress: ResourceOperationProgress) {
        val now = elapsedMillis()
        val phaseChanged = progress.phase != lastPhase
        if (!phaseChanged && now - lastPostedAt.get() < MINIMUM_REPOST_MILLIS) return
        lastPhase = progress.phase
        lastPostedAt.set(now)
        post(progress)
    }

    override fun stop() {
        lastPhase = null
        // Not startForegroundService: a stop must never be the call that promotes
        // a dead service back into the foreground and then fails to be finished.
        runCatching { context.startService(ResourceImportForegroundService.stopIntent(context)) }
            .onFailure { failure ->
                AppLog.w(
                    LogComponent.RESOURCES,
                    "import.foreground.stop",
                    failure,
                    "outcome" to "fail",
                )
            }
    }

    private fun post(progress: ResourceOperationProgress) {
        val intent =
            ResourceImportForegroundService.startIntent(
                context,
                progress.label,
                progress.completed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                progress.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        // A failure here loses the notification, never the import: the work runs on
        // the resource executor either way, so this must not propagate.
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { failure ->
                AppLog.w(
                    LogComponent.RESOURCES,
                    "import.foreground.start",
                    failure,
                    "phase" to progress.phase.name,
                    "outcome" to "fail",
                )
            }
    }

    private companion object {
        const val MINIMUM_REPOST_MILLIS = 1_000L
    }
}
