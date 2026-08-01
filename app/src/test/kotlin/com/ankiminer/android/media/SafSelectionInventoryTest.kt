package com.ankiminer.android.media

import androidx.lifecycle.SavedStateHandle
import com.ankiminer.android.vm.SafSelectionClearTarget
import com.ankiminer.android.vm.SafSelectionOwnershipResult
import com.ankiminer.android.vm.SafSelectionOwnershipTransaction
import com.ankiminer.android.vm.SavedDocumentSelectionStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafSelectionInventoryTest {
    @Test
    fun replacementLeavesOnlyNewUriOwned() {
        val inventory = TransientSafSelectionInventory()
        inventory.putSelection(
            SafSelectionSlot.VIDEO,
            SafSelectionRecord("content://provider/old", "old.mkv"),
        )

        inventory.putSelection(
            SafSelectionSlot.VIDEO,
            SafSelectionRecord("content://provider/new", "new.mkv"),
        )

        assertEquals(setOf("content://provider/new"), inventory.ownedUris())
        assertEquals(
            "new.mkv",
            inventory.selection(SafSelectionSlot.VIDEO)?.displayName,
        )
    }

    @Test
    fun pruningMissingReadingSourceAlsoClearsSeriesName() {
        val inventory = TransientSafSelectionInventory()
        inventory.putSelection(
            SafSelectionSlot.READING_SOURCE,
            SafSelectionRecord("content://provider/episode", "episode.srt"),
        )
        inventory.putText(SafSelectionSlot.READING_SUBTITLE_SERIES, "Show")
        inventory.putSelection(
            SafSelectionSlot.READING_ARCHIVE,
            SafSelectionRecord("content://provider/archive", "episode.cbz"),
        )

        inventory.pruneMissingGrants(emptySet())

        assertNull(inventory.selection(SafSelectionSlot.READING_SOURCE))
        assertNull(inventory.selection(SafSelectionSlot.READING_ARCHIVE))
        assertNull(inventory.text(SafSelectionSlot.READING_SUBTITLE_SERIES))
    }

    @Test
    fun incompatibleReadingDependentsArePrunedEvenWhenTheirGrantsExist() {
        val inventory = TransientSafSelectionInventory()
        inventory.putSelection(
            SafSelectionSlot.READING_SOURCE,
            SafSelectionRecord("content://provider/book", "book.epub"),
        )
        inventory.putSelection(
            SafSelectionSlot.READING_ARCHIVE,
            SafSelectionRecord("content://provider/archive", "book.cbz"),
        )
        inventory.putText(SafSelectionSlot.READING_SUBTITLE_SERIES, "Wrong series")

        inventory.pruneMissingGrants(
            setOf("content://provider/book", "content://provider/archive"),
        )

        assertEquals("book.epub", inventory.selection(SafSelectionSlot.READING_SOURCE)?.displayName)
        assertNull(inventory.selection(SafSelectionSlot.READING_ARCHIVE))
        assertNull(inventory.text(SafSelectionSlot.READING_SUBTITLE_SERIES))
    }

    @Test
    fun invalidDurableMetadataIsRejected() {
        assertNull(safSelectionRecordOrNull("file:///private/video.mkv", "video.mkv"))
        assertNull(safSelectionRecordOrNull("content://provider/video", "../video.mkv"))
        assertNull(safSelectionRecordOrNull("content://provider/video", "folder/video.mkv"))
        assertEquals(
            SafSelectionRecord("content://provider/video", "video.mkv"),
            safSelectionRecordOrNull("content://provider/video", "video.mkv"),
        )
    }

    @Test
    fun inventoryWriteFailureReleasesAcquiredGrantBeforePublication() =
        runTest {
            val events = mutableListOf<String>()
            val inventory = RecordingInventory(events).also { it.failWrites = true }
            val transaction = transaction(inventory, RecordingBroker(events))
            var published = false

            val failure =
                runCatching {
                    transaction.acquirePersistPublish(
                        uri = "content://provider/new",
                        publish = {
                            published = true
                            Unit
                        },
                    )
                }.exceptionOrNull()

            assertTrue(failure is SafSelectionPersistenceException)
            assertEquals(
                listOf(
                    "retain:content://provider/new",
                    "save:VIDEO",
                    "clear:VIDEO",
                    "release:content://provider/new",
                ),
                events,
            )
            assertFalse(published)
            assertNull(inventory.selection(SafSelectionSlot.VIDEO))
        }

    @Test
    fun rejectedRestoredSelectionClearsDurableOwnerBeforeFinalRelease() =
        runTest {
            val events = mutableListOf<String>()
            val inventory = RecordingInventory(events)
            inventory.putSelection(
                SafSelectionSlot.VIDEO,
                SafSelectionRecord("content://provider/restored", "old.mkv"),
            )
            events.clear()
            val transaction = transaction(inventory, RecordingBroker(events))

            val result =
                transaction.acquirePersistPublish(
                    uri = "content://provider/restored",
                    accept = { false },
                    discardPersistedOnRejection = true,
                    publish = { error("rejected selection must not publish") },
                )

            assertTrue(result is SafSelectionOwnershipResult.Rejected)
            assertEquals(
                listOf(
                    "retain:content://provider/restored",
                    "clear:VIDEO",
                    "release:content://provider/restored",
                ),
                events,
            )
            assertNull(inventory.selection(SafSelectionSlot.VIDEO))
        }

    @Test
    fun failedClearPreservesPublishedStateAndDoesNotReleaseGrant() =
        runTest {
            val events = mutableListOf<String>()
            val inventory = RecordingInventory(events)
            inventory.putSelection(
                SafSelectionSlot.VIDEO,
                SafSelectionRecord("content://provider/current", "current.mkv"),
            )
            events.clear()
            inventory.failWrites = true
            val transaction = transaction(inventory, RecordingBroker(events))
            var publishedClear = false

            val failure =
                runCatching {
                    transaction.clearPersistPublishRelease(
                        ownedDocument =
                            SafDocument(
                                "content://provider/current",
                                "current.mkv",
                                null,
                                null,
                            ),
                        publish = {
                            publishedClear = true
                            Unit
                        },
                    )
                }.exceptionOrNull()

            assertTrue(failure is SafSelectionPersistenceException)
            assertFalse(publishedClear)
            assertEquals(listOf("clear:VIDEO", "save:VIDEO"), events)
            assertTrue(events.none { it.startsWith("release:") })
            assertEquals(
                "content://provider/current",
                inventory.selection(SafSelectionSlot.VIDEO)?.uri,
            )
        }

    @Test
    fun durableInventoryWritesRunOnTheInjectedIoDispatcher() =
        runTest {
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "test-saf-inventory-io")
                }
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                val inventory = RecordingInventory()
                val store =
                    SavedDocumentSelectionStore(
                        savedStateHandle = SavedStateHandle(),
                        keyPrefix = "test.video",
                        inventory = inventory,
                        inventorySlot = SafSelectionSlot.VIDEO,
                        ioDispatcher = dispatcher,
                    )
                val transaction =
                    SafSelectionOwnershipTransaction(RecordingBroker(), store)

                transaction.acquirePersistPublish(
                    uri = "content://provider/new",
                    publish = { Unit },
                )

                assertEquals(listOf("test-saf-inventory-io"), inventory.writeThreads)
            } finally {
                dispatcher.close()
                executor.shutdownNow()
            }
        }

    @Test
    fun staleFailedSaveCannotRollBackOverANewerPublishedSelection() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val inventory = BlockingFailedSaveInventory()
            val store =
                SavedDocumentSelectionStore(
                    savedStateHandle = SavedStateHandle(),
                    keyPrefix = "test.video",
                    inventory = inventory,
                    inventorySlot = SafSelectionSlot.VIDEO,
                    ioDispatcher = dispatcher,
                )
            val broker = RecordingBroker()
            val first = SafSelectionOwnershipTransaction(broker, store)
            val second = SafSelectionOwnershipTransaction(broker, store)

            runBlocking {
                val stale =
                    async(dispatcher) {
                        runCatching {
                            first.acquirePersistPublish(
                                uri = "content://provider/stale.mkv",
                                publish = { Unit },
                            )
                        }
                    }
                assertTrue(inventory.failedSaveStarted.await(1, TimeUnit.SECONDS))
                val newer =
                    async(dispatcher) {
                        second.acquirePersistPublish(
                            uri = "content://provider/newer.mkv",
                            publish = { Unit },
                        )
                    }
                delay(100)
                inventory.finishFailedSave.countDown()

                assertTrue(stale.await().isFailure)
                assertTrue(newer.await() is SafSelectionOwnershipResult.Published)
            }

            assertEquals(
                "content://provider/newer.mkv",
                inventory.selection(SafSelectionSlot.VIDEO)?.uri,
            )
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationDuringAcquireCommitRollsBackAndNeverPublishes() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val events = mutableListOf<String>()
            val inventory = BlockingSuccessfulSaveInventory()
            inventory.putSelection(
                SafSelectionSlot.VIDEO,
                SafSelectionRecord("content://provider/old.mkv", "old.mkv"),
            )
            val transaction =
                SafSelectionOwnershipTransaction(
                    RecordingBroker(events),
                    SavedDocumentSelectionStore(
                        SavedStateHandle(),
                        "test.video",
                        inventory,
                        SafSelectionSlot.VIDEO,
                        dispatcher,
                    ),
                )
            var published = false

            runBlocking {
                val acquire =
                    async(dispatcher) {
                        transaction.acquirePersistPublish(
                            uri = "content://provider/new.mkv",
                            publish = {
                                published = true
                                Unit
                            },
                        )
                    }
                assertTrue(inventory.saveStarted.await(1, TimeUnit.SECONDS))
                acquire.cancel()
                inventory.finishSave.countDown()
                acquire.cancelAndJoin()
            }

            assertFalse(published)
            assertEquals(
                "content://provider/old.mkv",
                inventory.selection(SafSelectionSlot.VIDEO)?.uri,
            )
            // The broker and the inventory record into one shared list, and the transaction must
            // retain before it persists, so the rollback trace is retain-then-release.
            assertEquals(
                listOf(
                    "retain:content://provider/new.mkv",
                    "release:content://provider/new.mkv",
                ),
                events,
            )
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun multiSlotClearRollsBackEveryDurableOwnerBeforePublishingOrReleasing() =
        runTest {
            val events = mutableListOf<String>()
            val inventory = RecordingInventory(events)
            val source =
                SafDocument("content://provider/source", "book.mokuro", null, null)
            val archive =
                SafDocument("content://provider/archive", "book.cbz", null, null)
            inventory.putSelection(
                SafSelectionSlot.READING_SOURCE,
                SafSelectionRecord(source.uri, source.displayName),
            )
            inventory.putSelection(
                SafSelectionSlot.READING_ARCHIVE,
                SafSelectionRecord(archive.uri, archive.displayName),
            )
            val sourceStore =
                SavedDocumentSelectionStore(
                    SavedStateHandle(),
                    "test.source",
                    inventory,
                    SafSelectionSlot.READING_SOURCE,
                )
            val archiveStore =
                SavedDocumentSelectionStore(
                    SavedStateHandle(),
                    "test.archive",
                    inventory,
                    SafSelectionSlot.READING_ARCHIVE,
                )
            events.clear()
            inventory.failWrites = true
            val broker = RecordingBroker(events)
            var published = false

            val failure =
                runCatching {
                    SafSelectionOwnershipTransaction(broker, sourceStore)
                        .clearPersistPublishRelease(
                            ownedDocument = source,
                            additionalTargets =
                                listOf(
                                    SafSelectionClearTarget(archiveStore, archive),
                                ),
                            publish = {
                                published = true
                                Unit
                            },
                        )
                }.exceptionOrNull()

            assertTrue(failure is SafSelectionPersistenceException)
            assertFalse(published)
            assertTrue(events.none { it.startsWith("release:") })
            assertEquals(source.uri, inventory.selection(SafSelectionSlot.READING_SOURCE)?.uri)
            assertEquals(archive.uri, inventory.selection(SafSelectionSlot.READING_ARCHIVE)?.uri)
        }

    @Test
    fun cancellationDuringDurableClearStillReleasesTheFormerOwner() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val events = mutableListOf<String>()
            val inventory = BlockingClearInventory()
            val document =
                SafDocument("content://provider/current", "current.mkv", null, null)
            inventory.putSelection(
                SafSelectionSlot.VIDEO,
                SafSelectionRecord(document.uri, document.displayName),
            )
            val transaction =
                SafSelectionOwnershipTransaction(
                    RecordingBroker(events),
                    SavedDocumentSelectionStore(
                        SavedStateHandle(),
                        "test.video",
                        inventory,
                        SafSelectionSlot.VIDEO,
                        dispatcher,
                    ),
                )

            runBlocking {
                val clear =
                    async(dispatcher) {
                        transaction.clearPersistPublishRelease(document) { Unit }
                    }
                assertTrue(inventory.clearStarted.await(1, TimeUnit.SECONDS))
                clear.cancel()
                inventory.finishClear.countDown()
                clear.cancelAndJoin()
            }

            assertEquals(listOf("release:${document.uri}"), events)
            assertNull(inventory.selection(SafSelectionSlot.VIDEO))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun multiSlotClearReleasesOneLedgerReferencePerSameUriSlot() =
        runTest {
            val events = mutableListOf<String>()
            val inventory = RecordingInventory(events)
            val document =
                SafDocument("content://provider/shared", "shared.mokuro", null, null)
            inventory.putSelection(
                SafSelectionSlot.READING_SOURCE,
                SafSelectionRecord(document.uri, document.displayName),
            )
            inventory.putSelection(
                SafSelectionSlot.READING_ARCHIVE,
                SafSelectionRecord(document.uri, document.displayName),
            )
            val sourceStore =
                SavedDocumentSelectionStore(
                    SavedStateHandle(),
                    "test.source",
                    inventory,
                    SafSelectionSlot.READING_SOURCE,
                )
            val archiveStore =
                SavedDocumentSelectionStore(
                    SavedStateHandle(),
                    "test.archive",
                    inventory,
                    SafSelectionSlot.READING_ARCHIVE,
                )
            events.clear()

            SafSelectionOwnershipTransaction(RecordingBroker(events), sourceStore)
                .clearPersistPublishRelease(
                    ownedDocument = document,
                    additionalTargets =
                        listOf(
                            SafSelectionClearTarget(archiveStore, document),
                        ),
                    publish = { Unit },
                )

            // Shared event list: both slots are cleared durably before either grant is released,
            // so a crash between the two can never leave a published slot without its grant.
            assertEquals(
                listOf(
                    "clear:READING_SOURCE",
                    "clear:READING_ARCHIVE",
                    "release:${document.uri}",
                    "release:${document.uri}",
                ),
                events,
            )
        }

    @Test
    fun mainThreadClearNeverBlocksOnTheDurableWriteAndStaysClearedAcrossRestart() {
        val inventory = BlockingDurableWriteInventory()
        inventory.install(
            SafSelectionSlot.READING_SOURCE,
            SafSelectionRecord("content://provider/book", "book.epub"),
        )
        val store = restartableStore(SavedStateHandle(), inventory)
        val returned = CountDownLatch(1)

        try {
            val caller =
                Thread(
                    {
                        store.clear()
                        returned.countDown()
                    },
                    "test-ui-thread",
                )
            caller.start()

            assertTrue(
                "clear() blocked its caller on the durable write",
                returned.await(2, TimeUnit.SECONDS),
            )
            caller.join(TimeUnit.SECONDS.toMillis(2))
            assertEquals(0, inventory.durableWrites.get())
            // The write is deferred to storage only: the reads that follow the clear, including
            // the restore a recreated ViewModel runs, must already see the slot gone.
            assertNull(inventory.selection(SafSelectionSlot.READING_SOURCE))
            assertNull(restartableStore(SavedStateHandle(), inventory).restore())

            // The transactional path keeps its blocking, durable write.
            inventory.install(
                SafSelectionSlot.READING_SOURCE,
                SafSelectionRecord("content://provider/book", "book.epub"),
            )
            inventory.finishDurableWrite.countDown()
            runBlocking { store.clearDurably() }
            assertEquals(1, inventory.durableWrites.get())
            assertNull(inventory.selection(SafSelectionSlot.READING_SOURCE))
        } finally {
            inventory.finishDurableWrite.countDown()
        }
    }

    private fun restartableStore(
        savedStateHandle: SavedStateHandle,
        inventory: SafSelectionInventory,
    ): SavedDocumentSelectionStore =
        SavedDocumentSelectionStore(
            savedStateHandle = savedStateHandle,
            keyPrefix = "test.source",
            inventory = inventory,
            inventorySlot = SafSelectionSlot.READING_SOURCE,
        )

    private fun transaction(
        inventory: SafSelectionInventory,
        broker: SafBroker,
    ): SafSelectionOwnershipTransaction =
        SafSelectionOwnershipTransaction(
            broker = broker,
            store =
                SavedDocumentSelectionStore(
                    savedStateHandle = SavedStateHandle(),
                    keyPrefix = "test.video",
                    inventory = inventory,
                    inventorySlot = SafSelectionSlot.VIDEO,
                ),
        )

    private class RecordingBroker(
        private val events: MutableList<String> = mutableListOf(),
    ) : SafBroker {
        override suspend fun retainReadAccess(uri: String): SafDocument {
            events += "retain:$uri"
            return SafDocument(uri, uri.substringAfterLast('/'), null, null)
        }

        override suspend fun releaseReadAccess(uri: String) {
            events += "release:$uri"
        }

        override fun releaseReadAccessEventually(uri: String) {
            events += "release-eventually:$uri"
        }
    }

    private class RecordingInventory(
        private val events: MutableList<String> = mutableListOf(),
    ) : SafSelectionInventory {
        private val selections = mutableMapOf<SafSelectionSlot, SafSelectionRecord>()
        private val text = mutableMapOf<SafSelectionSlot, String>()
        var failWrites = false
        val writeThreads = mutableListOf<String>()

        override fun selection(slot: SafSelectionSlot): SafSelectionRecord? = selections[slot]

        override fun putSelection(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord?,
        ) {
            writeThreads += Thread.currentThread().name
            events += if (selection == null) "clear:$slot" else "save:$slot"
            if (selection == null) selections.remove(slot) else selections[slot] = selection
            if (failWrites) {
                failWrites = false
                throw SafSelectionPersistenceException("injected inventory failure")
            }
        }

        override fun text(slot: SafSelectionSlot): String? = text[slot]

        override fun putText(
            slot: SafSelectionSlot,
            value: String?,
        ) {
            if (value == null) text.remove(slot) else text[slot] = value
        }

        override fun ownedUris(): Set<String> = selections.values.mapTo(linkedSetOf()) { it.uri }

        override fun pruneMissingGrants(grantedUris: Set<String>) {
            selections.entries.removeAll { it.value.uri !in grantedUris }
        }
    }

    /**
     * Stands in for [AndroidSafSelectionInventory]: [putSelection] is the synchronous `commit`
     * that must never run on the caller thread of a UI clear, while
     * [clearSelectionEventually] mutates in memory and defers only storage.
     */
    private class BlockingDurableWriteInventory : SafSelectionInventory {
        private val monitor = Any()
        private val selections = mutableMapOf<SafSelectionSlot, SafSelectionRecord>()
        val durableWrites = AtomicInteger()
        val finishDurableWrite = CountDownLatch(1)

        fun install(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord,
        ) {
            synchronized(monitor) { selections[slot] = selection }
        }

        override fun selection(slot: SafSelectionSlot): SafSelectionRecord? =
            synchronized(monitor) { selections[slot] }

        override fun putSelection(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord?,
        ) {
            durableWrites.incrementAndGet()
            check(finishDurableWrite.await(2, TimeUnit.SECONDS)) { "durable write never released" }
            synchronized(monitor) {
                if (selection == null) selections.remove(slot) else selections[slot] = selection
            }
        }

        override fun clearSelectionEventually(slot: SafSelectionSlot) {
            synchronized(monitor) { selections.remove(slot) }
        }

        override fun text(slot: SafSelectionSlot): String? = null

        override fun putText(
            slot: SafSelectionSlot,
            value: String?,
        ) = Unit

        override fun ownedUris(): Set<String> =
            synchronized(monitor) { selections.values.mapTo(linkedSetOf()) { it.uri } }

        override fun pruneMissingGrants(grantedUris: Set<String>) = Unit
    }

    private class BlockingFailedSaveInventory : SafSelectionInventory {
        private val monitor = Any()
        private val selections = mutableMapOf<SafSelectionSlot, SafSelectionRecord>()
        val failedSaveStarted = CountDownLatch(1)
        val finishFailedSave = CountDownLatch(1)

        override fun selection(slot: SafSelectionSlot): SafSelectionRecord? =
            synchronized(monitor) { selections[slot] }

        override fun putSelection(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord?,
        ) {
            synchronized(monitor) {
                if (selection == null) selections.remove(slot) else selections[slot] = selection
            }
            if (selection?.uri == "content://provider/stale.mkv") {
                failedSaveStarted.countDown()
                check(finishFailedSave.await(1, TimeUnit.SECONDS))
                throw SafSelectionPersistenceException("injected stale save failure")
            }
        }

        override fun text(slot: SafSelectionSlot): String? = null

        override fun putText(
            slot: SafSelectionSlot,
            value: String?,
        ) = Unit

        override fun ownedUris(): Set<String> =
            synchronized(monitor) { selections.values.mapTo(linkedSetOf()) { it.uri } }

        override fun pruneMissingGrants(grantedUris: Set<String>) = Unit
    }

    private class BlockingClearInventory : SafSelectionInventory {
        private val selections = mutableMapOf<SafSelectionSlot, SafSelectionRecord>()
        val clearStarted = CountDownLatch(1)
        val finishClear = CountDownLatch(1)

        override fun selection(slot: SafSelectionSlot): SafSelectionRecord? = selections[slot]

        override fun putSelection(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord?,
        ) {
            if (selection == null) {
                clearStarted.countDown()
                check(finishClear.await(1, TimeUnit.SECONDS))
                selections.remove(slot)
            } else {
                selections[slot] = selection
            }
        }

        override fun text(slot: SafSelectionSlot): String? = null

        override fun putText(
            slot: SafSelectionSlot,
            value: String?,
        ) = Unit

        override fun ownedUris(): Set<String> =
            selections.values.mapTo(linkedSetOf()) { it.uri }

        override fun pruneMissingGrants(grantedUris: Set<String>) = Unit
    }

    private class BlockingSuccessfulSaveInventory : SafSelectionInventory {
        private val monitor = Any()
        private val selections = mutableMapOf<SafSelectionSlot, SafSelectionRecord>()
        val saveStarted = CountDownLatch(1)
        val finishSave = CountDownLatch(1)

        override fun selection(slot: SafSelectionSlot): SafSelectionRecord? =
            synchronized(monitor) { selections[slot] }

        override fun putSelection(
            slot: SafSelectionSlot,
            selection: SafSelectionRecord?,
        ) {
            synchronized(monitor) {
                if (selection == null) selections.remove(slot) else selections[slot] = selection
            }
            if (selection?.uri == "content://provider/new.mkv") {
                saveStarted.countDown()
                check(finishSave.await(1, TimeUnit.SECONDS))
            }
        }

        override fun text(slot: SafSelectionSlot): String? = null

        override fun putText(
            slot: SafSelectionSlot,
            value: String?,
        ) = Unit

        override fun ownedUris(): Set<String> =
            synchronized(monitor) { selections.values.mapTo(linkedSetOf()) { it.uri } }

        override fun pruneMissingGrants(grantedUris: Set<String>) = Unit
    }
}
