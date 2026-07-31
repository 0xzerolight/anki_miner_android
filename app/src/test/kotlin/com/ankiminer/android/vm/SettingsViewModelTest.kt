package com.ankiminer.android.vm

import androidx.lifecycle.viewModelScope
import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.resources.KnownWordsResetScope
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.AppSettingsValidator
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.engine.BridgeJsonValue
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    fun settingsReadFailureLeavesDraftUnloadedAndRejectsEdits() =
        runTest(mainDispatcherRule.dispatcher) {
            var writeCount = 0
            val repository =
                object : AppSettingsRepository {
                    override val settings: Flow<AppSettings> =
                        flow {
                            throw IOException("transient read failure")
                        }

                    override suspend fun update(settings: AppSettings) {
                        writeCount += 1
                    }

                    override suspend fun update(transform: (AppSettings) -> AppSettings) {
                        writeCount += 1
                    }
                }
            val viewModel =
                SettingsViewModel(
                    repository,
                    FakeResourceManager(resources("first")),
                )
            advanceUntilIdle()

            assertFalse(viewModel.draftState.value.loaded)
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(deckName = "Default"))
            advanceUntilIdle()

            assertFalse(viewModel.draftState.value.loaded)
            assertFalse(viewModel.draftState.value.dirty)
            assertEquals(0, writeCount)
        }

    @Test
    fun freshWordsetDefaultsSurviveAnInventoryWhichHasNotLoadedYet() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(ResourceManagerState()))
            advanceUntilIdle()

            assertEquals(
                AppSettings.DEFAULT_ENABLED_WORDSETS,
                viewModel.draftState.value.draft.enabledWordsets,
            )
            assertEquals(0, repository.writeCount)
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
    fun outOfRangeWorkersRemainPendingBesideTheFieldAndPersistNothing() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            // Passes the numeric gate but fails the validator (workers must be 1..32).
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(workers = "33"))
            advanceUntilIdle()

            assertEquals(0, repository.writeCount)
            assertEquals(
                R.string.b3_validation_parallel_workers,
                viewModel.draftState.value.draft.validation[SettingsFieldKey.WORKERS]?.resourceId,
            )
            assertTrue(viewModel.error.value == null)
            assertEquals("33", viewModel.draftState.value.draft.workers)
            assertEquals(SettingsSaveState.Pending(1), viewModel.saveState.value)
        }

    @Test
    fun rapidTextEditsCoalesceIntoOneWrite() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(deckName = "A"))
            runCurrent()
            advanceTimeBy(SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS / 2)
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(deckName = "AB"))
            runCurrent()
            advanceTimeBy(SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS / 2)
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(deckName = "ABC"))
            runCurrent()

            assertEquals(0, repository.writeCount)

            advanceTimeBy(SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS)
            runCurrent()

            assertEquals(1, repository.writeCount)
            assertEquals("ABC", repository.current.deckName)
        }

    @Test
    fun togglePersistsWithoutDebounceDelay() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            val toggled = !viewModel.draftState.value.draft.jisho
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = toggled))
            runCurrent()

            assertEquals(1, repository.writeCount)
            assertEquals(toggled, repository.current.jishoEnabled)
        }

    @Test
    fun invalidNumericDoesNotBlockImmediateTogglePersistence() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(subtitleOffsetSeconds = 0.25, jishoEnabled = false),
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(subtitleOffset = "-"))
            runCurrent()
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            runCurrent()

            assertEquals(1, repository.writeCount)
            assertTrue(repository.current.jishoEnabled)
            assertEquals(0.25, repository.current.subtitleOffsetSeconds!!, 0.0)
            assertEquals("-", viewModel.draftState.value.draft.subtitleOffset)
        }

    @Test
    fun invalidRegexPairDoesNotBlockImmediateTogglePersistence() =
        runTest(mainDispatcherRule.dispatcher) {
            val persistedPattern = "(a)"
            val persistedReplacement = """\1"""
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(
                        subtitleRegexFilter = persistedPattern,
                        subtitleRegexReplacement = persistedReplacement,
                        jishoEnabled = false,
                    ),
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(
                viewModel.draftState.value.draft.copy(
                    subtitleRegex = "a",
                    jisho = true,
                ),
            )
            advanceUntilIdle()

            assertEquals(1, repository.writeCount)
            assertEquals(persistedPattern, repository.current.subtitleRegexFilter)
            assertEquals(persistedReplacement, repository.current.subtitleRegexReplacement)
            assertTrue(repository.current.jishoEnabled)
            assertEquals("a", viewModel.draftState.value.draft.subtitleRegex)
        }

    @Test
    fun outOfRangeWorkersDoNotBlockConcurrentValidTogglePersistence() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(maxParallelWorkers = 6, jishoEnabled = false),
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(workers = "33"))
            runCurrent()
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            runCurrent()

            assertEquals(1, repository.writeCount)
            assertEquals(6, repository.current.maxParallelWorkers)
            assertTrue(repository.current.jishoEnabled)
            assertEquals("33", viewModel.draftState.value.draft.workers)
            assertEquals(SettingsSaveState.Pending(2), viewModel.saveState.value)
        }

    @Test
    fun ordinaryAutosavePublishesSavingThenSavedForTheSameRevision() =
        runTest(mainDispatcherRule.dispatcher) {
            val writeStarted = CompletableDeferred<Unit>()
            val allowWrite = CompletableDeferred<Unit>()
            val repository =
                FakeAppSettingsRepository(AppSettings()) { attempt ->
                    if (attempt == 1) {
                        writeStarted.complete(Unit)
                        allowWrite.await()
                    }
                }
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            runCurrent()

            assertTrue(writeStarted.isCompleted)
            assertEquals(SettingsSaveState.Saving(1), viewModel.saveState.value)
            assertTrue(viewModel.saving.value)

            allowWrite.complete(Unit)
            advanceUntilIdle()

            assertEquals(SettingsSaveState.Saved(1), viewModel.saveState.value)
            assertFalse(viewModel.saving.value)
        }

    @Test
    fun failedAutosavePublishesFailedAndExplicitRetryPublishesSaved() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeAppSettingsRepository(
                    initial = AppSettings(jishoEnabled = false),
                    failuresRemaining = 1,
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            advanceUntilIdle()

            assertEquals(SettingsSaveState.Failed(1), viewModel.saveState.value)
            assertFalse(repository.current.jishoEnabled)

            viewModel.retrySave()
            advanceUntilIdle()

            assertEquals(SettingsSaveState.Saved(1), viewModel.saveState.value)
            assertTrue(repository.current.jishoEnabled)
        }

    @Test
    fun resourceReorderPersistsWithoutDebounceDelay() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel =
                SettingsViewModel(repository, FakeResourceManager(resources("first", "second")))
            advanceUntilIdle()

            val reversed = viewModel.draftState.value.draft.dictionarySources.reversed()
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(dictionarySources = reversed))
            runCurrent()

            assertEquals(1, repository.writeCount)
            assertEquals(reversed, repository.current.dictionarySources)
        }

    @Test
    fun unrelatedEditPreservesUnavailablePersistedPitchChoices() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedPitch =
                listOf(
                    ResourceChainSelection("unavailable", enabled = false),
                    ResourceChainSelection("available", enabled = true),
                )
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(pitchSources = savedPitch, jishoEnabled = false),
                )
            val resourceManager =
                FakeResourceManager(
                    ResourceManagerState(pitchSources = listOf(pitchSource("available"))),
                )
            val viewModel = SettingsViewModel(repository, resourceManager)
            advanceUntilIdle()

            assertEquals(
                listOf("available"),
                viewModel.draftState.value.draft.pitchSources.map(
                    ResourceChainSelection::resourceId,
                ),
            )

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            advanceUntilIdle()

            assertEquals(savedPitch, repository.current.pitchSources)
            assertTrue(repository.current.jishoEnabled)
        }

    @Test
    fun scalarEditPreservesOutOfBandPitchUpdate() =
        runTest(mainDispatcherRule.dispatcher) {
            val initialPitch = listOf(ResourceChainSelection("first"))
            val updatedPitch =
                listOf(
                    ResourceChainSelection("second", enabled = false),
                    ResourceChainSelection("first", enabled = true),
                )
            val inventory =
                ResourceManagerState(
                    pitchSources = listOf(pitchSource("first"), pitchSource("second")),
                )
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(pitchSources = initialPitch, jishoEnabled = false),
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(inventory))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(deckName = "Pending"))
            runCurrent()
            repository.update { it.copy(pitchSources = updatedPitch) }
            runCurrent()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            runCurrent()

            assertEquals(updatedPitch, repository.current.pitchSources)
            assertEquals("Pending", repository.current.deckName)
            assertTrue(repository.current.jishoEnabled)
        }

    @Test
    fun explicitPitchReorderPersistsWithoutDebounceDelay() =
        runTest(mainDispatcherRule.dispatcher) {
            val inventory =
                ResourceManagerState(
                    pitchSources = listOf(pitchSource("first"), pitchSource("second")),
                )
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(
                        pitchSources =
                            listOf(
                                ResourceChainSelection("first"),
                                ResourceChainSelection("second"),
                            ),
                    ),
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(inventory))
            advanceUntilIdle()

            val reversed = viewModel.draftState.value.draft.pitchSources.reversed()
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(pitchSources = reversed))
            runCurrent()

            assertEquals(1, repository.writeCount)
            assertEquals(reversed, repository.current.pitchSources)
        }

    @Test
    fun pendingTextEditFlushesOnLifecycleStop() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(deckName = "Before stop"))
            runCurrent()
            assertEquals(0, repository.writeCount)

            // SettingsRoute invokes this for ON_STOP and route disposal.
            viewModel.flushPendingWrites()
            runCurrent()

            assertEquals(1, repository.writeCount)
            assertEquals("Before stop", repository.current.deckName)

            advanceTimeBy(SETTINGS_AUTOSAVE_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(1, repository.writeCount)
        }

    @Test
    fun lifecycleFlushSurvivesViewModelScopeCancellation() =
        runTest(mainDispatcherRule.dispatcher) {
            val writeStarted = CompletableDeferred<Unit>()
            val allowWrite = CompletableDeferred<Unit>()
            val repository =
                FakeAppSettingsRepository(AppSettings()) { attempt ->
                    if (attempt == 1) {
                        writeStarted.complete(Unit)
                        allowWrite.await()
                    }
                }
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(deckName = "Before stop"))
            runCurrent()
            viewModel.flushPendingWrites()
            runCurrent()
            assertTrue(writeStarted.isCompleted)

            viewModel.viewModelScope.cancel()
            allowWrite.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, repository.writeCount)
            assertEquals("Before stop", repository.current.deckName)
        }

    @Test
    fun failedWriteIsRetriedByLifecycleFlush() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeAppSettingsRepository(
                    initial = AppSettings(jishoEnabled = false),
                    failuresRemaining = 1,
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            runCurrent()

            assertEquals(1, repository.attemptedWriteCount)
            assertEquals(0, repository.writeCount)
            assertFalse(repository.current.jishoEnabled)

            viewModel.flushPendingWrites()
            advanceUntilIdle()

            assertEquals(2, repository.attemptedWriteCount)
            assertEquals(1, repository.writeCount)
            assertTrue(repository.current.jishoEnabled)
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
    fun wizardDeckSelectionSurvivesDirtySettingsAutoSaveAndFeedsMiningTarget() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = SessionSettingsRepository(AppSettings(deckName = "D0"))
            val resources = SessionResourceManager(resources("first"))
            val settingsViewModel = SettingsViewModel(repository, resources)
            val setupViewModel =
                setupSessionViewModel(
                    repository = repository,
                    resources = resources,
                    deckNames = listOf("D"),
                )
            advanceUntilIdle()

            settingsViewModel.updateDraft(
                settingsViewModel.draftState.value.draft.copy(audioPadding = "1.50"),
            )
            advanceUntilIdle()
            assertTrue(settingsViewModel.draftState.value.dirty)
            assertEquals("D0", settingsViewModel.draftState.value.draft.deckName)

            setupViewModel.selectDeck("D")
            advanceUntilIdle()

            settingsViewModel.updateDraft(
                settingsViewModel.draftState.value.draft.copy(
                    tags = "mined",
                    tagsOverride = true,
                ),
            )
            advanceUntilIdle()

            assertEquals("D", repository.current.deckName)
            assertEquals("D", settingsViewModel.draftState.value.draft.deckName)
            assertEquals(
                BridgeJsonValue.Text("D"),
                repository.snapshot(listOf("first")).settings["anki_deck_name"],
            )
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
    fun scopedResetFoldsInPendingTextThenSupersedesItsDebouncedWrite() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(
                viewModel.draftState.value.draft.copy(
                    deckName = "Pending target",
                    audioPadding = "1.0",
                ),
            )
            runCurrent()
            assertEquals(0, repository.writeCount)

            viewModel.restoreMiningDefaults()
            advanceUntilIdle()

            // Target survives this scoped reset; mining value is reset. Stale debounce cannot
            // replay audioPadding after markClean.
            assertEquals(1, repository.writeCount)
            assertEquals("Pending target", repository.current.deckName)
            assertNull(repository.current.audioPaddingSeconds)
        }

    @Test
    fun confirmedResetQueuesBehindInFlightAutosave() =
        runTest(mainDispatcherRule.dispatcher) {
            val autosaveStarted = CompletableDeferred<Unit>()
            val allowAutosave = CompletableDeferred<Unit>()
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(audioPaddingSeconds = 1.0, jishoEnabled = false),
                ) { attempt ->
                    if (attempt == 1) {
                        autosaveStarted.complete(Unit)
                        allowAutosave.await()
                    }
                }
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            runCurrent()
            assertTrue(autosaveStarted.isCompleted)
            assertTrue(viewModel.saving.value)

            assertTrue(viewModel.restoreMiningDefaults())
            runCurrent()
            assertEquals(1, repository.attemptedWriteCount)

            allowAutosave.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, repository.writeCount)
            assertTrue(repository.current.jishoEnabled)
            assertNull(repository.current.audioPaddingSeconds)
            assertFalse(viewModel.saving.value)
        }

    @Test
    fun editDuringInFlightScopedResetPersistsAfterReset() =
        runTest(mainDispatcherRule.dispatcher) {
            val writeStarted = CompletableDeferred<Unit>()
            val allowWrite = CompletableDeferred<Unit>()
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(audioPaddingSeconds = 1.0, jishoEnabled = false),
                ) { attempt ->
                    if (attempt == 1) {
                        writeStarted.complete(Unit)
                        allowWrite.await()
                    }
                }
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.restoreMiningDefaults()
            runCurrent()
            assertTrue(writeStarted.isCompleted)

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            runCurrent()
            assertTrue(viewModel.draftState.value.draft.jisho)

            allowWrite.complete(Unit)
            advanceUntilIdle()

            assertEquals(2, repository.writeCount)
            assertNull(repository.current.audioPaddingSeconds)
            assertTrue(repository.current.jishoEnabled)
            assertTrue(viewModel.draftState.value.draft.jisho)
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

    // READY: startup recovery gates every setup command, so a PENDING fixture reports busy
    // and silently drops deck selection.
    private fun resources(vararg ids: String): ResourceManagerState =
        ResourceManagerState(
            startupReadiness = ResourceStartupReadiness.READY,
            dictionaries = ids.map(::dictionary),
        )

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

    private fun pitchSource(id: String): InstalledPitchSource =
        InstalledPitchSource(
            sourceId = id,
            sourceName = id,
            sourceRevision = "1",
            format = "yomitan",
            entryCount = 1,
            schemaOk = true,
            schemaVersion = 1,
        )

    /**
     * Mirrors [com.ankiminer.android.data.settings.DataStoreAppSettingsRepository]: both overloads
     * validate before committing, so a value that fails [AppSettingsValidator] throws and leaves the
     * store (and [writeCount]) untouched, and the transform reads the freshest persisted value.
     */
    private class FakeAppSettingsRepository(
        initial: AppSettings,
        private var failuresRemaining: Int = 0,
        private val writeGate: suspend (attempt: Int) -> Unit = {},
    ) : AppSettingsRepository {
        private val flow = MutableStateFlow(AppSettingsValidator.validate(initial))
        override val settings: Flow<AppSettings> = flow.asStateFlow()

        var attemptedWriteCount = 0
            private set

        var writeCount = 0
            private set

        val current: AppSettings
            get() = flow.value

        override suspend fun update(settings: AppSettings) {
            prepareWrite()
            val validated = AppSettingsValidator.validate(settings)
            flow.value = validated
            writeCount += 1
        }

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            prepareWrite()
            val validated = AppSettingsValidator.validate(transform(flow.value))
            flow.value = validated
            writeCount += 1
        }

        private suspend fun prepareWrite() {
            attemptedWriteCount += 1
            writeGate(attemptedWriteCount)
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                throw IOException("transient write failure")
            }
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
            sourceId: String,
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

        override suspend fun importWordList(uri: String, kind: WordListKind) = Unit

        override suspend fun removeWordList(kind: WordListKind) = Unit

        override fun wordListPath(kind: WordListKind): String? = null

        override suspend fun previewKnownWords(uri: String, format: KnownWordsSourceFormat) = Unit

        override suspend fun confirmKnownWordsImport() = Unit

        override suspend fun retryKnownWordsFailure() = Unit

        override fun dismissKnownWordsImportPreview() = Unit

        override suspend fun searchKnownWords(query: String, loadMore: Boolean) = Unit

        override suspend fun removeKnownWords(words: List<String>) = Unit

        override suspend fun resetKnownWords(scope: KnownWordsResetScope) = Unit

        override suspend fun exportKnownWords(uri: String) = Unit

        override suspend fun lookup(slotId: String, term: String) = Unit

        override fun cancelActive() = Unit

        override fun dismissFailure() = Unit
    }
}
