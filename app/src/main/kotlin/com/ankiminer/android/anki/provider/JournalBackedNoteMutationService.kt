package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.journal.ActiveNoteMaterialization
import com.ankiminer.android.anki.journal.ActiveNoteTermination
import com.ankiminer.android.anki.journal.AlignedResult
import com.ankiminer.android.anki.journal.AnkiMutationStore
import com.ankiminer.android.anki.journal.ChildState
import com.ankiminer.android.anki.journal.ChildlessRoutingOutcome
import com.ankiminer.android.anki.journal.DurableDuplicateDecision
import com.ankiminer.android.anki.journal.DurableMediaBinding
import com.ankiminer.android.anki.journal.JournalCorruptionException
import com.ankiminer.android.anki.journal.JournalError
import com.ankiminer.android.anki.journal.JournalErrorCode
import com.ankiminer.android.anki.journal.JournalRequest
import com.ankiminer.android.anki.journal.JournalResponse
import com.ankiminer.android.anki.journal.MutationCommand
import com.ankiminer.android.anki.journal.NoteRoutingPhase
import com.ankiminer.android.anki.journal.OrderedNoteField
import com.ankiminer.android.anki.journal.ParentKey
import com.ankiminer.android.anki.journal.ParentRecord
import com.ankiminer.android.anki.journal.PreparedRoutingFailure
import com.ankiminer.android.anki.journal.ProviderReceipt
import com.ankiminer.android.anki.journal.ReplayResult
import com.ankiminer.android.anki.journal.RoutingCardObservation
import com.ankiminer.android.anki.journal.RoutingIntentDraft
import com.ankiminer.android.anki.journal.RoutingIntentRecord
import com.ankiminer.android.anki.journal.RoutingIntentState
import com.ankiminer.android.anki.protocol.AnkiErrorCode
import com.ankiminer.android.anki.protocol.AnkiErrorDetail
import com.ankiminer.android.anki.protocol.CommittedFailedNote
import com.ankiminer.android.anki.protocol.CreateNote
import com.ankiminer.android.anki.protocol.CreateNotesRequest
import com.ankiminer.android.anki.protocol.CreateNotesResult
import com.ankiminer.android.anki.protocol.CreatedNote
import com.ankiminer.android.anki.protocol.DuplicateCandidate
import com.ankiminer.android.anki.protocol.DuplicateNote
import com.ankiminer.android.anki.protocol.FailedNote
import com.ankiminer.android.anki.protocol.NotAttemptedNote
import com.ankiminer.android.anki.protocol.UncertainNote
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import java.util.concurrent.CancellationException

internal data class CreateNotesMutationOutcome(
    val result: CreateNotesResult,
    val replayed: Boolean,
)

internal fun interface NoteMutationService {
    fun create(
        owner: AnkiRunStateRegistry.RunOwner,
        request: CreateNotesRequest,
    ): CreateNotesMutationOutcome
}

/** Narrow durable surface used by the production note saga and its state-machine tests. */
internal interface NoteMutationJournal {
    fun replay(request: JournalRequest): ReplayResult
    fun begin(request: JournalRequest, target: TargetSnapshot)
    fun parent(key: ParentKey): ParentRecord?
    fun append(key: ParentKey, row: AlignedResult)
    fun materialize(key: ParentKey, note: ActiveNoteMaterialization)
    fun prepareNote(key: ParentKey, command: MutationCommand.InsertNote): Long
    fun recordProviderEntry(childId: Long, recoveryReissue: Boolean = false)
    fun commitNoteReceipt(childId: Long, receipt: ProviderReceipt.Note, evidence: String)
    fun advance(key: ParentKey, requestIndex: Int, phase: NoteRoutingPhase)
    fun createRoutingIntents(
        key: ParentKey,
        requestIndex: Int,
        drafts: List<RoutingIntentDraft>,
    ): List<RoutingIntentRecord>
    fun completeChildless(intentId: Long, outcome: ChildlessRoutingOutcome): RoutingIntentRecord
    fun prepareRoutingChild(intentId: Long): Long
    fun recordCardReceipt(childId: Long)
    fun completeRouting(
        childId: Long,
        childState: ChildState,
        intentState: RoutingIntentState,
        evidence: String,
    ): RoutingIntentRecord
    fun terminate(key: ParentKey, termination: ActiveNoteTermination)
    fun completeVerified(key: ParentKey, requestIndex: Int, noteId: Long, evidence: String)
    fun results(key: ParentKey): List<AlignedResult>
    fun markReady(request: JournalRequest, response: JournalResponse.CreateNotes)
}

