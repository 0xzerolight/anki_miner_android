package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.AnkiMutationStore
import com.ankiminer.android.anki.journal.ChildRecord
import com.ankiminer.android.anki.journal.ChildState
import com.ankiminer.android.anki.journal.DurableTargetExpectation
import com.ankiminer.android.anki.journal.DurableTargetSnapshot
import com.ankiminer.android.anki.journal.JournalError
import com.ankiminer.android.anki.journal.JournalErrorCode
import com.ankiminer.android.anki.journal.JournalRequest
import com.ankiminer.android.anki.journal.JournalResponse
import com.ankiminer.android.anki.journal.MutationCommand
import com.ankiminer.android.anki.journal.ParentKey
import com.ankiminer.android.anki.journal.ProviderReceipt
import com.ankiminer.android.anki.journal.ReplayResult
import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.AnkiErrorResult
import com.ankiminer.android.anki.protocol.AnkiResponse
import com.ankiminer.android.anki.protocol.VerifyTargetRequest
import com.ankiminer.android.anki.protocol.VerifyTargetResult
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class TargetVerificationOutcome(
    val response: AnkiResponse,
    val durable: Boolean,
    val targetForAdmission: TargetSnapshot?,
    val replayed: Boolean,
)

internal fun interface AnkiTargetVerifier {
    fun verify(
        owner: AnkiRunStateRegistry.RunOwner,
        reservation: TargetVerificationReservation,
        request: VerifyTargetRequest,
    ): TargetVerificationOutcome
}

internal interface TargetVerificationJournal {
    fun replay(request: JournalRequest): ReplayResult

    fun begin(request: JournalRequest)

    fun storeExpectation(
        key: ParentKey,
        expectation: DurableTargetExpectation,
    )

    fun storeTarget(
        key: ParentKey,
        target: DurableTargetSnapshot,
    )

    fun prepareDeck(
        key: ParentKey,
        deckName: String,
    ): ChildRecord

    fun recordEntry(childId: Long)

    fun recordReceipt(
        childId: Long,
        receipt: DeckCreateReceipt,
    )

    fun completeVerifiedDeck(
        childId: Long,
        target: DurableTargetSnapshot,
        evidence: String,
    )

    fun completeUncertainDeck(
        childId: Long,
        evidence: String,
    )

    fun completePreEntryDeck(
        childId: Long,
        evidence: String,
    )

    fun markResultReady(
        request: JournalRequest,
        response: JournalResponse,
    )
}

internal class AnkiMutationTargetVerificationJournal(
    private val store: AnkiMutationStore,
) : TargetVerificationJournal {
    override fun replay(request: JournalRequest): ReplayResult = store.replay(request, liveRun = true)

    override fun begin(request: JournalRequest) {
        store.createParent(request)
        store.beginParent(request.key)
    }

    override fun storeExpectation(
        key: ParentKey,
        expectation: DurableTargetExpectation,
    ) {
        store.storeTargetExpectation(key, expectation)
    }

    override fun storeTarget(
        key: ParentKey,
        target: DurableTargetSnapshot,
    ) {
        store.storeTargetSnapshot(key, target)
    }

    override fun prepareDeck(
        key: ParentKey,
        deckName: String,
    ): ChildRecord = store.prepareChild(key, MutationCommand.CreateDeck(deckName))

    override fun recordEntry(childId: Long) {
        store.recordProviderEntry(childId)
    }

    override fun recordReceipt(
        childId: Long,
        receipt: DeckCreateReceipt,
    ) {
        store.recordDeckReceipt(
            childId,
            ProviderReceipt.Deck(receipt.deckId, receipt.contentUri),
        )
    }

    override fun completeVerifiedDeck(
        childId: Long,
        target: DurableTargetSnapshot,
        evidence: String,
    ) {
        store.completeVerifiedDeck(childId, target, evidence)
    }

    override fun completeUncertainDeck(
        childId: Long,
        evidence: String,
    ) {
        store.completeUncertainDeck(childId, evidence)
    }

    override fun completePreEntryDeck(
        childId: Long,
        evidence: String,
    ) {
        store.completeChild(childId, ChildState.PROVEN_NOT_COMMITTED, evidence)
    }

    override fun markResultReady(
        request: JournalRequest,
        response: JournalResponse,
    ) {
        store.markResultReady(request, response)
    }
}

