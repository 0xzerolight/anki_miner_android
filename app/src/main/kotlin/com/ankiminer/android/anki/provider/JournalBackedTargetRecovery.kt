package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.AnkiMutationRecovery
import com.ankiminer.android.anki.journal.AnkiMutationStore
import com.ankiminer.android.anki.journal.ChildOperation
import com.ankiminer.android.anki.journal.ChildState
import com.ankiminer.android.anki.journal.PreparedMutationRecovery
import java.util.concurrent.atomic.AtomicBoolean

internal interface AnkiStartupRecoveryGate : AnkiStartupAdmission {
    fun ensureRecovered()
}

internal object OpenAnkiStartupRecoveryGate : AnkiStartupRecoveryGate {
    override fun ensureRecovered() = Unit

    override fun isOpen(): Boolean = true
}

internal class JournalAnkiRunCleanup(
    private val store: AnkiMutationStore,
) : AnkiRunCleanup {
    override fun cleanup(
        runId: String,
        durableResponseIds: Set<String>?,
    ) {
        store.cleanupRun(
            runId = runId,
            acknowledgeAuthorized = durableResponseIds != null,
            frozenDurableRequestIds = durableResponseIds?.sorted().orEmpty(),
        )
    }
}

/** Closed startup gate. Recovery is serialized, provider-read-only, and never admits a target. */
internal class JournalBackedTargetRecoveryGate(
    private val store: AnkiMutationStore,
    gateway: AnkiProviderGateway,
    private val workerThreadGuard: WorkerThreadGuard,
) : AnkiStartupRecoveryGate {
    private val gateLock = Any()
    private val open = AtomicBoolean(false)
    private val snapshots = TargetSnapshotReader(CheckedProvider(gateway))

    override fun isOpen(): Boolean = open.get()

    override fun ensureRecovered() {
        if (open.get()) return
        workerThreadGuard.checkWorkerThread()
        synchronized(gateLock) {
            if (open.get()) return
            val plan = AnkiMutationRecovery.plan(store.recoveryInventory())
            when (val action = plan.preparedMutation) {
                null -> Unit
                is PreparedMutationRecovery.ProveNotCommitted -> recoverPreEntry(action)
                is PreparedMutationRecovery.ReconcileDeck -> recoverEnteredDeck(action)
                is PreparedMutationRecovery.FinalizeMediaReceipt,
                is PreparedMutationRecovery.MarkMediaUncertain,
                is PreparedMutationRecovery.PromoteNoteReceipt,
                is PreparedMutationRecovery.MarkNoteUncertain,
                is PreparedMutationRecovery.InspectCardRouting,
                -> {
                    store.abandonOwnerless(emptySet())
                    throw PendingNonTargetMutationRecoveryException()
                }
            }
            store.abandonOwnerless(emptySet())
            val remaining = store.recoveryInventory()
            if (remaining.preparedChild != null || remaining.unfinishedParents.isNotEmpty()) {
                throw PendingNonTargetMutationRecoveryException()
            }
            open.set(true)
        }
    }

    private fun recoverPreEntry(action: PreparedMutationRecovery.ProveNotCommitted) {
        if (action.child.command.operation != ChildOperation.DECK_CREATE) {
            throw PendingNonTargetMutationRecoveryException()
        }
        store.completeChild(
            action.child.id,
            ChildState.PROVEN_NOT_COMMITTED,
            "startupRecovery=deck;providerEntry=false;requestSha256=${action.parent.requestSha256}",
        )
    }

    private fun recoverEnteredDeck(action: PreparedMutationRecovery.ReconcileDeck) {
        val receipt = action.returnedReceipt
        val validatedReceipt =
            receipt?.let { returned ->
                DeckCreateReceiptValidator.validate(returned.contentUri)
                    ?.takeIf { it.deckId == returned.deckId }
            }
        val receiptIsValid = receipt == null || validatedReceipt != null
        val target =
            if (receiptIsValid) {
                reconcile(action.expectedTarget.model.toProviderSnapshot(), action.expectedTarget.expectedDeckName, validatedReceipt)
            } else {
                null
            }
        val evidence =
            buildString(512) {
                append("startupRecovery=deck;providerEntry=true;requestSha256=")
                append(action.parent.requestSha256)
                append(";returnedDeckId=")
                append(receipt?.deckId ?: "none")
                append(";receiptValid=")
                append(receiptIsValid)
            }
        if (target != null) {
            store.completeVerifiedDeck(action.child.id, target.toDurableSnapshot(), evidence)
        } else {
            store.completeUncertainDeck(action.child.id, evidence)
        }
    }

    private fun reconcile(
        expectedModel: ModelSnapshot,
        expectedDeckName: String,
        receipt: DeckCreateReceipt?,
    ): TargetSnapshot? {
        return try {
            ProviderSnapshotValidation.validateModel(expectedModel)
            val model = snapshots.readModelById(expectedModel.id, AnkiCancellation.NONE)
            if (model != expectedModel) return null
            val byName = snapshots.readDeckByName(expectedDeckName, AnkiCancellation.NONE) ?: return null
            if (receipt != null) {
                val byId = snapshots.readDeckById(receipt.deckId, AnkiCancellation.NONE)
                if (byId != byName) return null
            }
            TargetSnapshot(byName, model)
        } catch (_: RuntimeException) {
            null
        }
    }
}

internal class PendingNonTargetMutationRecoveryException : RuntimeException()
