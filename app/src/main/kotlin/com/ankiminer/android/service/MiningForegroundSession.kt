package com.ankiminer.android.service

import java.util.UUID

/** Identity shared by the coordinator, foreground service, and notification actions. */
data class MiningForegroundSessionIdentity(
    val runId: String,
    val generation: Long,
    val leaseId: String,
) {
    init {
        require(runId.isNotBlank() && runId.length <= MAX_RUN_ID_LENGTH) {
            "runId must contain between 1 and $MAX_RUN_ID_LENGTH characters"
        }
        require(runId.none(Char::isISOControl)) { "runId must not contain control characters" }
        require(generation > 0) { "generation must be positive" }
        require(leaseId.matches(LEASE_ID_PATTERN)) { "leaseId must be a canonical UUID" }
    }

    companion object {
        private const val MAX_RUN_ID_LENGTH = 256
        private val LEASE_ID_PATTERN =
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        internal fun create(
            runId: String,
            generation: Long,
        ): MiningForegroundSessionIdentity =
            MiningForegroundSessionIdentity(
                runId = runId,
                generation = generation,
                leaseId = UUID.randomUUID().toString(),
            )
    }
}

enum class MiningForegroundCancellationReason {
    USER_REQUESTED,
    SYSTEM_TIMEOUT,
    SERVICE_LOST,
    PROTOCOL_ERROR,
}

fun interface MiningForegroundSessionListener {
    /** Called away from the Android main thread and at most once for a lease. */
    fun onCancellationRequested(
        identity: MiningForegroundSessionIdentity,
        reason: MiningForegroundCancellationReason,
    )
}

/** What [MiningForegroundProgress.completed] counts, so bytes never render as an item count. */
enum class MiningForegroundProgressUnit {
    ITEMS,
    BYTES,
}

/**
 * Counts only. Engine progress descriptions embed mined terms, and a notification can be surfaced
 * on a locked device, so this type deliberately carries no channel for engine-supplied text.
 * Notification bodies are built from app-owned string resources.
 *
 * [unit] selects between those resources; it is a closed enum, not a caller-supplied label, so it
 * cannot become a text channel.
 */
data class MiningForegroundProgress(
    val completed: Int? = null,
    val total: Int? = null,
    val unit: MiningForegroundProgressUnit = MiningForegroundProgressUnit.ITEMS,
) {
    init {
        require((completed == null) == (total == null)) {
            "completed and total must both be set or both be absent"
        }
        if (completed != null && total != null) {
            require(total > 0) { "total must be positive" }
            require(completed in 0..total) { "completed must be between zero and total" }
        }
    }
}

/**
 * Whether re-posting the notification for [next] would show the user anything [previous] does not.
 *
 * Publishing is a `startService` Binder round trip plus a notification rebuild, and the staging copy
 * emits one progress event per buffer — roughly 8,200 for a 2 GiB source. Coalescing on the rendered
 * percentage keeps a skipped event within one percentage point of what is on screen, and a completed
 * count always redraws so a determinate bar reaches its total.
 */
internal fun miningNotificationRedrawRequired(
    previous: MiningForegroundProgress,
    next: MiningForegroundProgress,
): Boolean {
    val previousTotal = previous.total
    val nextTotal = next.total
    // Two indeterminate values render the same string, whatever their counts were.
    if (previousTotal == null || nextTotal == null) {
        return (previousTotal == null) != (nextTotal == null)
    }
    if (previousTotal != nextTotal || previous.unit != next.unit) return true
    val nextCompleted = requireNotNull(next.completed)
    val previousCompleted = requireNotNull(previous.completed)
    if (nextCompleted == previousCompleted) return false
    if (nextCompleted == nextTotal) return true
    return percentagePoint(nextCompleted, nextTotal) !=
        percentagePoint(previousCompleted, previousTotal)
}

private fun percentagePoint(
    completed: Int,
    total: Int,
): Int = (completed.toLong() * 100L / total.toLong()).toInt()

interface MiningForegroundLease : AutoCloseable {
    val identity: MiningForegroundSessionIdentity

    /** Makes cancellation presentation monotonic for this live session. */
    fun markCancelling(): Boolean = false

    /** Returns false once this lease is no longer the live foreground session. */
    fun updateProgress(progress: MiningForegroundProgress): Boolean

    /**
     * Drops the CPU wake lock while the run is parked in curation. The foreground service keeps
     * running: only the media-processing lease is at issue, and no media is processed here.
     */
    fun parkCpuWake(): Boolean = false

    /** Re-arms the CPU wake lock when the confirmed run resumes. */
    fun resumeCpuWake(): Boolean = false

    /** Marks this as an expected shutdown before asking Android to stop the service. */
    override fun close()
}
