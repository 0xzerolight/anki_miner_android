package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.AlignedResult
import com.ankiminer.android.anki.journal.AnkiMutationRecovery
import com.ankiminer.android.anki.journal.AnkiMutationStore
import com.ankiminer.android.anki.journal.ChildOperation
import com.ankiminer.android.anki.journal.ChildRecord
import com.ankiminer.android.anki.journal.ChildState
import com.ankiminer.android.anki.journal.JournalCorruptionException
import com.ankiminer.android.anki.journal.MediaClaimRecord
import com.ankiminer.android.anki.journal.MediaClaimState
import com.ankiminer.android.anki.journal.MutationCommand
import com.ankiminer.android.anki.journal.ParentKey
import com.ankiminer.android.anki.journal.ParentOperation
import com.ankiminer.android.anki.journal.ParentRecord
import com.ankiminer.android.anki.journal.PreparedMutationRecovery
import com.ankiminer.android.anki.journal.RecoveryInventory
import com.ankiminer.android.anki.journal.RemediationKind
import com.ankiminer.android.anki.journal.StagingState
import com.ankiminer.android.anki.protocol.AnkiProtocolException
import com.ankiminer.android.anki.protocol.AnkiValidators
import java.util.concurrent.atomic.AtomicBoolean

internal interface AnkiStartupRecoveryGate : AnkiStartupAdmission {
    fun ensureRecovered()
}

internal object OpenAnkiStartupRecoveryGate : AnkiStartupRecoveryGate {
    override fun ensureRecovered() = Unit

    override fun isOpen(): Boolean = true
}

