package com.ankiminer.android.anki.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMutationRecoveryTest {
    @Test
    fun everyPreEntryCommandIsProvenNotCommitted() {
        ChildOperation.entries.forEachIndexed { index, operation ->
            val parent = testParent(id = index + 1L, operation = operation.parentOperation())
            val child = testChild(parentId = parent.id, operation = operation)
            val intent =
                if (operation == ChildOperation.CARD_DECK_UPDATE) {
                    testRoutingIntent(parentId = parent.id, childId = child.id)
                } else {
                    null
                }
            val plan =
                AnkiMutationRecovery.plan(
                    RecoveryInventory(
                        listOf(parent),
                        child,
                        intent,
                        preparedTargetExpectation =
                            if (operation == ChildOperation.DECK_CREATE) testTargetSnapshot().expectation else null,
                    ),
                )

            val recovery = plan.preparedMutation as PreparedMutationRecovery.ProveNotCommitted
            assertEquals(operation, recovery.child.command.operation)
            assertEquals(parent.id, recovery.parent.id)
            assertTrue(plan.safeToAbandon.isEmpty())
        }
    }

    @Test
    fun enteredDeckRecoveryReconcilesAndNeverCreatesAgain() {
        val parent = testParent(operation = ParentOperation.VERIFY_TARGET)
        val withoutReceipt = testChild(parentId = parent.id, operation = ChildOperation.DECK_CREATE, attemptCount = 1)
        val expectation = testTargetSnapshot().expectation
        val noReceiptAction =
            plan(parent, withoutReceipt, expectation).preparedMutation as PreparedMutationRecovery.ReconcileDeck
        assertNull(noReceiptAction.returnedReceipt)
        assertEquals(expectation, noReceiptAction.expectedTarget)

        val receipt = testReceipt(ChildOperation.DECK_CREATE) as ProviderReceipt.Deck
        val withReceipt = withoutReceipt.copy(receipt = receipt)
        val receiptAction =
            plan(parent, withReceipt, expectation).preparedMutation as PreparedMutationRecovery.ReconcileDeck
        assertEquals(receipt, receiptAction.returnedReceipt)
    }

    @Test
    fun preEntryDeckRecoveryRequiresTheFrozenFullTargetExpectation() {
        val parent = testParent(operation = ParentOperation.VERIFY_TARGET)
        val child = testChild(parentId = parent.id, operation = ChildOperation.DECK_CREATE)
        val expectation = testTargetSnapshot().expectation
        val recovered =
            AnkiMutationRecovery.plan(
                RecoveryInventory(listOf(parent), child, null, expectation),
            ).preparedMutation as PreparedMutationRecovery.ProveNotCommitted
        assertEquals(child, recovered.child)

        assertThrows(JournalCorruptionException::class.java) {
            AnkiMutationRecovery.plan(RecoveryInventory(listOf(parent), child, null, null))
        }
        assertThrows(JournalCorruptionException::class.java) {
            AnkiMutationRecovery.plan(
                RecoveryInventory(
                    listOf(parent),
                    child,
                    null,
                    expectation.copy(expectedDeckName = "Other"),
                ),
            )
        }
    }

    @Test
    fun enteredMediaRecoverySeparatesUnknownCommitFromDurableSafeReceipt() {
        val parent = testParent(operation = ParentOperation.STORE_MEDIA)
        val entered =
            testChild(
                parentId = parent.id,
                operation = ChildOperation.MEDIA_INSERT,
                attemptCount = 1,
                mediaClaimId = 44,
            )
        val uncertain = plan(parent, entered).preparedMutation as PreparedMutationRecovery.MarkMediaUncertain
        assertEquals(44, uncertain.claimId)

        val receipt = testReceipt(ChildOperation.MEDIA_INSERT) as ProviderReceipt.Media
        val finalized =
            plan(parent, entered.copy(receipt = receipt)).preparedMutation as
                PreparedMutationRecovery.FinalizeMediaReceipt
        assertEquals(44, finalized.claimId)
        assertEquals(receipt, finalized.receipt)
    }

    @Test
    fun enteredNoteRecoveryPromotesKnownIdAndOtherwiseStaysUncertain() {
        val parent = testParent(operation = ParentOperation.CREATE_NOTES)
        val entered = testChild(parentId = parent.id, operation = ChildOperation.NOTE_INSERT, attemptCount = 1)
        assertTrue(plan(parent, entered).preparedMutation is PreparedMutationRecovery.MarkNoteUncertain)

        val receipt = testReceipt(ChildOperation.NOTE_INSERT) as ProviderReceipt.Note
        val promoted =
            plan(parent, entered.copy(receipt = receipt)).preparedMutation as
                PreparedMutationRecovery.PromoteNoteReceipt
        assertEquals(receipt, promoted.receipt)
    }

    @Test
    fun enteredCardRecoveryAlwaysInspectsExactIdentityAndCarriesReceiptFact() {
        val parent = testParent(operation = ParentOperation.CREATE_NOTES)
        val entered = testChild(parentId = parent.id, operation = ChildOperation.CARD_DECK_UPDATE, attemptCount = 1)
        val intent = testRoutingIntent(parentId = parent.id, childId = entered.id)

        val withoutReceipt =
            AnkiMutationRecovery.plan(
                RecoveryInventory(listOf(parent), entered, intent),
            ).preparedMutation as PreparedMutationRecovery.InspectCardRouting
        assertFalse(withoutReceipt.hasAffectedCountReceipt)
        assertEquals(intent, withoutReceipt.intent)

        val withReceipt =
            AnkiMutationRecovery.plan(
                RecoveryInventory(
                    listOf(parent),
                    entered.copy(receipt = ProviderReceipt.CardAffectedOne),
                    intent,
                ),
            ).preparedMutation as PreparedMutationRecovery.InspectCardRouting
        assertTrue(withReceipt.hasAffectedCountReceipt)
    }

    @Test
    fun finalCardRecoveryMatrixNeverReissuesReceiptBearingOrDriftedUpdates() {
        for (attemptCount in 1..2) {
            for (hasReceipt in listOf(false, true)) {
                assertEquals(
                    CardRecoveryDisposition.VERIFY_POSTCONDITION,
                    AnkiMutationRecovery.decideCardRecovery(
                        CardRecoveryObservation.DESIRED_DECK,
                        hasReceipt,
                        attemptCount,
                    ),
                )
                assertEquals(
                    CardRecoveryDisposition.COMMITTED_FAILED_EXTERNAL_DRIFT,
                    AnkiMutationRecovery.decideCardRecovery(
                        CardRecoveryObservation.THIRD_DECK,
                        hasReceipt,
                        attemptCount,
                    ),
                )
                assertEquals(
                    CardRecoveryDisposition.COMMITTED_FAILED_UNCERTAIN,
                    AnkiMutationRecovery.decideCardRecovery(
                        CardRecoveryObservation.UNVERIFIABLE_IDENTITY_OR_DECK,
                        hasReceipt,
                        attemptCount,
                    ),
                )
                val preDeckExpected =
                    when {
                        hasReceipt -> CardRecoveryDisposition.COMMITTED_FAILED_EXTERNAL_DRIFT
                        attemptCount == 1 -> CardRecoveryDisposition.REISSUE_ONCE_THEN_REQUERY
                        else -> CardRecoveryDisposition.COMMITTED_FAILED_UNCERTAIN
                    }
                assertEquals(
                    preDeckExpected,
                    AnkiMutationRecovery.decideCardRecovery(
                        CardRecoveryObservation.PRE_UPDATE_DECK,
                        hasReceipt,
                        attemptCount,
                    ),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnkiMutationRecovery.decideCardRecovery(CardRecoveryObservation.DESIRED_DECK, false, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnkiMutationRecovery.decideCardRecovery(CardRecoveryObservation.DESIRED_DECK, false, 3)
        }
    }

    @Test
    fun receiptlessSecondCardAttemptRemainingInPreUpdateDeckIsUncertain() {
        assertEquals(
            CardRecoveryDisposition.COMMITTED_FAILED_UNCERTAIN,
            AnkiMutationRecovery.decideCardRecovery(
                CardRecoveryObservation.PRE_UPDATE_DECK,
                hasAffectedCountReceipt = false,
                attemptCount = 2,
            ),
        )
    }

    @Test
    fun recoveryAbandonsOnlyOwnerlessNonPreparedParentsInStableOrder() {
        val prepared = testParent(id = 3, runId = "prepared", requestId = "active")
        val live = testParent(id = 1, runId = "live", requestId = "request")
        val dead = testParent(id = 2, runId = "dead", requestId = "request")
        val ready = testParent(id = 4, runId = "ready", requestId = "request", state = ParentState.RESULT_READY)
        val child = testChild(parentId = prepared.id, operation = ChildOperation.NOTE_INSERT, attemptCount = 1)

        val plan =
            AnkiMutationRecovery.plan(
                RecoveryInventory(listOf(ready, prepared, dead, live), child, null),
                activeRunIds = setOf("live"),
            )

        assertTrue(plan.preparedMutation is PreparedMutationRecovery.MarkNoteUncertain)
        assertEquals(listOf(dead.key, ready.key), plan.safeToAbandon)
        assertThrows(IllegalArgumentException::class.java) {
            AnkiMutationRecovery.plan(RecoveryInventory(emptyList(), null, null), setOf(" "))
        }
    }

    @Test
    fun corruptInventoryFailsClosedBeforeAnyRecoveryClassification() {
        val parent = testParent(operation = ParentOperation.CREATE_NOTES)
        fun assertCorrupt(inventory: RecoveryInventory) {
            assertThrows(JournalCorruptionException::class.java) {
                AnkiMutationRecovery.plan(inventory)
            }
        }

        assertCorrupt(
            RecoveryInventory(
                listOf(parent.copy(state = ParentState.RESULT_READY)),
                testChild(parentId = parent.id, operation = ChildOperation.NOTE_INSERT),
                null,
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent.copy(state = ParentState.ABANDONED)),
                null,
                null,
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                testChild(parentId = 999, operation = ChildOperation.NOTE_INSERT),
                null,
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                testChild(
                    parentId = parent.id,
                    operation = ChildOperation.NOTE_INSERT,
                    state = ChildState.COMMIT_UNCERTAIN,
                ),
                null,
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                testChild(
                    parentId = parent.id,
                    operation = ChildOperation.NOTE_INSERT,
                    receipt = testReceipt(ChildOperation.NOTE_INSERT),
                ),
                null,
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                testChild(parentId = parent.id, operation = ChildOperation.NOTE_INSERT, attemptCount = 2),
                null,
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                testChild(
                    parentId = parent.id,
                    operation = ChildOperation.MEDIA_INSERT,
                    attemptCount = 1,
                    mediaClaimId = null,
                ),
                null,
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                testChild(
                    parentId = parent.id,
                    operation = ChildOperation.NOTE_INSERT,
                    attemptCount = 1,
                    receipt = testReceipt(ChildOperation.MEDIA_INSERT),
                ),
                null,
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                null,
                testRoutingIntent(parentId = parent.id),
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                testChild(parentId = parent.id, operation = ChildOperation.NOTE_INSERT, attemptCount = 1),
                testRoutingIntent(parentId = parent.id),
            ),
        )

        val card = testChild(parentId = parent.id, operation = ChildOperation.CARD_DECK_UPDATE, attemptCount = 1)
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                card,
                testRoutingIntent(parentId = parent.id, childId = 999),
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                card,
                testRoutingIntent(parentId = parent.id, childId = card.id, state = RoutingIntentState.PENDING),
            ),
        )
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                card,
                testRoutingIntent(parentId = parent.id, childId = card.id).copy(cardId = 71),
            ),
        )

        val preEntryCard = testChild(parentId = parent.id, operation = ChildOperation.CARD_DECK_UPDATE)
        assertCorrupt(
            RecoveryInventory(
                listOf(parent),
                preEntryCard,
                testRoutingIntent(parentId = 999, childId = preEntryCard.id),
            ),
        )
    }

    private fun plan(
        parent: ParentRecord,
        child: ChildRecord,
        expectation: DurableTargetExpectation? = null,
    ): RecoveryPlan =
        AnkiMutationRecovery.plan(RecoveryInventory(listOf(parent), child, null, expectation))

    private fun ChildOperation.parentOperation(): ParentOperation =
        when (this) {
            ChildOperation.DECK_CREATE -> ParentOperation.VERIFY_TARGET
            ChildOperation.MEDIA_INSERT -> ParentOperation.STORE_MEDIA
            ChildOperation.NOTE_INSERT, ChildOperation.CARD_DECK_UPDATE -> ParentOperation.CREATE_NOTES
        }
}
