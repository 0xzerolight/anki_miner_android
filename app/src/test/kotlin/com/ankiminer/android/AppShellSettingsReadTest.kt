package com.ankiminer.android

import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app shell and the startup recovery sequence both read settings outside any scope that can
 * absorb a throw: the composition collects for the theme, and `applicationScope` has no exception
 * handler, so an unreadable store used to end the process at launch.
 */
class AppShellSettingsReadTest {
    @Test
    fun `an unreadable store still yields a shell theme`() =
        runTest {
            val theme = UnreadableSettingsRepository().appShellTheme().first()

            assertEquals(AppSettings().theme, theme)
        }

    @Test
    fun `the shell theme follows the persisted value while the store is readable`() =
        runTest {
            val repository = StoredSettingsRepository(AppSettings(theme = ThemeMode.LIGHT))

            assertEquals(ThemeMode.LIGHT, repository.appShellTheme().first())
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
