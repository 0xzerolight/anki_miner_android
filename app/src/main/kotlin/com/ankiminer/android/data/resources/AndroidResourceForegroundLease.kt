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
    private val startService: (ResourceOperationProgress) -> Unit,
    private val stopService: () -> Unit,
    private val elapsedMillis: () -> Long = System::currentTimeMillis,
) : ResourceForegroundLease {
    constructor(
        context: Context,
        elapsedMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        startService = { progress ->
            val intent =
                ResourceImportForegroundService.startIntent(
                    context,
                    progress.label,
                    progress.completed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    progress.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            ContextCompat.startForegroundService(context, intent)
            Unit
        },
        stopService = {
            context.startService(ResourceImportForegroundService.stopIntent(context))
            Unit
        },
        elapsedMillis = elapsedMillis,
    )

    private val lastPostedAt = AtomicLong(0)

    @Volatile
    private var lastPhase: ResourceOperationPhase? = null

    @Volatile
    private var started = false

    override fun start(progress: ResourceOperationProgress) {
        started = false
        startService(progress)
        lastPhase = progress.phase
        lastPostedAt.set(elapsedMillis())
        started = true
    }

    override fun update(progress: ResourceOperationProgress) {
        if (!started) return
        val now = elapsedMillis()
        val phaseChanged = progress.phase != lastPhase
        if (!phaseChanged && now - lastPostedAt.get() < MINIMUM_REPOST_MILLIS) return
        lastPhase = progress.phase
        lastPostedAt.set(now)
        postBestEffort(progress)
    }

    override fun stop() {
        started = false
        lastPhase = null
        // Not startForegroundService: a stop must never be the call that promotes
        // a dead service back into the foreground and then fails to be finished.
        runCatching(stopService)
            .onFailure { failure ->
                AppLog.w(
                    LogComponent.RESOURCES,
                    "import.foreground.stop",
                    failure,
                    "outcome" to "fail",
                )
            }
    }

    private fun postBestEffort(progress: ResourceOperationProgress) {
        runCatching { startService(progress) }
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
