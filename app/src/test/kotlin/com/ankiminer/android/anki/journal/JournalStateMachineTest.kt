package com.ankiminer.android.anki.journal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalStateMachineTest {
    @Test
    fun parentTransitionsAreExactAndFinalStatesCannotMove() {
        val legal =
            setOf(
                ParentState.PREPARED to ParentState.RUNNING,
                ParentState.PREPARED to ParentState.RESULT_READY,
                ParentState.RUNNING to ParentState.RESULT_READY,
                ParentState.RESULT_READY to ParentState.RESPONSE_ACKNOWLEDGED,
                ParentState.RESULT_READY to ParentState.ABANDONED,
            )

        ParentState.entries.forEach { from ->
            ParentState.entries.forEach { to ->
                if (from to to in legal) {
                    JournalStateMachine.requireParentTransition(from, to)
                } else {
                    assertThrows(JournalInvariantViolation::class.java) {
                        JournalStateMachine.requireParentTransition(from, to)
                    }
                }
            }
        }

        assertTrue(ParentState.RESULT_READY.isReplayable)
        assertFalse(ParentState.RESULT_READY.isFinalized)
        assertTrue(ParentState.RESPONSE_ACKNOWLEDGED.isFinalized)
        assertTrue(ParentState.ABANDONED.isFinalized)
        assertFalse(ParentState.PREPARED.isReplayable)
    }

    @Test
    fun childCompletionRequiresProviderEntryAndOperationTypedProof() {
        ChildOperation.entries.forEach { operation ->
            val preEntry = testChild(operation = operation)
            JournalStateMachine.requireChildCompletion(preEntry, ChildState.PROVEN_NOT_COMMITTED)
            listOf(
                ChildState.COMMIT_KNOWN,
                ChildState.POSTCONDITION_VERIFIED,
                ChildState.POSTCONDITION_FAILED,
                ChildState.COMMIT_UNCERTAIN,
            ).forEach { outcome ->
                assertThrows("$operation $outcome", JournalInvariantViolation::class.java) {
                    JournalStateMachine.requireChildCompletion(preEntry, outcome)
                }
            }

            val entered = testChild(operation = operation, attemptCount = 1)
            if (operation == ChildOperation.CARD_DECK_UPDATE) {
                JournalStateMachine.requireChildCompletion(entered, ChildState.POSTCONDITION_FAILED)
            } else {
                assertThrows(JournalInvariantViolation::class.java) {
                    JournalStateMachine.requireChildCompletion(entered, ChildState.POSTCONDITION_FAILED)
                }
            }
            JournalStateMachine.requireChildCompletion(entered, ChildState.COMMIT_UNCERTAIN)
            assertThrows(JournalInvariantViolation::class.java) {
                JournalStateMachine.requireChildCompletion(entered, ChildState.PROVEN_NOT_COMMITTED)
            }
            assertThrows(JournalInvariantViolation::class.java) {
                JournalStateMachine.requireChildCompletion(entered, ChildState.COMMIT_KNOWN)
            }

            val receiptBearing = entered.copy(receipt = testReceipt(operation))
            JournalStateMachine.requireChildCompletion(receiptBearing, ChildState.COMMIT_KNOWN)
            if (operation in setOf(ChildOperation.DECK_CREATE, ChildOperation.CARD_DECK_UPDATE)) {
                JournalStateMachine.requireChildCompletion(entered, ChildState.POSTCONDITION_VERIFIED)
            } else {
                assertThrows(JournalInvariantViolation::class.java) {
                    JournalStateMachine.requireChildCompletion(entered, ChildState.POSTCONDITION_VERIFIED)
                }
            }
        }

        val wrongReceipt =
            testChild(
                operation = ChildOperation.NOTE_INSERT,
                attemptCount = 1,
                receipt = ProviderReceipt.Media("clip.ogg", "file:///clip.ogg"),
            )
        assertThrows(JournalInvariantViolation::class.java) {
            JournalStateMachine.requireChildCompletion(wrongReceipt, ChildState.COMMIT_KNOWN)
        }
        assertThrows(JournalInvariantViolation::class.java) {
            JournalStateMachine.requireChildCompletion(
                testChild(operation = ChildOperation.NOTE_INSERT, state = ChildState.COMMIT_UNCERTAIN),
                ChildState.COMMIT_UNCERTAIN,
            )
        }
        assertThrows(JournalInvariantViolation::class.java) {
            JournalStateMachine.requireChildCompletion(
                testChild(operation = ChildOperation.NOTE_INSERT),
                ChildState.PREPARED,
            )
        }
    }

    @Test
    fun notePhasesAdvanceExactlyOneStep() {
        NoteRoutingPhase.entries.zipWithNext().forEach { (from, to) ->
            JournalStateMachine.requireNotePhaseTransition(from, to)
        }
        NoteRoutingPhase.entries.forEach { from ->
            NoteRoutingPhase.entries.forEach { to ->
                if (NoteRoutingPhase.entries.indexOf(to) != NoteRoutingPhase.entries.indexOf(from) + 1) {
                    assertThrows(JournalInvariantViolation::class.java) {
                        JournalStateMachine.requireNotePhaseTransition(from, to)
                    }
                }
            }
        }
    }

    @Test
    fun routingIntentsHaveOnePreparedMutationAndTerminalStatesAreFinal() {
        val legal =
            setOf(
                RoutingIntentState.PENDING to RoutingIntentState.UPDATE_PREPARED,
                RoutingIntentState.PENDING to RoutingIntentState.VERIFIED,
                RoutingIntentState.PENDING to RoutingIntentState.FAILED,
                RoutingIntentState.UPDATE_PREPARED to RoutingIntentState.VERIFIED,
                RoutingIntentState.UPDATE_PREPARED to RoutingIntentState.FAILED,
                RoutingIntentState.UPDATE_PREPARED to RoutingIntentState.COMMIT_UNCERTAIN,
            )
        RoutingIntentState.entries.forEach { from ->
            RoutingIntentState.entries.forEach { to ->
                if (from to to in legal) {
                    JournalStateMachine.requireRoutingTransition(from, to)
                } else {
                    assertThrows(JournalInvariantViolation::class.java) {
                        JournalStateMachine.requireRoutingTransition(from, to)
                    }
                }
            }
        }
    }

    @Test
    fun mediaClaimsResolveMonotonically() {
        val resolved =
            setOf(
                MediaClaimState.ATTACHED_VERIFIED,
                MediaClaimState.CLEANED_VERIFIED,
                MediaClaimState.ACKNOWLEDGED_BY_USER,
            )
        val legal = mutableSetOf<Pair<MediaClaimState, MediaClaimState>>()
        setOf(
            MediaClaimState.STORED,
            MediaClaimState.COMMIT_UNCERTAIN,
            MediaClaimState.PRESENT_BYTES_VERIFIED,
            MediaClaimState.CLEANED_VERIFIED,
            MediaClaimState.ACKNOWLEDGED_BY_USER,
        )
            .forEach { legal += MediaClaimState.PENDING to it }
        legal += MediaClaimState.STORED to MediaClaimState.PRESENT_BYTES_VERIFIED
        resolved.forEach { legal += MediaClaimState.STORED to it }
        legal += MediaClaimState.COMMIT_UNCERTAIN to MediaClaimState.PRESENT_BYTES_VERIFIED
        legal += MediaClaimState.COMMIT_UNCERTAIN to MediaClaimState.CLEANED_VERIFIED
        legal += MediaClaimState.COMMIT_UNCERTAIN to MediaClaimState.ACKNOWLEDGED_BY_USER
        resolved.forEach { legal += MediaClaimState.PRESENT_BYTES_VERIFIED to it }

        MediaClaimState.entries.forEach { from ->
            MediaClaimState.entries.forEach { to ->
                if (from to to in legal) {
                    JournalStateMachine.requireClaimTransition(from, to)
                } else {
                    assertThrows(JournalInvariantViolation::class.java) {
                        JournalStateMachine.requireClaimTransition(from, to)
                    }
                }
            }
        }
        assertTrue(MediaClaimState.STORED.isUnresolved)
        assertTrue(MediaClaimState.PRESENT_BYTES_VERIFIED.isUnresolved)
        assertFalse(MediaClaimState.ATTACHED_VERIFIED.isUnresolved)
    }

    @Test
    fun stagingMovesOnlyTowardCleanupOrVisibleQuarantine() {
        val legal =
            setOf(
                StagingState.STAGED to StagingState.GRANTED,
                StagingState.STAGED to StagingState.CLEANUP_PENDING,
                StagingState.STAGED to StagingState.CLEANED,
                StagingState.STAGED to StagingState.QUARANTINED,
                StagingState.GRANTED to StagingState.CLEANUP_PENDING,
                StagingState.GRANTED to StagingState.CLEANED,
                StagingState.GRANTED to StagingState.QUARANTINED,
                StagingState.CLEANUP_PENDING to StagingState.CLEANED,
                StagingState.CLEANUP_PENDING to StagingState.QUARANTINED,
                StagingState.QUARANTINED to StagingState.CLEANED,
            )
        StagingState.entries.forEach { from ->
            StagingState.entries.forEach { to ->
                if (from to to in legal) {
                    JournalStateMachine.requireStagingTransition(from, to)
                } else {
                    assertThrows(JournalInvariantViolation::class.java) {
                        JournalStateMachine.requireStagingTransition(from, to)
                    }
                }
            }
        }
    }
}
