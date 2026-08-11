package com.ankiminer.android.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
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
        val UPDATE_CHECK_ENABLED = booleanPreferencesKey("update_check_enabled")
    }
}
