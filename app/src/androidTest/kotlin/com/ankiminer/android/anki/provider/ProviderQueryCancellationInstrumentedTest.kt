package com.ankiminer.android.anki.provider

import android.database.MatrixCursor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderQueryCancellationInstrumentedTest {
    @Test
    fun deadline_covers_scope_lifetime_and_remains_distinct_from_user_cancellation() {
        val cancellation = TestCancellation()
        val scheduler = CapturingDeadlineScheduler()
        val scope = ProviderQueryCancellation(cancellation, 30_000L, scheduler)

        assertFalse(scope.signal.isCanceled)
        scheduler.fire()
        assertTrue(scope.signal.isCanceled)
        assertEquals(
            ProviderFailureKind.TIMEOUT,
            assertThrows(ProviderGatewayException::class.java, scope::throwIfCancelled).kind,
        )

        scope.close()
        assertEquals(1, scheduler.closeCount)
    }

    @Test
    fun first_cancellation_cause_wins_and_close_unregisters_both_sources() {
        val cancellation = TestCancellation()
        val scheduler = CapturingDeadlineScheduler()
        val scope = ProviderQueryCancellation(cancellation, 30_000L, scheduler)

        cancellation.cancel()
        scheduler.fire()
        assertEquals(
            ProviderFailureKind.CANCELLED,
            assertThrows(ProviderGatewayException::class.java, scope::throwIfCancelled).kind,
        )

        scope.close()
        assertEquals(1, cancellation.closeCount)
        assertEquals(1, scheduler.closeCount)
    }

    @Test
    fun deadline_or_user_cancellation_during_the_final_delegate_result_cannot_escape() {
        val deadlineScheduler = CapturingDeadlineScheduler()
        val deadlineScope =
            ProviderQueryCancellation(TestCancellation(), 30_000L, deadlineScheduler)
        assertEquals(
            ProviderFailureKind.TIMEOUT,
            assertThrows(ProviderGatewayException::class.java) {
                deadlineScope.checkedCall {
                    deadlineScheduler.fire()
                    false
                }
            }.kind,
        )
        assertEquals(
            ProviderFailureKind.TIMEOUT,
            deadlineScope.mapFailure(IllegalStateException("late provider failure")).kind,
        )
        deadlineScope.close()

        val userCancellation = TestCancellation()
        val userScope =
            ProviderQueryCancellation(
                userCancellation,
                30_000L,
                CapturingDeadlineScheduler(),
            )
        assertEquals(
            ProviderFailureKind.CANCELLED,
            assertThrows(ProviderGatewayException::class.java) {
                userScope.checkedCall {
                    userCancellation.cancel()
                    "last cell"
                }
            }.kind,
        )
        userScope.close()
    }

    @Test
    fun recorded_cause_dominates_a_late_provider_shape_failure() {
        val deadlineScheduler = CapturingDeadlineScheduler()
        val deadlineScope =
            ProviderQueryCancellation(TestCancellation(), 30_000L, deadlineScheduler)
        assertEquals(
            ProviderFailureKind.TIMEOUT,
            assertThrows(ProviderGatewayException::class.java) {
                deadlineScope.checkedCall<Unit> {
                    deadlineScheduler.fire()
                    throw ProviderGatewayException(ProviderFailureKind.QUERY_FAILED)
                }
            }.kind,
        )
        deadlineScope.close()

        val userCancellation = TestCancellation()
        val userScope =
            ProviderQueryCancellation(
                userCancellation,
                30_000L,
                CapturingDeadlineScheduler(),
            )
        assertEquals(
            ProviderFailureKind.CANCELLED,
            assertThrows(ProviderGatewayException::class.java) {
                userScope.checkedCall<Unit> {
                    userCancellation.cancel()
                    throw ProviderGatewayException(ProviderFailureKind.QUERY_FAILED)
                }
            }.kind,
        )
        userScope.close()

        val uncancelledScope =
            ProviderQueryCancellation(
                TestCancellation(),
                30_000L,
                CapturingDeadlineScheduler(),
            )
        assertEquals(
            ProviderFailureKind.PERMISSION_REQUIRED,
            assertThrows(ProviderGatewayException::class.java) {
                uncancelledScope.checkedCall<Unit> {
                    throw ProviderGatewayException(ProviderFailureKind.PERMISSION_REQUIRED)
                }
            }.kind,
        )
        uncancelledScope.close()
    }

    @Test
    fun gateway_closes_cursor_and_registrations_when_cancelled_after_resolver_return() {
        val cancellation = TestCancellation()
        val scheduler = CapturingDeadlineScheduler()
        val cursor = TrackingCursor(arrayOf("_id"))
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                deadlineScheduler = scheduler,
                accessStatusOverride = { AVAILABLE },
                resolverQueryOverride =
                    ProviderResolverQuery { _, _, _, _, _, _ ->
                        cancellation.cancel()
                        cursor
                    },
            )

        assertEquals(
            ProviderFailureKind.CANCELLED,
            assertThrows(ProviderGatewayException::class.java) {
                gateway.query(NOTE_SNAPSHOT_QUERY, cancellation)
            }.kind,
        )
        assertEquals(1, cursor.closeCount)
        assertEquals(1, cancellation.closeCount)
        assertEquals(1, scheduler.closeCount)
    }

    @Test
    fun bulk_reads_get_the_bulk_deadline_and_every_other_read_keeps_the_interactive_one() {
        // The deadline is armed for the whole cursor walk, so a ceiling-bounded scan of up to a
        // million rows cannot share the deadline a screen waits on: the timeout would always beat
        // the row ceiling, and the ceiling is the bound that refuses with a reason.
        val scheduler = CapturingDeadlineScheduler()
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                deadlineScheduler = scheduler,
                accessStatusOverride = { AVAILABLE },
                resolverQueryOverride =
                    ProviderResolverQuery { _, _, _, _, _, _ -> MatrixCursor(arrayOf("_id")) },
            )

        gateway.query(NOTE_SNAPSHOT_QUERY, TestCancellation())?.close()
        assertEquals(30_000L, scheduler.lastDelayMs)

        gateway.query(BULK_NOTE_SNAPSHOT_QUERY, TestCancellation())?.close()
        assertEquals(300_000L, scheduler.lastDelayMs)
    }

    @Test
    fun gateway_constructor_and_close_failure_still_release_every_registration() {
        val cancellation = TestCancellation()
        val scheduler = CapturingDeadlineScheduler()
        val cursor = TrackingCursor(arrayOf("wrong"), failOnClose = true)
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                deadlineScheduler = scheduler,
                accessStatusOverride = { AVAILABLE },
                resolverQueryOverride =
                    ProviderResolverQuery { _, _, _, _, _, _ -> cursor },
            )

        assertEquals(
            ProviderFailureKind.QUERY_FAILED,
            assertThrows(ProviderGatewayException::class.java) {
                gateway.query(NOTE_SNAPSHOT_QUERY, cancellation)
            }.kind,
        )
        assertEquals(1, cursor.closeCount)
        assertEquals(1, cancellation.closeCount)
        assertEquals(1, scheduler.closeCount)
    }

    @Test
    fun resolver_null_is_empty_only_while_provider_remains_available() {
        fun gatewayFor(statusAfterQuery: ProviderAccessStatus): Pair<ContentResolverAnkiGateway, () -> Int> {
            var checks = 0
            val gateway =
                ContentResolverAnkiGateway(
                    context = ApplicationProvider.getApplicationContext(),
                    workerThreadGuard = WorkerThreadGuard { },
                    deadlineScheduler = CapturingDeadlineScheduler(),
                    accessStatusOverride = {
                        checks += 1
                        if (checks == 1) AVAILABLE else statusAfterQuery
                    },
                    resolverQueryOverride =
                        ProviderResolverQuery { _, _, _, _, _, _ -> null },
                )
            return gateway to { checks }
        }

        val (available, availableChecks) = gatewayFor(AVAILABLE)
        assertNull(available.query(NOTE_SNAPSHOT_QUERY, AnkiCancellation.NONE))
        assertEquals(2, availableChecks())

        val failures =
            listOf(
                ProviderAccessStatus.Absent to ProviderFailureKind.PROVIDER_UNAVAILABLE,
                ProviderAccessStatus.ApiDisabled to ProviderFailureKind.API_DISABLED,
                ProviderAccessStatus.Incompatible(1) to ProviderFailureKind.API_DISABLED,
                ProviderAccessStatus.PermissionRequired to ProviderFailureKind.PERMISSION_REQUIRED,
            )
        for ((status, expected) in failures) {
            val (gateway, checks) = gatewayFor(status)
            assertEquals(
                expected,
                assertThrows(ProviderGatewayException::class.java) {
                    gateway.query(NOTE_SNAPSHOT_QUERY, AnkiCancellation.NONE)
                }.kind,
            )
            assertEquals(2, checks())
        }
    }

    @Test
    fun gateway_revalidates_query_shape_before_resolver_access() {
        val mutableIds = mutableListOf(1L)
        val query =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTES_V2,
                projection = ProviderQueryShapes.NOTE_PAGE_PROJECTION,
                selection = ProviderSelection.NoteIds(mutableIds),
            )
        mutableIds.clear()
        var resolverCalls = 0
        val gateway =
            ContentResolverAnkiGateway(
                context = ApplicationProvider.getApplicationContext(),
                workerThreadGuard = WorkerThreadGuard { },
                accessStatusOverride = { AVAILABLE },
                resolverQueryOverride =
                    ProviderResolverQuery { _, _, _, _, _, _ ->
                        resolverCalls += 1
                        null
                    },
            )

        assertEquals(
            ProviderFailureKind.QUERY_FAILED,
            assertThrows(ProviderGatewayException::class.java) {
                gateway.query(query, AnkiCancellation.NONE)
            }.kind,
        )
        assertEquals(0, resolverCalls)
    }

    private class CapturingDeadlineScheduler : ProviderDeadlineScheduler {
        private var action: (() -> Unit)? = null
        var closeCount = 0
            private set
        var lastDelayMs: Long? = null
            private set

        override fun schedule(
            delayMs: Long,
            action: () -> Unit,
        ): CancellationRegistration {
            lastDelayMs = delayMs
            this.action = action
            return CancellationRegistration { closeCount += 1 }
        }

        fun fire() = requireNotNull(action).invoke()
    }

    private class TestCancellation : AnkiCancellation {
        private val cancelled = AtomicBoolean(false)
        private val listeners = CopyOnWriteArrayList<() -> Unit>()
        var closeCount = 0
            private set

        override fun isCancelled(): Boolean = cancelled.get()

        override fun invokeOnCancellation(listener: () -> Unit): CancellationRegistration {
            if (cancelled.get()) listener() else listeners += listener
            return CancellationRegistration {
                listeners -= listener
                closeCount += 1
            }
        }

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) listeners.forEach { it() }
        }
    }

    private class TrackingCursor(
        columnNames: Array<String>,
        private val failOnClose: Boolean = false,
    ) : MatrixCursor(columnNames) {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
            super.close()
            if (failOnClose) error("injected close failure")
        }
    }

    private companion object {
        val AVAILABLE = ProviderAccessStatus.Available("com.ichi2.anki", 2, 1L)
        val NOTE_SNAPSHOT_QUERY =
            ProviderQuery(
                endpoint = ProviderEndpoint.NOTES_V2,
                projection = ProviderQueryShapes.NOTE_ID_PROJECTION,
                sortOrder = ProviderOrder.NOTE_ID_ASCENDING,
            )
        val BULK_NOTE_SNAPSHOT_QUERY =
            NOTE_SNAPSHOT_QUERY.copy(deadline = ProviderReadDeadline.BULK)
    }
}
