package com.ankiminer.android.anki.provider

import com.ankiminer.android.R
import com.ankiminer.android.anki.journal.AnkiMutationStore
import com.ankiminer.android.anki.journal.RemediationKind
import com.ankiminer.android.anki.journal.RemediationRecord
import com.ankiminer.android.anki.journal.RemediationState
import com.ankiminer.android.localization.StringResourceResolver

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

/** Stable durable summaries mapped to localized copy without weakening their recorded meaning. */
internal enum class AnkiRemediationSummary(val stableText: String?) {
    DECK_COMMIT_UNCERTAIN("Deck creation could not be conclusively reconciled"),
    DECK_COMMIT_UNCERTAIN_OWNER_LOSS("Deck creation could not be conclusively reconciled after owner loss"),
    MEDIA_COMMIT_UNCERTAIN("Media provider commit could not be conclusively reconciled"),
    MEDIA_COMMIT_UNCERTAIN_OWNER_LOSS("Media provider commit could not be confirmed after owner loss"),
    MEDIA_STORED_UNATTACHED("Stored Anki media was not attached to a verified note"),
    NOTE_COMMIT_UNCERTAIN("Note provider commit could not be conclusively reconciled"),
    NOTE_COMMIT_UNCERTAIN_OWNER_LOSS("Note provider commit could not be confirmed after owner loss"),
    NOTE_POSTCHECK_FAILED("A committed note requires review because exact postchecks failed"),
    NOTE_POSTCHECK_UNFINISHED("A committed note requires review because postchecks did not finish"),
    CARD_ROUTING_FAILED("Committed note card routing requires review"),
    STAGING_CLEANUP_RETRY("Anki media staging cleanup requires retry"),
    UNKNOWN(null),
    ;

    companion object {
        fun fromStableText(value: String): AnkiRemediationSummary =
            entries.firstOrNull { it.stableText == value } ?: UNKNOWN
    }
}

/** The only per-item operations which the current journal can complete without inventing evidence. */
internal enum class AnkiRemediationActionKind {
    RETRY_STAGING_CLEANUP,
    ACKNOWLEDGE_UNATTACHED_MEDIA,
    ACKNOWLEDGE_UNCERTAIN_MEDIA,
    RESOLVE_AFTER_EXTERNAL_REVIEW,
}

internal data class AnkiPendingRemediation(
    val id: Long,
    val type: AnkiRemediationType,
    val summaryReason: AnkiRemediationSummary,
    val title: String,
    val summary: String,
    val compactEvidence: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val availableActions: Set<AnkiRemediationActionKind>,
)

internal data class AnkiRemediationInventory(
    val pending: List<AnkiPendingRemediation>,
)

/**
 * Explicit user attestations accepted by the generic journal resolution boundary.
 *
 * These values never claim that the app queried AnkiDroid. They record what the user established
 * by reviewing or correcting the collection outside this process.
 */
internal enum class AnkiExternalReviewOutcome {
    COMMIT_CONFIRMED,
    NOT_COMMITTED_CONFIRMED,
    CURRENT_STATE_ACCEPTED_OR_CORRECTED,
    CAPACITY_AVAILABLE,
}

internal sealed interface AnkiRemediationCommand {
    val remediationId: Long

    data class RetryStagingCleanup(
        override val remediationId: Long,
    ) : AnkiRemediationCommand

    data class AcknowledgeUnattachedMedia(
        override val remediationId: Long,
    ) : AnkiRemediationCommand

    data class AcknowledgeUncertainMedia(
        override val remediationId: Long,
    ) : AnkiRemediationCommand

    data class ResolveAfterExternalReview(
        override val remediationId: Long,
        val outcome: AnkiExternalReviewOutcome,
    ) : AnkiRemediationCommand
}

internal sealed interface AnkiRemediationCommandResult {
    val inventory: AnkiRemediationInventory

    data class Resolved(
        override val inventory: AnkiRemediationInventory,
    ) : AnkiRemediationCommandResult

    /** A staging retry ran to completion, but the exact quarantine is still present. */
    data class StillPending(
        override val inventory: AnkiRemediationInventory,
    ) : AnkiRemediationCommandResult
}