internal class AnkiMutationNoteJournal(
    private val store: AnkiMutationStore,
) : NoteMutationJournal {
    override fun replay(request: JournalRequest): ReplayResult = store.replay(request, liveRun = true)

    override fun begin(request: JournalRequest, target: TargetSnapshot) {
        store.createParent(request)
        store.beginParent(request.key)
        store.storeTargetSnapshot(request.key, target.toDurableSnapshot())
    }

    override fun parent(key: ParentKey): ParentRecord? = store.parent(key)

    override fun append(key: ParentKey, row: AlignedResult) {
        store.appendAlignedResult(key, row)
    }

    override fun materialize(key: ParentKey, note: ActiveNoteMaterialization) {
        store.materializeActiveNote(key, note)
    }

    override fun prepareNote(key: ParentKey, command: MutationCommand.InsertNote): Long =
        store.prepareChild(key, command).id

    override fun recordProviderEntry(childId: Long, recoveryReissue: Boolean) {
        store.recordProviderEntry(childId, recoveryReissue)
    }

    override fun commitNoteReceipt(childId: Long, receipt: ProviderReceipt.Note, evidence: String) {
        store.commitNoteReceipt(childId, receipt, evidence)
    }

    override fun advance(key: ParentKey, requestIndex: Int, phase: NoteRoutingPhase) {
        store.advanceNotePhase(key, requestIndex, phase)
    }

    override fun createRoutingIntents(
        key: ParentKey,
        requestIndex: Int,
        drafts: List<RoutingIntentDraft>,
    ): List<RoutingIntentRecord> = store.createRoutingIntents(key, requestIndex, drafts)

    override fun completeChildless(
        intentId: Long,
        outcome: ChildlessRoutingOutcome,
    ): RoutingIntentRecord = store.completeChildlessRoutingIntent(intentId, outcome)

    override fun prepareRoutingChild(intentId: Long): Long = store.prepareRoutingChild(intentId).id

    override fun recordCardReceipt(childId: Long) {
        store.recordCardReceipt(childId)
    }

    override fun completeRouting(
        childId: Long,
        childState: ChildState,
        intentState: RoutingIntentState,
        evidence: String,
    ): RoutingIntentRecord = store.completeRoutingChild(childId, childState, intentState, evidence)

    override fun terminate(key: ParentKey, termination: ActiveNoteTermination) {
        store.terminateActiveNote(key, termination)
    }

    override fun completeVerified(key: ParentKey, requestIndex: Int, noteId: Long, evidence: String) {
        store.completeVerifiedNote(key, requestIndex, noteId, evidence)
    }

    override fun results(key: ParentKey): List<AlignedResult> = store.alignedResults(key)

    override fun markReady(request: JournalRequest, response: JournalResponse.CreateNotes) {
        store.markResultReady(request, response)
    }
}

internal interface NoteMutationReads {
    fun readTargetBeforeEntry(
        owner: AnkiRunStateRegistry.RunOwner,
        expected: TargetSnapshot,
    ): TargetSnapshot

    fun readDuplicateBeforeEntry(
        owner: AnkiRunStateRegistry.RunOwner,
        target: TargetSnapshot,
        candidate: DuplicateCandidate,
    ): DuplicateRawSnapshot

    /** Every method below uses non-cancellable reads for mandatory post-entry reconciliation. */
    fun readTargetAfterEntry(expected: TargetSnapshot): TargetSnapshot
    fun readNoteAfterEntry(noteId: Long): NoteSnapshot
    fun readCardsAfterEntry(noteId: Long, templateCount: Int): List<CardIdentity>
    fun readCardAfterEntry(cardId: Long): CardIdentity
}

internal class ExactNoteMutationReads(
    gateway: AnkiProviderGateway,
    private val ownedReads: AnkiProviderReadService,
) : NoteMutationReads {
    private val checked = CheckedProvider(gateway)
    private val targets = TargetSnapshotReader(checked)
    private val notes = NoteSnapshotReader(checked)
    private val cards = GlobalCardReader(checked)

    override fun readTargetBeforeEntry(
        owner: AnkiRunStateRegistry.RunOwner,
        expected: TargetSnapshot,
    ): TargetSnapshot = ownedReads.readTargetById(owner, expected)

    override fun readDuplicateBeforeEntry(
        owner: AnkiRunStateRegistry.RunOwner,
        target: TargetSnapshot,
        candidate: DuplicateCandidate,
    ): DuplicateRawSnapshot = ownedReads.readDuplicateSnapshot(owner, target, listOf(candidate))

    override fun readTargetAfterEntry(expected: TargetSnapshot): TargetSnapshot {
        val actual =
            TargetSnapshot(
                model = targets.readModelById(expected.model.id, AnkiCancellation.NONE),
                deck = targets.readDeckById(expected.deck.id, AnkiCancellation.NONE),
            )
        if (actual != expected) {
            throw AnkiReadFailure(
                AnkiErrorCode.TARGET_INVALID,
                retryable = false,
                stableMessage = "The verified Anki target changed after note insertion",
            )
        }
        return actual
    }

    override fun readNoteAfterEntry(noteId: Long): NoteSnapshot =
        notes.readById(noteId, AnkiCancellation.NONE)

    override fun readCardsAfterEntry(noteId: Long, templateCount: Int): List<CardIdentity> =
        cards.readForNote(noteId, templateCount, AnkiCancellation.NONE)

    override fun readCardAfterEntry(cardId: Long): CardIdentity =
        cards.readById(cardId, AnkiCancellation.NONE)
}

internal interface NoteMutationProvider {
    fun preflight(cancellation: AnkiCancellation)
    fun insert(command: AnkiProviderMutationCommand.InsertNote): String?
    fun route(command: AnkiProviderMutationCommand.RouteCard): Int
}

