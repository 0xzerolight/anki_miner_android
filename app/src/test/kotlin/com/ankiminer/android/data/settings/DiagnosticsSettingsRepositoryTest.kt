package com.ankiminer.android.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsSettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a fresh store leaves verbose logging off`() =
        runTest {
            val repository = DataStoreDiagnosticsSettingsRepository(createDataStore(backgroundScope, "fresh"))

            assertFalse(repository.verboseLogging.first())
        }

    @Test
    fun `the switch survives a process restart`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "restart")
            DataStoreDiagnosticsSettingsRepository(dataStore).setVerboseLogging(true)

            // A second repository, not a second DataStore: DataStore forbids two instances over
            // one file per process, so this reaches its in-memory cache rather than re-reading
            // the file. A true relaunch cannot be exercised in a JVM unit test; what this does
            // prove is that the value lives in the store rather than in repository state.
            assertTrue(DataStoreDiagnosticsSettingsRepository(dataStore).verboseLogging.first())

            DataStoreDiagnosticsSettingsRepository(dataStore).setVerboseLogging(false)

            assertFalse(DataStoreDiagnosticsSettingsRepository(dataStore).verboseLogging.first())
        }

    @Test
    fun `a value stored under the wrong type falls back to off`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "corrupt")
            dataStore.updateData { preferences ->
                preferences.toMutablePreferences().apply {
                    this[stringPreferencesKey("verbose_logging_enabled")] = "yes"
                    this[stringPreferencesKey("verbose_enabled_at")] = "recently"
                }.toPreferences()
            }

            assertFalse(DataStoreDiagnosticsSettingsRepository(dataStore).verboseLogging.first())
        }

    @Test
    fun `a stamp inside the window keeps verbose logging on`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "within-window")
            var clock = ENABLED_AT
            val repository = DataStoreDiagnosticsSettingsRepository(dataStore) { clock }
            repository.setVerboseLogging(true)

            clock = ENABLED_AT + TimeUnit.DAYS.toMillis(7) - 1

            assertTrue(repository.verboseLogging.first())
            assertEquals(true, dataStore.data.first()[VERBOSE_LOGGING])
        }

    @Test
    fun `a stamp exactly one window old is still inside the window`() =
        runTest {
            // Pins the inclusive bound: `!in 0..W` and `!in 0 until W` differ only here, and a
            // window that expires a millisecond early is indistinguishable from a bug in the
            // clock for anyone reading the code later.
            val dataStore = createDataStore(backgroundScope, "window-boundary")
            var clock = ENABLED_AT
            val repository = DataStoreDiagnosticsSettingsRepository(dataStore) { clock }
            repository.setVerboseLogging(true)

            clock = ENABLED_AT + TimeUnit.DAYS.toMillis(7)

            assertTrue(repository.verboseLogging.first())
            assertEquals(true, dataStore.data.first()[VERBOSE_LOGGING])
        }

    @Test
    fun `a stamp past the window reverts to off and clears the stored flag`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "expired")
            var clock = ENABLED_AT
            val repository = DataStoreDiagnosticsSettingsRepository(dataStore) { clock }
            repository.setVerboseLogging(true)

            clock = ENABLED_AT + TimeUnit.DAYS.toMillis(7) + 1

            assertFalse(repository.verboseLogging.first())
            // Reverting the emission alone would leave the tester's next launch verbose again.
            val stored = dataStore.data.first()
            assertNull(stored[VERBOSE_LOGGING])
            assertNull(stored[VERBOSE_ENABLED_AT])
        }

    @Test
    fun `a stamp from the future is treated as expired`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "future")
            var clock = ENABLED_AT
            val repository = DataStoreDiagnosticsSettingsRepository(dataStore) { clock }
            repository.setVerboseLogging(true)

            // A manual clock change or a restored backup; there is no window to measure from.
            clock = ENABLED_AT - TimeUnit.DAYS.toMillis(1)

            assertFalse(repository.verboseLogging.first())
            assertNull(dataStore.data.first()[VERBOSE_LOGGING])
        }

    @Test
    fun `a flag with no stamp at all is treated as expired`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "stampless")
            dataStore.updateData { preferences ->
                preferences.toMutablePreferences().apply { this[VERBOSE_LOGGING] = true }.toPreferences()
            }

            assertFalse(DataStoreDiagnosticsSettingsRepository(dataStore).verboseLogging.first())
        }

    private fun createDataStore(
        scope: CoroutineScope,
        name: String,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(temporaryFolder.root, "$name.preferences_pb") },
        )

    private companion object {
        const val ENABLED_AT = 1_700_000_000_000L
        val VERBOSE_LOGGING = booleanPreferencesKey("verbose_logging_enabled")
        val VERBOSE_ENABLED_AT = longPreferencesKey("verbose_enabled_at")
    }
}
