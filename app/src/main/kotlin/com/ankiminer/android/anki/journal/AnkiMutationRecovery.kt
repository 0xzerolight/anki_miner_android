package com.ankiminer.android.anki.journal

internal data class RecoveryInventory(
    val unfinishedParents: List<ParentRecord>,
    val preparedChild: ChildRecord?,
    val preparedRoutingIntent: RoutingIntentRecord?,
    val preparedTargetExpectation: DurableTargetExpectation? = null,
)

/** A startup plan classifies only the one globally serialized PREPARED mutation. */
internal sealed interface PreparedMutationRecovery {
    val parent: ParentRecord
    val child: ChildRecord

    /** No provider-entry record exists, so the command is proven not committed for every operation. */
    data class ProveNotCommitted(
        override val parent: ParentRecord,
        override val child: ChildRecord,
    ) : PreparedMutationRecovery

    /** Requery the exact deck and complete model/template snapshot; recovery never creates a deck. */
    data class ReconcileDeck(
        override val parent: ParentRecord,
        override val child: ChildRecord,
        val expectedTarget: DurableTargetExpectation,
        val returnedReceipt: ProviderReceipt.Deck?,
    ) : PreparedMutationRecovery

    /** Defensively finish the receipt transaction if a crash exposed this otherwise unreachable gap. */
    data class FinalizeMediaReceipt(
        override val parent: ParentRecord,
        override val child: ChildRecord,
        val claimId: Long,
        val receipt: ProviderReceipt.Media,
    ) : PreparedMutationRecovery

    data class MarkMediaUncertain(
        override val parent: ParentRecord,
        override val child: ChildRecord,
        val claimId: Long,
    ) : PreparedMutationRecovery

    /** Promote the known note ID before conservative ownerless abandonment. */
    data class PromoteNoteReceipt(
        override val parent: ParentRecord,
        override val child: ChildRecord,
        val receipt: ProviderReceipt.Note,
    ) : PreparedMutationRecovery

    data class MarkNoteUncertain(
        override val parent: ParentRecord,
        override val child: ChildRecord,
    ) : PreparedMutationRecovery

    data class InspectCardRouting(
        override val parent: ParentRecord,
        override val child: ChildRecord,
        val intent: RoutingIntentRecord,
        val hasAffectedCountReceipt: Boolean,
    ) : PreparedMutationRecovery
}

internal data class RecoveryPlan(
    val preparedMutation: PreparedMutationRecovery?,
    /** Ownerless parents safe to scrub now; the prepared mutation's parent is deliberately absent. */
    val safeToAbandon: List<ParentKey>,
)

internal enum class CardRecoveryObservation {
    DESIRED_DECK,
    PRE_UPDATE_DECK,
    THIRD_DECK,
    UNVERIFIABLE_IDENTITY_OR_DECK,
}

internal enum class CardRecoveryDisposition {
    VERIFY_POSTCONDITION,
    REISSUE_ONCE_THEN_REQUERY,
    COMMITTED_FAILED_EXTERNAL_DRIFT,
    COMMITTED_FAILED_UNCERTAIN,
}

internal object AnkiMutationRecovery {
    fun plan(
        inventory: RecoveryInventory,
        activeRunIds: Set<String> = emptySet(),
    ): RecoveryPlan {
        require(activeRunIds.none(String::isBlank)) { "activeRunIds must not contain blanks" }
        val parents = inventory.unfinishedParents.sortedWith(compareBy(ParentRecord::createdAtMs, ParentRecord::id))
        if (parents.any { it.state.isFinalized }) {
            throw JournalCorruptionException("Recovery inventory contains a finalized parent")
        }

        val child = inventory.preparedChild
        if (child != null && child.state != ChildState.PREPARED) {
            throw JournalCorruptionException("Recovery child is not PREPARED")
        }
        val parent =
            child?.let {
                parents.singleOrNull { candidate -> candidate.id == it.parentId }
                    ?: throw JournalCorruptionException("Prepared child lacks exactly one unfinished parent")
            }
        if (parent?.state == ParentState.RESULT_READY) {
            throw JournalCorruptionException("RESULT_READY parent retains a PREPARED child")
        }

        val action = child?.let { classify(parent = checkNotNull(parent), child = it, inventory = inventory) }
        if (child == null && inventory.preparedRoutingIntent != null) {
            throw JournalCorruptionException("Prepared routing intent exists without a PREPARED child")
        }

        val safe =
            parents.asSequence()
                .filterNot { it.key.runId in activeRunIds }
                .filterNot { it.id == parent?.id }
                .map(ParentRecord::key)
                .toList()
        return RecoveryPlan(action, safe)
    }

    fun decideCardRecovery(
        observation: CardRecoveryObservation,
        hasAffectedCountReceipt: Boolean,
        attemptCount: Int,
    ): CardRecoveryDisposition {
        require(attemptCount in 1..2) { "entered card recovery requires one or two attempts" }
        return when (observation) {
            CardRecoveryObservation.DESIRED_DECK -> CardRecoveryDisposition.VERIFY_POSTCONDITION
            CardRecoveryObservation.PRE_UPDATE_DECK ->
                when {
                    hasAffectedCountReceipt -> CardRecoveryDisposition.COMMITTED_FAILED_EXTERNAL_DRIFT
                    attemptCount == 1 -> CardRecoveryDisposition.REISSUE_ONCE_THEN_REQUERY
                    else -> CardRecoveryDisposition.COMMITTED_FAILED_UNCERTAIN
                }
            CardRecoveryObservation.THIRD_DECK -> CardRecoveryDisposition.COMMITTED_FAILED_EXTERNAL_DRIFT
            CardRecoveryObservation.UNVERIFIABLE_IDENTITY_OR_DECK ->
                CardRecoveryDisposition.COMMITTED_FAILED_UNCERTAIN
        }
    }

