package com.ankiminer.android.vm

import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.LanguageProfileFixtures
import com.ankiminer.android.data.settings.LanguageProfileSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Records each language's set install and answers what [answer] says the run did. */
internal class LanguageSetInstaller(
    var answer: suspend (language: String) -> Boolean,
) : ResourceManager by SessionResourceManager() {
    val installs = mutableListOf<String>()

    override suspend fun installRecommendedResources(language: String): Boolean {
        installs += language
        return answer(language)
    }
}

/** The fixture profiles (ja and he available, ar needing its data), Arabic's data in once [available]. */
internal fun profilesWithArabic(available: () -> Boolean) =
    LanguageProfileSource {
        Result.success(
            LanguageProfileFixtures.all.map { profile ->
                if (profile.code == "ar" && available()) profile.copy(unavailableReason = null) else profile
            },
        )
    }

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadAndSwitchLanguageTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `a set cancelled after its language data landed keeps the current language`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = SessionSettingsRepository(AppSettings())
            var dataLanded = false
            // The data member commits first; the user then cancels the dictionary that follows it.
            val resources =
                LanguageSetInstaller {
                    dataLanded = true
                    false
                }
            val viewModel =
                SettingsViewModel(repository, resources, languageProfileSource = profilesWithArabic { dataLanded })
            advanceUntilIdle()

            viewModel.downloadAndSwitchLanguage("ar")
            advanceUntilIdle()

            assertEquals(listOf("ar"), resources.installs)
            assertEquals("ja", repository.current.language)
            // The list still learns that the data is in.
            assertNull(viewModel.languageProfiles.value.single { it.code == "ar" }.unavailableReason)
            assertNull(viewModel.languageDownload.value)
        }

    @Test
    fun `a set that ran to its end switches once the data is in, even with a later member failed`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = SessionSettingsRepository(AppSettings())
            var dataLanded = false
            // The dictionary after the data failed and recorded its own failure; the run still ended.
            val resources =
                LanguageSetInstaller {
                    dataLanded = true
                    true
                }
            val viewModel =
                SettingsViewModel(repository, resources, languageProfileSource = profilesWithArabic { dataLanded })
            advanceUntilIdle()

            viewModel.downloadAndSwitchLanguage("ar")
            advanceUntilIdle()

            assertEquals(listOf("ar"), resources.installs)
            assertEquals("ar", repository.current.language)
        }
}
