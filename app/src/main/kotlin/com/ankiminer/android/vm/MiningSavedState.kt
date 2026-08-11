package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import com.ankiminer.android.media.SafBroker
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafSelectionAccessBroker
import com.ankiminer.android.media.SafSelectionInventory
import com.ankiminer.android.media.SafSelectionPersistenceException
import com.ankiminer.android.media.SafSelectionSlot
import com.ankiminer.android.media.safSelectionRecordOrNull
import com.ankiminer.android.ui.mining.SavedDocumentSelection
import com.ankiminer.android.ui.mining.restoredDocumentSelection
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal class SavedDocumentSelectionStore(
    private val savedStateHandle: SavedStateHandle,
    keyPrefix: String,
    internal val inventory: SafSelectionInventory? = null,
    internal val inventorySlot: SafSelectionSlot? = null,
    internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val uriKey = "$keyPrefix.uri"
    private val displayNameKey = "$keyPrefix.displayName"
    private val transactionMutex = Mutex()
    internal val transactionOrder: Long = nextTransactionOrder.getAndIncrement()

    fun restore(): SavedDocumentSelection? {
        val restored = snapshot()
        if (restored != null) {
            publishSavedState(restored)
        }
        return restored
    }

    fun save(document: SafDocument): Boolean {
        val durableRecord =
            safSelectionRecordOrNull(uri = document.uri, displayName = document.displayName)
        if (inventory != null && durableRecord == null) return false
        inventory?.putSelection(requireNotNull(inventorySlot), requireNotNull(durableRecord))
        savedStateHandle[uriKey] = document.uri
        savedStateHandle[displayNameKey] = document.displayName
        return true
    }

    /**
     * Clear from a non-suspending caller. `clearSource`, `clearArchive` and selection restore all
     * run on the main thread, so this must not reach storage synchronously; it must still be
     * ordered against the reads that follow, which
     * [SafSelectionInventory.clearSelectionEventually] guarantees. Callers that need durability
     * before releasing a SAF grant use [clearDurably].
     */
    fun clear() {
        inventory?.clearSelectionEventually(requireNotNull(inventorySlot))
        publishSavedState(null)
    }

    /**
     * Persist selection ownership off the caller thread while keeping synchronous commit
     * durability. The SavedState mirror is published only after the durable write succeeds.
     */
    suspend fun persistDurably(document: SafDocument): Boolean {
        val durableRecord =
            safSelectionRecordOrNull(uri = document.uri, displayName = document.displayName)
        if (inventory != null && durableRecord == null) return false
        writeInventory {
            inventory?.putSelection(requireNotNull(inventorySlot), requireNotNull(durableRecord))
        }
        return true
    }

    /**
     * Remove durable ownership before clearing the SavedState mirror.
     *
     * Returns the prior selection so a transaction can restore it if later publication fails.
     */
    suspend fun clearDurably(): SavedDocumentSelection? {
        val previous = durableSnapshot()
        writeInventory {
            inventory?.putSelection(requireNotNull(inventorySlot), null)
        }
        return previous
    }

    internal suspend fun durableSnapshot(): SavedDocumentSelection? =
        if (inventory == null) {
            snapshot()
        } else {
            withContext(ioDispatcher) { snapshot() }
        }

    internal suspend fun replaceDurably(selection: SavedDocumentSelection?) {
        writeDurableSelections(mapOf(this to selection))
        publishSavedState(selection)
    }

    internal fun publish(document: SafDocument?) {
        publishSavedState(
            document?.let { SavedDocumentSelection(it.uri, it.displayName) },
        )
    }

    internal suspend fun lockTransaction() {
        transactionMutex.lock()
    }

    internal fun unlockTransaction() {
        transactionMutex.unlock()
    }

    private fun snapshot(): SavedDocumentSelection? {
        val durable = inventory?.selection(requireNotNull(inventorySlot))
        return durable?.let { SavedDocumentSelection(uri = it.uri, displayName = it.displayName) }
            ?: if (inventory == null) {
                restoredDocumentSelection(
                    uri = savedStateHandle[uriKey],
                    displayName = savedStateHandle[displayNameKey],
                )
            } else {
                null
            }
    }

    private suspend fun writeInventory(write: () -> Unit) {
        if (inventory == null) return
        try {
            withContext(ioDispatcher) { write() }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: SafSelectionPersistenceException) {
            throw failure
        } catch (failure: Exception) {
            throw SafSelectionPersistenceException(
                "Could not update SAF selection ownership",
                failure,
            )
        }
    }

    private fun publishSavedState(selection: SavedDocumentSelection?) {
        if (selection == null) {
            savedStateHandle.remove<String>(uriKey)
            savedStateHandle.remove<String>(displayNameKey)
        } else {
            savedStateHandle[uriKey] = selection.uri
            savedStateHandle[displayNameKey] = selection.displayName
        }
    }

    internal companion object {
        private val nextTransactionOrder = AtomicLong()

        suspend fun writeDurableSelections(
            replacements: Map<SavedDocumentSelectionStore, SavedDocumentSelection?>,
        ) {
            replacements.entries
                .filter { it.key.inventory != null }
                .groupBy { it.key.inventory }
                .values
                .forEach { entries ->
                    val store = entries.first().key
                    val inventory = requireNotNull(store.inventory)
                    val records =
                        entries.associate { (entryStore, selection) ->
                            requireNotNull(entryStore.inventorySlot) to
                                selection?.let {
                                    safSelectionRecordOrNull(
                                        uri = it.uri,
                                        displayName = it.displayName,
                                    )
                                        ?: throw SafSelectionPersistenceException(
                                            "Could not restore invalid SAF selection ownership",
                                        )
                                }
                        }
                    store.writeInventory {
                        inventory.putSelections(records)
                    }
                }
        }
    }
}

