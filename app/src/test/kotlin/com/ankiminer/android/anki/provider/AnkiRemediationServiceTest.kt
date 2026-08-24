package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.RemediationKind
import com.ankiminer.android.anki.journal.RemediationRecord
import com.ankiminer.android.anki.journal.RemediationState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnkiRemediationServiceTest {
    @Test
    fun `inventory exposes every durable kind sorted by creation identity`() {
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
        assertTrue(pending.all { it.compactEvidence == "evidence-${it.id}" })
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
    fun `inventory wraps journal read failures as typed journal failures`() {
        val journal = FakeRemediationJournal()
        journal.beforeRead = { throw IllegalStateException("read broke") }
        assertFailure(AnkiRemediationFailure.JOURNAL_FAILED) {
            service(journal).inventory()
        }
    }

    @Test
    fun `inventory is cancelled before any journal read`() {
        val journal = FakeRemediationJournal()
        val cancelled = AtomicBoolean(true)
        assertFailure(AnkiRemediationFailure.CANCELLED) {
            service(journal).inventory(cancellation(cancelled))
        }
        assertEquals(0, journal.readCount)
    }

    @Test
    fun `concurrent inventory reads are serialized by the process lock`() {
        val journal = FakeRemediationJournal()
        val lock = AnkiRemediationProcessLock()
        val service = service(journal, processLock = lock)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        journal.beforeRead = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<AnkiRemediationInventory> { service.inventory() }
            check(entered.await(5, TimeUnit.SECONDS))
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
    fun `inventory enforces the worker thread boundary`() {
        val checks = AtomicInteger()
        val service = service(FakeRemediationJournal(), workerThreadGuard = { checks.incrementAndGet() })

        service.inventory()

        assertEquals(1, checks.get())
    }

    private fun service(
        journal: FakeRemediationJournal,
        workerThreadGuard: WorkerThreadGuard = WorkerThreadGuard { },
        processLock: AnkiRemediationProcessLock = AnkiRemediationProcessLock(),
    ) =
        AnkiRemediationService(
            journal = journal,
            workerThreadGuard = workerThreadGuard,
            processLock = processLock,
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
}
