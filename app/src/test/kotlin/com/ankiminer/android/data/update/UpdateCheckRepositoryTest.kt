package com.ankiminer.android.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `a successful first read defaults update checks to enabled`() =
        runTest {
            val repository =
                DataStoreUpdateCheckRepository(createDataStore(backgroundScope, "fresh"))

            assertTrue(repository.state.first().enabled)
        }

    @Test
    fun `a read failure after opt-out fails closed`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "unreadable")
            DataStoreUpdateCheckRepository(dataStore).setEnabled(false)
            val repository = DataStoreUpdateCheckRepository(ReadFailureDataStore(dataStore))

            assertFalse(dataStore.data.first()[UPDATE_CHECK_ENABLED] ?: true)
            assertFalse(repository.state.first().enabled)
        }

    @Test
    fun `an automatic attempt persists without replacing the last successful result`() =
        runTest {
            val repository =
                DataStoreUpdateCheckRepository(createDataStore(backgroundScope, "attempt"))
            val update =
                AvailableUpdate(
                    version = "0.5.0",
                    releasePageUrl = "https://github.com/example/release",
                )
            repository.recordCheck(PREVIOUS_CHECK, update)

            repository.recordAutomaticAttempt(NOW)

            val stored = repository.state.first()
            assertEquals(NOW, stored.lastAutomaticAttemptAtMillis)
            assertEquals(PREVIOUS_CHECK, stored.lastCheckedAtMillis)
            assertEquals(update.version, stored.availableVersion)
            assertEquals(update.releasePageUrl, stored.availableUrl)
        }

    @Test
    fun `an existing successful check initializes the automatic attempt window`() =
        runTest {
            val dataStore = createDataStore(backgroundScope, "migrated")
            dataStore.updateData { preferences ->
                preferences.toMutablePreferences().apply {
                    this[UPDATE_LAST_CHECKED_AT] = PREVIOUS_CHECK
                }.toPreferences()
            }
            val repository = DataStoreUpdateCheckRepository(dataStore)

            assertEquals(PREVIOUS_CHECK, repository.state.first().lastAutomaticAttemptAtMillis)
        }

    private fun createDataStore(
        scope: CoroutineScope,
        name: String,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(temporaryFolder.root, "$name.preferences_pb") },
        )

    private class ReadFailureDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> =
            flow {
                throw IOException("transient read failure")
            }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences = delegate.updateData(transform)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val PREVIOUS_CHECK = NOW - 1_000L
        val UPDATE_CHECK_ENABLED = booleanPreferencesKey("update_check_enabled")
        val UPDATE_LAST_CHECKED_AT = longPreferencesKey("update_last_checked_at")
    }
}
