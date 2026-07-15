package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.ReleaseState
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiRunStateRegistryTest {
    @Test
    fun `release is absent without a tombstone and registration stays single-run`() {
        val registry = AnkiRunStateRegistry()

        assertEquals(ReleaseState.ABSENT, registry.release(RUN_ID, true))
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        assertFalse(registry.register(OTHER_RUN_ID, AnkiCancellation.NONE))
        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(ReleaseState.ABSENT, registry.release(RUN_ID, true))
        assertTrue(registry.register(OTHER_RUN_ID, AnkiCancellation.NONE))
    }

    @Test
    fun `release defers for owners rejects new admission and cleans exactly once`() {
        val cleanupCalls = Collections.synchronizedList(mutableListOf<Pair<String, Set<String>?>>())
        val registry = AnkiRunStateRegistry { runId, ids -> cleanupCalls += runId to ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        val admitted = CountDownLatch(2)
        val finish = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(2) {
            thread {
                try {
                    registry.withOwner(RUN_ID) {
                        admitted.countDown()
                        finish.await(5, TimeUnit.SECONDS)
                    }
                } catch (failure: Throwable) {
                    failures += failure
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(admitted.await(5, TimeUnit.SECONDS))

        assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
        assertThrows(RunReleasingException::class.java) { registry.withOwner(RUN_ID) {} }
        finish.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty())
        assertEquals(listOf(RUN_ID to emptySet<String>()), cleanupCalls)
        assertEquals(ReleaseState.ABSENT, registry.release(RUN_ID, true))
    }

    @Test
    fun `false acknowledgement and receipt failure are sticky`() {
        val acknowledgements = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> acknowledgements += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            registry.retainDurableTerminalResponse(owner, REQUEST_ID)
            registry.markTerminalResponseFailure(owner)
        }

        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(listOf(null), acknowledgements)

        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, false))
        assertEquals(listOf(null, null), acknowledgements)
    }

    @Test
    fun `terminal receipts are bounded and duplicate receipt fails closed`() {
        val acknowledgements = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> acknowledgements += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            repeat(8192) { index ->
                registry.retainDurableTerminalResponse(owner, "anki_${index.toString(16).padStart(32, '0')}")
            }
            assertThrows(RunStateCapacityException::class.java) {
                registry.retainDurableTerminalResponse(owner, "anki_${"f".repeat(32)}")
            }
        }
        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(listOf(null), acknowledgements)

        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            registry.retainDurableTerminalResponse(owner, REQUEST_ID)
            assertThrows(RunStateConflictException::class.java) {
                registry.retainDurableTerminalResponse(owner, REQUEST_ID)
            }
        }
        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(listOf(null, null), acknowledgements)
    }

    @Test
    fun `receipt after first true release fails and forces abandonment`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        val ownerReady = CountDownLatch(1)
        val retain = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val worker =
            thread {
                try {
                    registry.withOwner(RUN_ID) { owner ->
                        ownerReady.countDown()
                        check(retain.await(5, TimeUnit.SECONDS))
                        registry.retainDurableTerminalResponse(owner, REQUEST_ID)
                    }
                } catch (failure: Throwable) {
                    failures += failure
                } finally {
                    finished.countDown()
                }
            }
        assertTrue(ownerReady.await(5, TimeUnit.SECONDS))
        assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
        retain.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        worker.join(5_000)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is RunStateConflictException)
        assertEquals(listOf(null), cleanup)
    }

    @Test
    fun `first true release freezes the exact immutable pre-release receipts`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))

        registry.withOwner(RUN_ID) { owner ->
            registry.retainDurableTerminalResponse(owner, REQUEST_ID)
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
        }

        assertEquals(listOf(setOf(REQUEST_ID)), cleanup)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (requireNotNull(cleanup.single()) as MutableSet<String>).add(
                "anki_${"f".repeat(32)}",
            )
        }
    }

    @Test
    fun `encoding failure after first true release is sticky abandonment`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))

        registry.withOwner(RUN_ID) { owner ->
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
            registry.markTerminalResponseFailure(owner)
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
        }

        assertEquals(listOf(null), cleanup)
    }

    @Test
    fun `live-owner repeated release makes true then false acknowledgement sticky`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))

        registry.withOwner(RUN_ID) { owner ->
            registry.retainDurableTerminalResponse(owner, REQUEST_ID)
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, false))
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
        }

        assertEquals(listOf(null), cleanup)
        assertEquals(ReleaseState.ABSENT, registry.release(RUN_ID, true))
    }

    @Test
    fun `live-owner concurrent release keeps false then true acknowledgement false`() {
        val cleanup = Collections.synchronizedList(mutableListOf<Set<String>?>())
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        val admitted = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val ownerDone = CountDownLatch(1)
        val owner =
            thread {
                registry.withOwner(RUN_ID) { runOwner ->
                    registry.retainDurableTerminalResponse(runOwner, REQUEST_ID)
                    admitted.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                }
                ownerDone.countDown()
            }
        assertTrue(admitted.await(5, TimeUnit.SECONDS))
        assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, false))

        val releasesDone = CountDownLatch(8)
        val releaseResults = Collections.synchronizedList(mutableListOf<ReleaseState>())
        repeat(8) {
            thread {
                releaseResults += registry.release(RUN_ID, true)
                releasesDone.countDown()
            }
        }
        assertTrue(releasesDone.await(5, TimeUnit.SECONDS))
        assertEquals(List(8) { ReleaseState.DEFERRED }, releaseResults)
        finish.countDown()
        assertTrue(ownerDone.await(5, TimeUnit.SECONDS))
        owner.join(5_000)

        assertEquals(listOf(null), cleanup)
        assertEquals(ReleaseState.ABSENT, registry.release(RUN_ID, true))
    }

    @Test
    fun `cleanup failure quarantines the run and explicit release retries`() {
        var calls = 0
        val registry =
            AnkiRunStateRegistry { _, ids ->
                calls += 1
                assertEquals(emptySet<String>(), ids)
                if (calls == 1) error("injected cleanup failure")
            }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        assertThrows(IllegalStateException::class.java) { registry.release(RUN_ID, true) }
        assertFalse(registry.register(OTHER_RUN_ID, AnkiCancellation.NONE))
        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(2, calls)
    }

    @Test
    fun `cleanup retry preserves the exact non-empty durable response ids`() {
        val expected = setOf(REQUEST_ID, OTHER_REQUEST_ID)
        val calls = mutableListOf<Set<String>?>()
        val registry =
            AnkiRunStateRegistry { _, ids ->
                calls += ids
                assertEquals(expected, ids)
                if (calls.size == 1) error("injected cleanup failure")
            }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            registry.retainDurableTerminalResponse(owner, REQUEST_ID)
            registry.retainDurableTerminalResponse(owner, OTHER_REQUEST_ID)
        }

        assertThrows(IllegalStateException::class.java) { registry.release(RUN_ID, true) }
        assertFalse(registry.register(OTHER_RUN_ID, AnkiCancellation.NONE))
        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(listOf(expected, expected), calls)
    }

    @Test
    fun `media acknowledgements are bounded by asset and unique filename`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val first = MediaAcknowledgement(ASSET_ID, "clip.mp3", 1L)
            registry.recordMediaAcknowledgement(owner, first)
            assertEquals(first, registry.mediaAcknowledgement(owner, ASSET_ID))
            assertThrows(RunStateConflictException::class.java) {
                registry.recordMediaAcknowledgement(
                    owner,
                    MediaAcknowledgement(OTHER_ASSET_ID, "clip.mp3", 2L),
                )
            }
        }
    }

    @Test
    fun `known cursor is one-use scoped and traversal disappears at the end`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val initialization = registry.beginKnownTraversal(owner, listOf("Excluded"))
            val first =
                registry.finishKnownTraversalInitialization(
                    owner,
                    initialization,
                    (1L..257L).toList(),
                )
            val cursor = registry.completeKnownPage(owner, first, "cursor_${"1".repeat(32)}")
            assertNotNull(cursor)
            assertThrows(InvalidCapabilityException::class.java) {
                registry.reserveKnownPage(
                    owner,
                    listOf("Wrong"),
                    requireNotNull(cursor),
                )
            }
            val second =
                registry.reserveKnownPage(
                    owner,
                    listOf("Excluded"),
                    requireNotNull(cursor),
                )
            assertEquals(listOf(257L), second.noteIds)
            assertNull(registry.completeKnownPage(owner, second, null))
            assertThrows(InvalidCapabilityException::class.java) {
                registry.reserveKnownPage(owner, listOf("Excluded"), cursor)
            }
        }
    }

    private companion object {
        const val RUN_ID = "run_11111111111111111111111111111111"
        const val OTHER_RUN_ID = "run_22222222222222222222222222222222"
        const val REQUEST_ID = "anki_11111111111111111111111111111111"
        const val OTHER_REQUEST_ID = "anki_22222222222222222222222222222222"
        const val ASSET_ID = "asset_11111111111111111111111111111111"
        const val OTHER_ASSET_ID = "asset_22222222222222222222222222222222"
    }
}