/** Process-shared staging recovery supplied by the provider runtime composition root. */
internal fun interface MediaStagingRecovery {
    fun recover(): AnkiMediaRecoveryReport
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

/**
 * Closed startup gate for every durable provider mutation and private media staging artifact.
 *
 * Recovery never reissues a provider media write. The one PREPARED mutation is first reduced to
 * durable journal evidence, ownerless parents are then abandoned, and only a typed-quiescent
 * journal may hand control to staging cleanup. The gate opens after a clean staging report and a
 * second quiescence check.
 */
internal class JournalBackedTargetRecoveryGate(
    private val store: AnkiMutationStore,
    gateway: AnkiProviderGateway,
    private val workerThreadGuard: WorkerThreadGuard,
    private val mediaStagingRecovery: MediaStagingRecovery = MissingMediaStagingRecovery,
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
                is PreparedMutationRecovery.FinalizeMediaReceipt -> recoverMediaReceipt(action)
                is PreparedMutationRecovery.MarkMediaUncertain -> recoverMediaUncertain(action)
                is PreparedMutationRecovery.PromoteNoteReceipt,
                is PreparedMutationRecovery.MarkNoteUncertain,
                is PreparedMutationRecovery.InspectCardRouting,
                -> throw PendingNonTargetMutationRecoveryException()
            }

            store.abandonOwnerless(emptySet())
            requireTypedQuiescence(store.recoveryInventory(), allowStagingRemediation = true)

            val report = mediaStagingRecovery.recover()
            if (
                report.cleanedRecords < 0 ||
                    report.quarantinedRecords < 0 ||
                    report.sweptOrphans < 0
            ) {
                throw JournalCorruptionException("Media staging recovery returned an invalid report")
            }
            if (!report.isClean) throw PendingMediaStagingRecoveryException()

            requireTypedQuiescence(store.recoveryInventory(), allowStagingRemediation = false)
            open.set(true)
        }
    }

    private fun recoverPreEntry(action: PreparedMutationRecovery.ProveNotCommitted) {
        when (action.child.command.operation) {
            ChildOperation.DECK_CREATE -> recoverPreEntryDeck(action)
            ChildOperation.MEDIA_INSERT -> recoverPreEntryMedia(action)
            ChildOperation.NOTE_INSERT,
            ChildOperation.CARD_DECK_UPDATE,
            -> throw PendingNonTargetMutationRecoveryException()
        }
    }

    private fun recoverPreEntryDeck(action: PreparedMutationRecovery.ProveNotCommitted) {
        store.completeChild(
            action.child.id,
            ChildState.PROVEN_NOT_COMMITTED,
            "startupRecovery=deck;providerEntry=false;requestSha256=${action.parent.requestSha256}",
        )
    }

    private fun recoverPreEntryMedia(action: PreparedMutationRecovery.ProveNotCommitted) {
        val identity = requirePreparedMediaIdentity(action.parent, action.child, action.child.mediaClaimId)
        if (action.child.attemptCount != 0 || action.child.receipt != null) {
            throw JournalCorruptionException("Pre-entry media recovery contradicts provider evidence")
        }
        store.completeMediaFailure(
            childId = action.child.id,
            claimId = identity.claim.id,
            childOutcome = ChildState.PROVEN_NOT_COMMITTED,
            claimState = MediaClaimState.CLEANED_VERIFIED,
            result =
                AlignedResult.MediaNotAttempted(
                    requestIndex = identity.command.requestIndexValue,
                    itemId = identity.command.assetId,
                ),
            compactEvidence = mediaRecoveryEvidence(action.parent, providerEntered = false),
        )
    }

    private fun recoverMediaUncertain(action: PreparedMutationRecovery.MarkMediaUncertain) {
        val identity = requirePreparedMediaIdentity(action.parent, action.child, action.claimId)
        if (action.child.attemptCount <= 0 || action.child.receipt != null) {
            throw JournalCorruptionException("Entered media uncertainty contradicts provider evidence")
        }
        store.completeMediaFailure(
            childId = action.child.id,
            claimId = identity.claim.id,
            childOutcome = ChildState.COMMIT_UNCERTAIN,
            claimState = MediaClaimState.COMMIT_UNCERTAIN,
            result =
                AlignedResult.MediaUncertain(
                    requestIndex = identity.command.requestIndexValue,
                    itemId = identity.command.assetId,
                    compactEvidence = mediaRecoveryEvidence(action.parent, providerEntered = true),
                ),
            compactEvidence = mediaRecoveryEvidence(action.parent, providerEntered = true),
        )
    }

    private fun recoverMediaReceipt(action: PreparedMutationRecovery.FinalizeMediaReceipt) {
        val identity = requirePreparedMediaIdentity(action.parent, action.child, action.claimId)
        if (action.child.attemptCount <= 0 || action.child.receipt != action.receipt) {
            throw JournalCorruptionException("Persisted media receipt contradicts provider evidence")
        }
        val validated = MediaInsertReceiptValidator.validate(action.receipt.fileUri)
            ?: throw JournalCorruptionException("Persisted media receipt URI is invalid")
        if (validated.actualFilename != action.receipt.actualFilename) {
            throw JournalCorruptionException("Persisted media receipt filename and URI disagree")
        }
        try {
            AnkiValidators.validateProviderFilename(
                actual = action.receipt.actualFilename,
                requested = identity.claim.requestedFilename,
                preferred = identity.claim.preferredName,
            )
        } catch (error: AnkiProtocolException) {
            throw JournalCorruptionException("Persisted media receipt is unrelated to its durable request", error)
        }
        store.commitMediaReceipt(
            childId = action.child.id,
            claimId = identity.claim.id,
            receipt = action.receipt,
            compactEvidence = mediaRecoveryEvidence(action.parent, providerEntered = true),
        )
    }

    private fun requirePreparedMediaIdentity(
        parent: ParentRecord,
        child: ChildRecord,
        expectedClaimId: Long?,
    ): PreparedMediaIdentity {
        try {
            if (parent.operation != ParentOperation.STORE_MEDIA || child.parentId != parent.id) {
                throw JournalCorruptionException("Prepared media parent and child disagree")
            }
            val command = child.command as? MutationCommand.StoreMedia
                ?: throw JournalCorruptionException("Prepared media child lacks a media command")
            val claimId = expectedClaimId
                ?: throw JournalCorruptionException("Prepared media child lacks a claim ID")
            if (child.mediaClaimId != claimId) {
                throw JournalCorruptionException("Prepared media action and child claim IDs disagree")
            }
            val item = store.requestItems(parent.key).singleOrNull { requestItem ->
                requestItem.requestIndex == command.requestIndexValue
            } ?: throw JournalCorruptionException("Prepared media command lacks one exact request item")
            if (
                item.parentId != parent.id ||
                    item.itemId != command.assetId ||
                    command.identityKey != command.assetId
            ) {
                throw JournalCorruptionException("Prepared media command and request item disagree")
            }
            val claim = store.mediaClaim(parent.key, command.assetId)
                ?: throw JournalCorruptionException("Prepared media command lacks an exact claim")
            if (
                claim.id != claimId ||
                    claim.runId != parent.key.runId ||
                    claim.requestId != parent.key.requestId ||
                    claim.assetId != command.assetId ||
                    claim.preferredName != command.preferredName ||
                    claim.state != MediaClaimState.PENDING ||
                    claim.actualFilename != null
            ) {
                throw JournalCorruptionException("Prepared media claim identity or state is invalid")
            }
            AnkiValidators.validateProviderFilename(
                actual = claim.requestedFilename,
                requested = claim.requestedFilename,
                preferred = claim.preferredName,
            )

            val staging = store.stagingForRecovery().singleOrNull { record ->
                record.runId == parent.key.runId &&
                    record.requestId == parent.key.requestId &&
                    record.assetId == command.assetId
            } ?: throw JournalCorruptionException("Prepared media command lacks one exact staging record")
            if (
                staging.contentUri != command.fileUri ||
                    staging.packageName != ANKIDROID_PACKAGE ||
                    staging.sha256 != claim.sha256 ||
                    staging.state != StagingState.GRANTED
            ) {
                throw JournalCorruptionException("Prepared media command and staging identity disagree")
            }
            return PreparedMediaIdentity(command, claim)
        } catch (error: JournalCorruptionException) {
            throw error
        } catch (error: RuntimeException) {
            throw JournalCorruptionException("Prepared media durable identity could not be validated", error)
        }
    }

    private fun requireTypedQuiescence(
        inventory: RecoveryInventory,
        allowStagingRemediation: Boolean,
    ) {
        if (
            inventory.preparedChild != null ||
                inventory.preparedRoutingIntent != null ||
                inventory.preparedTargetExpectation != null ||
                inventory.unfinishedParents.isNotEmpty() ||
                inventory.activeMediaLeaseRunIds.isNotEmpty() ||
                inventory.reservedMediaReservationIds.isNotEmpty()
        ) {
            throw JournalCorruptionException("Startup recovery did not drain durable mutation capabilities")
        }

        val claimsById = inventory.unresolvedClaims.associateBy(MediaClaimRecord::id)
        if (claimsById.size != inventory.unresolvedClaims.size) {
            throw JournalCorruptionException("Startup recovery inventory repeats an unresolved media claim")
        }
        val mediaRemediations =
            inventory.openRemediations.filter { remediation ->
                remediation.kind == RemediationKind.MEDIA_COMMIT_UNCERTAIN ||
                    remediation.kind == RemediationKind.MEDIA_STORED_UNATTACHED
            }
        inventory.unresolvedClaims.forEach { claim ->
            val expectedKind =
                when (claim.state) {
                    MediaClaimState.COMMIT_UNCERTAIN -> RemediationKind.MEDIA_COMMIT_UNCERTAIN
                    MediaClaimState.STORED,
                    MediaClaimState.PRESENT_BYTES_VERIFIED,
                    -> RemediationKind.MEDIA_STORED_UNATTACHED
                    else -> throw JournalCorruptionException(
                        "Startup recovery retained an unsupported unresolved media claim",
                    )
                }
            val exact = mediaRemediations.filter { remediation -> remediation.claimId == claim.id }
            val parent = store.parent(ParentKey(claim.runId, claim.requestId))
            if (
                exact.size != 1 ||
                    exact.single().kind != expectedKind ||
                    parent == null ||
                    exact.single().parentId != parent.id ||
                    parent.operation != ParentOperation.STORE_MEDIA ||
                    !parent.state.isFinalized
            ) {
                throw JournalCorruptionException("Unresolved media claim lacks one exact remediation")
            }
        }
        mediaRemediations.forEach { remediation ->
            val claim = remediation.claimId?.let(claimsById::get)
                ?: throw JournalCorruptionException("Media remediation lacks its unresolved claim")
            val expectedKind =
                when (claim.state) {
                    MediaClaimState.COMMIT_UNCERTAIN -> RemediationKind.MEDIA_COMMIT_UNCERTAIN
                    MediaClaimState.STORED,
                    MediaClaimState.PRESENT_BYTES_VERIFIED,
                    -> RemediationKind.MEDIA_STORED_UNATTACHED
                    else -> throw JournalCorruptionException("Media remediation has an invalid claim state")
                }
            if (remediation.kind != expectedKind) {
                throw JournalCorruptionException("Media remediation kind disagrees with its claim")
            }
        }
        if (
            !allowStagingRemediation &&
                inventory.openRemediations.any { it.kind == RemediationKind.STAGING_QUARANTINED }
        ) {
            throw JournalCorruptionException("Clean staging recovery retained an open staging remediation")
        }
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
                reconcile(
                    action.expectedTarget.model.toProviderSnapshot(),
                    action.expectedTarget.expectedDeckName,
                    validatedReceipt,
                )
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

    private fun mediaRecoveryEvidence(
        parent: ParentRecord,
        providerEntered: Boolean,
    ): String =
        "startupRecovery=media;providerEntry=$providerEntered;requestSha256=${parent.requestSha256}"

    private data class PreparedMediaIdentity(
        val command: MutationCommand.StoreMedia,
        val claim: MediaClaimRecord,
    )
}

private object MissingMediaStagingRecovery : MediaStagingRecovery {
    override fun recover(): AnkiMediaRecoveryReport = throw PendingMediaStagingRecoveryException()
}

internal class PendingNonTargetMutationRecoveryException : RuntimeException()

internal class PendingMediaStagingRecoveryException : RuntimeException()
