package com.ankiminer.android.anki.provider

import com.ankiminer.android.R
import com.ankiminer.android.anki.journal.RemediationKind
import com.ankiminer.android.anki.journal.RemediationRecord
import com.ankiminer.android.anki.journal.RemediationState
import com.ankiminer.android.localization.StringResourceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AnkiRemediationServiceTest {
    @Test
    fun `inventory exposes every durable kind with only its supported actions`() {
        val journal =
            FakeRemediationJournal(
                mutableListOf(
                    record(8, RemediationKind.CAPACITY_EXHAUSTED),
                    record(2, RemediationKind.MEDIA_COMMIT_UNCERTAIN),
                    record(7, RemediationKind.STAGING_QUARANTINED),
                    record(1, RemediationKind.DECK_COMMIT_UNCERTAIN),
                    record(6, RemediationKind.CARD_ROUTING_FAILED),
                    record(5, RemediationKind.NOTE_COMMITTED_FAILED),
                    record(4, RemediationKind.NOTE_COMMIT_UNCERTAIN),
                    record(3, RemediationKind.MEDIA_STORED_UNATTACHED),
                ),
            )

        val pending = service(journal).inventory().pending

        assertEquals((1L..8L).toList(), pending.map(AnkiPendingRemediation::id))
        assertEquals(
            listOf(
                AnkiRemediationType.DECK_COMMIT_UNCERTAIN,
                AnkiRemediationType.MEDIA_COMMIT_UNCERTAIN,
                AnkiRemediationType.MEDIA_STORED_UNATTACHED,
                AnkiRemediationType.NOTE_COMMIT_UNCERTAIN,
                AnkiRemediationType.NOTE_COMMITTED_FAILED,
                AnkiRemediationType.CARD_ROUTING_FAILED,
                AnkiRemediationType.STAGING_QUARANTINED,
                AnkiRemediationType.CAPACITY_EXHAUSTED,
            ),
            pending.map(AnkiPendingRemediation::type),
        )
        assertEquals(
            listOf(
                setOf(AnkiRemediationActionKind.RESOLVE_AFTER_EXTERNAL_REVIEW),
                setOf(AnkiRemediationActionKind.ACKNOWLEDGE_UNCERTAIN_MEDIA),
                setOf(AnkiRemediationActionKind.ACKNOWLEDGE_UNATTACHED_MEDIA),
                setOf(AnkiRemediationActionKind.RESOLVE_AFTER_EXTERNAL_REVIEW),
                setOf(AnkiRemediationActionKind.RESOLVE_AFTER_EXTERNAL_REVIEW),
                setOf(AnkiRemediationActionKind.RESOLVE_AFTER_EXTERNAL_REVIEW),
                setOf(AnkiRemediationActionKind.RETRY_STAGING_CLEANUP),
                setOf(AnkiRemediationActionKind.RESOLVE_AFTER_EXTERNAL_REVIEW),
            ),
            pending.map(AnkiPendingRemediation::availableActions),
        )
        assertTrue(pending.all { it.title.isNotBlank() && it.summary == "summary-${it.id}" })
    }

    @Test
    fun `failed and unfinished note postchecks retain distinct rendered semantics`() {
        val journal =
            FakeRemediationJournal(
                mutableListOf(
                    record(9, RemediationKind.NOTE_COMMITTED_FAILED).copy(
                        summary = "A committed note requires review because exact postchecks failed",
                    ),
                    record(10, RemediationKind.NOTE_COMMITTED_FAILED).copy(
                        summary = "A committed note requires review because postchecks did not finish",
                    ),
                ),
            )

        val pending = service(journal).inventory().pending

        assertEquals(
            listOf(
                "A committed note requires review because exact postchecks failed",
                "A committed note requires review because postchecks did not finish",
            ),
            pending.map(AnkiPendingRemediation::summary),
        )
        assertEquals(
            listOf(
                AnkiRemediationSummary.NOTE_POSTCHECK_FAILED,
                AnkiRemediationSummary.NOTE_POSTCHECK_UNFINISHED,
            ),
            pending.map(AnkiPendingRemediation::summaryReason),
        )
        assertFalse(pending[1].summary.contains("failed", ignoreCase = true))
    }

    @Test
    fun `stored unattached acknowledgement uses its atomic journal boundary and retains evidence`() {
        val journal =
            FakeRemediationJournal(
                mutableListOf(record(10, RemediationKind.MEDIA_STORED_UNATTACHED)),
            )

        val result =
            service(journal).perform(
                AnkiRemediationCommand.AcknowledgeUnattachedMedia(10),
            )

        assertTrue(result is AnkiRemediationCommandResult.Resolved)
        assertTrue(result.inventory.pending.isEmpty())
        assertEquals(listOf(10L), journal.acknowledgedIds)
        assertTrue(journal.reviewedIds.isEmpty())
        val retained = journal.resolved.single()
        assertEquals(RemediationState.RESOLVED, retained.state)
        assertTrue(retained.compactEvidence.orEmpty().contains("not attached"))
    }

    @Test
    fun `staging retry delegates to existing recovery and never calls generic resolution`() {
        val journal =
            FakeRemediationJournal(
                mutableListOf(record(11, RemediationKind.STAGING_QUARANTINED)),
            )
        var recoveryCalls = 0
        val service =
            service(
                journal = journal,
                stagingRecovery = {
                    recoveryCalls += 1
                    journal.resolveByRecovery(11, "exact staging cleanup completed")
                    AnkiMediaRecoveryReport(1, 0, 0)
                },
            )

        val result = service.perform(AnkiRemediationCommand.RetryStagingCleanup(11))

        assertTrue(result is AnkiRemediationCommandResult.Resolved)
        assertEquals(1, recoveryCalls)
        assertTrue(journal.acknowledgedIds.isEmpty())
        assertTrue(journal.reviewedIds.isEmpty())
        assertEquals("exact staging cleanup completed", journal.resolved.single().compactEvidence)
    }

    @Test
    fun `staging retry reports still pending when cleanup remains quarantined`() {
        val journal =
            FakeRemediationJournal(
                mutableListOf(record(12, RemediationKind.STAGING_QUARANTINED)),
            )
        val service =
            service(
                journal = journal,
                stagingRecovery = { AnkiMediaRecoveryReport(0, 1, 0) },
            )

        val result = service.perform(AnkiRemediationCommand.RetryStagingCleanup(12))

        assertTrue(result is AnkiRemediationCommandResult.StillPending)
        assertEquals(listOf(12L), result.inventory.pending.map(AnkiPendingRemediation::id))
        assertTrue(journal.resolved.isEmpty())
    }

    @Test
    fun `uncertain provider commits are never accepted by the staging retry path`() {
        val journal =
            FakeRemediationJournal(
                mutableListOf(
                    record(20, RemediationKind.DECK_COMMIT_UNCERTAIN),
                    record(21, RemediationKind.MEDIA_COMMIT_UNCERTAIN),
                    record(22, RemediationKind.NOTE_COMMIT_UNCERTAIN),
                ),
            )
        var recoveryCalls = 0
        val service =
            service(
                journal = journal,
                stagingRecovery = {
                    recoveryCalls += 1
                    AnkiMediaRecoveryReport(0, 0, 0)
                },
            )

        listOf(20L, 21L, 22L).forEach { id ->
            assertFailure(AnkiRemediationFailure.ACTION_NOT_ALLOWED) {
                service.perform(AnkiRemediationCommand.RetryStagingCleanup(id))
            }
        }

        assertEquals(0, recoveryCalls)
        assertTrue(journal.acknowledgedIds.isEmpty())
        assertTrue(journal.reviewedIds.isEmpty())
        assertEquals(3, journal.open.size)
    }

    @Test
    fun `external review resolution validates typed evidence and retains the resolved row`() {
        val journal =
            FakeRemediationJournal(
                mutableListOf(record(30, RemediationKind.NOTE_COMMIT_UNCERTAIN)),
            )
        val service = service(journal)

        assertFailure(AnkiRemediationFailure.REVIEW_OUTCOME_NOT_ALLOWED) {
            service.perform(
                AnkiRemediationCommand.ResolveAfterExternalReview(
                    30,
                    AnkiExternalReviewOutcome.CURRENT_STATE_ACCEPTED_OR_CORRECTED,
                ),
            )
        }
        assertTrue(journal.reviewedIds.isEmpty())

        val result =
            service.perform(
                AnkiRemediationCommand.ResolveAfterExternalReview(
                    30,
                    AnkiExternalReviewOutcome.NOT_COMMITTED_CONFIRMED,
                ),
            )

        assertTrue(result is AnkiRemediationCommandResult.Resolved)
        assertEquals(listOf(30L), journal.reviewedIds)
        val retained = journal.resolved.single()
        assertEquals(RemediationState.RESOLVED, retained.state)
        assertEquals(
            "userReview=not_committed_confirmed;remediationType=NOTE_COMMIT_UNCERTAIN",
            retained.compactEvidence,
        )
    }

    @Test
    fun `media uncertainty requires typed abandonment and cannot use external review`() {
        val journal =
            FakeRemediationJournal(
                mutableListOf(record(31, RemediationKind.MEDIA_COMMIT_UNCERTAIN)),
            )

        assertFailure(AnkiRemediationFailure.ACTION_NOT_ALLOWED) {
            service(journal).perform(
                AnkiRemediationCommand.ResolveAfterExternalReview(
                    31,
                    AnkiExternalReviewOutcome.COMMIT_CONFIRMED,
                ),
            )
        }

        assertTrue(journal.reviewedIds.isEmpty())
        val result =
            service(journal).perform(AnkiRemediationCommand.AcknowledgeUncertainMedia(31))
        assertTrue(result is AnkiRemediationCommandResult.Resolved)
        assertEquals(listOf(31L), journal.uncertainAcknowledgedIds)
        assertTrue(journal.open.isEmpty())
        assertTrue(
            journal.resolved.single().compactEvidence.orEmpty().contains("possible orphaned media"),
        )
    }

    @Test
    fun `interrupted work reconciliation delegates once and finishes its durable boundary`() {
        val journal = FakeRemediationJournal()
        val cancelled = AtomicBoolean(false)
        var reconciliations = 0
        val service =
            service(
                journal = journal,
                interruptedWorkRecovery = {
                    reconciliations += 1
                    journal.open += record(40, RemediationKind.DECK_COMMIT_UNCERTAIN)
                    cancelled.set(true)
                },
            )

        val inventory = service.reconcileInterruptedWork(cancellation(cancelled))

        assertEquals(1, reconciliations)
        assertEquals(listOf(40L), inventory.pending.map(AnkiPendingRemediation::id))
    }

    @Test
    fun `pre-entry cancellation prevents inventory reads and every recovery action`() {
        val journal =
            FakeRemediationJournal(
                mutableListOf(record(50, RemediationKind.STAGING_QUARANTINED)),
            )
        var reconciliations = 0
        var stagingRecoveries = 0
        val service =
            service(
                journal = journal,
                interruptedWorkRecovery = { reconciliations += 1 },
                stagingRecovery = {
                    stagingRecoveries += 1
                    AnkiMediaRecoveryReport(0, 0, 0)
                },
            )
        val cancelled = cancellation(AtomicBoolean(true))

        assertFailure(AnkiRemediationFailure.CANCELLED) { service.inventory(cancelled) }
        assertFailure(AnkiRemediationFailure.CANCELLED) {
            service.reconcileInterruptedWork(cancelled)
        }
        assertFailure(AnkiRemediationFailure.CANCELLED) {
            service.perform(AnkiRemediationCommand.RetryStagingCleanup(50), cancelled)
        }

        assertEquals(0, journal.readCount)
        assertEquals(0, reconciliations)
        assertEquals(0, stagingRecoveries)
    }

    @Test
    fun `concurrent callers fail fast instead of overlapping journal access`() {
        val journal = FakeRemediationJournal()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        journal.beforeRead = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val service = service(journal)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<AnkiRemediationInventory> { service.inventory() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            assertFailure(AnkiRemediationFailure.BUSY) { service.inventory() }

            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS).pending.isEmpty())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        assertEquals(1, journal.maxConcurrentReads.get())
    }

    @Test
    fun `inventory fails closed on duplicate or malformed durable identities`() {
        val duplicate = record(60, RemediationKind.DECK_COMMIT_UNCERTAIN)
        assertFailure(AnkiRemediationFailure.CORRUPT_INVENTORY) {
            service(FakeRemediationJournal(mutableListOf(duplicate, duplicate))).inventory()
        }

        val malformed =
            record(61, RemediationKind.STAGING_QUARANTINED).copy(
                stagingSubjectId = 999,
            )
        assertFailure(AnkiRemediationFailure.CORRUPT_INVENTORY) {
            service(FakeRemediationJournal(mutableListOf(malformed))).inventory()
        }
    }

    @Test
    fun `every public operation enforces the worker thread boundary`() {
        val checks = AtomicInteger()
        val journal = FakeRemediationJournal()
        val service = service(journal, workerThreadGuard = { checks.incrementAndGet() })

        service.inventory()
        service.reconcileInterruptedWork()

        assertEquals(2, checks.get())
    }

    private fun service(
        journal: FakeRemediationJournal,
        interruptedWorkRecovery: InterruptedAnkiWorkRecovery = InterruptedAnkiWorkRecovery { },
        stagingRecovery: MediaStagingRecovery = MediaStagingRecovery {
            AnkiMediaRecoveryReport(0, 0, 0)
        },
        workerThreadGuard: WorkerThreadGuard = WorkerThreadGuard { },
    ) = AnkiRemediationService(
        journal = journal,
        interruptedWorkRecovery = interruptedWorkRecovery,
        stagingRecovery = stagingRecovery,
        workerThreadGuard = workerThreadGuard,
        strings =
            StringResourceResolver { resourceId, formatArguments ->
                when (resourceId) {
                    R.string.anki_recovery_item_unknown_summary ->
                        formatArguments.single().toString()
                    R.string.anki_recovery_summary_note_postcheck_failed ->
                        "A committed note requires review because exact postchecks failed"
                    R.string.anki_recovery_summary_note_postcheck_unfinished ->
                        "A committed note requires review because postchecks did not finish"
                    else -> "resource:$resourceId"
                }
            },
        processLock = AnkiRemediationProcessLock(),
    )

    private fun cancellation(cancelled: AtomicBoolean): AnkiCancellation =
        object : AnkiCancellation {
            override fun isCancelled(): Boolean = cancelled.get()

            override fun invokeOnCancellation(listener: () -> Unit): CancellationRegistration =
                CancellationRegistration { }
        }

    private fun record(
        id: Long,
        kind: RemediationKind,
    ): RemediationRecord {
        val parentId =
            when (kind) {
                RemediationKind.STAGING_QUARANTINED -> null
                else -> 100L + id
            }
        val claimId =
            when (kind) {
                RemediationKind.MEDIA_COMMIT_UNCERTAIN,
                RemediationKind.MEDIA_STORED_UNATTACHED,
                -> 200L + id
                else -> null
            }
        val stagingId = if (kind == RemediationKind.STAGING_QUARANTINED) 300L + id else null
        return RemediationRecord(
            id = id,
            parentId = parentId,
            claimId = claimId,
            stagingId = stagingId,
            stagingSubjectId = stagingId,
            kind = kind,
            state = RemediationState.OPEN,
            summary = "summary-$id",
            compactEvidence = "evidence-$id",
            createdAtMs = id,
            updatedAtMs = id,
        )
    }

    private fun assertFailure(
        expected: AnkiRemediationFailure,
        block: () -> Unit,
    ): AnkiRemediationException {
        try {
            block()
            fail("Expected $expected")
        } catch (error: AnkiRemediationException) {
            assertEquals(expected, error.failure)
            return error
        }
        throw AssertionError("unreachable")
    }
}

