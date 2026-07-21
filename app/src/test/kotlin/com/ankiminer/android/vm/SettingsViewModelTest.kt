package com.ankiminer.android.vm

import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.AppSettingsValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun validDecimalEditAutoPersistsExactlyOnceAndKeepsRawText() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            assertTrue(viewModel.draftState.value.loaded)
            assertFalse(viewModel.draftState.value.dirty)
            assertEquals(0, repository.writeCount)

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(audioPadding = "1.50"))
            advanceUntilIdle()

            // Persisted exactly once: a non-idempotent withInventory would either re-persist here
            // (writeCount > 1) or spin forever and time out the test.
            assertEquals(1, repository.writeCount)
            assertEquals(1.5, repository.current.audioPaddingSeconds!!, 0.0)
            // Raw text survives the persist -> settings-emit -> merge-while-dirty round-trip, and the
            // draft stays dirty because auto-save never markClean-rebuilds the controlled fields.
            assertEquals("1.50", viewModel.draftState.value.draft.audioPadding)
            assertTrue(viewModel.draftState.value.dirty)
        }

    @Test
    fun loadAndInventoryRescanWhileCleanPerformZeroWrites() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings(deckName = "Persisted"))
            val resourceManager = FakeResourceManager(resources("first"))
            val viewModel = SettingsViewModel(repository, resourceManager)
            advanceUntilIdle()

            assertTrue(viewModel.draftState.value.loaded)
            assertFalse(viewModel.draftState.value.dirty)
            assertEquals(0, repository.writeCount)

            resourceManager.emit(resources("first", "second"))
            advanceUntilIdle()

            // Neither the initial load nor a clean inventory rescan is a user edit, so nothing
            // persists. A filter that dropped the dirty predicate would write on both.
            assertEquals(0, repository.writeCount)
            assertFalse(viewModel.draftState.value.dirty)
        }

    @Test
    fun numericInvalidEditIsNotPersistedAndDraftRetainsText() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(bitrate = "1.5"))
            advanceUntilIdle()

            assertEquals(0, repository.writeCount)
            assertEquals("1.5", viewModel.draftState.value.draft.bitrate)
        }

    @Test
    fun validatorRejectedEditSetsErrorAndPersistsNothing() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            // Passes the numeric gate but fails the validator (workers must be 1..32).
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(workers = "33"))
            advanceUntilIdle()

            assertEquals(0, repository.writeCount)
            assertNotNull(viewModel.error.value)
            assertEquals("33", viewModel.draftState.value.draft.workers)
        }

    @Test
    fun rapidEditsPersistOnlyTheLatest() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(deckName = "A"))
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(deckName = "B"))
            advanceUntilIdle()

            assertEquals(1, repository.writeCount)
            assertEquals("B", repository.current.deckName)
        }

    @Test
    fun scalarEditPreservesConcurrentlyWrittenNoteType() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            // Out-of-band write to a non-draft field, exactly like SetupViewModel choosing a note type.
            repository.update { it.copy(noteType = "jp-mining") }
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(deckName = "MyDeck"))
            advanceUntilIdle()

            // The transactional transform reads the freshest persisted value, so the auto-saved deck
            // edit never clobbers the concurrently written note type.
            assertEquals("jp-mining", repository.current.noteType)
            assertEquals("MyDeck", repository.current.deckName)
        }

    @Test
    fun reeditAfterRestoreMiningDefaultsRepersists() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(audioPadding = "1.0"))
            advanceUntilIdle()
            assertEquals(1.0, repository.current.audioPaddingSeconds!!, 0.0)

            viewModel.restoreMiningDefaults()
            advanceUntilIdle()
            assertNull(repository.current.audioPaddingSeconds)
            assertFalse(viewModel.draftState.value.dirty)

            // The identical re-edit must persist again. A distinctUntilChanged keyed on the draft
            // would suppress it and silently drop the write, leaving the setting null.
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(audioPadding = "1.0"))
            advanceUntilIdle()

            assertEquals(1.0, repository.current.audioPaddingSeconds!!, 0.0)
            assertTrue(viewModel.draftState.value.dirty)
        }

    @Test
    fun restoreMiningDefaultsPreservesTargetAndRebuildsDraft() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(deckName = "Deck", noteType = "note", tags = "mined"),
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.restoreMiningDefaults()
            advanceUntilIdle()

            assertEquals("Deck", repository.current.deckName)
            assertEquals("note", repository.current.noteType)
            assertNull(repository.current.tags)
            assertFalse(viewModel.draftState.value.dirty)
            assertEquals("Deck", viewModel.draftState.value.draft.deckName)
        }

    private fun resources(vararg ids: String): ResourceManagerState =
        ResourceManagerState(dictionaries = ids.map(::dictionary))

    private fun dictionary(id: String): InstalledDictionary =
        InstalledDictionary(
            slotId = id,
            occupied = true,
            valid = true,
            sourceName = id,
            sourceRevision = "1",
            format = "yomitan",
            entryCount = 1,
            schemaOk = true,
            embeddedAttribution = emptyMap(),
            catalogResourceId = null,
            attribution = emptyList(),
        )

    /**
     * Mirrors [com.ankiminer.android.data.settings.DataStoreAppSettingsRepository]: both overloads
     * validate before committing, so a value that fails [AppSettingsValidator] throws and leaves the
     * store (and [writeCount]) untouched, and the transform reads the freshest persisted value.
     */
    private class FakeAppSettingsRepository(initial: AppSettings) : AppSettingsRepository {
        private val flow = MutableStateFlow(AppSettingsValidator.validate(initial))
        override val settings: Flow<AppSettings> = flow.asStateFlow()

        var writeCount = 0
            private set

        val current: AppSettings
            get() = flow.value

        override suspend fun update(settings: AppSettings) {
            val validated = AppSettingsValidator.validate(settings)
            flow.value = validated
            writeCount += 1
        }

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            val validated = AppSettingsValidator.validate(transform(flow.value))
            flow.value = validated
            writeCount += 1
        }
    }

    private class FakeResourceManager(initial: ResourceManagerState) : ResourceManager {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<ResourceManagerState> = mutableState.asStateFlow()

        fun emit(value: ResourceManagerState) {
            mutableState.value = value
        }

        override suspend fun recoverAndRefresh() = Unit

        override suspend fun installUniDic() = Unit

        override suspend fun installCatalogDictionary(resourceId: String, replace: Boolean) = Unit

        override suspend fun importCustomDictionary(
            uri: String,
            slotId: String,
            replace: Boolean,
        ) = Unit

        override suspend fun importFrequencySource(
            uri: String,
            sourceId: String,
            sourceName: String,
            format: FrequencySourceFormat,
            replace: Boolean,
        ) = Unit

        override suspend fun importPitchAccent(
            uri: String,
            sourceName: String,
            format: PitchAccentSourceFormat,
            replace: Boolean,
        ) = Unit

        override suspend fun importAudioPack(
            uri: String,
            packId: String,
            replace: Boolean,
        ) = Unit

        override suspend fun importKnownWords(
            uri: String,
            format: KnownWordsSourceFormat,
        ) = Unit

        override suspend fun lookup(slotId: String, term: String) = Unit

        override fun cancelActive() = Unit

        override fun dismissFailure() = Unit
    }
}