    private fun classify(
        parent: ParentRecord,
        child: ChildRecord,
        inventory: RecoveryInventory,
    ): PreparedMutationRecovery {
        if (child.attemptCount == 0) {
            if (child.receipt != null) throw JournalCorruptionException("Receipt exists before provider entry")
            requireNoRoutingIntentUnlessCard(child, inventory)
            if (child.command is MutationCommand.CreateDeck) {
                val expectation = inventory.preparedTargetExpectation
                    ?: throw JournalCorruptionException("Pre-entry deck child lacks its frozen target expectation")
                if (expectation.expectedDeckName != child.command.deckName) {
                    throw JournalCorruptionException("Pre-entry deck command differs from target expectation")
                }
            }
            return PreparedMutationRecovery.ProveNotCommitted(parent, child)
        }
        if (child.attemptCount !in 1..2 || (child.attemptCount == 2 && child.command.operation != ChildOperation.CARD_DECK_UPDATE)) {
            throw JournalCorruptionException("Illegal provider-attempt history")
        }
        if (child.receipt?.operation != null && child.receipt.operation != child.command.operation) {
            throw JournalCorruptionException("Receipt operation disagrees with command")
        }

        return when (child.command.operation) {
            ChildOperation.DECK_CREATE -> {
                requireNoRoutingIntent(inventory)
                val expectation = inventory.preparedTargetExpectation
                    ?: throw JournalCorruptionException("Prepared deck child lacks its frozen target expectation")
                val command = child.command as MutationCommand.CreateDeck
                if (expectation.expectedDeckName != command.deckName) {
                    throw JournalCorruptionException("Deck command differs from frozen target expectation")
                }
                PreparedMutationRecovery.ReconcileDeck(
                    parent,
                    child,
                    expectation,
                    child.receipt as ProviderReceipt.Deck?,
                )
            }
            ChildOperation.MEDIA_INSERT -> {
                requireNoRoutingIntent(inventory)
                val claimId = child.mediaClaimId ?: throw JournalCorruptionException("Prepared media child lacks a claim")
                val receipt = child.receipt
                if (receipt is ProviderReceipt.Media) {
                    PreparedMutationRecovery.FinalizeMediaReceipt(parent, child, claimId, receipt)
                } else {
                    PreparedMutationRecovery.MarkMediaUncertain(parent, child, claimId)
                }
            }
            ChildOperation.NOTE_INSERT -> {
                requireNoRoutingIntent(inventory)
                val receipt = child.receipt
                if (receipt is ProviderReceipt.Note) {
                    PreparedMutationRecovery.PromoteNoteReceipt(parent, child, receipt)
                } else {
                    PreparedMutationRecovery.MarkNoteUncertain(parent, child)
                }
            }
            ChildOperation.CARD_DECK_UPDATE -> {
                val intent = inventory.preparedRoutingIntent
                    ?: throw JournalCorruptionException("Prepared card child lacks its routing intent")
                requireCardIntentMatches(parent, child, intent)
                if (child.receipt != null && child.receipt !is ProviderReceipt.CardAffectedOne) {
                    throw JournalCorruptionException("Card child has a non-card receipt")
                }
                PreparedMutationRecovery.InspectCardRouting(
                    parent,
                    child,
                    intent,
                    child.receipt is ProviderReceipt.CardAffectedOne,
                )
            }
        }
    }

    private fun requireNoRoutingIntentUnlessCard(child: ChildRecord, inventory: RecoveryInventory) {
        if (child.command.operation == ChildOperation.CARD_DECK_UPDATE) {
            val intent = inventory.preparedRoutingIntent
                ?: throw JournalCorruptionException("Pre-entry card child lacks its routing intent")
            val parent = inventory.unfinishedParents.singleOrNull { it.id == child.parentId }
                ?: throw JournalCorruptionException("Pre-entry card child lacks exactly one parent")
            requireCardIntentMatches(parent, child, intent)
        } else {
            requireNoRoutingIntent(inventory)
        }
    }

    private fun requireNoRoutingIntent(inventory: RecoveryInventory) {
        if (inventory.preparedRoutingIntent != null) {
            throw JournalCorruptionException("Non-card child unexpectedly owns a routing intent")
        }
    }

    private fun requireCardIntentMatches(
        parent: ParentRecord,
        child: ChildRecord,
        intent: RoutingIntentRecord,
    ) {
        val command = child.command as? MutationCommand.RouteCard
            ?: throw JournalCorruptionException("Card child lacks a typed route command")
        if (
            intent.childId != child.id || intent.parentId != parent.id ||
            intent.state != RoutingIntentState.UPDATE_PREPARED ||
            command.intentId != intent.id || command.requestIndex != intent.requestIndex ||
            command.cardId != intent.cardId || command.noteId != intent.noteId ||
            command.ordinal != intent.ordinal || command.targetDeckId != intent.targetDeckId ||
            command.preUpdateDeckId != intent.preUpdateDeckId
        ) {
            throw JournalCorruptionException("Prepared card command and routing intent disagree")
        }
    }
}