internal class CheckedNoteMutationProvider(
    private val gateway: AnkiProviderGateway,
) : NoteMutationProvider {
    private val checked = CheckedProvider(gateway)

    override fun preflight(cancellation: AnkiCancellation) {
        checked.preflightMutation(cancellation)
    }

    override fun insert(command: AnkiProviderMutationCommand.InsertNote): String? = gateway.insertNote(command)

    override fun route(command: AnkiProviderMutationCommand.RouteCard): Int = gateway.routeCard(command)
}

/** One-shot ContentProvider note/card saga with durable receipts and fail-closed reconciliation. */
internal class JournalBackedNoteMutationService(
    private val registry: AnkiRunStateRegistry,
    private val journal: NoteMutationJournal,
    private val reads: NoteMutationReads,
    private val provider: NoteMutationProvider,
) : NoteMutationService {
    override fun create(
        owner: AnkiRunStateRegistry.RunOwner,
        request: CreateNotesRequest,
    ): CreateNotesMutationOutcome {
        require(owner.runId == request.runId) { "Note request belongs to a different run owner" }
        val durableRequest = JournalRequest.from(request)
        when (val replay = journal.replay(durableRequest)) {
            is ReplayResult.Ready -> return replayOutcome(request, replay.response)
            ReplayResult.Missing -> Unit
            ReplayResult.DigestMismatch -> throw noteMutationConflict("The durable note request digest changed")
            ReplayResult.NotReplayable -> throw noteMutationConflict("The durable note request is not replayable")
            ReplayResult.LiveOwnerRequired -> throw noteMutationConflict("Note replay requires a live run owner")
        }

        // Initiating createNotes consumes this one-use baseline before any other fallible work.
        val baseline = registry.consumeBaseline(owner, request.baselineToken)
        val target = validateRequestAgainstBaseline(request, baseline)
        reads.readTargetBeforeEntry(owner, target)
        val prepared = request.notes.mapIndexed { index, note -> prepareMaterialization(owner, index, note, target, baseline) }
        journal.begin(durableRequest, target)

        for ((index, note) in request.notes.withIndex()) {
            val candidate = prepared[index].candidate
            val fresh =
                try {
                    reads.readDuplicateBeforeEntry(owner, target, candidate)
                } catch (failure: AnkiReadFailure) {
                    val error = failure.toStableNoteError("The final duplicate check failed before note insertion")
                    journal.append(
                        durableRequest.key,
                        AlignedResult.NoteFailed(index, note.clientNoteId, error, "providerEntry=false;duplicateRead=failed"),
                    )
                    return finishStopped(request, durableRequest, index, error)
                }
            val uniqueIndex = baseline.occurrences[note.duplicateCandidate.occurrence]
            if (
                baseline.normalizedMatchingNoteIds[uniqueIndex].isNotEmpty() ||
                fresh.normalizedMatchingNoteIds.single().isNotEmpty()
            ) {
                journal.append(durableRequest.key, AlignedResult.NoteDuplicate(index, note.clientNoteId))
                continue
            }

            val materialization = prepared[index].materialization
            try {
                journal.materialize(durableRequest.key, materialization)
                // instrumentation: silent — refusal becomes a journaled aligned failure below
            } catch (failure: JournalCorruptionException) {
                // Corruption is never one row's problem, and its evidence must not be overwritten
                // by an ordinary failed row.
                throw failure
            } catch (failure: RuntimeException) {
                AppLog.w(
                    LogComponent.JOURNAL,
                    "note.materialize",
                    failure,
                    "outcome" to "reconcile",
                    "note_ordinal" to index,
                )
                // Everything else that reaches here rolled back: materialization runs entirely
                // inside one write transaction that commits only on the non-throwing path. A disk
                // -full or locked database on one note is a row failure, not a run failure —
                // narrowing this to the typed refusal made those end the whole batch. The probe
                // still guards the one case that is not a rollback: a throw after the commit, when
                // the parent already carries this note as active.
                val activeIndex =
                    runCatching { journal.parent(durableRequest.key)?.activeRequestIndex }
                        .onFailure { probeFailure ->
                            // The probe failing is itself evidence: the degrade below then rests on
                            // an unread parent, so the entry has to survive for the bundle.
                            AppLog.w(
                                LogComponent.ANKI,
                                "createNotes",
                                probeFailure,
                                "outcome" to "fail",
                                "probe" to "materialization_commit",
                            )
                        }
                        .getOrNull()
                if (activeIndex == index) throw failure
                val error = stableInternal("The note media bindings could not be durably admitted")
                journal.append(
                    durableRequest.key,
                    AlignedResult.NoteFailed(index, note.clientNoteId, error, "providerEntry=false;materialization=refused"),
                )
                return finishStopped(request, durableRequest, index, error)
            }
            val noteChildId =
                journal.prepareNote(
                    durableRequest.key,
                    MutationCommand.InsertNote(
                        requestIndexValue = index,
                        clientNoteId = note.clientNoteId,
                        modelId = target.model.id,
                        joinedFields = materialization.joinedFields,
                        providerTagsWire = materialization.providerTagsWire,
                    ),
                )

            val noteFailure = insertAndReconcile(owner, request, durableRequest, index, noteChildId, materialization, target)
            if (noteFailure != null) return finishStopped(request, durableRequest, index, noteFailure)
        }
        return finish(request, durableRequest, topLevelError = null, replayed = false)
    }

    private fun insertAndReconcile(
        owner: AnkiRunStateRegistry.RunOwner,
        request: CreateNotesRequest,
        durableRequest: JournalRequest,
        index: Int,
        noteChildId: Long,
        materialization: ActiveNoteMaterialization,
        target: TargetSnapshot,
    ): JournalError? {
        val note = request.notes[index]
        val scope =
            ProviderMutationScope(
                requestId = request.requestId,
                operation = ProviderMutationOperation.NOTE_INSERT,
                durableChildId = noteChildId,
                itemIdentity = note.clientNoteId,
            )
        var capability: ProviderEntryCapability? = null
        var authorized = false
        try {
            val activeCapability =
                try {
                    registry.beginProviderEntry(owner, scope)
                } catch (_: RunReleasingException) {
                    val error = cancelledBeforeNoteEntry("The Anki run was released before note insertion")
                    stopNoteBeforeEntry(durableRequest.key, index, noteChildId, error)
                    return error
                } catch (_: RuntimeException) {
                    val error = stableInternal("The note insertion stopped before provider entry")
                    stopNoteBeforeEntry(durableRequest.key, index, noteChildId, error)
                    return error
                }
            capability = activeCapability
            try {
                provider.preflight(registry.cancellation(owner))
            } catch (failure: AnkiReadFailure) {
                val error = failure.toStableNoteError("AnkiDroid was unavailable before note insertion")
                stopNoteBeforeEntry(durableRequest.key, index, noteChildId, error)
                registry.abortProviderEntry(owner, activeCapability, scope)
                capability = null
                return error
            }
            when (registry.authorizeProviderEntry(owner, activeCapability, scope)) {
                ProviderEntryAuthorization.AUTHORIZED -> authorized = true
                ProviderEntryAuthorization.CANCELLED,
                ProviderEntryAuthorization.RELEASING,
                -> {
                    val error = cancelledBeforeNoteEntry("The note insertion was cancelled before provider entry")
                    stopNoteBeforeEntry(durableRequest.key, index, noteChildId, error)
                    registry.abortProviderEntry(owner, activeCapability, scope)
                    capability = null
                    return error
                }
                ProviderEntryAuthorization.QUARANTINED -> {
                    val error = stableInternal("The note insertion was quarantined before provider entry")
                    stopNoteBeforeEntry(durableRequest.key, index, noteChildId, error)
                    registry.abortProviderEntry(owner, activeCapability, scope)
                    capability = null
                    return error
                }
            }

            journal.recordProviderEntry(noteChildId)
            val rawReceipt =
                try {
                    provider.insert(
                        AnkiProviderMutationCommand.InsertNote(
                            target.model.id,
                            materialization.joinedFields,
                            materialization.providerTagsWire,
                        ),
                    ).also { receipt ->
                        if (receipt == null) {
                            AppLog.w(
                                LogComponent.JOURNAL,
                                "note.insert",
                                Throwable("AnkiDroid returned a null note-insert receipt"),
                                "outcome" to "fail",
                                "entry_id" to noteChildId,
                                "note_ordinal" to index,
                                "receipt" to "null",
                            )
                        }
                    }
                } catch (_: CancellationException) {
                    null
                } catch (failure: RuntimeException) {
                    AppLog.e(
                        LogComponent.JOURNAL,
                        "note.insert",
                        failure,
                        "outcome" to "fail",
                        "entry_id" to noteChildId,
                        "note_ordinal" to index,
                        "receipt" to "exception",
                    )
                    null
                }
            val receipt = NoteInsertReceiptValidator.validate(rawReceipt)
            if (receipt == null) {
                journal.terminate(
                    durableRequest.key,
                    ActiveNoteTermination.EnteredReceiptlessUnknown(
                        index,
                        noteChildId,
                        noteReceiptEvidence(rawReceipt),
                    ),
                )
                registry.completeProviderEntry(owner, activeCapability, scope)
                capability = null
                return postCommitUnknown("AnkiDroid may have inserted the note, but returned no attributable receipt")
            }

            val durableReceipt = ProviderReceipt.Note(receipt.noteId, receipt.contentUri)
            try {
                journal.commitNoteReceipt(
                    noteChildId,
                    durableReceipt,
                    "providerEntry=true;noteId=${receipt.noteId};receipt=canonical",
                )
            } catch (failure: RuntimeException) {
                AppLog.w(
                    LogComponent.JOURNAL,
                    "note.receipt.commit",
                    failure,
                    "outcome" to "reconcile",
                    "entry_id" to noteChildId,
                    "note_ordinal" to index,
                )
                val parent =
                    runCatching { journal.parent(durableRequest.key) }
                        .onFailure { lookupFailure ->
                            AppLog.ignored(
                                LogComponent.JOURNAL,
                                "note.receipt.parent",
                                "original_receipt_failure_retained",
                                lookupFailure,
                            )
                        }.getOrNull()
                if (
                    parent?.activeRequestIndex != index ||
                    parent.activeNoteId != receipt.noteId ||
                    parent.routingPhase != NoteRoutingPhase.NOTE_COMMIT_KNOWN
                ) {
                    // Leave the authorized capability installed: startup recovery owns this seam.
                    throw failure
                }
            }
            registry.completeProviderEntry(owner, activeCapability, scope)
            capability = null

            val knownFailure = reconcileKnownNote(owner, request, durableRequest, index, receipt.noteId, materialization, target)
            return knownFailure
        } finally {
            val pending = capability
            if (pending != null && !authorized) {
                runCatching { registry.abortProviderEntry(owner, pending, scope) }
            }
        }
    }

    private fun reconcileKnownNote(
        owner: AnkiRunStateRegistry.RunOwner,
        request: CreateNotesRequest,
        durableRequest: JournalRequest,
        index: Int,
        noteId: Long,
        materialization: ActiveNoteMaterialization,
        target: TargetSnapshot,
    ): JournalError? {
        val noteReadback =
            try {
                reads.readNoteAfterEntry(noteId)
            } catch (_: RuntimeException) {
                val error = postCommitUnknown("The committed note could not be read back exactly")
                terminateKnown(durableRequest.key, index, noteId, error, "noteId=$noteId;readback=unavailable")
                return error
            }
        if (!noteReadback.matches(noteId, target.model.id, materialization)) {
            val error = stableWriteFailure("AnkiDroid stored different note fields, tags, or model identity")
            terminateKnown(durableRequest.key, index, noteId, error, "noteId=$noteId;readback=mismatch")
            return error
        }
        journal.advance(durableRequest.key, index, NoteRoutingPhase.NOTE_READBACK_VERIFIED)

        val discovered =
            try {
                reads.readCardsAfterEntry(noteId, target.model.templates.size)
            } catch (_: RuntimeException) {
                val error = postCommitUnknown("The committed note's cards could not be discovered exactly")
                terminateKnown(durableRequest.key, index, noteId, error, "noteId=$noteId;cards=unavailable")
                return error
            }
        journal.advance(durableRequest.key, index, NoteRoutingPhase.CARDS_DISCOVERED)
        val intents =
            journal.createRoutingIntents(
                durableRequest.key,
                index,
                discovered.map { card ->
                    RoutingIntentDraft(index, card.id, card.noteId, card.ordinal, target.deck.id, card.deckId)
                },
            )

        for (intent in intents) {
            val routingFailure = routeOneCard(owner, request, durableRequest.key, index, noteId, intent)
            if (routingFailure != null) return routingFailure
        }

        val postcheck =
            try {
                reads.readTargetAfterEntry(target)
                val exactNote = reads.readNoteAfterEntry(noteId)
                val exactCards = reads.readCardsAfterEntry(noteId, target.model.templates.size)
                exactNote.matches(noteId, target.model.id, materialization) &&
                    exactCards.map { it.id to it.ordinal }.toSet() == intents.map { it.cardId to it.ordinal }.toSet() &&
                    exactCards.all { it.noteId == noteId && it.deckId == target.deck.id }
            } catch (_: RuntimeException) {
                false
            }
        if (!postcheck) {
            val error = postCommitUnknown("The final Anki note/card postcondition could not be proven")
            terminateKnown(durableRequest.key, index, noteId, error, "noteId=$noteId;postcheck=failed")
            return error
        }
        journal.advance(durableRequest.key, index, NoteRoutingPhase.POSTCHECK_VERIFIED)
        try {
            // This transaction is the sole attachment-verification and CREATED-row boundary.
            journal.completeVerified(durableRequest.key, index, noteId, "noteId=$noteId;postcheck=exact")
        } catch (failure: RuntimeException) {
            AppLog.w(
                LogComponent.JOURNAL,
                "note.complete",
                failure,
                "outcome" to "reconcile",
                "note_id" to noteId,
                "note_ordinal" to index,
            )
            val parent =
                runCatching { journal.parent(durableRequest.key) }
                    .onFailure { lookupFailure ->
                        AppLog.ignored(
                            LogComponent.JOURNAL,
                            "note.complete.parent",
                            "original_completion_failure_retained",
                            lookupFailure,
                        )
                    }.getOrNull()
            if (parent?.activeRequestIndex == null && journal.results(durableRequest.key).getOrNull(index) is AlignedResult.NoteCreated) {
                return null
            }
            throw failure
        }
        return null
    }

    private fun routeOneCard(
        owner: AnkiRunStateRegistry.RunOwner,
        request: CreateNotesRequest,
        key: ParentKey,
        index: Int,
        noteId: Long,
        intent: RoutingIntentRecord,
    ): JournalError? {
        val before =
            try {
                reads.readCardAfterEntry(intent.cardId)
            } catch (_: RuntimeException) {
                val error = postCommitUnknown("A committed note card could not be read before routing")
                terminateKnown(key, index, noteId, error, "cardId=${intent.cardId};preRouteRead=unavailable")
                return error
            }
        val exactIdentity = before.id == intent.cardId && before.noteId == intent.noteId && before.ordinal == intent.ordinal
        if (!exactIdentity || (before.deckId != intent.targetDeckId && before.deckId != intent.preUpdateDeckId)) {
            val error = stableWriteFailure("A committed note card changed before routing")
            journal.completeChildless(
                intent.id,
                ChildlessRoutingOutcome.Failed(before.toObservation(), "cardId=${intent.cardId};preRoute=drift"),
            )
            terminateKnown(key, index, noteId, error, "cardId=${intent.cardId};preRoute=drift")
            return error
        }
        if (before.deckId == intent.targetDeckId) {
            journal.completeChildless(
                intent.id,
                ChildlessRoutingOutcome.Verified(before.toObservation(), "cardId=${intent.cardId};target=observed"),
            )
            return null
        }

        val childId = journal.prepareRoutingChild(intent.id)
        val scope =
            ProviderMutationScope(
                requestId = request.requestId,
                operation = ProviderMutationOperation.CARD_ROUTING,
                durableChildId = childId,
                itemIdentity = intent.cardId.toString(),
            )
        var capability: ProviderEntryCapability? = null
        var authorized = false
        try {
            val activeCapability =
                try {
                    registry.beginProviderEntry(owner, scope)
                } catch (_: RuntimeException) {
                    val error = stableWriteFailure("The committed note could not begin card routing")
                    terminateKnown(
                        key,
                        index,
                        noteId,
                        error,
                        "cardId=${intent.cardId};providerEntry=false",
                        PreparedRoutingFailure.ProvenNotCommitted(childId),
                    )
                    return error
                }
            capability = activeCapability
            val preflightError =
                try {
                    provider.preflight(AnkiCancellation.NONE)
                    null
                } catch (failure: AnkiReadFailure) {
                    failure.toStableNoteError("AnkiDroid became unavailable before card routing")
                } catch (_: RuntimeException) {
                    stableWriteFailure("AnkiDroid became unavailable before card routing")
                }
            if (preflightError != null) {
                terminateKnown(
                    key,
                    index,
                    noteId,
                    preflightError,
                    "cardId=${intent.cardId};providerEntry=false",
                    PreparedRoutingFailure.ProvenNotCommitted(childId),
                )
                registry.abortProviderEntry(owner, activeCapability, scope)
                capability = null
                return preflightError
            }
            if (
                registry.authorizeMandatoryReconciliationEntry(owner, activeCapability, scope) !=
                    ProviderEntryAuthorization.AUTHORIZED
            ) {
                val error = stableWriteFailure("The committed note could not finish card routing")
                terminateKnown(
                    key,
                    index,
                    noteId,
                    error,
                    "cardId=${intent.cardId};providerEntry=false",
                    PreparedRoutingFailure.ProvenNotCommitted(childId),
                )
                registry.abortProviderEntry(owner, activeCapability, scope)
                capability = null
                return error
            }
            authorized = true
            journal.recordProviderEntry(childId)
            val affected =
                try {
                    provider.route(
                        AnkiProviderMutationCommand.RouteCard(
                            expectedCardId = intent.cardId,
                            noteId = intent.noteId,
                            ordinal = intent.ordinal,
                            targetDeckId = intent.targetDeckId,
                        ),
                    )
                } catch (_: CancellationException) {
                    null
                } catch (failure: RuntimeException) {
                    AppLog.e(
                        LogComponent.JOURNAL,
                        "card.route",
                        failure,
                        "outcome" to "fail",
                        "entry_id" to childId,
                        "note_ordinal" to index,
                        "receipt" to "exception",
                    )
                    null
                }
            if (affected != 1) {
                if (affected != null) {
                    AppLog.w(
                        LogComponent.JOURNAL,
                        "card.route",
                        Throwable("AnkiDroid returned a non-one card-routing count"),
                        "outcome" to "fail",
                        "entry_id" to childId,
                        "note_ordinal" to index,
                        "receipt" to "count",
                        "affected" to affected,
                    )
                }
                val error = postCommitUnknown("AnkiDroid returned no attributable card-routing receipt")
                journal.completeRouting(
                    childId,
                    ChildState.COMMIT_UNCERTAIN,
                    RoutingIntentState.COMMIT_UNCERTAIN,
                    "cardId=${intent.cardId};affected=${affected ?: "exception"}",
                )
                registry.completeProviderEntry(owner, activeCapability, scope)
                capability = null
                terminateKnown(key, index, noteId, error, "cardId=${intent.cardId};routing=uncertain")
                return error
            }
            journal.recordCardReceipt(childId)
            val after =
                try {
                    reads.readCardAfterEntry(intent.cardId)
                } catch (_: RuntimeException) {
                    val error = postCommitUnknown("The routed card could not be read back exactly")
                    journal.completeRouting(
                        childId,
                        ChildState.COMMIT_UNCERTAIN,
                        RoutingIntentState.COMMIT_UNCERTAIN,
                        "cardId=${intent.cardId};postRouteRead=unavailable",
                    )
                    registry.completeProviderEntry(owner, activeCapability, scope)
                    capability = null
                    terminateKnown(key, index, noteId, error, "cardId=${intent.cardId};routing=uncertain")
                    return error
                }
            val exactAfter =
                after.id == intent.cardId && after.noteId == intent.noteId &&
                    after.ordinal == intent.ordinal && after.deckId == intent.targetDeckId
            if (!exactAfter) {
                val identityStillExact =
                    after.id == intent.cardId && after.noteId == intent.noteId && after.ordinal == intent.ordinal
                val error =
                    if (identityStillExact) {
                        stableWriteFailure("The card did not remain in the exact target deck after routing")
                    } else {
                        postCommitUnknown("The routed card identity could not be verified")
                    }
                journal.completeRouting(
                    childId,
                    if (identityStillExact) ChildState.POSTCONDITION_FAILED else ChildState.COMMIT_UNCERTAIN,
                    if (identityStillExact) RoutingIntentState.FAILED else RoutingIntentState.COMMIT_UNCERTAIN,
                    "cardId=${intent.cardId};postRoute=${if (identityStillExact) "deck-mismatch" else "identity-uncertain"}",
                )
                registry.completeProviderEntry(owner, activeCapability, scope)
                capability = null
                terminateKnown(key, index, noteId, error, "cardId=${intent.cardId};routing=failed")
                return error
            }
            journal.completeRouting(
                childId,
                ChildState.POSTCONDITION_VERIFIED,
                RoutingIntentState.VERIFIED,
                "cardId=${intent.cardId};postRoute=exact",
            )
            registry.completeProviderEntry(owner, activeCapability, scope)
            capability = null
            return null
        } finally {
            val pending = capability
            if (pending != null && !authorized) runCatching { registry.abortProviderEntry(owner, pending, scope) }
        }
    }

    private fun prepareMaterialization(
        owner: AnkiRunStateRegistry.RunOwner,
        index: Int,
        note: CreateNote,
        target: TargetSnapshot,
        baseline: DuplicateBaseline,
    ): PreparedNote {
        val occurrence = note.duplicateCandidate.occurrence
        if (occurrence !in baseline.occurrences.indices) {
            throw noteMutationConflict("A note duplicate occurrence is outside the consumed baseline")
        }
        val uniqueIndex = baseline.occurrences[occurrence]
        val candidate = DuplicateCandidate(note.duplicateCandidate.key, note.duplicateCandidate.firstField)
        if (baseline.candidates[uniqueIndex] != candidate) {
            throw noteMutationConflict("A note duplicate candidate changed after its baseline")
        }
        if (note.fields.keys.any { it !in target.model.fieldNames }) {
            throw noteMutationConflict("A note contains a field outside the verified model")
        }
        if (note.fields[target.model.fieldNames.first()] != candidate.firstField) {
            throw noteMutationConflict("A note first field differs from its duplicate candidate")
        }
        if (note.fields.values.any { FIELD_SEPARATOR in it }) {
            throw noteMutationConflict("A note field contains the provider field separator")
        }
        val ordered = target.model.fieldNames.map { name -> OrderedNoteField(name, note.fields[name].orEmpty()) }
        val normalizedTags = normalizeTags(note.tags)
        val providerTags = normalizedTags.joinToString(" ")
        val bindings =
            note.mediaBindings.map { binding ->
                val acknowledgement = registry.mediaAcknowledgement(owner, binding.assetId)
                    ?: throw noteMutationConflict("A note references media without a durable storeMedia acknowledgement")
                if (acknowledgement.actualFilename != binding.actualFilename) {
                    throw noteMutationConflict("A note media filename differs from its durable acknowledgement")
                }
                if (ordered.none { providerFieldReferencesFilename(it.value, binding.actualFilename) }) {
                    throw noteMutationConflict("A note media binding is not referenced by any provider field")
                }
                DurableMediaBinding(binding.assetId, binding.actualFilename, acknowledgement.durableClaimId)
            }
        if (bindings.map { it.actualFilename }.distinct().size != bindings.size) {
            throw noteMutationConflict("A note repeats one provider media filename")
        }
        return PreparedNote(
            candidate = candidate,
            materialization =
                ActiveNoteMaterialization(
                    requestIndex = index,
                    clientNoteId = note.clientNoteId,
                    orderedFields = ordered,
                    joinedFields = ordered.joinToString(FIELD_SEPARATOR.toString(), transform = OrderedNoteField::value),
                    normalizedTags = normalizedTags,
                    providerTagsWire = providerTags,
                    duplicateDecision =
                        DurableDuplicateDecision(candidate.key, candidate.firstField, occurrence, duplicate = false),
                    mediaBindings = bindings,
                ),
        )
    }

    private fun validateRequestAgainstBaseline(
        request: CreateNotesRequest,
        baseline: DuplicateBaseline,
    ): TargetSnapshot {
        val target = registryTargetOrConflict(baseline)
        if (
            target.deck.name != request.deckName ||
            target.model.name != request.modelName ||
            target.model.fieldNames.first() != request.firstFieldName ||
            baseline.firstFieldName != request.firstFieldName
        ) {
            throw noteMutationConflict("The createNotes request differs from its consumed duplicate baseline")
        }
        return target
    }

    private fun registryTargetOrConflict(baseline: DuplicateBaseline): TargetSnapshot = baseline.target

    private fun stopNoteBeforeEntry(key: ParentKey, index: Int, childId: Long, error: JournalError) {
        journal.terminate(
            key,
            ActiveNoteTermination.StablePreEntryFailure(
                requestIndex = index,
                error = error,
                compactEvidence = "providerEntry=false;code=${error.code.name}",
                preparedNoteChildId = childId,
            ),
        )
    }

    private fun terminateKnown(
        key: ParentKey,
        index: Int,
        noteId: Long,
        error: JournalError,
        evidence: String,
        routingFailure: PreparedRoutingFailure? = null,
    ) {
        journal.terminate(
            key,
            ActiveNoteTermination.KnownNoteFailure(index, noteId, error, evidence, routingFailure),
        )
    }

    private fun finishStopped(
        request: CreateNotesRequest,
        durableRequest: JournalRequest,
        terminalIndex: Int,
        error: JournalError,
    ): CreateNotesMutationOutcome {
        for (suffix in terminalIndex + 1..request.notes.lastIndex) {
            journal.append(
                durableRequest.key,
                AlignedResult.NoteNotAttempted(suffix, request.notes[suffix].clientNoteId),
            )
        }
        return finish(request, durableRequest, error, replayed = false)
    }

    private fun finish(
        request: CreateNotesRequest,
        durableRequest: JournalRequest,
        topLevelError: JournalError?,
        replayed: Boolean,
    ): CreateNotesMutationOutcome {
        val response = JournalResponse.CreateNotes(durableRequest.key, journal.results(durableRequest.key), topLevelError)
        journal.markReady(durableRequest, response)
        return outcomeFromDurable(request, response, replayed)
    }

    private fun replayOutcome(request: CreateNotesRequest, response: JournalResponse): CreateNotesMutationOutcome {
        val notes = response as? JournalResponse.CreateNotes
            ?: throw noteMutationConflict("The durable response operation does not match createNotes")
        return outcomeFromDurable(request, notes, replayed = true)
    }

    private fun outcomeFromDurable(
        request: CreateNotesRequest,
        response: JournalResponse.CreateNotes,
        replayed: Boolean,
    ): CreateNotesMutationOutcome {
        if (response.key != ParentKey(request.runId, request.requestId) || response.results.size != request.notes.size) {
            throw noteMutationConflict("The durable note response identity changed")
        }
        val rows =
            response.results.mapIndexed { index, row ->
                val expectedId = request.notes[index].clientNoteId
                if (row.requestIndex != index || row.itemId != expectedId) {
                    throw noteMutationConflict("A durable note row identity changed")
                }
                when (row) {
                    is AlignedResult.NoteCreated -> CreatedNote(expectedId, row.committedId)
                    is AlignedResult.NoteDuplicate -> DuplicateNote(expectedId)
                    is AlignedResult.NoteFailed -> FailedNote(expectedId)
                    is AlignedResult.NoteCommittedFailed -> CommittedFailedNote(expectedId, row.committedId)
                    is AlignedResult.NoteUncertain -> UncertainNote(expectedId)
                    is AlignedResult.NoteNotAttempted -> NotAttemptedNote(expectedId)
                    else -> throw noteMutationConflict("A durable non-note row entered createNotes")
                }
            }
        return CreateNotesMutationOutcome(
            CreateNotesResult(
                runId = request.runId,
                requestId = request.requestId,
                results = rows,
                error = response.error?.toProtocolNoteError(),
            ),
            replayed,
        )
    }

    private data class PreparedNote(
        val candidate: DuplicateCandidate,
        val materialization: ActiveNoteMaterialization,
    )

    private companion object {
        const val FIELD_SEPARATOR = '\u001f'
    }
}

