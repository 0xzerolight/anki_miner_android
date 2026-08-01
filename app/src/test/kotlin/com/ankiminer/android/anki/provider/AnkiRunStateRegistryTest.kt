package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.ReleaseState
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
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
    fun `closed startup admission rejects registration until recovery opens`() {
        var open = false
        val registry = AnkiRunStateRegistry(startupAdmission = AnkiStartupAdmission { open })

        assertFalse(registry.register(RUN_ID, AnkiCancellation.NONE))
        open = true
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
    }

    @Test
    fun `one run-scoped target reservation excludes concurrent verification`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        val reserved = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val done = CountDownLatch(1)
        val worker =
            thread {
                registry.withOwner(RUN_ID) { owner ->
                    val reservation = registry.beginTargetVerification(owner, verifyRequest())
                    reserved.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                    registry.abortTargetVerification(owner, reservation)
                }
                done.countDown()
            }
        assertTrue(reserved.await(5, TimeUnit.SECONDS))

        registry.withOwner(RUN_ID) { owner ->
            assertThrows(TargetVerificationInProgressException::class.java) {
                registry.beginTargetVerification(owner, verifyRequest(OTHER_REQUEST_ID))
            }
        }
        finish.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        worker.join(5_000)
    }

    @Test
    fun `durable target and response id commit atomically then release exact evidence`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val request = verifyRequest()
            val reservation = registry.beginTargetVerification(owner, request)
            registry.commitDurableTargetResponse(owner, reservation, request.requestId, target())

            assertEquals(target(), registry.target(owner))
        }

        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(listOf(setOf(REQUEST_ID)), cleanup)
    }

    @Test
    fun `failed durable target commit installs neither id nor target and is sticky`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            registry.retainDurableTerminalResponse(owner, REQUEST_ID)
            val request = verifyRequest()
            val reservation = registry.beginTargetVerification(owner, request)

            assertThrows(RunStateConflictException::class.java) {
                registry.commitDurableTargetResponse(owner, reservation, request.requestId, target())
            }
            assertNull(registry.target(owner))
            registry.abortTargetVerification(owner, reservation)
        }

        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(listOf(null), cleanup)
    }

    @Test
    fun `durable verify error atomically clears an installed target`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val firstRequest = verifyRequest()
            val first = registry.beginTargetVerification(owner, firstRequest)
            registry.commitDurableTargetResponse(owner, first, firstRequest.requestId, target())
            assertEquals(target(), registry.target(owner))

            val errorRequest = verifyRequest(OTHER_REQUEST_ID)
            val error = registry.beginTargetVerification(owner, errorRequest)
            registry.commitDurableTargetResponse(owner, error, errorRequest.requestId, target = null)

            assertNull(registry.target(owner))
        }
    }

    @Test
    fun `duplicate durable replay failure quarantines an installed target`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val request = verifyRequest()
            val first = registry.beginTargetVerification(owner, request)
            registry.commitDurableTargetResponse(owner, first, request.requestId, target())
            val replay = registry.beginTargetVerification(owner, request)

            assertThrows(RunStateConflictException::class.java) {
                registry.commitDurableTargetResponse(owner, replay, request.requestId, target())
            }
            assertNull(registry.target(owner))
            registry.abortTargetVerification(owner, replay)
            assertThrows(RunStateConflictException::class.java) {
                registry.beginTargetVerification(owner, verifyRequest(OTHER_REQUEST_ID))
            }
        }

        assertThrows(RunStateConflictException::class.java) {
            registry.withOwner(RUN_ID) { }
        }

        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(listOf(null), cleanup)
    }

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
    fun `media acknowledgement filename collision cannot bypass atomic response admission`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val first = MediaAcknowledgement(ASSET_ID, "clip.mp3", 1L)
            registry.commitDurableMutationResponse(owner, REQUEST_ID, listOf(first))
            assertEquals(first, registry.mediaAcknowledgement(owner, ASSET_ID))
            assertThrows(RunStateConflictException::class.java) {
                registry.commitDurableMutationResponse(
                    owner,
                    OTHER_REQUEST_ID,
                    listOf(MediaAcknowledgement(OTHER_ASSET_ID, "clip.mp3", 2L)),
                )
            }
            assertNull(registry.mediaAcknowledgement(owner, OTHER_ASSET_ID))
        }
    }

    @Test
    fun `provider entry capability rejects wrong owner and exact scope mismatch`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val scope = mediaScope()
            val capability = registry.beginProviderEntry(owner, scope)
            registry.withOwner(RUN_ID) { wrongOwner ->
                assertThrows(InvalidCapabilityException::class.java) {
                    registry.authorizeProviderEntry(wrongOwner, capability, scope)
                }
            }
            assertThrows(InvalidCapabilityException::class.java) {
                registry.authorizeProviderEntry(
                    owner,
                    capability,
                    scope.copy(itemIdentity = OTHER_ASSET_ID),
                )
            }

            assertEquals(
                ProviderEntryAuthorization.AUTHORIZED,
                registry.authorizeProviderEntry(owner, capability, scope),
            )
            registry.completeProviderEntry(owner, capability, scope)
        }
    }

    @Test
    fun `provider entry authorization is one use and authorized entry cannot abort`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val scope = mediaScope()
            val capability = registry.beginProviderEntry(owner, scope)
            assertEquals(
                ProviderEntryAuthorization.AUTHORIZED,
                registry.authorizeProviderEntry(owner, capability, scope),
            )
            assertThrows(InvalidCapabilityException::class.java) {
                registry.authorizeProviderEntry(owner, capability, scope)
            }
            assertThrows(InvalidCapabilityException::class.java) {
                registry.abortProviderEntry(owner, capability, scope)
            }
            registry.completeProviderEntry(owner, capability, scope)
            assertThrows(InvalidCapabilityException::class.java) {
                registry.completeProviderEntry(owner, capability, scope)
            }
        }
    }

    @Test
    fun `provider entry cancellation before authorization is abortable`() {
        val cancellation = MutableAnkiCancellation()
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, cancellation))
        registry.withOwner(RUN_ID) { owner ->
            val scope = mediaScope()
            val capability = registry.beginProviderEntry(owner, scope)
            cancellation.cancel()

            assertEquals(
                ProviderEntryAuthorization.CANCELLED,
                registry.authorizeProviderEntry(owner, capability, scope),
            )
            assertThrows(InvalidCapabilityException::class.java) {
                registry.authorizeProviderEntry(owner, capability, scope)
            }
            registry.abortProviderEntry(owner, capability, scope)
        }
    }

    @Test
    fun `mandatory card reconciliation ignores cancellation but not operation scope`() {
        val cancellation = MutableAnkiCancellation()
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, cancellation))
        registry.withOwner(RUN_ID) { owner ->
            val cardScope =
                ProviderMutationScope(
                    requestId = REQUEST_ID,
                    operation = ProviderMutationOperation.CARD_ROUTING,
                    durableChildId = 3L,
                    itemIdentity = "42",
                )
            val cardCapability = registry.beginProviderEntry(owner, cardScope)
            cancellation.cancel()
            assertEquals(
                ProviderEntryAuthorization.AUTHORIZED,
                registry.authorizeMandatoryReconciliationEntry(owner, cardCapability, cardScope),
            )
            registry.completeProviderEntry(owner, cardCapability, cardScope)

            val mediaScope = mediaScope().copy(requestId = OTHER_REQUEST_ID)
            val mediaCapability = registry.beginProviderEntry(owner, mediaScope)
            assertThrows(InvalidCapabilityException::class.java) {
                registry.authorizeMandatoryReconciliationEntry(owner, mediaCapability, mediaScope)
            }
            registry.abortProviderEntry(owner, mediaCapability, mediaScope)
        }
    }

    @Test
    fun `mandatory card reconciliation still denies release`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val scope =
                ProviderMutationScope(
                    requestId = REQUEST_ID,
                    operation = ProviderMutationOperation.CARD_ROUTING,
                    durableChildId = 3L,
                    itemIdentity = "42",
                )
            val capability = registry.beginProviderEntry(owner, scope)
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
            assertEquals(
                ProviderEntryAuthorization.RELEASING,
                registry.authorizeMandatoryReconciliationEntry(owner, capability, scope),
            )
            registry.abortProviderEntry(owner, capability, scope)
        }
    }

    @Test
    fun `provider entry release before authorization is abortable`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val scope = mediaScope()
            val capability = registry.beginProviderEntry(owner, scope)
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))

            assertEquals(
                ProviderEntryAuthorization.RELEASING,
                registry.authorizeProviderEntry(owner, capability, scope),
            )
            registry.abortProviderEntry(owner, capability, scope)
        }
        assertEquals(listOf(emptySet<String>()), cleanup)
    }

    @Test
    fun `cancellation after provider authorization cannot revoke completion`() {
        val cancellation = MutableAnkiCancellation()
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, cancellation))
        registry.withOwner(RUN_ID) { owner ->
            val scope = mediaScope()
            val capability = registry.beginProviderEntry(owner, scope)
            assertEquals(
                ProviderEntryAuthorization.AUTHORIZED,
                registry.authorizeProviderEntry(owner, capability, scope),
            )
            cancellation.cancel()

            assertThrows(InvalidCapabilityException::class.java) {
                registry.abortProviderEntry(owner, capability, scope)
            }
            registry.completeProviderEntry(owner, capability, scope)
        }
        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
    }

    @Test
    fun `release after provider authorization waits for explicit completion`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val scope = mediaScope()
            val capability = registry.beginProviderEntry(owner, scope)
            assertEquals(
                ProviderEntryAuthorization.AUTHORIZED,
                registry.authorizeProviderEntry(owner, capability, scope),
            )
            assertEquals(ReleaseState.DEFERRED, registry.release(RUN_ID, true))
            assertThrows(InvalidCapabilityException::class.java) {
                registry.abortProviderEntry(owner, capability, scope)
            }
            registry.completeProviderEntry(owner, capability, scope)
        }
        assertEquals(listOf(emptySet<String>()), cleanup)
    }

    @Test
    fun `only one provider entry capability may be in flight`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val firstScope = mediaScope()
            val first = registry.beginProviderEntry(owner, firstScope)
            assertThrows(ProviderEntryInProgressException::class.java) {
                registry.beginProviderEntry(
                    owner,
                    firstScope.copy(requestId = OTHER_REQUEST_ID, itemIdentity = OTHER_ASSET_ID),
                )
            }
            registry.abortProviderEntry(owner, first, firstScope)

            val secondScope =
                firstScope.copy(requestId = OTHER_REQUEST_ID, itemIdentity = OTHER_ASSET_ID)
            val second = registry.beginProviderEntry(owner, secondScope)
            registry.abortProviderEntry(owner, second, secondScope)
        }
    }

    @Test
    fun `provider entry validates media note and card identities at the boundary`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            listOf(
                mediaScope(),
                ProviderMutationScope(
                    requestId = REQUEST_ID,
                    operation = ProviderMutationOperation.NOTE_INSERT,
                    durableChildId = 2L,
                    itemIdentity = NOTE_ID,
                ),
                ProviderMutationScope(
                    requestId = REQUEST_ID,
                    operation = ProviderMutationOperation.CARD_ROUTING,
                    durableChildId = 3L,
                    itemIdentity = "42",
                ),
            ).forEach { scope ->
                val capability = registry.beginProviderEntry(owner, scope)
                registry.abortProviderEntry(owner, capability, scope)
            }

            listOf(
                mediaScope().copy(requestId = "bad"),
                mediaScope().copy(durableChildId = 0L),
                mediaScope().copy(itemIdentity = "bad"),
                mediaScope().copy(
                    operation = ProviderMutationOperation.NOTE_INSERT,
                    itemIdentity = "bad",
                ),
                mediaScope().copy(
                    operation = ProviderMutationOperation.CARD_ROUTING,
                    itemIdentity = "01",
                ),
            ).forEach { scope ->
                assertThrows(InvalidCapabilityException::class.java) {
                    registry.beginProviderEntry(owner, scope)
                }
            }
        }
    }

    @Test
    fun `provider authorization observes a concurrent release without holding the registry lock`() {
        lateinit var registry: AnkiRunStateRegistry
        val releaseCompleted = CountDownLatch(1)
        var cancellationChecks = 0
        val cancellation =
            object : AnkiCancellation {
                override fun isCancelled(): Boolean {
                    cancellationChecks += 1
                    if (cancellationChecks == 1) return false
                    val releaser =
                        thread {
                            registry.release(RUN_ID, true)
                            releaseCompleted.countDown()
                        }
                    assertTrue(releaseCompleted.await(5, TimeUnit.SECONDS))
                    releaser.join(5_000)
                    return false
                }

                override fun invokeOnCancellation(listener: () -> Unit): CancellationRegistration =
                    CancellationRegistration { }
            }
        registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, cancellation))
        registry.withOwner(RUN_ID) { owner ->
            val scope = mediaScope()
            val capability = registry.beginProviderEntry(owner, scope)
            assertEquals(
                ProviderEntryAuthorization.RELEASING,
                registry.authorizeProviderEntry(owner, capability, scope),
            )
            registry.abortProviderEntry(owner, capability, scope)
        }
    }

    @Test
    fun `active provider entry blocks terminal admission before reconciliation`() {
        listOf(false, true).forEach { authorize ->
            val cleanup = mutableListOf<Set<String>?>()
            val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
            assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
            registry.withOwner(RUN_ID) { owner ->
                val targetRequest = verifyRequest(THIRD_REQUEST_ID)
                val targetReservation = registry.beginTargetVerification(owner, targetRequest)
                registry.commitDurableTargetResponse(
                    owner,
                    targetReservation,
                    targetRequest.requestId,
                    target(),
                )
                val scope = mediaScope()
                val capability = registry.beginProviderEntry(owner, scope)
                if (authorize) {
                    assertEquals(
                        ProviderEntryAuthorization.AUTHORIZED,
                        registry.authorizeProviderEntry(owner, capability, scope),
                    )
                }

                assertThrows(RunStateConflictException::class.java) {
                    registry.commitDurableMutationResponse(
                        owner,
                        REQUEST_ID,
                        listOf(MediaAcknowledgement(ASSET_ID, "clip.mp3", 1L)),
                    )
                }
                assertNull(registry.mediaAcknowledgement(owner, ASSET_ID))
                assertNull(registry.target(owner))
                if (authorize) {
                    registry.completeProviderEntry(owner, capability, scope)
                } else {
                    registry.abortProviderEntry(owner, capability, scope)
                }
            }
            assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
            assertEquals(listOf(null), cleanup)
        }
    }

    @Test
    fun `durable mutation response admits exact receipt and acknowledgements atomically`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val first = MediaAcknowledgement(ASSET_ID, "clip.mp3", 1L)
            val second = MediaAcknowledgement(OTHER_ASSET_ID, "image.webp", 2L)
            registry.commitDurableMutationResponse(owner, REQUEST_ID, listOf(first, second))

            assertEquals(first, registry.mediaAcknowledgement(owner, ASSET_ID))
            assertEquals(second, registry.mediaAcknowledgement(owner, OTHER_ASSET_ID))
        }
        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(listOf(setOf(REQUEST_ID)), cleanup)
    }

    @Test
    fun `invalid second acknowledgement installs no part of durable response`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val targetRequest = verifyRequest(OTHER_REQUEST_ID)
            val targetReservation = registry.beginTargetVerification(owner, targetRequest)
            registry.commitDurableTargetResponse(
                owner,
                targetReservation,
                targetRequest.requestId,
                target(),
            )
            assertThrows(RunStateConflictException::class.java) {
                registry.commitDurableMutationResponse(
                    owner,
                    REQUEST_ID,
                    listOf(
                        MediaAcknowledgement(ASSET_ID, "clip.mp3", 1L),
                        MediaAcknowledgement(OTHER_ASSET_ID, "[sound:bad.mp3]", 2L),
                    ),
                )
            }
            assertNull(registry.mediaAcknowledgement(owner, ASSET_ID))
            assertNull(registry.mediaAcknowledgement(owner, OTHER_ASSET_ID))
            assertNull(registry.target(owner))
        }
        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(listOf(null), cleanup)
    }

    @Test
    fun `duplicate acknowledgement filename or claim installs nothing`() {
        listOf(
            listOf(
                MediaAcknowledgement(ASSET_ID, "same.mp3", 1L),
                MediaAcknowledgement(OTHER_ASSET_ID, "same.mp3", 2L),
            ),
            listOf(
                MediaAcknowledgement(ASSET_ID, "first.mp3", 1L),
                MediaAcknowledgement(OTHER_ASSET_ID, "second.mp3", 1L),
            ),
        ).forEach { acknowledgements ->
            val registry = AnkiRunStateRegistry()
            assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
            registry.withOwner(RUN_ID) { owner ->
                assertThrows(RunStateConflictException::class.java) {
                    registry.commitDurableMutationResponse(owner, REQUEST_ID, acknowledgements)
                }
                assertNull(registry.mediaAcknowledgement(owner, ASSET_ID))
                assertNull(registry.mediaAcknowledgement(owner, OTHER_ASSET_ID))
            }
            assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        }
    }

    @Test
    fun `identical acknowledgement is reusable but conflicting existing mapping is sticky`() {
        val cleanup = mutableListOf<Set<String>?>()
        val registry = AnkiRunStateRegistry { _, ids -> cleanup += ids }
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val existing = MediaAcknowledgement(ASSET_ID, "clip.mp3", 1L)
            registry.commitDurableMutationResponse(owner, REQUEST_ID, listOf(existing))
            registry.commitDurableMutationResponse(owner, OTHER_REQUEST_ID, listOf(existing))

            assertThrows(RunStateConflictException::class.java) {
                registry.commitDurableMutationResponse(
                    owner,
                    THIRD_REQUEST_ID,
                    listOf(MediaAcknowledgement(OTHER_ASSET_ID, "other.mp3", 1L)),
                )
            }
            assertEquals(existing, registry.mediaAcknowledgement(owner, ASSET_ID))
            assertNull(registry.mediaAcknowledgement(owner, OTHER_ASSET_ID))
        }
        assertEquals(ReleaseState.RELEASED, registry.release(RUN_ID, true))
        assertEquals(listOf(null), cleanup)
    }

    @Test
    fun `durable mutation admission enforces canonical provider filenames`() {
        listOf(
            " clip.mp3",
            "clip.mp3 ",
            "e\u0301.mp3",
            "clip:bad.mp3",
            "[clip].mp3",
            "clip\".mp3",
        ).forEach { filename ->
            val registry = AnkiRunStateRegistry()
            assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
            registry.withOwner(RUN_ID) { owner ->
                assertThrows(RunStateConflictException::class.java) {
                    registry.commitDurableMutationResponse(
                        owner,
                        REQUEST_ID,
                        listOf(MediaAcknowledgement(ASSET_ID, filename, 1L)),
                    )
                }
                assertNull(registry.mediaAcknowledgement(owner, ASSET_ID))
            }
        }
    }

    @Test
    fun `durable mutation admission rejects invalid ids duplicate receipt and count overflow`() {
        val invalidInputs =
            listOf(
                "bad" to listOf(MediaAcknowledgement(ASSET_ID, "clip.mp3", 1L)),
                REQUEST_ID to listOf(MediaAcknowledgement("bad", "clip.mp3", 1L)),
                REQUEST_ID to listOf(MediaAcknowledgement(ASSET_ID, "clip.mp3", 0L)),
                REQUEST_ID to
                    (0..50).map { index ->
                        MediaAcknowledgement(
                            assetId = "asset_${index.toString(16).padStart(32, '0')}",
                            actualFilename = "clip-$index.mp3",
                            durableClaimId = index + 1L,
                        )
                    },
            )
        invalidInputs.forEach { (requestId, acknowledgements) ->
            val registry = AnkiRunStateRegistry()
            assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
            registry.withOwner(RUN_ID) { owner ->
                assertThrows(RuntimeException::class.java) {
                    registry.commitDurableMutationResponse(owner, requestId, acknowledgements)
                }
                assertNull(registry.mediaAcknowledgement(owner, ASSET_ID))
            }
        }

        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val acknowledgement = MediaAcknowledgement(ASSET_ID, "clip.mp3", 1L)
            registry.commitDurableMutationResponse(owner, REQUEST_ID, listOf(acknowledgement))
            assertThrows(RunStateConflictException::class.java) {
                registry.commitDurableMutationResponse(owner, REQUEST_ID, listOf(acknowledgement))
            }
            assertEquals(acknowledgement, registry.mediaAcknowledgement(owner, ASSET_ID))
        }
    }

    @Test
    fun `durable mutation admission fails sticky at receipt capacity and after release`() {
        val capacityCleanup = mutableListOf<Set<String>?>()
        val capacityRegistry = AnkiRunStateRegistry { _, ids -> capacityCleanup += ids }
        assertTrue(capacityRegistry.register(RUN_ID, AnkiCancellation.NONE))
        capacityRegistry.withOwner(RUN_ID) { owner ->
            repeat(8192) { index ->
                capacityRegistry.retainDurableTerminalResponse(
                    owner,
                    "anki_${index.toString(16).padStart(32, '0')}",
                )
            }
            assertThrows(RunStateCapacityException::class.java) {
                capacityRegistry.commitDurableMutationResponse(
                    owner,
                    "anki_${"f".repeat(32)}",
                    emptyList(),
                )
            }
        }
        assertEquals(ReleaseState.RELEASED, capacityRegistry.release(RUN_ID, true))
        assertEquals(listOf(null), capacityCleanup)

        val releaseCleanup = mutableListOf<Set<String>?>()
        val releaseRegistry = AnkiRunStateRegistry { _, ids -> releaseCleanup += ids }
        assertTrue(releaseRegistry.register(RUN_ID, AnkiCancellation.NONE))
        releaseRegistry.withOwner(RUN_ID) { owner ->
            assertEquals(ReleaseState.DEFERRED, releaseRegistry.release(RUN_ID, true))
            assertThrows(RunReleasingException::class.java) {
                releaseRegistry.commitDurableMutationResponse(owner, REQUEST_ID, emptyList())
            }
        }
        assertEquals(listOf(null), releaseCleanup)
    }

    @Test
    fun `known cursor is one-use scoped and traversal disappears at the end`() {
        val registry = AnkiRunStateRegistry()
        assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
        registry.withOwner(RUN_ID) { owner ->
            val scope = KnownTraversalScope(listOf("Excluded"))
            val initialization = registry.beginKnownTraversal(owner, scope)
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
                    KnownTraversalScope(listOf("Wrong")),
                    requireNotNull(cursor),
                )
            }
            val second =
                registry.reserveKnownPage(
                    owner,
                    scope,
                    requireNotNull(cursor),
                )
            assertEquals(listOf(257L), second.noteIds)
            assertNull(registry.completeKnownPage(owner, second, null))
            assertThrows(InvalidCapabilityException::class.java) {
                registry.reserveKnownPage(owner, scope, cursor)
            }
        }
    }

    private companion object {
        const val RUN_ID = "run_11111111111111111111111111111111"
        const val OTHER_RUN_ID = "run_22222222222222222222222222222222"
        const val REQUEST_ID = "anki_11111111111111111111111111111111"
        const val OTHER_REQUEST_ID = "anki_22222222222222222222222222222222"
        const val THIRD_REQUEST_ID = "anki_33333333333333333333333333333333"
        const val ASSET_ID = "asset_11111111111111111111111111111111"
        const val OTHER_ASSET_ID = "asset_22222222222222222222222222222222"
        const val NOTE_ID = "note_11111111111111111111111111111111"
    }

    private fun verifyRequest(requestId: String = REQUEST_ID) =
        VerifyTargetRequest(
            runId = RUN_ID,
            requestId = requestId,
            deckName = "Mining",
            modelName = "Mining",
            requiredFields = listOf("Expression"),
        )

    private fun mediaScope() =
        ProviderMutationScope(
            requestId = REQUEST_ID,
            operation = ProviderMutationOperation.MEDIA_INSERT,
            durableChildId = 1L,
            itemIdentity = ASSET_ID,
        )

    private fun target() =
        TargetSnapshot(
            deck = DeckSnapshot(20L, "Mining", dynamic = false),
            model =
                ModelSnapshot(
                    id = 10L,
                    name = "Mining",
                    type = 0,
                    fieldNames = listOf("Expression"),
                    cardCount = 1,
                    sortFieldIndex = 0,
                    effectiveDefaultDeckId = 1L,
                    css = "css",
                    latexPre = null,
                    latexPost = null,
                    templates =
                        listOf(
                            TemplateSnapshot(
                                modelId = 10L,
                                ordinal = 0,
                                name = "Card 1",
                                questionFormat = "{{Expression}}",
                                answerFormat = "{{Expression}}",
                                browserQuestionFormat = null,
                                browserAnswerFormat = null,
                            ),
                        ),
                ),
        )
}
