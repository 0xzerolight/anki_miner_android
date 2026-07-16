package com.ankiminer.android.anki.journal

import java.io.Closeable

/** Synchronous, worker-thread-only durable state for AnkiDroid provider mutations. */
internal interface AnkiMutationStore : Closeable {
    fun createParent(request: JournalRequest): ParentRecord
    fun parent(key: ParentKey): ParentRecord?
    fun requestItems(key: ParentKey): List<ParentRequestItem>
    fun beginParent(key: ParentKey): ParentRecord

    /** Freezes complete model/template state and expected deck name before any deck-create entry. */
    fun storeTargetExpectation(key: ParentKey, expectation: DurableTargetExpectation): ParentRecord
    fun targetExpectation(key: ParentKey): DurableTargetExpectation?

    /** Adds the separately verified exact non-dynamic deck to an equal frozen expectation. */
    fun storeTargetSnapshot(key: ParentKey, snapshot: DurableTargetSnapshot): ParentRecord
    fun targetSnapshot(key: ParentKey): DurableTargetSnapshot?

    fun materializeActiveNote(key: ParentKey, note: ActiveNoteMaterialization): ParentRecord
    fun activeNote(key: ParentKey): ActiveNoteRecord?

    /** ROUTED is journal-owned and may only be reached when the complete exact intent set verifies. */
    fun advanceNotePhase(key: ParentKey, requestIndex: Int, phase: NoteRoutingPhase): ParentRecord

    fun prepareChild(
        key: ParentKey,
        command: MutationCommand,
        mediaClaimId: Long? = null,
    ): ChildRecord

    /** Immutable provider-entry record. Only a card command may receive one recovery reissue. */
    fun recordProviderEntry(childId: Long, recoveryReissue: Boolean = false): ChildRecord

    /** Deck URI evidence remains PREPARED until exact deck/model reconciliation completes. */
    fun recordDeckReceipt(childId: Long, receipt: ProviderReceipt.Deck): ChildRecord

    /** Exact target snapshot and the entered deck child's verified outcome are one transaction. */
    fun completeVerifiedDeck(
        childId: Long,
        snapshot: DurableTargetSnapshot,
        compactEvidence: String,
    ): ParentRecord

    /** Entered deck uncertainty and its remediation are one transaction. */
    fun completeUncertainDeck(
        childId: Long,
        compactEvidence: String,
    ): ParentRecord

    /** Safe media receipt + child + claim + stored aligned row are one transaction. */
    fun commitMediaReceipt(
        childId: Long,
        claimId: Long,
        receipt: ProviderReceipt.Media,
        compactEvidence: String,
    ): ParentRecord

    /** Note receipt + durable note ID/phase + COMMIT_KNOWN child are one transaction. */
    fun commitNoteReceipt(
        childId: Long,
        receipt: ProviderReceipt.Note,
        compactEvidence: String,
    ): ParentRecord

    /** Count-one evidence is durable before mandatory card requery. */
    fun recordCardReceipt(childId: Long): ChildRecord

    fun completeChild(childId: Long, outcome: ChildState, compactEvidence: String): ChildRecord

    /** Pre-entry media failure/stop or post-entry uncertainty closes claim/child/result atomically. */
    fun completeMediaFailure(
        childId: Long,
        claimId: Long,
        childOutcome: ChildState,
        claimState: MediaClaimState,
        result: AlignedResult,
        compactEvidence: String,
    ): ParentRecord

    fun createRoutingIntents(
        key: ParentKey,
        requestIndex: Int,
        intents: List<RoutingIntentDraft>,
    ): List<RoutingIntentRecord>

    fun prepareRoutingChild(intentId: Long): ChildRecord

    /** Freezes a fresh exact readback without inventing a provider mutation child. */
    fun completeChildlessRoutingIntent(
        intentId: Long,
        outcome: ChildlessRoutingOutcome,
    ): RoutingIntentRecord

    fun completeRoutingChild(
        childId: Long,
        childOutcome: ChildState,
        intentOutcome: RoutingIntentState,
        compactEvidence: String,
    ): RoutingIntentRecord

    /**
     * Atomically proves the completed note, resolves exactly its durable media bindings, appends the
     * created row, and clears the active saga. This is the only attachment-verification path.
     */
    fun completeVerifiedNote(
        key: ParentKey,
        requestIndex: Int,
        noteId: Long,
        compactEvidence: String,
    ): ParentRecord

    /** Atomically stops the current note while preserving all mutation and routing evidence. */
    fun terminateActiveNote(key: ParentKey, termination: ActiveNoteTermination): ParentRecord

    /** Adds only the next exact request-aligned row and never overwrites mutation evidence. */
    fun appendAlignedResult(key: ParentKey, result: AlignedResult): ParentRecord
    fun alignedResults(key: ParentKey): List<AlignedResult>

    /** Typed terminalization; no raw response JSON enters the journal. */
    fun markResultReady(request: JournalRequest, response: JournalResponse): ParentRecord
    fun replay(request: JournalRequest, liveRun: Boolean): ReplayResult