private fun validateTagsFromProvider(value: String): Set<String> =
    value.split(Regex("\\s+")).filter(String::isNotEmpty).toSet()

private fun NoteSnapshot.matches(
    noteId: Long,
    modelId: Long,
    materialization: ActiveNoteMaterialization,
): Boolean =
    id == noteId &&
        this.modelId == modelId &&
        joinedFields == materialization.joinedFields &&
        validateTagsFromProvider(providerTagsWire) == materialization.normalizedTags.toSet()

private fun CardIdentity.toObservation() = RoutingCardObservation(id, noteId, ordinal, deckId)

private fun normalizeTags(tags: List<String>): List<String> {
    if (tags.any { tag -> tag.any { it in "\t\n\u000B\u000C\r" } }) {
        throw noteMutationConflict("An Anki tag contains an ambiguous provider separator")
    }
    val normalized = tags.map { it.replace(' ', '_') }
    if (normalized.distinct().size != normalized.size) {
        throw noteMutationConflict("Anki tags collide after provider normalization")
    }
    return normalized
}

private fun providerFieldReferencesFilename(
    fieldValue: String,
    filename: String,
): Boolean = filename in fieldValue || htmlEscapeProviderAttribute(filename) in fieldValue

/** Matches Python html.escape(value, quote=True), used by the Android adapter for img src. */
private fun htmlEscapeProviderAttribute(value: String): String =
    buildString(value.length) {
        for (character in value) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#x27;")
                else -> append(character)
            }
        }
    }

private fun JournalError.toProtocolNoteError() =
    AnkiErrorDetail(AnkiErrorCode.valueOf(code.name), message, retryable)

private fun AnkiReadFailure.toStableNoteError(fallback: String) =
    JournalError(JournalErrorCode.valueOf(code.name), stableMessage.ifBlank { fallback }, retryable = false)

private fun stableInternal(message: String) =
    JournalError(JournalErrorCode.INTERNAL_ERROR, message, retryable = false)

private fun cancelledBeforeNoteEntry(message: String) =
    JournalError(JournalErrorCode.CANCELLED, message, retryable = false)

private fun stableWriteFailure(message: String) =
    JournalError(JournalErrorCode.WRITE_FAILED, message, retryable = false)

private fun postCommitUnknown(message: String) =
    JournalError(JournalErrorCode.POST_COMMIT_UNCERTAIN, message, retryable = false)

private fun noteReceiptEvidence(rawReceipt: String?): String =
    "providerEntry=true;receipt=${if (rawReceipt == null) "null-or-exception" else "invalid"}"

private fun noteMutationConflict(message: String) = IllegalStateException(message)