private class FakeRemediationJournal(
    val open: MutableList<RemediationRecord> = mutableListOf(),
) : AnkiRemediationJournal {
    val resolved = mutableListOf<RemediationRecord>()
    val acknowledgedIds = mutableListOf<Long>()
    val uncertainAcknowledgedIds = mutableListOf<Long>()
    val reviewedIds = mutableListOf<Long>()
    var beforeRead: (() -> Unit)? = null
    var readCount = 0
    val maxConcurrentReads = AtomicInteger()
    private val concurrentReads = AtomicInteger()

    override fun openRemediations(): List<RemediationRecord> {
        readCount += 1
        val concurrent = concurrentReads.incrementAndGet()
        maxConcurrentReads.updateAndGet { current -> maxOf(current, concurrent) }
        return try {
            beforeRead?.invoke()
            open.toList()
        } finally {
            concurrentReads.decrementAndGet()
        }
    }

    override fun acknowledgeUnattachedMedia(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord {
        acknowledgedIds += remediationId
        return resolve(remediationId, compactEvidence)
    }

    override fun acknowledgeUncertainMedia(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord {
        uncertainAcknowledgedIds += remediationId
        return resolve(remediationId, compactEvidence)
    }

    override fun resolveAfterExternalReview(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord {
        reviewedIds += remediationId
        return resolve(remediationId, compactEvidence)
    }

    fun resolveByRecovery(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord = resolve(remediationId, compactEvidence)

    private fun resolve(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord {
        val current = open.single { it.id == remediationId }
        open.remove(current)
        return current.copy(
            state = RemediationState.RESOLVED,
            compactEvidence = compactEvidence,
            updatedAtMs = current.updatedAtMs + 1,
        ).also(resolved::add)
    }
}
