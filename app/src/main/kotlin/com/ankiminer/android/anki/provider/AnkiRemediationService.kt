package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.AnkiMutationStore
import com.ankiminer.android.anki.journal.RemediationKind
import com.ankiminer.android.anki.journal.RemediationRecord
import com.ankiminer.android.anki.journal.RemediationState

/** Stable, UI-facing remediation categories. Durable journal implementation details stay private. */
internal enum class AnkiRemediationType {
    DECK_COMMIT_UNCERTAIN,
    MEDIA_COMMIT_UNCERTAIN,
    MEDIA_STORED_UNATTACHED,
    NOTE_COMMIT_UNCERTAIN,
    NOTE_COMMITTED_FAILED,
    CARD_ROUTING_FAILED,
    STAGING_QUARANTINED,
    CAPACITY_EXHAUSTED,
}

internal data class AnkiPendingRemediation(
    val id: Long,
    val type: AnkiRemediationType,
    val compactEvidence: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

internal data class AnkiRemediationInventory(
    val pending: List<AnkiPendingRemediation>,
)

internal enum class AnkiRemediationFailure {
    BUSY,
    CANCELLED,
    CORRUPT_INVENTORY,
    JOURNAL_FAILED,
}

internal class AnkiRemediationException(
    val failure: AnkiRemediationFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Narrow journal seam for focused JVM tests. */
internal interface AnkiRemediationJournal {
    fun openRemediations(): List<RemediationRecord>
}

internal class StoreAnkiRemediationJournal(
    private val store: AnkiMutationStore,
) : AnkiRemediationJournal {
    override fun openRemediations(): List<RemediationRecord> = store.openRemediations()
}

/**
 * Non-blocking process lock for remediation reads.
 *
 * RuntimeWorkCoordinator remains the outer exclusion boundary with mining and resource work. This
 * lock prevents two UI/control callers from entering remediation machinery concurrently.
 */
internal class AnkiRemediationProcessLock internal constructor() {
    private val monitor = Any()
    private var activeGeneration: Long? = null
    private var nextGeneration = 1L

    internal class Lease(
        private val owner: AnkiRemediationProcessLock,
        private val generation: Long,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            synchronized(this) {
                if (closed) return
                owner.release(generation)
                closed = true
            }
        }
    }

    fun tryAcquire(): Lease? =
        synchronized(monitor) {
            if (activeGeneration != null) return@synchronized null
            val generation = nextGeneration++
            activeGeneration = generation
            Lease(this, generation)
        }

    private fun release(generation: Long) {
        synchronized(monitor) {
            check(activeGeneration == generation) { "Anki remediation lease is stale" }
            activeGeneration = null
        }
    }

    internal companion object {
        val shared = AnkiRemediationProcessLock()
    }
}

/**
 * Worker-only read facade over the durable remediation inventory.
 *
 * The finalization sweep and the startup recovery gate own every resolution path; residual open
 * rows surface only through diagnostics, so this service exposes no per-item actions.
 */
internal class AnkiRemediationService(
    private val journal: AnkiRemediationJournal,
    private val workerThreadGuard: WorkerThreadGuard,
    private val processLock: AnkiRemediationProcessLock = AnkiRemediationProcessLock.shared,
) {
    fun inventory(cancellation: AnkiCancellation = AnkiCancellation.NONE): AnkiRemediationInventory =
        exclusive(cancellation) {
            val inventory = readInventory()
            ensureActive(cancellation)
            inventory
        }

    private fun readInventory(): AnkiRemediationInventory {
        val records =
            try {
                journal.openRemediations()
            } catch (error: RuntimeException) {
                throw failure(
                    AnkiRemediationFailure.JOURNAL_FAILED,
                    "The Anki remediation inventory could not be read",
                    error,
                )
            }
        if (records.map(RemediationRecord::id).distinct().size != records.size) {
            throw failure(
                AnkiRemediationFailure.CORRUPT_INVENTORY,
                "The Anki remediation inventory repeats an identity",
            )
        }
        return AnkiRemediationInventory(
            records
                .sortedWith(compareBy(RemediationRecord::createdAtMs, RemediationRecord::id))
                .map(::toPending),
        )
    }

    private fun toPending(record: RemediationRecord): AnkiPendingRemediation {
        validateRecord(record)
        return AnkiPendingRemediation(
            id = record.id,
            type = record.kind.toDomainType(),
            compactEvidence = record.compactEvidence,
            createdAtMs = record.createdAtMs,
            updatedAtMs = record.updatedAtMs,
        )
    }

    private fun validateRecord(record: RemediationRecord) {
        val identityValid =
            when (record.kind) {
                RemediationKind.DECK_COMMIT_UNCERTAIN,
                RemediationKind.NOTE_COMMIT_UNCERTAIN,
                RemediationKind.NOTE_COMMITTED_FAILED,
                RemediationKind.CARD_ROUTING_FAILED,
                -> record.parentId != null && record.claimId == null && record.stagingId == null
                RemediationKind.MEDIA_COMMIT_UNCERTAIN,
                RemediationKind.MEDIA_STORED_UNATTACHED,
                -> record.parentId != null && record.claimId != null && record.stagingId == null
                RemediationKind.STAGING_QUARANTINED ->
                    record.parentId == null &&
                        record.claimId == null &&
                        record.stagingId != null &&
                        record.stagingSubjectId == record.stagingId
                RemediationKind.CAPACITY_EXHAUSTED ->
                    record.parentId != null || record.claimId != null || record.stagingId != null
            }
        if (
            record.id <= 0 ||
                record.state != RemediationState.OPEN ||
                record.summary.isBlank() ||
                record.createdAtMs < 0 ||
                record.updatedAtMs < record.createdAtMs ||
                !identityValid
        ) {
            throw failure(
                AnkiRemediationFailure.CORRUPT_INVENTORY,
                "The Anki remediation inventory contains invalid evidence",
            )
        }
    }

    private fun <T> exclusive(
        cancellation: AnkiCancellation,
        block: () -> T,
    ): T {
        workerThreadGuard.checkWorkerThread()
        ensureActive(cancellation)
        val lease = processLock.tryAcquire()
            ?: throw failure(
                AnkiRemediationFailure.BUSY,
                "Another Anki remediation operation is active",
            )
        try {
            ensureActive(cancellation)
            return block()
        } finally {
            lease.close()
        }
    }

    private fun ensureActive(cancellation: AnkiCancellation) {
        if (cancellation.isCancelled()) {
            throw failure(
                AnkiRemediationFailure.CANCELLED,
                "The Anki remediation operation was cancelled",
            )
        }
    }
}

private fun RemediationKind.toDomainType(): AnkiRemediationType =
    when (this) {
        RemediationKind.DECK_COMMIT_UNCERTAIN -> AnkiRemediationType.DECK_COMMIT_UNCERTAIN
        RemediationKind.MEDIA_COMMIT_UNCERTAIN -> AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN
        RemediationKind.MEDIA_STORED_UNATTACHED -> AnkiRemediationType.MEDIA_STORED_UNATTACHED
        RemediationKind.NOTE_COMMIT_UNCERTAIN -> AnkiRemediationType.NOTE_COMMIT_UNCERTAIN
        RemediationKind.NOTE_COMMITTED_FAILED -> AnkiRemediationType.NOTE_COMMITTED_FAILED
        RemediationKind.CARD_ROUTING_FAILED -> AnkiRemediationType.CARD_ROUTING_FAILED
        RemediationKind.STAGING_QUARANTINED -> AnkiRemediationType.STAGING_QUARANTINED
        RemediationKind.CAPACITY_EXHAUSTED -> AnkiRemediationType.CAPACITY_EXHAUSTED
    }

private fun failure(
    failure: AnkiRemediationFailure,
    message: String,
    cause: Throwable? = null,
) = AnkiRemediationException(failure, message, cause)
