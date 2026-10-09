package com.ankiminer.android.vm

import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.LanguageSwitchRefusal
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureRetry
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Every language-switch path refuses while a known-words import waits, a restored one included. */
class LanguageSwitchRefusalViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `a restored known-words import refuses a switch that is otherwise allowed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val allowedRepository = SessionSettingsRepository(AppSettings())
            val refusedRepository = SessionSettingsRepository(AppSettings())
            val allowed = settingsViewModel(allowedRepository, ResourceManagerState(startupReadiness = READY))
            val refused =
                settingsViewModel(
                    refusedRepository,
                    ResourceManagerState(startupReadiness = READY, failure = RESTORED_IMPORT),
                )
            advanceUntilIdle()

            assertTrue(allowed.switchLanguage("he"))
            // No preview survives process death; the import waits behind its Retry.
            assertFalse(refused.switchLanguage("he"))
            advanceUntilIdle()

            assertEquals("he", allowedRepository.current.language)
            assertEquals("ja", refusedRepository.current.language)
        }

    @Test
    fun `the setup state carries the refusal to the pickers`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel =
                setupSessionViewModel(
                    SessionSettingsRepository(AppSettings()),
                    SessionResourceManager(ResourceManagerState(startupReadiness = READY, failure = RESTORED_IMPORT)),
                )
            advanceUntilIdle()

            assertEquals(LanguageSwitchRefusal.KNOWN_WORDS_IMPORT_PENDING, viewModel.uiState.value.languageSwitchRefusal)
        }

    private fun settingsViewModel(
        repository: SessionSettingsRepository,
        state: ResourceManagerState,
    ) = SettingsViewModel(
        repository = repository,
        resources = SessionResourceManager(state),
        languageProfileSource = {
            Result.success(LanguageProfileFixtures.all.map { it.copy(unavailableReason = null) })
        },
    )

    private companion object {
        val READY = ResourceStartupReadiness.READY

        /** What recovery records beside a pending import it restored after process death. */
        val RESTORED_IMPORT =
            ResourceFailure(
                code = "resource_operation_interrupted",
                message = "interrupted",
                retryable = true,
                origin = ResourceFailureOrigin.KNOWN_WORDS,
                retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
                knownWordsOperation = KnownWordsFailureOperation.IMPORT,
            )
    }
}
