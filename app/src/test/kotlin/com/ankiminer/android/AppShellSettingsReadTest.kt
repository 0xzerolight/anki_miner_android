package com.ankiminer.android

import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.ui.theme.resolveTheme
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app shell and the startup recovery sequence both read settings outside any scope that can
 * absorb a throw: the composition collects for the theme, and `applicationScope` has no exception
 * handler, so an unreadable store used to end the process at launch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppShellSettingsReadTest {
    @Test
    fun `an unreadable store still yields default shell settings`() =
        runTest {
            val settings = UnreadableSettingsRepository().appShellSettings().first()

            assertEquals(AppSettings(), settings)
            assertEquals("dark", resolveTheme(settings, systemInDarkTheme = true).palette.key)
        }

    @Test
    fun `the shell settings follow the persisted value while the store is readable`() =
        runTest {
            val stored = AppSettings(theme = ThemeMode.LIGHT)
            val repository = StoredSettingsRepository(stored)

            assertEquals(stored, repository.appShellSettings().first())
        }

    @Test
    fun `the mining view models' settings end quietly on an unreadable store`() =
        runTest {
            // Their collectors run in viewModelScope, which has no exception handler: a throw here
            // ended the process at launch. Ending empty leaves each input at its not-read-yet value.
            assertEquals(
                emptyList<AppSettings>(),
                UnreadableSettingsRepository().miningViewModelSettings().toList(),
            )
        }

    @Test
    fun `the mining view models' audio packs end quietly on an unreadable store`() =
        runTest {
            val emitted = mutableListOf<List<InstalledAudioPack>>()
            backgroundScope.launch {
                activeLanguageAudioPacks(
                    UnreadableSettingsRepository().miningViewModelSettings(),
                    MutableStateFlow(ResourceManagerState()),
                ).collect { emitted += it }
            }
            runCurrent()

            assertEquals(emptyList<List<InstalledAudioPack>>(), emitted)
        }

    @Test
    fun `the mining view models' settings follow the persisted value while the store is readable`() =
        runTest {
            val stored = AppSettings(deckName = "Mining", fieldMap = mapOf("word" to "Word"))

            assertEquals(stored, StoredSettingsRepository(stored).miningViewModelSettings().first())
        }

    @Test
    fun `startup setup refresh skips an unreadable store instead of refreshing on defaults`() =
        runTest {
            var refreshedWith: AppSettings? = null

            refreshAnkiSetupFromSettings(UnreadableSettingsRepository()) { refreshedWith = it }

            // Refreshing on defaults would publish "no note type selected" over the real setup
            // state; the target probe blocks the run with its own reason instead.
            assertNull(refreshedWith)
        }

    @Test
    fun `startup setup refresh passes persisted settings through`() =
        runTest {
            val stored = AppSettings(noteType = "Mining", fieldMap = mapOf("word" to "Word"))
            var refreshedWith: AppSettings? = null

            refreshAnkiSetupFromSettings(StoredSettingsRepository(stored)) { refreshedWith = it }

            assertEquals(stored, refreshedWith)
        }

    private class UnreadableSettingsRepository : AppSettingsRepository {
        override val settings: Flow<AppSettings> =
            flow { throw IOException("transient read failure") }

        override suspend fun update(settings: AppSettings) = error("write not expected")

        override suspend fun update(transform: (AppSettings) -> AppSettings) =
            error("write not expected")
    }

    private class StoredSettingsRepository(initial: AppSettings) : AppSettingsRepository {
        private val stored = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = stored.asStateFlow()

        override suspend fun update(settings: AppSettings) {
            stored.value = settings
        }

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            stored.value = transform(stored.value)
        }
    }
}
