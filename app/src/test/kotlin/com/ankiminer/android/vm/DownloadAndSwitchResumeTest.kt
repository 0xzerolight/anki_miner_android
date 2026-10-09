package com.ankiminer.android.vm

import androidx.lifecycle.viewModelScope
import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import com.ankiminer.android.data.settings.LanguageProfileSource
import com.ankiminer.android.engine.LanguageUnavailableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadAndSwitchResumeTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `after a process death a language whose data landed resumes its set and switches`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = SessionSettingsRepository(AppSettings())
            var dataLanded = false
            val neverEnds = CompletableDeferred<Boolean>()
            // Next starts Arabic's set; its data commits, then Android ends the process mid-dictionary.
            val resources =
                LanguageSetInstaller {
                    dataLanded = true
                    neverEnds.await()
                }
            val before =
                SettingsViewModel(repository, resources, languageProfileSource = profilesWithArabic { dataLanded })
            advanceUntilIdle()
            before.downloadAndSwitchLanguage("ar")
            advanceUntilIdle()
            before.viewModelScope.cancel()

            // The restored process builds a new view model: no download in memory, Arabic reads available.
            resources.answer = { true }
            val after =
                SettingsViewModel(repository, resources, languageProfileSource = profilesWithArabic { dataLanded })
            advanceUntilIdle()
            assertNull(after.languageDownload.value)
            assertNull(after.languageProfiles.value.single { it.code == "ar" }.unavailableReason)

            // The wizard's held language offers "Download and switch" again.
            after.downloadAndSwitchLanguage("ar")
            advanceUntilIdle()

            assertEquals(listOf("ar", "ar"), resources.installs)
            assertEquals("ar", repository.current.language)
            assertNull(after.languageDownload.value)
        }

    @Test
    fun `a language this build cannot mine offers no download`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = SessionSettingsRepository(AppSettings())
            val resources = LanguageSetInstaller { true }
            val unsupportedHebrew =
                LanguageProfileSource {
                    Result.success(
                        LanguageProfileFixtures.all.map { profile ->
                            if (profile.code == "he") {
                                profile.copy(unavailableReason = LanguageUnavailableReason.UNSUPPORTED)
                            } else {
                                profile
                            }
                        },
                    )
                }
            val viewModel = SettingsViewModel(repository, resources, languageProfileSource = unsupportedHebrew)
            advanceUntilIdle()

            viewModel.downloadAndSwitchLanguage("he")
            advanceUntilIdle()

            assertEquals(emptyList<String>(), resources.installs)
            assertEquals("ja", repository.current.language)
        }
}