internal sealed interface SafSelectionOwnershipResult<out T> {
    /** The caller now owns exactly one live and durable reference for [document]. */
    data class Published<T>(
        val document: SafDocument,
        val previousDurableDocument: SafDocument?,
        val value: T,
    ) : SafSelectionOwnershipResult<T> {
        /** Keeps a live same-URI owner releasable but ignores a same-URI durable-only record. */
        fun supersededDocuments(liveDocument: SafDocument?): List<SafDocument> {
            val documents = linkedMapOf<String, SafDocument>()
            liveDocument?.let { documents[it.uri] = it }
            previousDurableDocument
                ?.takeIf { it.uri != document.uri && it.uri !in documents }
                ?.let { documents[it.uri] = it }
            return documents.values.toList()
        }
    }

    /** Metadata is available for error classification, but the acquired grant was released. */
    data class Rejected(
        val document: SafDocument,
    ) : SafSelectionOwnershipResult<Nothing>
}

internal data class SafSelectionClearTarget(
    val store: SavedDocumentSelectionStore,
    val ownedDocument: SafDocument? = null,
)

/**
 * Exception-safe ownership transfer for one SAF selection slot.
 *
 * Acquisition resolves provider metadata cancellably, then narrows non-cancellable work to the
 * platform-grant handoff. Publication occurs only after synchronous inventory persistence on the
 * store's IO dispatcher. Every exit before publication releases the acquired grant; a publication
 * failure restores the prior durable record first. Clear performs the inverse order: durable clear,
 * publication, then final release. A failed durable clear therefore preserves both visible state
 * and grant ownership.
 */
