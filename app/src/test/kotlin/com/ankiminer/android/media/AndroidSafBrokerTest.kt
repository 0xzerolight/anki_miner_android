package com.ankiminer.android.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSafBrokerTest {
    @Test
    fun stalledMetadataQueryCanBeCancelledWithoutBlockingUnrelatedRelease() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val access = BlockingSafProviderAccess()
            val broker =
                AndroidSafBroker(
                    providerAccess = access,
                    ioDispatcher = dispatcher,
                    selectionInventory = TransientSafSelectionInventory(),
                    providerIoTimeoutMillis = 5_000L,
                )

            runBlocking {
                broker.retainReadAccess(EXISTING_URI)
                access.blockMetadataFor = BLOCKED_URI
                val blocked = async { broker.retainReadAccess(BLOCKED_URI) }
                assertTrue(access.metadataStarted.await(1, TimeUnit.SECONDS))

                withTimeout(1_000L) {
                    broker.releaseReadAccess(EXISTING_URI)
                }
                blocked.cancelAndJoin()
            }

            assertEquals(listOf(EXISTING_URI), access.releasedUris)
            assertTrue(access.metadataCancelled.await(1, TimeUnit.SECONDS))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun clearingTheLastDurableOwnerAllowsSameProcessOrphanCleanup() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val access = BlockingSafProviderAccess()
            val inventory = TransientSafSelectionInventory()
            val broker =
                AndroidSafBroker(
                    providerAccess = access,
                    ioDispatcher = dispatcher,
                    selectionInventory = inventory,
                )

            runBlocking {
                broker.retainReadAccess(EXISTING_URI)
                inventory.putSelection(
                    SafSelectionSlot.VIDEO,
                    SafSelectionRecord(EXISTING_URI, "video.mkv"),
                )
                broker.releaseReadAccess(EXISTING_URI)
                assertEquals(emptyList<String>(), access.releasedUris)

                inventory.putSelection(SafSelectionSlot.VIDEO, null)
                broker.releaseReadAccess(EXISTING_URI)
            }

            assertEquals(listOf(EXISTING_URI), access.releasedUris)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedFinalPlatformReleaseKeepsTheLedgerReferenceRetryable() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val access = BlockingSafProviderAccess(releaseFailures = 1)
            val broker =
                AndroidSafBroker(
                    providerAccess = access,
                    ioDispatcher = dispatcher,
                    selectionInventory = TransientSafSelectionInventory(),
                )

            runBlocking {
                broker.retainReadAccess(EXISTING_URI)
                assertTrue(
                    runCatching { broker.releaseReadAccess(EXISTING_URI) }
                        .exceptionOrNull() is IllegalStateException,
                )
                broker.releaseReadAccess(EXISTING_URI)
            }

            assertEquals(2, access.releaseAttempts)
            assertEquals(listOf(EXISTING_URI), access.releasedUris)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun stalledStartupReconciliationDoesNotBlockANewerRetainOrRelease() {
        val executor = Executors.newFixedThreadPool(3)
        val dispatcher = executor.asCoroutineDispatcher()
        val access = FirstStartupQueryStallsProviderAccess()
        try {
            val broker =
                AndroidSafBroker(
                    providerAccess = access,
                    ioDispatcher = dispatcher,
                    selectionInventory = TransientSafSelectionInventory(),
                    providerIoTimeoutMillis = 5_000L,
                )

            runBlocking {
                val stalled = async { broker.retainReadAccess(BLOCKED_URI) }
                assertTrue(access.firstQueryStarted.await(1, TimeUnit.SECONDS))
                stalled.cancelAndJoin()

                withTimeout(1_000L) {
                    broker.retainReadAccess(EXISTING_URI)
                    broker.releaseReadAccess(EXISTING_URI)
                }
                access.finishFirstQuery.countDown()
            }

            assertEquals(listOf(EXISTING_URI), access.releasedUris)
        } finally {
            access.finishFirstQuery.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private class BlockingSafProviderAccess(
        private var releaseFailures: Int = 0,
    ) : SafProviderAccess {
        @Volatile
        var blockMetadataFor: String? = null
        val metadataStarted = CountDownLatch(1)
        val metadataCancelled = CountDownLatch(1)
        val releasedUris = mutableListOf<String>()
        var releaseAttempts = 0

        override fun persistedReadGrantUris(
            cancellation: ProviderIoCancellation,
        ): List<String> = emptyList()

        override fun resolveDocument(
            uri: String,
            cancellation: ProviderIoCancellation,
        ): SafDocument {
            if (uri == blockMetadataFor) {
                val unblocked = CountDownLatch(1)
                cancellation.invokeOnCancellation {
                    metadataCancelled.countDown()
                    unblocked.countDown()
                }.use {
                    metadataStarted.countDown()
                    check(unblocked.await(5, TimeUnit.SECONDS)) {
                        "test metadata query was not cancelled"
                    }
                    throw ProviderIoCancelledException()
                }
            }
            return SafDocument(uri, uri.substringAfterLast('/'), null, null)
        }

        override fun takeReadGrant(uri: String) = Unit

        override fun releaseReadGrant(uri: String) {
            releaseAttempts += 1
            if (releaseFailures > 0) {
                releaseFailures -= 1
                error("injected release failure")
            }
            releasedUris += uri
        }
    }

    private class FirstStartupQueryStallsProviderAccess : SafProviderAccess {
        private val queryCalls = AtomicInteger()
        val firstQueryStarted = CountDownLatch(1)
        val finishFirstQuery = CountDownLatch(1)
        val releasedUris = mutableListOf<String>()

        override fun persistedReadGrantUris(
            cancellation: ProviderIoCancellation,
        ): List<String> {
            if (queryCalls.incrementAndGet() == 1) {
                firstQueryStarted.countDown()
                check(finishFirstQuery.await(5, TimeUnit.SECONDS)) {
                    "test startup query was not released"
                }
            }
            return emptyList()
        }

        override fun resolveDocument(
            uri: String,
            cancellation: ProviderIoCancellation,
        ): SafDocument = SafDocument(uri, uri.substringAfterLast('/'), null, null)

        override fun takeReadGrant(uri: String) = Unit

        override fun releaseReadGrant(uri: String) {
            releasedUris += uri
        }
    }

    private companion object {
        const val EXISTING_URI = "content://provider/existing"
        const val BLOCKED_URI = "content://provider/blocked"
    }
}