internal enum class AnkiRemediationFailure {
    BUSY,
    CANCELLED,
    NOT_FOUND,
    ACTION_NOT_ALLOWED,
    REVIEW_OUTCOME_NOT_ALLOWED,
    CORRUPT_INVENTORY,
    JOURNAL_FAILED,
    RECOVERY_FAILED,
}

internal class AnkiRemediationException(
    val failure: AnkiRemediationFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Narrow journal seam for focused JVM tests and for preventing a generic delete capability. */
internal interface AnkiRemediationJournal {
    fun openRemediations(): List<RemediationRecord>

    fun acknowledgeUnattachedMedia(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord

    fun acknowledgeUncertainMedia(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord

    fun resolveAfterExternalReview(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord
}

/** Production adapter: both resolution paths retain the durable remediation row and its evidence. */
internal class StoreAnkiRemediationJournal(
    private val store: AnkiMutationStore,
) : AnkiRemediationJournal {
    override fun openRemediations(): List<RemediationRecord> = store.openRemediations()

    override fun acknowledgeUnattachedMedia(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord = store.acknowledgeUnattachedMedia(remediationId, compactEvidence)

    override fun acknowledgeUncertainMedia(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord = store.acknowledgeUncertainMedia(remediationId, compactEvidence)

    override fun resolveAfterExternalReview(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord = store.resolveRemediation(remediationId, compactEvidence)
}

/** Existing startup recovery owns every provider query, reconciliation, and permitted reissue. */
internal fun interface InterruptedAnkiWorkRecovery {
    fun reconcile()
}

/**
 * Non-blocking process lock for remediation reads and actions.
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
 * Worker-only facade for the durable remediation inventory and its conservative actions.
 *
 * Cancellation is honored while an operation is read-only and immediately before any recovery or
 * journal transition. Once an evidence-producing recovery/transaction starts, it runs to its
 * durable boundary and the result is returned even if cancellation arrives meanwhile.
 */
internal class AnkiRemediationService(
    private val journal: AnkiRemediationJournal,
    private val interruptedWorkRecovery: InterruptedAnkiWorkRecovery,
    private val stagingRecovery: MediaStagingRecovery,
    private val workerThreadGuard: WorkerThreadGuard,
    private val strings: StringResourceResolver,
    private val processLock: AnkiRemediationProcessLock = AnkiRemediationProcessLock.shared,
) {
    fun inventory(cancellation: AnkiCancellation = AnkiCancellation.NONE): AnkiRemediationInventory =
        exclusive(cancellation) {
            val inventory = readInventory()
            ensureActive(cancellation)
            inventory
        }

    /**
     * Reconciles only interrupted durable work through the existing recovery gate.
     *
     * The gate may perform its one evidence-backed card reissue; this facade never retries an
     * already-classified uncertain deck, media, or note commit.
     */
    fun reconcileInterruptedWork(
        cancellation: AnkiCancellation = AnkiCancellation.NONE,
    ): AnkiRemediationInventory =
        exclusive(cancellation) {
            ensureActive(cancellation)
            try {
                interruptedWorkRecovery.reconcile()
            } catch (error: RuntimeException) {
                throw failure(
                    AnkiRemediationFailure.RECOVERY_FAILED,
                    "Interrupted Anki work could not be reconciled",
                    error,
                )
            }
            readInventory()
        }

    fun perform(
        command: AnkiRemediationCommand,
        cancellation: AnkiCancellation = AnkiCancellation.NONE,
    ): AnkiRemediationCommandResult =
        exclusive(cancellation) {
            val before = readInventory()
            val pending = before.pending.singleOrNull { it.id == command.remediationId }
                ?: throw failure(
                    AnkiRemediationFailure.NOT_FOUND,
                    "The Anki remediation is no longer pending",
                )
            ensureCommandAllowed(pending, command)
            ensureActive(cancellation)

            val requiresInventoryRefresh =
                when (command) {
                    is AnkiRemediationCommand.RetryStagingCleanup -> {
                        retryStagingCleanup()
                        true
                    }
                    is AnkiRemediationCommand.AcknowledgeUnattachedMedia -> {
                        acknowledgeUnattachedMedia(command.remediationId)
                        false
                    }
                    is AnkiRemediationCommand.AcknowledgeUncertainMedia -> {
                        acknowledgeUncertainMedia(command.remediationId)
                        false
                    }
                    is AnkiRemediationCommand.ResolveAfterExternalReview -> {
                        resolveAfterExternalReview(pending, command.outcome)
                        false
                    }
                }

            if (!requiresInventoryRefresh) {
                return@exclusive AnkiRemediationCommandResult.Resolved(
                    AnkiRemediationInventory(
                        before.pending.filterNot { it.id == command.remediationId },
                    ),
                )
            }

            val after = readInventory()
            if (after.pending.any { it.id == command.remediationId }) {
                AnkiRemediationCommandResult.StillPending(after)
            } else {
                AnkiRemediationCommandResult.Resolved(after)
            }
        }

    private fun retryStagingCleanup() {
        try {
            val report = stagingRecovery.recover()
            if (
                report.cleanedRecords < 0 ||
                    report.quarantinedRecords < 0 ||
                    report.sweptOrphans < 0
            ) {
                throw failure(
                    AnkiRemediationFailure.RECOVERY_FAILED,
                    "Anki media cleanup returned an invalid report",
                )
            }
        } catch (error: AnkiRemediationException) {
            throw error
        } catch (error: RuntimeException) {
            throw failure(
                AnkiRemediationFailure.RECOVERY_FAILED,
                "Anki media cleanup retry failed",
                error,
            )
        }
    }

    private fun acknowledgeUnattachedMedia(remediationId: Long) {
        val resolved =
            try {
                journal.acknowledgeUnattachedMedia(
                    remediationId,
                    EVIDENCE_UNATTACHED_MEDIA_ACKNOWLEDGED,
                )
            } catch (error: RuntimeException) {
                throw failure(
                    AnkiRemediationFailure.JOURNAL_FAILED,
                    "Stored Anki media could not be acknowledged",
                    error,
                )
            }
        requireResolvedIdentity(resolved, remediationId, RemediationKind.MEDIA_STORED_UNATTACHED)
    }

    private fun acknowledgeUncertainMedia(remediationId: Long) {
        val resolved =
            try {
                journal.acknowledgeUncertainMedia(
                    remediationId,
                    EVIDENCE_UNCERTAIN_MEDIA_ABANDONED,
                )
            } catch (error: RuntimeException) {
                throw failure(
                    AnkiRemediationFailure.JOURNAL_FAILED,
                    "Uncertain Anki media could not be acknowledged",
                    error,
                )
            }
        requireResolvedIdentity(resolved, remediationId, RemediationKind.MEDIA_COMMIT_UNCERTAIN)
    }

    private fun resolveAfterExternalReview(
        pending: AnkiPendingRemediation,
        outcome: AnkiExternalReviewOutcome,
    ) {
        if (outcome !in allowedReviewOutcomes(pending.type)) {
            throw failure(
                AnkiRemediationFailure.REVIEW_OUTCOME_NOT_ALLOWED,
                "That review outcome does not apply to this Anki remediation",
            )
        }
        val resolved =
            try {
                journal.resolveAfterExternalReview(
                    pending.id,
                    "userReview=${outcome.name.lowercase()};remediationType=${pending.type.name}",
                )
            } catch (error: RuntimeException) {
                throw failure(
                    AnkiRemediationFailure.JOURNAL_FAILED,
                    "The reviewed Anki remediation could not be resolved",
                    error,
                )
            }
        requireResolvedIdentity(resolved, pending.id, pending.type.toJournalKind())
    }

    private fun requireResolvedIdentity(
        resolved: RemediationRecord,
        expectedId: Long,
        expectedKind: RemediationKind,
    ) {
        if (
            resolved.id != expectedId ||
                resolved.kind != expectedKind ||
                resolved.state != RemediationState.RESOLVED ||
                resolved.compactEvidence.isNullOrBlank()
        ) {
            throw failure(
                AnkiRemediationFailure.CORRUPT_INVENTORY,
                "Anki remediation resolution evidence was invalid",
            )
        }
    }

    private fun ensureCommandAllowed(
        pending: AnkiPendingRemediation,
        command: AnkiRemediationCommand,
    ) {
        val requested =
            when (command) {
                is AnkiRemediationCommand.RetryStagingCleanup ->
                    AnkiRemediationActionKind.RETRY_STAGING_CLEANUP
                is AnkiRemediationCommand.AcknowledgeUnattachedMedia ->
                    AnkiRemediationActionKind.ACKNOWLEDGE_UNATTACHED_MEDIA
                is AnkiRemediationCommand.AcknowledgeUncertainMedia ->
                    AnkiRemediationActionKind.ACKNOWLEDGE_UNCERTAIN_MEDIA
                is AnkiRemediationCommand.ResolveAfterExternalReview ->
                    AnkiRemediationActionKind.RESOLVE_AFTER_EXTERNAL_REVIEW
            }
        if (requested !in pending.availableActions) {
            throw failure(
                AnkiRemediationFailure.ACTION_NOT_ALLOWED,
                "That action is not safe for this Anki remediation",
            )
        }
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
        val type = record.kind.toDomainType()
        val summaryReason = AnkiRemediationSummary.fromStableText(record.summary)
        return AnkiPendingRemediation(
            id = record.id,
            type = type,
            summaryReason = summaryReason,
            title = strings.resolve(type.titleResource()),
            summary =
                if (summaryReason == AnkiRemediationSummary.UNKNOWN) {
                    strings.resolve(
                        R.string.anki_recovery_item_unknown_summary,
                        listOf(record.summary),
                    )
                } else {
                    strings.resolve(summaryReason.summaryResource())
                },
            compactEvidence = record.compactEvidence,
            createdAtMs = record.createdAtMs,
            updatedAtMs = record.updatedAtMs,
            availableActions = actions(type),
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

private fun AnkiRemediationType.titleResource(): Int =
    when (this) {
        AnkiRemediationType.DECK_COMMIT_UNCERTAIN -> R.string.anki_recovery_item_deck_uncertain_title
        AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN -> R.string.anki_recovery_item_media_uncertain_title
        AnkiRemediationType.MEDIA_STORED_UNATTACHED -> R.string.anki_recovery_item_media_unattached_title
        AnkiRemediationType.NOTE_COMMIT_UNCERTAIN -> R.string.anki_recovery_item_note_uncertain_title
        AnkiRemediationType.NOTE_COMMITTED_FAILED -> R.string.anki_recovery_item_note_failed_title
        AnkiRemediationType.CARD_ROUTING_FAILED -> R.string.anki_recovery_item_card_routing_title
        AnkiRemediationType.STAGING_QUARANTINED -> R.string.anki_recovery_item_staging_title
        AnkiRemediationType.CAPACITY_EXHAUSTED -> R.string.anki_recovery_item_capacity_title
    }

private fun AnkiRemediationSummary.summaryResource(): Int =
    when (this) {
        AnkiRemediationSummary.DECK_COMMIT_UNCERTAIN ->
            R.string.anki_recovery_summary_deck_commit_uncertain
        AnkiRemediationSummary.DECK_COMMIT_UNCERTAIN_OWNER_LOSS ->
            R.string.anki_recovery_summary_deck_commit_uncertain_owner_loss
        AnkiRemediationSummary.MEDIA_COMMIT_UNCERTAIN ->
            R.string.anki_recovery_summary_media_commit_uncertain
        AnkiRemediationSummary.MEDIA_COMMIT_UNCERTAIN_OWNER_LOSS ->
            R.string.anki_recovery_summary_media_commit_uncertain_owner_loss
        AnkiRemediationSummary.MEDIA_STORED_UNATTACHED ->
            R.string.anki_recovery_summary_media_stored_unattached
        AnkiRemediationSummary.NOTE_COMMIT_UNCERTAIN ->
            R.string.anki_recovery_summary_note_commit_uncertain
        AnkiRemediationSummary.NOTE_COMMIT_UNCERTAIN_OWNER_LOSS ->
            R.string.anki_recovery_summary_note_commit_uncertain_owner_loss
        AnkiRemediationSummary.NOTE_POSTCHECK_FAILED ->
            R.string.anki_recovery_summary_note_postcheck_failed
        AnkiRemediationSummary.NOTE_POSTCHECK_UNFINISHED ->
            R.string.anki_recovery_summary_note_postcheck_unfinished
        AnkiRemediationSummary.CARD_ROUTING_FAILED ->
            R.string.anki_recovery_summary_card_routing_failed
        AnkiRemediationSummary.STAGING_CLEANUP_RETRY ->
            R.string.anki_recovery_summary_staging_cleanup_retry
        AnkiRemediationSummary.UNKNOWN -> R.string.anki_recovery_item_unknown_summary
    }

private fun AnkiRemediationType.toJournalKind(): RemediationKind =
    when (this) {
        AnkiRemediationType.DECK_COMMIT_UNCERTAIN -> RemediationKind.DECK_COMMIT_UNCERTAIN
        AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN -> RemediationKind.MEDIA_COMMIT_UNCERTAIN
        AnkiRemediationType.MEDIA_STORED_UNATTACHED -> RemediationKind.MEDIA_STORED_UNATTACHED
        AnkiRemediationType.NOTE_COMMIT_UNCERTAIN -> RemediationKind.NOTE_COMMIT_UNCERTAIN
        AnkiRemediationType.NOTE_COMMITTED_FAILED -> RemediationKind.NOTE_COMMITTED_FAILED
        AnkiRemediationType.CARD_ROUTING_FAILED -> RemediationKind.CARD_ROUTING_FAILED
        AnkiRemediationType.STAGING_QUARANTINED -> RemediationKind.STAGING_QUARANTINED
        AnkiRemediationType.CAPACITY_EXHAUSTED -> RemediationKind.CAPACITY_EXHAUSTED
    }

private fun actions(type: AnkiRemediationType): Set<AnkiRemediationActionKind> =
    when (type) {
        AnkiRemediationType.STAGING_QUARANTINED ->
            setOf(AnkiRemediationActionKind.RETRY_STAGING_CLEANUP)
        AnkiRemediationType.MEDIA_STORED_UNATTACHED ->
            setOf(AnkiRemediationActionKind.ACKNOWLEDGE_UNATTACHED_MEDIA)
        AnkiRemediationType.DECK_COMMIT_UNCERTAIN,
        AnkiRemediationType.NOTE_COMMIT_UNCERTAIN,
        AnkiRemediationType.NOTE_COMMITTED_FAILED,
        AnkiRemediationType.CARD_ROUTING_FAILED,
        AnkiRemediationType.CAPACITY_EXHAUSTED,
        -> setOf(AnkiRemediationActionKind.RESOLVE_AFTER_EXTERNAL_REVIEW)
        AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN ->
            setOf(AnkiRemediationActionKind.ACKNOWLEDGE_UNCERTAIN_MEDIA)
    }

private fun allowedReviewOutcomes(type: AnkiRemediationType): Set<AnkiExternalReviewOutcome> =
    when (type) {
        AnkiRemediationType.DECK_COMMIT_UNCERTAIN,
        AnkiRemediationType.NOTE_COMMIT_UNCERTAIN,
        -> setOf(
            AnkiExternalReviewOutcome.COMMIT_CONFIRMED,
            AnkiExternalReviewOutcome.NOT_COMMITTED_CONFIRMED,
        )
        AnkiRemediationType.NOTE_COMMITTED_FAILED,
        AnkiRemediationType.CARD_ROUTING_FAILED,
        -> setOf(AnkiExternalReviewOutcome.CURRENT_STATE_ACCEPTED_OR_CORRECTED)
        AnkiRemediationType.CAPACITY_EXHAUSTED ->
            setOf(AnkiExternalReviewOutcome.CAPACITY_AVAILABLE)
        AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
        AnkiRemediationType.MEDIA_STORED_UNATTACHED,
        AnkiRemediationType.STAGING_QUARANTINED,
        -> emptySet()
    }

private fun failure(
    failure: AnkiRemediationFailure,
    message: String,
    cause: Throwable? = null,
) = AnkiRemediationException(failure, message, cause)

private const val EVIDENCE_UNATTACHED_MEDIA_ACKNOWLEDGED =
    "user acknowledged exact stored media was not attached to a verified note"
private const val EVIDENCE_UNCERTAIN_MEDIA_ABANDONED =
    "user accepted possible orphaned media and abandoned the uncertain namespace claim"