internal class SafSelectionOwnershipTransaction(
    private val broker: SafBroker,
    private val store: SavedDocumentSelectionStore,
) {
    suspend fun <T> acquirePersistPublish(
        uri: String,
        accept: (SafDocument) -> Boolean = { true },
        discardPersistedOnRejection: Boolean = false,
        publish: (SafDocument) -> T,
    ): SafSelectionOwnershipResult<T> {
        var acquired: SafDocument? = null
        try {
            if (broker is SafSelectionAccessBroker) {
                val resolved = broker.resolveReadAccess(uri)
                withContext(NonCancellable) {
                    broker.acquireResolvedReadAccess(resolved)
                    acquired = resolved
                }
            } else {
                withContext(NonCancellable) {
                    acquired = broker.retainReadAccess(uri)
                }
            }
            val document = requireNotNull(acquired)
            if (!accept(document)) {
                if (!discardPersistedOnRejection) {
                    return SafSelectionOwnershipResult.Rejected(document)
                }
                return withSelectionStoreLocks(listOf(store)) {
                    val previous = store.durableSnapshot()
                    var durableMutationStarted = false
                    var publishedClear = false
                    try {
                        withContext(NonCancellable) {
                            durableMutationStarted = true
                            store.clearDurably()
                            store.publish(null)
                            publishedClear = true
                            SafSelectionOwnershipResult.Rejected(document)
                        }
                    } catch (failure: Throwable) {
                        if (durableMutationStarted && !publishedClear) {
                            withContext(NonCancellable) {
                                rollbackSelections(mapOf(store to previous), failure)
                            }
                        }
                        throw failure
                    }
                }
            }

            return withSelectionStoreLocks(listOf(store)) {
                val previous = store.durableSnapshot()
                var durableMutationStarted = false
                try {
                    val persisted =
                        withContext(NonCancellable) {
                            durableMutationStarted = true
                            store.persistDurably(document)
                        }
                    currentCoroutineContext().ensureActive()
                    if (!persisted) {
                        return@withSelectionStoreLocks SafSelectionOwnershipResult.Rejected(document)
                    }

                    store.publish(document)
                    val value = publish(document)
                    acquired = null
                    SafSelectionOwnershipResult.Published(
                        document = document,
                        previousDurableDocument =
                            previous?.let {
                                SafDocument(
                                    uri = it.uri,
                                    displayName = it.displayName,
                                    mimeType = null,
                                    sizeBytes = null,
                                )
                            },
                        value = value,
                    )
                } catch (failure: Throwable) {
                    if (durableMutationStarted) {
                        withContext(NonCancellable) {
                            rollbackSelections(mapOf(store to previous), failure)
                        }
                    }
                    throw failure
                }
            }
        } finally {
            acquired?.let { releaseSafely(it.uri) }
        }
    }

    suspend fun <T> clearPersistPublishRelease(
        ownedDocument: SafDocument? = null,
        additionalTargets: List<SafSelectionClearTarget> = emptyList(),
        publish: () -> T,
    ): T {
        val targets =
            listOf(SafSelectionClearTarget(store, ownedDocument)) + additionalTargets
        require(targets.map(SafSelectionClearTarget::store).distinct().size == targets.size) {
            "Each SAF selection store may be cleared only once per transaction"
        }
        var committedReleaseUris = emptyList<String>()
        var clearPublished = false
        try {
            return withSelectionStoreLocks(targets.map(SafSelectionClearTarget::store)) {
                val previous =
                    targets.associate { target ->
                        target.store to target.store.durableSnapshot()
                    }
                var durableMutationStarted = false
                try {
                    withContext(NonCancellable) {
                        durableMutationStarted = true
                        SavedDocumentSelectionStore.writeDurableSelections(
                            targets.associate { it.store to null },
                        )
                        targets.forEach { it.store.publish(null) }
                        val published = publish()
                        committedReleaseUris =
                            targets.flatMap { target ->
                                linkedSetOf<String>().apply {
                                    target.ownedDocument?.uri?.let(::add)
                                    previous[target.store]?.uri?.let(::add)
                                }
                            }
                        clearPublished = true
                        published
                    }
                } catch (failure: Throwable) {
                    if (durableMutationStarted && !clearPublished) {
                        withContext(NonCancellable) {
                            rollbackSelections(previous, failure)
                        }
                    }
                    throw failure
                }
            }
        } finally {
            committedReleaseUris.forEach { uri -> releaseSafely(uri) }
        }
    }

    private suspend fun rollbackSelections(
        previous: Map<SavedDocumentSelectionStore, SavedDocumentSelection?>,
        failure: Throwable,
    ) {
        try {
            SavedDocumentSelectionStore.writeDurableSelections(previous)
            previous.forEach { (store, selection) ->
                store.publish(
                    selection?.let {
                        SafDocument(it.uri, it.displayName, mimeType = null, sizeBytes = null)
                    },
                )
            }
        } catch (rollbackFailure: Exception) {
            failure.addSuppressed(rollbackFailure)
        }
    }

    private suspend fun <T> withSelectionStoreLocks(
        stores: List<SavedDocumentSelectionStore>,
        block: suspend () -> T,
    ): T {
        val ordered = stores.distinct().sortedBy(SavedDocumentSelectionStore::transactionOrder)
        val locked = mutableListOf<SavedDocumentSelectionStore>()
        try {
            ordered.forEach { selectionStore ->
                selectionStore.lockTransaction()
                locked += selectionStore
            }
            return block()
        } finally {
            locked.asReversed().forEach(SavedDocumentSelectionStore::unlockTransaction)
        }
    }

    private suspend fun releaseSafely(uri: String) {
        try {
            withContext(NonCancellable) {
                broker.releaseReadAccess(uri)
            }
            // instrumentation: silent — durable inventory owns eventual SAF release retry
        } catch (_: Exception) {
            broker.releaseReadAccessEventually(uri)
        }
    }
}

internal class SavedTextValueStore(
    private val savedStateHandle: SavedStateHandle,
    private val savedStateKey: String,
    private val inventory: SafSelectionInventory? = null,
    private val inventorySlot: SafSelectionSlot? = null,
) {
    fun restore(): String {
        val value =
            inventory?.text(requireNotNull(inventorySlot))
                ?: if (inventory == null) savedStateHandle[savedStateKey] else null
        if (value != null) savedStateHandle[savedStateKey] = value
        return value.orEmpty()
    }

    fun save(value: String) {
        inventory?.putText(requireNotNull(inventorySlot), value.takeIf(String::isNotBlank))
        if (value.isBlank()) {
            savedStateHandle.remove<String>(savedStateKey)
        } else {
            savedStateHandle[savedStateKey] = value
        }
    }

    fun clear() {
        inventory?.putText(requireNotNull(inventorySlot), null)
        savedStateHandle.remove<String>(savedStateKey)
    }
}
