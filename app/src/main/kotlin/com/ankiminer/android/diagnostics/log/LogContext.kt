package com.ankiminer.android.diagnostics.log

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/**
 * Carries the active run id so every record emitted underneath a mining run can be selected with one
 * `grep run=<id>`, without threading an id parameter through every call site.
 *
 * One [ThreadLocal] backs both callers: [asContextElement] installs into this same storage on each
 * dispatch, so a coroutine that hops threads and a plain worker thread (the serialized Python
 * executor) read the same id.
 */
internal object LogContext {
    private val currentRunId = ThreadLocal<String?>()

    fun runId(): String? = currentRunId.get()

    fun asContextElement(runId: String?): ThreadContextElement<String?> =
        currentRunId.asContextElement(runId)

    /** Not private only so the inline [withRunId] can reach the ThreadLocal. */
    fun setRunId(runId: String?) {
        // remove() rather than set(null): these threads are pooled and outlive the run.
        if (runId == null) currentRunId.remove() else currentRunId.set(runId)
    }

    /** Restores the previous id instead of clearing it — run ids nest. */
    inline fun <T> withRunId(
        runId: String?,
        block: () -> T,
    ): T {
        val previous = runId()
        setRunId(runId)
        return try {
            block()
        } finally {
            setRunId(previous)
        }
    }
}