    /**
     * The Boolean is authorization. Acknowledgement occurs only when the frozen list is duplicate-
     * free and exactly equals this run's durable RESULT_READY request IDs.
     */
    fun cleanupRun(
        runId: String,
        acknowledgeAuthorized: Boolean,
        frozenDurableRequestIds: List<String>,
    ): RunCleanupResult

    fun abandonOwnerless(activeRunIds: Set<String>): List<ParentRecord>
    fun preparedChild(): ChildRecord?
    fun recoveryParents(): List<ParentRecord>
    fun recoveryInventory(): RecoveryInventory

    fun acquireMediaLease(runId: String): MediaLeaseRecord
    fun mediaLease(runId: String): MediaLeaseRecord?
    fun reserveMedia(runId: String, assets: List<MediaReservationDraft>): List<MediaReservationRecord>
    fun releaseReservation(reservationId: Long): MediaReservationRecord
    fun promoteReservation(
        key: ParentKey,
        reservationId: Long,
        command: MutationCommand.StoreMedia,
    ): MediaPromotion

    fun transitionClaim(
        claimId: Long,
        state: MediaClaimState,
        actualFilename: String? = null,
        compactEvidence: String? = null,
    ): MediaClaimRecord

    /** Exact durable identity lookup across both unresolved and resolved claim states. */
    fun mediaClaim(key: ParentKey, assetId: String): MediaClaimRecord?

    fun unresolvedClaims(): List<MediaClaimRecord>
    fun releaseMediaLease(runId: String): MediaLeaseRecord?

    fun recordStaging(draft: StagingDraft): StagingRecord
    fun transitionStaging(
        stagingId: Long,
        state: StagingState,
        compactEvidence: String? = null,
    ): StagingRecord

    fun stagingForRecovery(): List<StagingRecord>

    /** Cleans, resolves attached remediation, detaches its retained subject, and deletes atomically. */
    fun completeStagingCleanup(stagingId: Long, compactEvidence: String)

    fun removeCleanedStaging(stagingId: Long)

    fun addRemediation(draft: RemediationDraft): RemediationRecord
    fun openRemediations(): List<RemediationRecord>

    /** Resolves an orphaned stored-media warning together with its exact durable claim. */
    fun acknowledgeUnattachedMedia(
        remediationId: Long,
        compactEvidence: String,
    ): RemediationRecord

    fun resolveRemediation(remediationId: Long, compactEvidence: String): RemediationRecord
}

internal fun interface JournalClock {
    fun nowEpochMillis(): Long
}

internal object SystemJournalClock : JournalClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

internal enum class JournalCrashPoint {
    BEFORE_PARENT_CREATE,
    AFTER_PARENT_CREATE,
    BEFORE_ACTIVE_NOTE_MATERIALIZATION,
    AFTER_ACTIVE_NOTE_MATERIALIZATION,
    BEFORE_CHILD_PREPARED,
    AFTER_CHILD_PREPARED,
    BEFORE_PROVIDER_ENTRY_RECORDED,
    AFTER_PROVIDER_ENTRY_RECORDED,
    BEFORE_DECK_RECEIPT_RECORDED,
    AFTER_DECK_RECEIPT_RECORDED,
    BEFORE_DECK_VERIFICATION_TRANSACTION,
    AFTER_DECK_VERIFICATION_TRANSACTION,
    BEFORE_DECK_UNCERTAINTY_TRANSACTION,
    AFTER_DECK_UNCERTAINTY_TRANSACTION,
    BEFORE_MEDIA_RECEIPT_TRANSACTION,
    AFTER_MEDIA_RECEIPT_TRANSACTION,
    BEFORE_NOTE_RECEIPT_TRANSACTION,
    AFTER_NOTE_RECEIPT_TRANSACTION,
    BEFORE_CARD_RECEIPT_RECORDED,
    AFTER_CARD_RECEIPT_RECORDED,
    BEFORE_TERMINAL_CHILD_COMMIT,
    AFTER_TERMINAL_CHILD_COMMIT,
    BEFORE_CARD_INTENT_BATCH,
    AFTER_CARD_INTENT_BATCH,
    BEFORE_ROUTING_TRANSACTION,
    AFTER_ROUTING_TRANSACTION,
    BEFORE_VERIFIED_NOTE_TRANSACTION,
    AFTER_VERIFIED_NOTE_TRANSACTION,
    BEFORE_RESULT_READY,
    AFTER_RESULT_READY,
    BEFORE_OWNERLESS_TERMINALIZATION,
    AFTER_OWNERLESS_TERMINALIZATION,
    BEFORE_RUN_CLEANUP,
    AFTER_RUN_CLEANUP,
    PROVIDER_ENTRY,
    PROVIDER_RETURN,
    NOTE_READBACK,
    CARD_READBACK,
    FINAL_POSTCHECK,
    RESPONSE_RETURN,
}

internal fun interface JournalCrashHooks {
    fun hit(point: JournalCrashPoint)
}

internal object NoOpJournalCrashHooks : JournalCrashHooks {
    override fun hit(point: JournalCrashPoint) = Unit
}
