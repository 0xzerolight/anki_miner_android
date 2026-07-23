package com.ankiminer.android.ui.wizard

import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.vm.SessionSettingsRepository
import com.ankiminer.android.vm.WizardCompletionStatus
import com.ankiminer.android.vm.setupSessionViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingWizardTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun wizardShowsOnlyForConfirmedUnseenStateOrExplicitRerun() {
        // null = settings store has not emitted yet: never flash the wizard.
        assertFalse(
            wizardVisible(
                wizardSeen = null,
                rerunRequested = false,
                sessionDismissed = false,
            ),
        )
        assertFalse(
            wizardVisible(
                wizardSeen = true,
                rerunRequested = false,
                sessionDismissed = false,
            ),
        )
        assertTrue(
            wizardVisible(
                wizardSeen = false,
                rerunRequested = false,
                sessionDismissed = false,
            ),
        )
        // Re-run from Settings works regardless of the persisted flag.
        assertTrue(
            wizardVisible(
                wizardSeen = true,
                rerunRequested = true,
                sessionDismissed = true,
            ),
        )
        assertTrue(
            wizardVisible(
                wizardSeen = null,
                rerunRequested = true,
                sessionDismissed = true,
            ),
        )
        assertFalse(
            wizardVisible(
                wizardSeen = false,
                rerunRequested = false,
                completion = WizardCompletionStatus.PERSISTED,
                sessionDismissed = false,
            ),
        )
    }

    @Test
    fun wizardStepNavigationIsBounded() {
        assertEquals(WizardStep.ANKIDROID, nextWizardStep(WizardStep.WELCOME))
        assertEquals(WizardStep.ANKIDROID_DECK, nextWizardStep(WizardStep.ANKIDROID))
        assertEquals(WizardStep.ANKIDROID_NOTE_TYPE, nextWizardStep(WizardStep.ANKIDROID_DECK))
        assertEquals(WizardStep.TOKENIZER, nextWizardStep(WizardStep.ANKIDROID_NOTE_TYPE))
        assertEquals(WizardStep.DICTIONARY, nextWizardStep(WizardStep.TOKENIZER))
        assertEquals(WizardStep.DONE, nextWizardStep(WizardStep.DONE))
        assertEquals(WizardStep.WELCOME, previousWizardStep(WizardStep.WELCOME))
        assertEquals(WizardStep.DICTIONARY, previousWizardStep(WizardStep.DONE))
    }

    @Test
    fun wizardOrderAndRequirementLabelsMatchMiningGates() {
        assertEquals(
            listOf(
                WizardStep.WELCOME,
                WizardStep.ANKIDROID,
                WizardStep.ANKIDROID_DECK,
                WizardStep.ANKIDROID_NOTE_TYPE,
                WizardStep.TOKENIZER,
                WizardStep.DICTIONARY,
                WizardStep.DONE,
            ),
            WizardStep.entries,
        )
        assertEquals(WizardStepRequirement.REQUIRED, wizardStepRequirement(WizardStep.ANKIDROID))
        assertEquals(WizardStepRequirement.OPTIONAL, wizardStepRequirement(WizardStep.ANKIDROID_DECK))
        assertEquals(
            WizardStepRequirement.REQUIRED,
            wizardStepRequirement(WizardStep.ANKIDROID_NOTE_TYPE),
        )
        assertEquals(WizardStepRequirement.REQUIRED, wizardStepRequirement(WizardStep.TOKENIZER))
        assertEquals(WizardStepRequirement.OPTIONAL, wizardStepRequirement(WizardStep.DICTIONARY))
        assertEquals(null, wizardStepRequirement(WizardStep.WELCOME))
        assertEquals(null, wizardStepRequirement(WizardStep.DONE))
    }

    @Test
    fun systemBackMovesToPreviousStepAndRequestsConfirmationAtWelcome() {
        assertEquals(
            WizardBackAction.Previous(WizardStep.ANKIDROID_DECK),
            wizardBackAction(WizardStep.ANKIDROID_NOTE_TYPE),
        )
        assertEquals(WizardBackAction.ConfirmSkip, wizardBackAction(WizardStep.WELCOME))
    }

    @Test
    fun finalReadinessCopyNeverClaimsReadyForIncompleteSetup() {
        assertEquals(
            WizardFinalState.READY,
            wizardFinalState(isMiningReady = true),
        )
        assertEquals(
            WizardFinalState.INCOMPLETE,
            wizardFinalState(isMiningReady = false),
        )
    }

    @Test
    fun failedCompletionReturnsAfterFreshSessionAndRetryPersists() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = SessionSettingsRepository(AppSettings()).apply { failWrites = true }
            val firstSession = setupSessionViewModel(repository)
            advanceUntilIdle()

            firstSession.markWizardSeen()
            advanceUntilIdle()
            assertEquals(WizardCompletionStatus.FAILED, firstSession.uiState.value.wizardCompletion)
            assertFalse(repository.current.setupWizardSeen)

            firstSession.dismissWizardForSession()
            assertTrue(firstSession.wizardDismissedForSession.value)
            assertFalse(
                wizardVisible(
                    wizardSeen = firstSession.uiState.value.wizardSeen,
                    rerunRequested = false,
                    completion = firstSession.uiState.value.wizardCompletion,
                    sessionDismissed = firstSession.wizardDismissedForSession.value,
                ),
            )

            val freshSession = setupSessionViewModel(repository)
            advanceUntilIdle()
            assertEquals(WizardCompletionStatus.IDLE, freshSession.uiState.value.wizardCompletion)
            assertFalse(freshSession.wizardDismissedForSession.value)
            assertTrue(
                wizardVisible(
                    wizardSeen = freshSession.uiState.value.wizardSeen,
                    rerunRequested = false,
                    completion = freshSession.uiState.value.wizardCompletion,
                    sessionDismissed = freshSession.wizardDismissedForSession.value,
                ),
            )

            repository.failWrites = false
            freshSession.retryWizardCompletion()
            advanceUntilIdle()

            assertTrue(repository.current.setupWizardSeen)
            assertEquals(WizardCompletionStatus.PERSISTED, freshSession.uiState.value.wizardCompletion)
            assertFalse(
                wizardVisible(
                    wizardSeen = freshSession.uiState.value.wizardSeen,
                    rerunRequested = false,
                    completion = freshSession.uiState.value.wizardCompletion,
                    sessionDismissed = freshSession.wizardDismissedForSession.value,
                ),
            )
        }
}
