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

/**
 * Counts only. Engine progress descriptions embed mined terms, and a notification can be surfaced
 * on a locked device, so this type deliberately carries no channel for engine-supplied text.
 * Notification bodies are built from app-owned string resources.
 */
data class MiningForegroundProgress(
    val completed: Int? = null,
    val total: Int? = null,
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

interface MiningForegroundLease : AutoCloseable {
    val identity: MiningForegroundSessionIdentity

    /** Makes cancellation presentation monotonic for this live session. */
    fun markCancelling(): Boolean = false

    /** Returns false once this lease is no longer the live foreground session. */
    fun updateProgress(progress: MiningForegroundProgress): Boolean

    /** Marks this as an expected shutdown before asking Android to stop the service. */
    override fun close()
}
