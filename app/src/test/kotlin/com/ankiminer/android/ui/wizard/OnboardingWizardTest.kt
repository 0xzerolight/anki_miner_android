package com.ankiminer.android.ui.wizard

import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.ResourceOperationPhase
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.engine.ContentDirection
import com.ankiminer.android.engine.LanguageProfileInfo
import com.ankiminer.android.engine.LanguageUnavailableReason
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.ui.settings.setupAttentionCount
import com.ankiminer.android.vm.SetupUiState
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
import java.util.Locale

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
    fun sameSessionRerunKeepsCompletionPersistenceFailureVisible() {
        assertTrue(
            wizardVisible(
                wizardSeen = false,
                rerunRequested = false,
                sessionDismissed = true,
                completion = WizardCompletionStatus.SAVING,
            ),
        )
        assertTrue(
            wizardVisible(
                wizardSeen = false,
                rerunRequested = false,
                sessionDismissed = true,
                completion = WizardCompletionStatus.FAILED,
            ),
        )
        assertFalse(
            wizardVisible(
                wizardSeen = false,
                rerunRequested = false,
                sessionDismissed = true,
                completion = WizardCompletionStatus.DISMISSED_FOR_SESSION,
            ),
        )
    }

    @Test
    fun theWizardWalksDesktopsFourPages() {
        assertEquals(
            listOf(WizardStep.LANGUAGE, WizardStep.DOWNLOADS, WizardStep.ANKIDROID, WizardStep.READY),
            WizardStep.entries,
        )
        assertEquals(WizardStep.DOWNLOADS, nextWizardStep(WizardStep.LANGUAGE))
        assertEquals(WizardStep.READY, nextWizardStep(WizardStep.ANKIDROID))
        assertEquals(WizardStep.READY, nextWizardStep(WizardStep.READY))
        assertEquals(WizardStep.LANGUAGE, previousWizardStep(WizardStep.LANGUAGE))
        assertEquals(WizardStep.ANKIDROID, previousWizardStep(WizardStep.READY))
    }

    @Test
    fun systemBackMovesToThePreviousPageAndAsksBeforeLeavingTheFirst() {
        assertEquals(WizardBackAction.Previous(WizardStep.DOWNLOADS), wizardBackAction(WizardStep.ANKIDROID))
        assertEquals(WizardBackAction.ConfirmSkip, wizardBackAction(WizardStep.LANGUAGE))
    }

    @Test
    fun finalPageNeverClaimsReadyAndSaysAlmostReadyOnlyWhileDownloadsAreAllThatIsLeft() {
        val ready = readySetup()
        val downloading =
            ready.copy(
                dictionaries = emptyList(),
                operation = ResourceOperationProgress("op", "JMdict", ResourceOperationPhase.DOWNLOADING),
            )

        assertEquals(WizardFinalState.READY, wizardFinalState(ready))
        assertEquals(WizardFinalState.ALMOST_READY, wizardFinalState(downloading))
        assertEquals(WizardFinalState.INCOMPLETE, wizardFinalState(downloading.copy(anki = AnkiProviderReadiness.NotInstalled)))
        assertEquals(WizardFinalState.INCOMPLETE, wizardFinalState(ready.copy(dictionaries = emptyList())))
    }

    @Test
    fun finalStateIsAlmostReadyWhileThePickedLanguageDownloads() {
        // The old language's missing tokenizer and dictionary are not what the user picked.
        assertEquals(
            WizardFinalState.ALMOST_READY,
            wizardFinalState(readySetup().copy(uniDicInstalled = false, dictionaries = emptyList()), languagePending = true),
        )
        assertEquals(
            WizardFinalState.INCOMPLETE,
            wizardFinalState(readySetup().copy(anki = AnkiProviderReadiness.NotInstalled), languagePending = true),
        )
        // Picked but no longer downloading (process death, or a failed download): never "Almost ready".
        assertEquals(
            WizardFinalState.INCOMPLETE,
            wizardFinalState(readySetup(), languagePending = true, languageDownloadRunning = false),
        )
    }

    @Test
    fun theStepsOwnRequiredActionTakesTheEmphasisUntilItRuns() {
        val ready = readySetup()
        val running = ResourceOperationProgress("op", "UniDic", ResourceOperationPhase.DOWNLOADING)

        assertTrue(wizardStepActionPending(WizardStep.DOWNLOADS, ready.copy(uniDicInstalled = false)))
        assertFalse(wizardStepActionPending(WizardStep.DOWNLOADS, ready))
        assertFalse(wizardStepActionPending(WizardStep.DOWNLOADS, ready.copy(uniDicInstalled = false, operation = running)))
        // While another language is picked, the old language's UniDic is not this page's action.
        assertFalse(wizardStepActionPending(WizardStep.DOWNLOADS, ready.copy(uniDicInstalled = false), languagePending = true))
        assertTrue(wizardStepActionPending(WizardStep.ANKIDROID, ready.copy(anki = AnkiProviderReadiness.NotInstalled)))
        assertFalse(wizardStepActionPending(WizardStep.LANGUAGE, ready.copy(uniDicInstalled = false)))
        assertFalse(wizardStepActionPending(WizardStep.READY, ready.copy(anki = AnkiProviderReadiness.NotInstalled)))
    }

    @Test
    fun aNonJapaneseLearnerIsNeitherRushedPastTheListNorSentToUniDic() {
        assertFalse(wizardNextEnabled(WizardStep.LANGUAGE, saving = false, profilesLoaded = false))
        assertTrue(wizardNextEnabled(WizardStep.LANGUAGE, saving = false, profilesLoaded = true))
        assertTrue(wizardNextEnabled(WizardStep.DOWNLOADS, saving = false, profilesLoaded = false))
        assertFalse(wizardNextEnabled(WizardStep.READY, saving = true, profilesLoaded = true))
        val german = readySetup().copy(language = "de", uniDicRequired = false, uniDicInstalled = false)
        assertFalse(wizardStepActionPending(WizardStep.DOWNLOADS, german))
        assertEquals(WizardFinalState.READY, wizardFinalState(german))
        assertEquals(0, german.setupAttentionCount())
    }

    @Test
    fun languageChoicesPutTheActiveLanguageFirstAndDropWhatThisBuildCannotMine() {
        val choices =
            wizardLanguageChoices(
                profiles =
                    listOf(
                        profile("th", "ไทย", "Thai"),
                        profile("ja", "日本語", "Japanese"),
                        profile("ko", "한국어", "Korean", LanguageUnavailableReason.DATA_REQUIRED),
                        profile("xx", "Xx", "Unminable", LanguageUnavailableReason.UNSUPPORTED),
                    ),
                uiLocale = Locale.ENGLISH,
                activeCode = "ja",
            )

        assertEquals(listOf("ja", "ko", "th"), choices.map { it.code })
        assertEquals("日本語 — Japanese", choices.first().label)
        assertEquals(listOf(false, true, false), choices.map { it.needsDownload })
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
            advanceUntilIdle()
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

    private fun readySetup() =
        SetupUiState(
            python = PythonRuntimeReadiness.Ready("/runtime"),
            resourceStartup = ResourceStartupReadiness.READY,
            anki = AnkiProviderReadiness.Ready(apiSpecVersion = 7, versionCode = 1L),
            ankiRecovery = AnkiRecoveryReadiness.Ready,
            noteTypeStatus = NoteTypeSetupStatus.Verified(modelId = 1L),
            miningTarget = AnkiMiningTargetReadiness.Ready,
            uniDicInstalled = true,
            dictionaries =
                listOf(
                    InstalledDictionary(
                        slotId = "dictionary-1",
                        occupied = true,
                        valid = true,
                        sourceName = "Jitendex",
                        sourceRevision = "2026-08-01",
                        format = "yomitan",
                        entryCount = 1_000L,
                        schemaOk = true,
                        embeddedAttribution = emptyMap(),
                        catalogResourceId = "jitendex",
                        attribution = emptyList(),
                        rebuildSourcePath = null,
                    ),
                ),
        )

    private fun profile(
        code: String,
        native: String,
        english: String,
        reason: LanguageUnavailableReason? = null,
    ) = LanguageProfileInfo(
        code = code,
        displayName = native,
        englishName = english,
        unavailableReason = reason,
        scriptVariants = emptyList(),
        contentDirection = ContentDirection.LTR,
        contentLanguage = code,
        speechLanguage = code,
        audioTrackCodes = emptyList(),
        capabilities = emptySet(),
        requiresUnidic = code == "ja",
        scopedDefaults = emptyMap(),
        extraCardFields = emptyList(),
    )
}
