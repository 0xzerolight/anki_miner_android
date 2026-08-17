package com.ankiminer.android.vm

import androidx.lifecycle.viewModelScope
import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.AudioPackCandidate
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.resources.KnownWordsResetScope
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.ResourceImportFileKind
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.AppSettingsValidator
import com.ankiminer.android.data.settings.EngineDefaults
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.data.settings.SettingsBackupException
import com.ankiminer.android.data.settings.SettingsBackupFailure
import com.ankiminer.android.data.settings.SettingsBackupCodec
import com.ankiminer.android.data.settings.SettingsBackupWriter
import com.ankiminer.android.data.settings.SettingsDocumentReader
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.engine.BridgeJsonValue
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    fun commaDecimalEditAutoPersistsUnderACommaDecimalLocale() =
        runTest(mainDispatcherRule.dispatcher) {
            val previousLocale = Locale.getDefault()
            try {
                Locale.setDefault(Locale.GERMANY)
                val repository = FakeAppSettingsRepository(AppSettings())
                val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
                advanceUntilIdle()

                viewModel.updateDraft(viewModel.draftState.value.draft.copy(audioPadding = "1,5"))
                advanceUntilIdle()

                assertEquals(1, repository.writeCount)
                assertEquals(1.5, repository.current.audioPaddingSeconds!!, 0.0)
                assertEquals("1,5", viewModel.draftState.value.draft.audioPadding)
            } finally {
                Locale.setDefault(previousLocale)
            }
        }

    @Test
    fun blankTagsPersistAndReachTheEngineAsNoTags() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(tags = ""))
            advanceUntilIdle()

            assertEquals("", repository.current.tags)
            assertEquals(
                BridgeJsonValue.Text(""),
                repository.snapshot(listOf("first")).settings["anki_tags"],
            )
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

            // Passes the numeric gate but fails the validator (workers must be 1..20).
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(workers = "21"))
            advanceUntilIdle()

            assertEquals(0, repository.writeCount)
            assertEquals(
                R.string.b3_validation_parallel_workers,
                viewModel.draftState.value.draft.validation[SettingsFieldKey.WORKERS]?.resourceId,
            )
            assertTrue(viewModel.error.value == null)
            assertEquals("21", viewModel.draftState.value.draft.workers)
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
            // A genuine override, not a value that happens to equal the engine default: a stored
            // default normalizes back to inherit on the next save, which would make this assertion
            // measure the prefill instead of "invalid text never clobbers the persisted value".
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(maxParallelWorkers = 12, jishoEnabled = false),
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(workers = "33"))
            runCurrent()
            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            runCurrent()

            assertEquals(1, repository.writeCount)
            assertEquals(12, repository.current.maxParallelWorkers)
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
    fun deletedResourceDropsOutOfTheDraftChainWithoutMarkingItDirty() =
        runTest(mainDispatcherRule.dispatcher) {
            // Deleting a source is a ResourceManager mutation, not a settings edit. The draft
            // reconciles against every inventory emission, so the stale id must disappear on its
            // own and must not raise an unsaved-changes badge over a change the user never made.
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(
                        pitchSources =
                            listOf(
                                ResourceChainSelection("kanjium"),
                                ResourceChainSelection("nhk"),
                            ),
                    ),
                )
            val resourceManager =
                FakeResourceManager(
                    ResourceManagerState(
                        pitchSources = listOf(pitchSource("kanjium"), pitchSource("nhk")),
                    ),
                )
            val viewModel = SettingsViewModel(repository, resourceManager)
            advanceUntilIdle()
            assertEquals(
                listOf("kanjium", "nhk"),
                viewModel.draftState.value.draft.pitchSources.map(
                    ResourceChainSelection::resourceId,
                ),
            )
            val dirtyBefore = viewModel.draftState.value.dirty

            resourceManager.emit(
                ResourceManagerState(pitchSources = listOf(pitchSource("nhk"))),
            )
            advanceUntilIdle()

            assertEquals(
                listOf("nhk"),
                viewModel.draftState.value.draft.pitchSources.map(
                    ResourceChainSelection::resourceId,
                ),
            )
            assertEquals(dirtyBefore, viewModel.draftState.value.dirty)
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
    fun malformedDisabledAnimatedFieldsRetainPersistedValuesAndAutosaveContinues() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(
                        animatedScreenshotsEnabled = false,
                        animatedScreenshotDurationSeconds = 2.5,
                        animatedScreenshotQuality = 60,
                    ),
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            viewModel.updateDraft(
                viewModel.draftState.value.draft.copy(
                    animatedScreenshots = false,
                    animatedScreenshotDuration = ".",
                    animatedScreenshotQuality = "unfinished",
                    jisho = true,
                ),
            )
            advanceUntilIdle()

            assertTrue(repository.current.jishoEnabled)
            assertEquals(2.5, repository.current.animatedScreenshotDurationSeconds!!, 0.0)
            assertEquals(60, repository.current.animatedScreenshotQuality)

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(readingTts = true))
            advanceUntilIdle()

            assertTrue(repository.current.readingTtsEnabled)
            assertEquals(2, repository.writeCount)
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
            assertEquals(EngineDefaults.TAGS, repository.current.tags)
            assertFalse(viewModel.draftState.value.dirty)
            assertEquals("Deck", viewModel.draftState.value.draft.deckName)
            assertEquals(EngineDefaults.TAGS, viewModel.draftState.value.draft.tags)
        }

    @Test
    fun draftHydratesEngineDefaultsIntoUnsetFields() {
        val draft = SettingsDraft.from(AppSettings(), resources("first"))

        assertEquals(EngineDefaults.DECK_NAME, draft.deckName)
        assertEquals(EngineDefaults.AUDIO_PADDING_SECONDS.toString(), draft.audioPadding)
        assertEquals(EngineDefaults.SCREENSHOT_OFFSET_SECONDS.toString(), draft.screenshotOffset)
        assertEquals(
            EngineDefaults.ANIMATED_SCREENSHOT_DURATION_SECONDS.toString(),
            draft.animatedScreenshotDuration,
        )
        assertEquals(
            EngineDefaults.ANIMATED_SCREENSHOT_QUALITY.toString(),
            draft.animatedScreenshotQuality,
        )
        assertEquals(EngineDefaults.SUBTITLE_OFFSET_SECONDS.toString(), draft.subtitleOffset)
        assertEquals(EngineDefaults.AUDIO_BITRATE_KBPS.toString(), draft.bitrate)
        assertEquals(EngineDefaults.MAX_SENTENCE_DURATION_SECONDS.toString(), draft.maxDuration)
        assertEquals(EngineDefaults.MAX_SENTENCE_CHARACTERS.toString(), draft.maxCharacters)
        assertEquals(
            EngineDefaults.READING_MINIMUM_OCCURRENCE.toString(),
            draft.readingOccurrence,
        )
        assertEquals(EngineDefaults.MAX_FREQUENCY_RANK.toString(), draft.maxFrequency)
        assertEquals(EngineDefaults.MAX_PARALLEL_WORKERS.toString(), draft.workers)
        // A prefilled value the validators reject would block every settings write behind a field
        // the user never touched.
        assertTrue(draft.validation.isEmpty())
    }

    @Test
    fun unEditedPrefilledDraftSavesAsInherit() {
        val base = AppSettings()

        val saved = SettingsDraft.from(base, resources()).toSettings(base)

        assertNull(saved.deckName)
        assertNull(saved.audioPaddingSeconds)
        assertNull(saved.screenshotOffsetSeconds)
        assertNull(saved.animatedScreenshotDurationSeconds)
        assertNull(saved.animatedScreenshotQuality)
        assertNull(saved.subtitleOffsetSeconds)
        assertNull(saved.audioBitrateKbps)
        assertNull(saved.maxSentenceDurationSeconds)
        assertNull(saved.maxSentenceCharacters)
        assertNull(saved.readingMinimumOccurrence)
        assertNull(saved.maxFrequencyRank)
        assertNull(saved.maxParallelWorkers)
        // Nothing else drifted either: the prefill is display text, not stored state.
        assertEquals(base, saved)
    }

    @Test
    fun typingAValueEqualToTheDefaultNormalizesToInherit() {
        val base = AppSettings()
        // A non-canonical spelling of the default, derived from the constant so a re-pin cannot
        // leave a stale literal behind.
        val padded = EngineDefaults.AUDIO_PADDING_SECONDS.toString() + "0"

        val saved =
            SettingsDraft.from(base, resources()).copy(audioPadding = padded).toSettings(base)

        assertNull(saved.audioPaddingSeconds)
    }

    @Test
    fun typingANonDefaultValueStoresAnOverride() {
        val base = AppSettings()

        val saved =
            SettingsDraft.from(base, resources()).copy(audioPadding = "0.8").toSettings(base)

        assertEquals(0.8, saved.audioPaddingSeconds!!, 0.0)
    }

    @Test
    fun clearingAPrefilledFieldStillInherits() {
        val base = AppSettings()

        val saved = SettingsDraft.from(base, resources()).copy(audioPadding = "").toSettings(base)

        assertNull(saved.audioPaddingSeconds)
    }

    @Test
    fun explicitlyStoredDefaultValueNormalizesOnNextSave() {
        // Accepted behavior change: a value stored explicitly before the prefill existed collapses
        // back to inherit the next time the draft is saved, so a re-pinned engine default flows
        // through again.
        val base = AppSettings(audioPaddingSeconds = EngineDefaults.AUDIO_PADDING_SECONDS)
        val draft = SettingsDraft.from(base, resources())

        assertEquals(EngineDefaults.AUDIO_PADDING_SECONDS.toString(), draft.audioPadding)
        assertNull(draft.toSettings(base).audioPaddingSeconds)
    }

    @Test
    fun restoreMiningDefaultsProducesAPrefilledDraft() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeAppSettingsRepository(
                    AppSettings(audioPaddingSeconds = 1.0, maxParallelWorkers = 12),
                )
            val viewModel = SettingsViewModel(repository, FakeResourceManager(resources("first")))
            advanceUntilIdle()

            assertEquals("1.0", viewModel.draftState.value.draft.audioPadding)
            assertEquals("12", viewModel.draftState.value.draft.workers)

            viewModel.restoreMiningDefaults()
            advanceUntilIdle()

            assertNull(repository.current.audioPaddingSeconds)
            assertNull(repository.current.maxParallelWorkers)
            assertEquals(
                EngineDefaults.AUDIO_PADDING_SECONDS.toString(),
                viewModel.draftState.value.draft.audioPadding,
            )
            assertEquals(
                EngineDefaults.MAX_PARALLEL_WORKERS.toString(),
                viewModel.draftState.value.draft.workers,
            )
            assertFalse(viewModel.draftState.value.dirty)
        }

    @Test
    fun `export writes the current settings as a backup document`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings(deckName = "Mining"))
            val io = RecordingDocumentIo()
            val viewModel =
                SettingsViewModel(
                    repository = repository,
                    resources = FakeResourceManager(resources("first")),
                    backupWriter = io,
                    appVersion = "0.4.1",
                )
            advanceUntilIdle()

            viewModel.exportSettings("content://out.json")
            viewModel.backupState.first { it !is SettingsBackupState.Working }
            advanceUntilIdle()

            assertTrue(io.written.contains("Mining"))
            assertEquals(SettingsBackupState.Exported, viewModel.backupState.value)
        }

    @Test
    fun `export flushes the latest debounced draft before encoding`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings(tags = "old"))
            val io = RecordingDocumentIo()
            val viewModel =
                SettingsViewModel(
                    repository = repository,
                    resources = FakeResourceManager(resources("first")),
                    backupWriter = io,
                )
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(tags = "latest"))
            runCurrent()
            assertEquals(0, repository.writeCount)

            viewModel.exportSettings("content://out.json")
            viewModel.backupState.first { it !is SettingsBackupState.Working }
            advanceUntilIdle()

            val exported =
                with(SettingsBackupCodec) {
                    parse(io.written.toString()).applyTo(AppSettings()).settings
                }
            assertEquals("latest", repository.current.tags)
            assertEquals("latest", exported.tags)
            assertEquals(SettingsBackupState.Exported, viewModel.backupState.value)
        }

    @Test
    fun `export waits for an in-flight autosave before encoding`() =
        runTest(mainDispatcherRule.dispatcher) {
            val writeStarted = CompletableDeferred<Unit>()
            val allowWrite = CompletableDeferred<Unit>()
            val repository =
                FakeAppSettingsRepository(AppSettings(jishoEnabled = false)) { attempt ->
                    if (attempt == 1) {
                        writeStarted.complete(Unit)
                        allowWrite.await()
                    }
                }
            val io = RecordingDocumentIo()
            val viewModel =
                SettingsViewModel(
                    repository = repository,
                    resources = FakeResourceManager(resources("first")),
                    backupWriter = io,
                )
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            runCurrent()
            assertTrue(writeStarted.isCompleted)

            viewModel.exportSettings("content://out.json")
            runCurrent()

            assertEquals(SettingsBackupState.Working, viewModel.backupState.value)
            assertTrue(io.written.isEmpty())

            allowWrite.complete(Unit)
            viewModel.backupState.first { it !is SettingsBackupState.Working }
            advanceUntilIdle()

            val exported =
                with(SettingsBackupCodec) {
                    parse(io.written.toString()).applyTo(AppSettings()).settings
                }
            assertTrue(repository.current.jishoEnabled)
            assertTrue(exported.jishoEnabled)
            assertEquals(SettingsBackupState.Exported, viewModel.backupState.value)
        }

    @Test
    fun `export failure does not write a stale backup after autosave failure`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeAppSettingsRepository(
                    initial = AppSettings(jishoEnabled = false),
                    failuresRemaining = 2,
                )
            val io = RecordingDocumentIo()
            val viewModel =
                SettingsViewModel(
                    repository = repository,
                    resources = FakeResourceManager(resources("first")),
                    backupWriter = io,
                )
            advanceUntilIdle()

            viewModel.updateDraft(viewModel.draftState.value.draft.copy(jisho = true))
            runCurrent()
            assertEquals(1, repository.attemptedWriteCount)

            viewModel.exportSettings("content://out.json")
            viewModel.backupState.first { it !is SettingsBackupState.Working }
            advanceUntilIdle()

            assertEquals(2, repository.attemptedWriteCount)
            assertFalse(repository.current.jishoEnabled)
            assertTrue(io.written.isEmpty())
            assertTrue(viewModel.backupState.value is SettingsBackupState.Failed)
        }

    @Test
    fun `import overlays the file and leaves absent keys alone`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings(deckName = "Mining"))
            val io =
                RecordingDocumentIo(
                    content =
                        """{"ankiMinerAndroidSettings":1,"appVersion":"0.4.1","schemaVersion":2,"settings":{"theme_mode":"light"}}""",
                )
            val viewModel =
                SettingsViewModel(
                    repository = repository,
                    resources = FakeResourceManager(resources("first")),
                    documentReader = io,
                )
            advanceUntilIdle()

            viewModel.importSettings("content://in.json")
            viewModel.backupState.first { it !is SettingsBackupState.Working }
            advanceUntilIdle()

            assertEquals(ThemeMode.LIGHT, repository.current.theme)
            assertEquals("Mining", repository.current.deckName)
            assertEquals(
                SettingsBackupState.Imported(applied = 1, ignored = 0, rejected = 0),
                viewModel.backupState.value,
            )
        }

    @Test
    fun `importing a file that is not a backup reports it and writes nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val io = RecordingDocumentIo(content = """{"hello":"world"}""")
            val viewModel =
                SettingsViewModel(
                    repository = repository,
                    resources = FakeResourceManager(resources("first")),
                    documentReader = io,
                )
            advanceUntilIdle()
            val writesBeforeImport = repository.writeCount

            viewModel.importSettings("content://in.json")
            viewModel.backupState.first { it !is SettingsBackupState.Working }
            advanceUntilIdle()

            assertEquals(writesBeforeImport, repository.writeCount)
            assertTrue(viewModel.backupState.value is SettingsBackupState.Failed)
        }

    @Test
    fun `importing an oversized document reports it as too large`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val reader =
                SettingsDocumentReader {
                    throw SettingsBackupException(SettingsBackupFailure.TOO_LARGE)
                }
            val viewModel =
                SettingsViewModel(
                    repository = repository,
                    resources = FakeResourceManager(resources("first")),
                    documentReader = reader,
                )
            advanceUntilIdle()

            viewModel.importSettings("content://in.json")
            viewModel.backupState.first { it !is SettingsBackupState.Working }
            advanceUntilIdle()

            val state = viewModel.backupState.value
            assertTrue(state is SettingsBackupState.Failed)
            assertEquals(
                R.string.settings_backup_too_large,
                (state as SettingsBackupState.Failed).message.resourceId,
            )
            assertEquals(0, repository.writeCount)
        }

    @Test
    fun `a failing document writer reports an export failure`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeAppSettingsRepository(AppSettings())
            val writer = SettingsBackupWriter { _, _ -> throw IOException("write failed") }
            val viewModel =
                SettingsViewModel(
                    repository = repository,
                    resources = FakeResourceManager(resources("first")),
                    backupWriter = writer,
                )
            advanceUntilIdle()

            viewModel.exportSettings("content://out.json")
            viewModel.backupState.first { it !is SettingsBackupState.Working }
            advanceUntilIdle()

            val state = viewModel.backupState.value
            assertTrue(state is SettingsBackupState.Failed)
            assertEquals(
                R.string.settings_backup_export_failed,
                (state as SettingsBackupState.Failed).message.resourceId,
            )
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
            rebuildSourcePath = null,
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
            rebuildSourcePath = null,
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

    private class RecordingDocumentIo(var content: String = "") :
        SettingsDocumentReader,
        SettingsBackupWriter {
        val written = StringBuilder()

        override suspend fun read(uri: String): String = content

        override suspend fun write(uri: String, bytes: ByteArray) {
            written.append(bytes.toString(Charsets.UTF_8))
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

        override suspend fun preflightAudioPack(uri: String) =
            listOf(AudioPackCandidate("jpod", "jpod_files", "ajt"))

        override suspend fun importAudioPack(
            uri: String,
            pack: AudioPackCandidate,
            replace: Boolean,
        ) = Unit

        override suspend fun discardAudioPackPreflight() = Unit

        override suspend fun importKnownWords(
            uri: String,
            format: KnownWordsSourceFormat,
        ) = Unit

        override suspend fun importWordList(uri: String, kind: WordListKind) = Unit

        override suspend fun removeWordList(kind: WordListKind) = Unit

        override fun wordListPath(kind: WordListKind): String? = null

        override suspend fun previewKnownWords(uri: String, fileKind: ResourceImportFileKind) = Unit

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
