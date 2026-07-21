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
        assertEquals(WizardStep.TOKENIZER, nextWizardStep(WizardStep.WELCOME))
        assertEquals(WizardStep.ANKIDROID_DECK, nextWizardStep(WizardStep.ANKIDROID))
        assertEquals(WizardStep.ANKIDROID_NOTE_TYPE, nextWizardStep(WizardStep.ANKIDROID_DECK))
        assertEquals(WizardStep.DONE, nextWizardStep(WizardStep.DONE))
        assertEquals(WizardStep.WELCOME, previousWizardStep(WizardStep.WELCOME))
        assertEquals(WizardStep.ANKIDROID_NOTE_TYPE, previousWizardStep(WizardStep.DONE))
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