internal interface TargetVerificationBoundaryHooks {
    fun beforeProviderEntry() = Unit

    fun afterProviderEntry() = Unit

    fun afterProviderReturn() = Unit
}

internal object NoOpTargetVerificationBoundaryHooks : TargetVerificationBoundaryHooks

internal class DurableTargetVerifier(
    gateway: AnkiProviderGateway,
    private val registry: AnkiRunStateRegistry,
    private val journal: TargetVerificationJournal,
    private val boundaryHooks: TargetVerificationBoundaryHooks = NoOpTargetVerificationBoundaryHooks,
) : AnkiTargetVerifier {
    private val gateway = gateway
    private val checkedProvider = CheckedProvider(gateway)
    private val snapshots = TargetSnapshotReader(checkedProvider)

    override fun verify(
        owner: AnkiRunStateRegistry.RunOwner,
        reservation: TargetVerificationReservation,
        request: VerifyTargetRequest,
    ): TargetVerificationOutcome {
        require(reservation.request == request) { "Target reservation and request differ" }
        val durableRequest = JournalRequest.from(request)
        when (val replay = journal.replay(durableRequest)) {
            is ReplayResult.Ready -> return replayOutcome(request, reservation, replay.response)
            ReplayResult.Missing -> Unit
            ReplayResult.DigestMismatch -> throw invalidRequest("The durable verifyTarget request digest changed")
            ReplayResult.NotReplayable -> throw invalidRequest("The durable verifyTarget request is not replayable")
            ReplayResult.LiveOwnerRequired -> throw invalidRequest("verifyTarget replay requires a live run owner")
        }

        journal.begin(durableRequest)
        var preparedDeck: ChildRecord? = null
        var providerEntryRecorded = false
        try {
            val installed = reservation.installedTarget
            if (installed != null) {
                requireRequestedTarget(request, installed)
                val exact = requeryPinnedTarget(installed, registry.cancellation(owner))
                return terminalSuccess(durableRequest, request, exact, replayed = false)
            }

            val cancellation = registry.cancellation(owner)
            val model = snapshots.readModelByName(request.modelName, cancellation)
            requireFields(request, model)
            val existingDeck = snapshots.readDeckByName(request.deckName, cancellation)
            if (existingDeck != null) {
                val target = TargetSnapshot(existingDeck, model)
                val durableTarget = target.toDurableSnapshot()
                journal.storeTarget(durableRequest.key, durableTarget)
                return terminalSuccess(
                    durableRequest,
                    request,
                    target,
                    replayed = false,
                    targetAlreadyStored = true,
                )
            }

            journal.storeExpectation(durableRequest.key, model.toDurableExpectation(request.deckName))
            preparedDeck = journal.prepareDeck(durableRequest.key, request.deckName)
            val command = AnkiProviderMutationCommand.CreateDeck(request.deckName)
            checkedProvider.preflightMutation(cancellation)
            boundaryHooks.beforeProviderEntry()
            when (registry.authorizeTargetProviderEntry(owner, reservation)) {
                TargetProviderEntryAuthorization.AUTHORIZED -> Unit
                TargetProviderEntryAuthorization.QUARANTINED -> throw quarantinedBeforeEntry()
                TargetProviderEntryAuthorization.RELEASING,
                TargetProviderEntryAuthorization.CANCELLED,
                -> throw cancelledBeforeEntry()
            }
            journal.recordEntry(preparedDeck.id)
            providerEntryRecorded = true
            boundaryHooks.afterProviderEntry()

            val rawReceipt =
                try {
                    gateway.createDeck(command)
                } catch (_: RuntimeException) {
                    null
                }
            boundaryHooks.afterProviderReturn()
            val receipt = DeckCreateReceiptValidator.validate(rawReceipt)
            if (receipt != null) journal.recordReceipt(preparedDeck.id, receipt)

            val reconciled = reconcileEnteredCreate(model, request.deckName, receipt)
            if (reconciled != null) {
                val durableTarget = reconciled.toDurableSnapshot()
                val evidence = deckEvidence(durableRequest, request.deckName, receipt, rawReceipt, "exact")
                journal.completeVerifiedDeck(preparedDeck.id, durableTarget, evidence)
                return terminalSuccess(
                    durableRequest,
                    request,
                    reconciled,
                    replayed = false,
                    targetAlreadyStored = true,
                )
            }

            val evidence = deckEvidence(durableRequest, request.deckName, receipt, rawReceipt, "inconclusive")
            journal.completeUncertainDeck(preparedDeck.id, evidence)
            return terminalError(durableRequest, request, postCommitUncertain(), replayed = false)
        } catch (failure: AnkiReadFailure) {
            preparedDeck?.let { child ->
                if (providerEntryRecorded) {
                    val evidence =
                        "providerEntry=true;requestSha256=${durableRequest.digest.sha256};postEntryFailure=${failure.code.wireName}"
                    journal.completeUncertainDeck(child.id, evidence)
                    return terminalError(
                        durableRequest,
                        request,
                        postCommitUncertain(),
                        replayed = false,
                    )
                } else {
                    journal.completePreEntryDeck(
                        child.id,
                        "providerEntry=false;requestSha256=${durableRequest.digest.sha256};code=${failure.code.wireName}",
                    )
                }
            }
            return terminalError(durableRequest, request, failure.toJournalError(), replayed = false)
        }
    }

    private fun replayOutcome(
        request: VerifyTargetRequest,
        reservation: TargetVerificationReservation,
        response: JournalResponse,
    ): TargetVerificationOutcome =
        when (response) {
            is JournalResponse.VerifySuccess -> {
                val target = response.target.toProviderSnapshot()
                requireRequestedTarget(request, target)
                val installed = reservation.installedTarget
                if (installed != null && installed != target) {
                    throw targetInvalid("The replayed Anki target conflicts with this live run")
                }
                TargetVerificationOutcome(
                    response = target.toProtocolResult(request),
                    durable = true,
                    targetForAdmission = target,
                    replayed = true,
                )
            }
            is JournalResponse.VerifyError ->
                TargetVerificationOutcome(
                    response = response.error.toProtocolResult(request),
                    durable = true,
                    targetForAdmission = null,
                    replayed = true,
                )
            is JournalResponse.StoreMedia, is JournalResponse.CreateNotes ->
                throw invalidRequest("The durable response operation does not match verifyTarget")
        }

    private fun terminalSuccess(
        journalRequest: JournalRequest,
        request: VerifyTargetRequest,
        target: TargetSnapshot,
        replayed: Boolean,
        targetAlreadyStored: Boolean = false,
    ): TargetVerificationOutcome {
        val durableTarget = target.toDurableSnapshot()
        if (!targetAlreadyStored) journal.storeTarget(journalRequest.key, durableTarget)
        journal.markResultReady(
            journalRequest,
            JournalResponse.VerifySuccess(journalRequest.key, durableTarget),
        )
        return TargetVerificationOutcome(
            response = target.toProtocolResult(request),
            durable = true,
            targetForAdmission = target,
            replayed = replayed,
        )
    }

    private fun terminalError(
        journalRequest: JournalRequest,
        request: VerifyTargetRequest,
        error: JournalError,
        replayed: Boolean,
    ): TargetVerificationOutcome {
        journal.markResultReady(
            journalRequest,
            JournalResponse.VerifyError(journalRequest.key, error),
        )
        return TargetVerificationOutcome(
            response = error.toProtocolResult(request),
            durable = true,
            targetForAdmission = null,
            replayed = replayed,
        )
    }

    private fun requeryPinnedTarget(
        expected: TargetSnapshot,
        cancellation: AnkiCancellation,
    ): TargetSnapshot {
        val model = snapshots.readModelById(expected.model.id, cancellation)
        if (model != expected.model) throw targetInvalid("The verified Anki note type changed during this run")
        val deck = snapshots.readDeckById(expected.deck.id, cancellation)
        if (deck != expected.deck) throw targetInvalid("The verified Anki deck changed during this run")
        return TargetSnapshot(deck, model)
    }

    /** No owner cancellation is consulted anywhere in this entered-command reconciliation. */
    private fun reconcileEnteredCreate(
        expectedModel: ModelSnapshot,
        expectedDeckName: String,
        receipt: DeckCreateReceipt?,
    ): TargetSnapshot? {
        return try {
            val model = snapshots.readModelById(expectedModel.id, AnkiCancellation.NONE)
            if (model != expectedModel) return null
            val byName = snapshots.readDeckByName(expectedDeckName, AnkiCancellation.NONE) ?: return null
            if (receipt != null) {
                val byReceipt = snapshots.readDeckById(receipt.deckId, AnkiCancellation.NONE)
                if (byReceipt != byName) return null
            }
            TargetSnapshot(byName, model)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun requireRequestedTarget(
        request: VerifyTargetRequest,
        target: TargetSnapshot,
    ) {
        if (target.deck.name != request.deckName || target.model.name != request.modelName) {
            throw targetInvalid("The requested Anki target conflicts with this live run")
        }
        requireFields(request, target.model)
    }

    private fun requireFields(
        request: VerifyTargetRequest,
        model: ModelSnapshot,
    ) {
        if (request.requiredFields.any { it !in model.fieldNames }) {
            throw AnkiReadFailure(
                AnkiErrorCode.FIELD_MISSING,
                retryable = false,
                stableMessage = "The selected note type is missing a required field",
            )
        }
    }
}

private fun TargetSnapshot.toProtocolResult(request: VerifyTargetRequest) =
    VerifyTargetResult(
        runId = request.runId,
        requestId = request.requestId,
        deckId = deck.id,
        modelId = model.id,
        fieldNames = model.fieldNames,
        deckCreated = false,
    )

private fun JournalError.toProtocolResult(request: VerifyTargetRequest) =
    AnkiErrorResult(
        runId = request.runId,
        requestId = request.requestId,
        operation = request.operation,
        code = AnkiErrorCode.valueOf(code.name),
        message = message,
        retryable = retryable,
    )

private fun AnkiReadFailure.toJournalError() =
    JournalError(
        code = JournalErrorCode.valueOf(code.name),
        message = stableMessage,
        retryable = retryable,
    )

private fun invalidRequest(message: String) =
    AnkiReadFailure(AnkiErrorCode.INVALID_REQUEST, retryable = false, stableMessage = message)

private fun targetInvalid(message: String) =
    AnkiReadFailure(AnkiErrorCode.TARGET_INVALID, retryable = false, stableMessage = message)

private fun cancelledBeforeEntry() =
    AnkiReadFailure(
        AnkiErrorCode.CANCELLED,
        retryable = false,
        stableMessage = "The Anki operation was cancelled before provider entry",
    )

private fun quarantinedBeforeEntry() =
    AnkiReadFailure(
        AnkiErrorCode.INVALID_REQUEST,
        retryable = false,
        stableMessage = "The Anki run was quarantined before provider entry",
    )

private fun postCommitUncertain() =
    JournalError(
        JournalErrorCode.POST_COMMIT_UNCERTAIN,
        "Anki deck creation could not be conclusively reconciled",
        retryable = false,
    )

private fun deckEvidence(
    request: JournalRequest,
    expectedDeckName: String,
    receipt: DeckCreateReceipt?,
    rawReceipt: String?,
    observation: String,
): String =
    buildString(768) {
        append("deck=")
        append(expectedDeckName)
        append(";requestSha256=")
        append(request.digest.sha256)
        append(";observation=")
        append(observation)
        if (receipt != null) {
            append(";returnedDeckId=")
            append(receipt.deckId)
            append(";returnedUri=")
            append(receipt.contentUri)
        } else if (rawReceipt != null) {
            append(";invalidReturnedUriUtf16Length=")
            append(rawReceipt.length)
            append(";invalidReturnedUriSha256=")
            append(utf16Sha256(rawReceipt))
        } else {
            append(";returnedUri=null")
        }
    }

private fun utf16Sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    value.forEach { character ->
        digest.update(ByteBuffer.allocate(2).putChar(character).array())
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
