package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.anki.provider.ModelSummary
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.AnkiSetupManager
import com.ankiminer.android.data.anki.AnkiSetupManagerState
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.resources.KnownWordsResetScope
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureRetry
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.AppSettingsValidator
import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.mining.MiningRunAdmissionState
import com.ankiminer.android.mining.NotificationPermissionReadiness
import com.ankiminer.android.localization.testStringResourceResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `picker result after recreation keeps the launched frequency import metadata`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val resources = FakeResourceManager()
            val repository = FakeSettingsRepository(AppSettings())
            val original =
                viewModel(
                    repository = repository,
                    setup = FakeAnkiSetupManager(emptyList()),
                    resources = resources,
                    savedStateHandle = savedState,
                )
            advanceUntilIdle()
            original.setFrequencySourceName("Custom TSV")
            original.setFrequencyFormat(FrequencySourceFormat.TSV)
            advanceUntilIdle()

            assertTrue(original.beginFrequencyPicker())

            val restored =
                viewModel(
                    repository = repository,
                    setup = FakeAnkiSetupManager(emptyList()),
                    resources = resources,
                    savedStateHandle = savedState,
                )
            advanceUntilIdle()
            restored.onFrequencyPicked("content://test/frequency.tsv")
            advanceUntilIdle()

            assertEquals(
                listOf(Triple("content://test/frequency.tsv", "custom-tsv", false)),
                resources.frequencyImports,
            )
            assertEquals(listOf("Custom TSV"), resources.frequencySourceNames)
            assertEquals(listOf(FrequencySourceFormat.TSV), resources.frequencyFormats)
        }

    @Test
    fun `same note type reselection performs no settings write or refresh`() =
        runTest(mainDispatcherRule.dispatcher) {
            val original = linkedMapOf("word" to "Expression", "sentence" to "Custom Sentence")
            val repository =
                FakeSettingsRepository(AppSettings(noteType = "Lapis", fieldMap = original))
            val setup = FakeAnkiSetupManager(listOf(model("Lapis", "Expression", "Custom Sentence")))
            val viewModel = viewModel(repository, setup)
            advanceUntilIdle()

            viewModel.selectNoteType("Lapis")
            advanceUntilIdle()

            assertEquals(0, repository.writeCount)
            assertEquals(0, setup.refreshCount)
            assertEquals(original, repository.current.fieldMap)
        }

    @Test
    fun `changed note type preserves compatible manual mapping`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeSettingsRepository(
                    AppSettings(
                        noteType = "Old",
                        fieldMap =
                            linkedMapOf(
                                "word" to "Expression",
                                "sentence" to "Custom Sentence",
                                "definition" to "Meaning",
                            ),
                    ),
                )
            val setup =
                FakeAnkiSetupManager(
                    listOf(
                        model("Old", "Expression"),
                        model(
                            "New",
                            "Expression",
                            "Sentence",
                            "Custom Sentence",
                            "Meaning",
                        ),
                    ),
                )
            val viewModel = viewModel(repository, setup)
            advanceUntilIdle()

            viewModel.selectNoteType("New")
            advanceUntilIdle()

            assertEquals("New", repository.current.noteType)
            assertEquals("Custom Sentence", repository.current.fieldMap["sentence"])
            assertEquals("Meaning", repository.current.fieldMap["definition"])
            assertEquals(1, repository.writeCount)
            assertEquals(1, setup.refreshCount)
        }

    @Test
    fun `manual duplicate assignment is blocked before persistence`() =
        runTest(mainDispatcherRule.dispatcher) {
            val original = linkedMapOf("word" to "Expression", "sentence" to "Sentence")
            val repository =
                FakeSettingsRepository(AppSettings(noteType = "Lapis", fieldMap = original))
            val setup =
                FakeAnkiSetupManager(
                    listOf(model("Lapis", "Expression", "Sentence", "Meaning")),
                )
            val viewModel = viewModel(repository, setup)
            advanceUntilIdle()

            viewModel.setFieldMapping("definition", "Sentence")
            advanceUntilIdle()

            assertEquals(0, repository.writeCount)
            assertEquals(0, setup.refreshCount)
            assertEquals(original, repository.current.fieldMap)
        }

    @Test
    fun `blank word assignment is blocked without a settings write or removal`() =
        runTest(mainDispatcherRule.dispatcher) {
            val original = linkedMapOf("word" to "Expression", "sentence" to "Sentence")
            val repository =
                FakeSettingsRepository(AppSettings(noteType = "Lapis", fieldMap = original))
            val setup =
                FakeAnkiSetupManager(
                    listOf(model("Lapis", "Expression", "Sentence", "Meaning")),
                )
            val viewModel = viewModel(repository, setup)
            advanceUntilIdle()

            viewModel.setFieldMapping("word", "")
            advanceUntilIdle()

            assertEquals(0, repository.writeCount)
            assertEquals(0, setup.refreshCount)
            assertEquals(original, repository.current.fieldMap)
        }

    @Test
    fun `discarded mappings are published for a clear UI explanation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeSettingsRepository(
                    AppSettings(
                        noteType = "Old",
                        fieldMap = linkedMapOf("word" to "Old Front", "source" to "Old Source"),
                    ),
                )
            val setup = FakeAnkiSetupManager(listOf(model("New", "Expression", "Sentence")))
            val viewModel = viewModel(repository, setup)
            advanceUntilIdle()

            viewModel.selectNoteType("New")
            advanceUntilIdle()

            assertEquals(
                listOf("word", "source"),
                viewModel.uiState.value.fieldMapChanges.map { it.logicalKey },
            )
            assertEquals("Expression", repository.current.fieldMap["word"])
            assertEquals("", repository.current.fieldMap["source"])
        }

    @Test
    fun `card type preselects the conventional marker only when the note type has it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeSettingsRepository(
                    AppSettings(noteType = "JPMN", fieldMap = mapOf("word" to "Word")),
                )
            val viewModel =
                viewModel(
                    repository,
                    FakeAnkiSetupManager(listOf(model("JPMN", "Word", "IsClickCard"))),
                )
            advanceUntilIdle()

            viewModel.selectCardType(CardType.CLICK)
            advanceUntilIdle()

            assertEquals(CardType.CLICK, repository.current.cardType)
            assertEquals("IsClickCard", repository.current.cardTypeMarkerField)

            // No IsAudioCard on this note type: the mode is stored with no marker, which the
            // snapshot mapper emits as off until the user picks a field.
            viewModel.selectCardType(CardType.AUDIO)
            advanceUntilIdle()

            assertEquals(CardType.AUDIO, repository.current.cardType)
            assertEquals(null, repository.current.cardTypeMarkerField)
        }

    @Test
    fun `a marker field already taken by the field map is not persisted`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeSettingsRepository(
                    AppSettings(
                        noteType = "JPMN",
                        fieldMap = mapOf("word" to "Word", "sentence" to "IsClickCard"),
                        cardType = CardType.CLICK,
                    ),
                )
            val viewModel =
                viewModel(
                    repository,
                    FakeAnkiSetupManager(listOf(model("JPMN", "Word", "IsClickCard"))),
                )
            advanceUntilIdle()

            viewModel.setCardTypeMarkerField("IsClickCard")
            advanceUntilIdle()

            assertEquals(0, repository.writeCount)
            assertEquals(null, repository.current.cardTypeMarkerField)
        }

    @Test
    fun `an active marker destination cannot be assigned to a logical field`() =
        runTest(mainDispatcherRule.dispatcher) {
            val original = mapOf("word" to "Word")
            val repository =
                FakeSettingsRepository(
                    AppSettings(
                        noteType = "JPMN",
                        fieldMap = original,
                        cardType = CardType.CLICK,
                        cardTypeMarkerField = "IsClickCard",
                    ),
                )
            val viewModel =
                viewModel(
                    repository,
                    FakeAnkiSetupManager(listOf(model("JPMN", "Word", "IsClickCard", "Meaning"))),
                )
            advanceUntilIdle()

            viewModel.setFieldMapping("definition", "IsClickCard")
            advanceUntilIdle()

            assertEquals(original, repository.current.fieldMap)
            assertEquals("IsClickCard", repository.current.cardTypeMarkerField)
            assertEquals(0, repository.writeCount)
        }

    @Test
    fun `note type switch clears a marker absent from the selected model`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeSettingsRepository(
                    AppSettings(
                        noteType = "Old",
                        fieldMap = mapOf("word" to "Word"),
                        cardType = CardType.CLICK,
                        cardTypeMarkerField = "IsClickCard",
                    ),
                )
            val setup =
                FakeAnkiSetupManager(
                    listOf(
                        model("Old", "Word", "IsClickCard"),
                        model("New", "Expression", "Meaning"),
                    ),
                )
            val viewModel = viewModel(repository, setup)
            advanceUntilIdle()

            viewModel.selectNoteType("New")
            advanceUntilIdle()

            assertEquals("New", repository.current.noteType)
            assertEquals(null, repository.current.cardTypeMarkerField)
            assertEquals(null, setup.lastCardTypeMarkerField)
        }

    @Test
    fun `rapid field mapping edits compose against the persisted map`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeSettingsRepository(
                    AppSettings(noteType = "Lapis", fieldMap = mapOf("word" to "Expression")),
                )
            val setup =
                FakeAnkiSetupManager(
                    listOf(model("Lapis", "Expression", "Meaning", "Audio")),
                )
            val viewModel = viewModel(repository, setup)
            advanceUntilIdle()

            viewModel.setFieldMapping("definition", "Meaning")
            viewModel.setFieldMapping("audio", "Audio")
            advanceUntilIdle()

            assertEquals("Meaning", repository.current.fieldMap["definition"])
            assertEquals("Audio", repository.current.fieldMap["audio"])
            assertEquals(2, repository.writeCount)
            assertEquals(repository.current.fieldMap, setup.lastFieldMap)
        }

    @Test
    fun `Anki target write failures stay contained and the same action can retry`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository =
                FakeSettingsRepository(
                    AppSettings(noteType = "Lapis", fieldMap = mapOf("word" to "Expression")),
                ).apply { failWrites = true }
            val setup =
                FakeAnkiSetupManager(
                    listOf(model("Lapis", "Expression", "Meaning", "IsClickCard")),
                )
            val viewModel = viewModel(repository, setup)
            advanceUntilIdle()

            viewModel.setFieldMapping("definition", "Meaning")
            advanceUntilIdle()
            assertEquals(null, repository.current.fieldMap["definition"])
            assertEquals(0, setup.refreshCount)

            repository.failWrites = false
            viewModel.setFieldMapping("definition", "Meaning")
            advanceUntilIdle()

            assertEquals("Meaning", repository.current.fieldMap["definition"])
            assertEquals(2, repository.writeAttempts)
            assertEquals(1, setup.refreshCount)
        }

    @Test
    fun `deck selection persists explicit existing and create-or-use choices`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeSettingsRepository(AppSettings())
            val setup = FakeAnkiSetupManager(emptyList(), deckNames = listOf("Japanese"))
            val viewModel = viewModel(repository, setup)
            advanceUntilIdle()

            viewModel.selectDeck("Japanese")
            advanceUntilIdle()
            assertEquals("Japanese", repository.current.deckName)

            viewModel.selectDeck("Anki Miner")
            advanceUntilIdle()
            assertEquals("Anki Miner", repository.current.deckName)
            assertEquals(2, repository.writeCount)
        }

    @Test
    fun `deck persistence failure is visible and retry eventually persists`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeSettingsRepository(AppSettings()).apply { failWrites = true }
            val viewModel =
                viewModel(
                    repository,
                    FakeAnkiSetupManager(emptyList(), deckNames = listOf("Japanese")),
                )
            advanceUntilIdle()

            viewModel.selectDeck("Japanese")
            advanceUntilIdle()

            assertEquals(DeckPersistenceStatus.FAILED, viewModel.uiState.value.deckPersistence)
            assertEquals("Japanese", viewModel.uiState.value.failedDeckName)
            assertEquals(null, repository.current.deckName)

            repository.failWrites = false
            viewModel.retryDeckSelection()
            advanceUntilIdle()

            assertEquals(DeckPersistenceStatus.IDLE, viewModel.uiState.value.deckPersistence)
            assertEquals(null, viewModel.uiState.value.failedDeckName)
            assertEquals("Japanese", repository.current.deckName)
            assertEquals(2, repository.writeAttempts)
        }

    @Test
    fun `wizard persistence failure stays visible and retry eventually persists`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeSettingsRepository(AppSettings()).apply { failWrites = true }
            val viewModel = viewModel(repository, FakeAnkiSetupManager(emptyList()))
            advanceUntilIdle()

            viewModel.markWizardSeen()
            advanceUntilIdle()

            assertEquals(WizardCompletionStatus.FAILED, viewModel.uiState.value.wizardCompletion)
            assertEquals(false, viewModel.uiState.value.wizardSeen)

            repository.failWrites = false
            viewModel.retryWizardCompletion()
            advanceUntilIdle()

            assertEquals(WizardCompletionStatus.PERSISTED, viewModel.uiState.value.wizardCompletion)
            assertEquals(true, viewModel.uiState.value.wizardSeen)
            assertEquals(2, repository.writeAttempts)
        }

    @Test
    fun `known-word actions use preview and scoped management operations`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeSettingsRepository(AppSettings())
            val setup = FakeAnkiSetupManager(emptyList())
            val resources = FakeResourceManager()
            val viewModel = viewModel(repository, setup, resources)
            advanceUntilIdle()

            viewModel.importKnownWords("content://known")
            viewModel.confirmKnownWordsImport()
            viewModel.setKnownWordsSearch("猫")
            advanceUntilIdle()
            viewModel.searchKnownWords()
            viewModel.loadMoreKnownWords()
            viewModel.removeKnownWord("犬")
            viewModel.resetKnownWords(KnownWordsResetScope.USER)
            viewModel.exportKnownWords("content://export")
            viewModel.dismissKnownWordsImportPreview()
            advanceUntilIdle()

            assertEquals(listOf("content://known" to KnownWordsSourceFormat.JSON), resources.previewCalls)
            assertEquals(emptyList<Pair<String, KnownWordsSourceFormat>>(), resources.importCalls)
            assertEquals(1, resources.confirmCount)
            assertEquals(listOf("猫" to false, "猫" to true), resources.searchCalls)
            assertEquals(listOf(listOf("犬")), resources.removeCalls)
            assertEquals(listOf(KnownWordsResetScope.USER), resources.resetCalls)
            assertEquals(listOf("content://export"), resources.exportCalls)
            assertEquals(1, resources.dismissCount)
        }

    @Test
    fun `known-word retry delegates to repository mutation contract instead of search`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setFailure(
                ResourceFailure(
                    code = "known_words_remove_failed",
                    message = "failed",
                    retryable = true,
                    origin = ResourceFailureOrigin.KNOWN_WORDS,
                    retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
                    knownWordsOperation = null,
                ),
            )
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                )
            advanceUntilIdle()

            model.retryResourceFailure()
            advanceUntilIdle()

            assertEquals(1, resources.knownWordsRetryCount)
            assertEquals(emptyList<Pair<String, Boolean>>(), resources.searchCalls)
        }

    @Test
    fun `known-word import retry delegates to the retained import mutation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setFailure(
                ResourceFailure(
                    code = "known_words_import_failed",
                    message = "failed",
                    retryable = true,
                    origin = ResourceFailureOrigin.KNOWN_WORDS,
                    retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
                    knownWordsOperation = KnownWordsFailureOperation.IMPORT,
                ),
            )
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                )
            advanceUntilIdle()

            model.retryResourceFailure()
            advanceUntilIdle()

            assertEquals(1, resources.knownWordsRetryCount)
            assertEquals(emptyList<Pair<String, Boolean>>(), resources.searchCalls)
        }

    @Test
    fun `a fresh frequency name imports immediately under its derived id`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            val model = viewModel(FakeSettingsRepository(AppSettings()), FakeAnkiSetupManager(emptyList()), resources)
            advanceUntilIdle()

            model.setFrequencySourceName("JPDB v2.1")
            advanceUntilIdle()
            model.importFrequencySource("content://import.zip")
            advanceUntilIdle()

            assertEquals(listOf(Triple("content://import.zip", "jpdb-v2-1", false)), resources.frequencyImports)
            assertEquals(null, model.uiState.value.pendingReplace)
        }

    @Test
    fun `frequency import dispatches pinned NFC display name`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                )
            advanceUntilIdle()

            model.setFrequencySourceName("\u306F\u3099")
            model.importFrequencySource("content://import.csv")
            advanceUntilIdle()

            assertEquals(listOf("\u3070"), resources.frequencySourceNames)
        }

    @Test
    fun `a colliding frequency name asks before importing and dispatches nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setInstalledFrequencySources(listOf(installedFrequency("jpdb-v2-1", "JPDB v2.1")))
            val model = viewModel(FakeSettingsRepository(AppSettings()), FakeAnkiSetupManager(emptyList()), resources)
            advanceUntilIdle()

            model.setFrequencySourceName("JPDB v2.1")
            advanceUntilIdle()
            model.importFrequencySource("content://import.zip")
            advanceUntilIdle()

            assertEquals(emptyList<Triple<String, String, Boolean>>(), resources.frequencyImports)
            val pending = requireNotNull(model.uiState.value.pendingReplace)
            assertEquals(ResourceReplaceKind.FREQUENCY, pending.kind)
            assertEquals("JPDB v2.1", pending.installedLabel)
        }

    /**
     * The migration case. A source installed before ids were derived lives under a fixed id the
     * current derivation would never produce, so confirming must write under *its* id - otherwise
     * the old directory is orphaned and its priority-chain entry is dropped.
     */
    @Test
    fun `confirming a name-matched frequency replace writes under the existing id`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setInstalledFrequencySources(
                listOf(installedFrequency("frequency", "Imported frequency")),
            )
            val model = viewModel(FakeSettingsRepository(AppSettings()), FakeAnkiSetupManager(emptyList()), resources)
            advanceUntilIdle()

            model.setFrequencySourceName("Imported frequency")
            advanceUntilIdle()
            model.importFrequencySource("content://import.zip")
            advanceUntilIdle()
            model.confirmPendingReplace()
            advanceUntilIdle()

            assertEquals(listOf(Triple("content://import.zip", "frequency", true)), resources.frequencyImports)
            assertEquals(null, model.uiState.value.pendingReplace)
        }

    @Test
    fun `dismissing a pending replace imports nothing and clears the prompt`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setInstalledFrequencySources(listOf(installedFrequency("jpdb-v2-1", "JPDB v2.1")))
            val model = viewModel(FakeSettingsRepository(AppSettings()), FakeAnkiSetupManager(emptyList()), resources)
            advanceUntilIdle()

            model.setFrequencySourceName("JPDB v2.1")
            advanceUntilIdle()
            model.importFrequencySource("content://import.zip")
            advanceUntilIdle()
            model.dismissPendingReplace()
            advanceUntilIdle()

            assertEquals(emptyList<Triple<String, String, Boolean>>(), resources.frequencyImports)
            assertEquals(null, model.uiState.value.pendingReplace)
        }

    @Test
    fun `pitch imports directly and asks only when the name lands on an installed slot`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            val model = viewModel(FakeSettingsRepository(AppSettings()), FakeAnkiSetupManager(emptyList()), resources)
            advanceUntilIdle()

            model.setPitchSourceName("Kanjium")
            advanceUntilIdle()
            model.importPitchAccent("content://first.csv")
            advanceUntilIdle()
            assertEquals(listOf("content://first.csv" to false), resources.pitchImports)

            // Pitch is a chain of named slots now: only a name that derives onto an
            // installed slot collides.
            resources.setInstalledPitchSources(listOf(installedPitch("kanjium", "Kanjium")))
            advanceUntilIdle()
            model.importPitchAccent("content://second.csv")
            advanceUntilIdle()
            assertEquals(listOf("content://first.csv" to false), resources.pitchImports)
            assertEquals("Kanjium", model.uiState.value.pendingReplace?.installedLabel)

            // A different name is a new slot, so it imports without asking.
            model.setPitchSourceName("NHK 2016")
            advanceUntilIdle()
            model.importPitchAccent("content://third.csv")
            advanceUntilIdle()
            assertEquals(
                listOf("content://first.csv" to false, "content://third.csv" to false),
                resources.pitchImports,
            )
        }

    @Test
    fun `display names are trimmed and stripped of control characters for the bridge`() =
        runTest(mainDispatcherRule.dispatcher) {
            val model = viewModel(FakeSettingsRepository(AppSettings()), FakeAnkiSetupManager(emptyList()))
            advanceUntilIdle()

            model.setFrequencySourceName("  JPDB\tv2  ")
            advanceUntilIdle()

            assertEquals("JPDBv2", model.uiState.value.frequencySourceName)
        }

    @Test
    fun `display name UTF-8 truncation never splits an astral scalar`() =
        runTest(mainDispatcherRule.dispatcher) {
            val model = viewModel(FakeSettingsRepository(AppSettings()), FakeAnkiSetupManager(emptyList()))
            advanceUntilIdle()

            model.setFrequencySourceName("a".repeat(509) + "😀")
            advanceUntilIdle()
            assertEquals("a".repeat(509), model.uiState.value.frequencySourceName)

            model.setPitchSourceName("a".repeat(508) + "😀")
            advanceUntilIdle()
            assertEquals("a".repeat(508) + "😀", model.uiState.value.pitchSourceName)
        }

    @Test
    fun `picker context survives ViewModel recreation and waits for startup recovery`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val firstResources = FakeResourceManager()
            val first =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    firstResources,
                    savedState,
                )
            advanceUntilIdle()
            first.setPitchSourceName("Kanjium CSV")
            first.setPitchFormat(PitchAccentSourceFormat.CSV)
            assertEquals(true, first.beginPitchPicker())

            val restoredResources = FakeResourceManager()
            restoredResources.setStartupReadiness(ResourceStartupReadiness.RECOVERING)
            val restored =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    restoredResources,
                    savedState,
                )
            restored.onPitchPicked("content://pitch.csv")
            advanceUntilIdle()
            assertEquals(emptyList<Pair<String, Boolean>>(), restoredResources.pitchImports)

            restoredResources.setStartupReadiness(ResourceStartupReadiness.READY)
            advanceUntilIdle()

            assertEquals(listOf("content://pitch.csv" to false), restoredResources.pitchImports)
            assertEquals(listOf("Kanjium CSV"), restoredResources.pitchSourceNames)
            assertEquals(listOf(PitchAccentSourceFormat.CSV), restoredResources.pitchFormats)
        }

    @Test
    fun `pending picker collision survives recreation with immutable replace context`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val firstResources =
                FakeResourceManager().apply {
                    setInstalledPitchSources(listOf(installedPitch("kanjium", "Kanjium")))
                }
            val first =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    firstResources,
                    savedState,
                )
            advanceUntilIdle()
            first.setPitchSourceName("Kanjium")
            first.setPitchFormat(PitchAccentSourceFormat.CSV)
            assertEquals(true, first.beginPitchPicker())
            first.onPitchPicked("content://kanjium.csv")
            advanceUntilIdle()
            assertEquals(ResourceReplaceKind.PITCH, first.uiState.value.pendingReplace?.kind)

            val restoredResources = FakeResourceManager()
            val restored =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    restoredResources,
                    savedState,
                )
            advanceUntilIdle()
            restored.confirmPendingReplace()
            advanceUntilIdle()

            assertEquals(listOf("content://kanjium.csv" to true), restoredResources.pitchImports)
            assertEquals(listOf("Kanjium"), restoredResources.pitchSourceNames)
            assertEquals(listOf(PitchAccentSourceFormat.CSV), restoredResources.pitchFormats)
            assertEquals(null, restored.uiState.value.pendingReplace)
        }

    private fun installedFrequency(sourceId: String, sourceName: String) =
        InstalledFrequencySource(
            sourceId = sourceId,
            sourceName = sourceName,
            format = "zip",
            entryCount = 100,
            schemaOk = true,
            schemaVersion = 1,
            isCategorical = false,
        )

    private fun installedPitch(
        sourceId: String,
        sourceName: String,
    ) = InstalledPitchSource(
        sourceId = sourceId,
        sourceName = sourceName,
        sourceRevision = "2026-07-17",
        format = "csv",
        entryCount = 1000,
        schemaOk = true,
        schemaVersion = 1,
    )

    private fun viewModel(
        repository: FakeSettingsRepository,
        setup: FakeAnkiSetupManager,
        resources: FakeResourceManager = FakeResourceManager(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): SetupViewModel =
        SetupViewModel(
            resources = resources,
            settingsRepository = repository,
            ankiSetup = setup,
            pythonReadiness = MutableStateFlow(PythonRuntimeReadiness.Pending),
            miningAdmission =
                MutableStateFlow(
                    MiningRunAdmissionState(
                        anki = AnkiProviderReadiness.NotChecked,
                        ankiRecovery = AnkiRecoveryReadiness.NotChecked,
                        notifications = NotificationPermissionReadiness.READY,
                        target = AnkiMiningTargetReadiness.NotChecked,
                    ),
                ),
            runtimeWorkState = MutableStateFlow<RuntimeWorkCoordinator.Kind?>(null),
            refreshExternalReadiness = {},
            strings = testStringResourceResolver,
            savedStateHandle = savedStateHandle,
        )

    private fun model(name: String, vararg fields: String) =
        ModelSummary(id = name.hashCode().toLong(), name = name, fieldNames = fields.toList())

    private class FakeSettingsRepository(initial: AppSettings) : AppSettingsRepository {
        private val mutableSettings = MutableStateFlow(AppSettingsValidator.validate(initial))
        override val settings: Flow<AppSettings> = mutableSettings.asStateFlow()
        var writeCount = 0
            private set
        var writeAttempts = 0
            private set
        var failWrites = false
        val current: AppSettings
            get() = mutableSettings.value

        override suspend fun update(settings: AppSettings) {
            writeAttempts += 1
            if (failWrites) error("settings unavailable")
            mutableSettings.value = AppSettingsValidator.validate(settings)
            writeCount += 1
        }

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            writeAttempts += 1
            if (failWrites) error("settings unavailable")
            mutableSettings.value = AppSettingsValidator.validate(transform(mutableSettings.value))
            writeCount += 1
        }
    }

    private class FakeAnkiSetupManager(
        models: List<ModelSummary>,
        deckNames: List<String> = emptyList(),
    ) : AnkiSetupManager {
        private val mutableState =
            MutableStateFlow(
                AnkiSetupManagerState(
                    availableNoteTypes = models,
                    availableDeckNames = deckNames,
                ),
            )
        override val state: StateFlow<AnkiSetupManagerState> = mutableState.asStateFlow()
        var refreshCount = 0
            private set
        var lastFieldMap: Map<String, String> = emptyMap()
            private set
        var lastCardTypeMarkerField: String? = null
            private set

        override fun refresh(
            noteType: String?,
            fieldMap: Map<String, String>,
            cardTypeMarkerField: String?,
        ) {
            refreshCount += 1
            lastFieldMap = fieldMap
            lastCardTypeMarkerField = cardTypeMarkerField
        }

        override fun reconcileInterruptedWork() = Unit

        override fun performRemediation(command: AnkiRemediationCommand) = Unit

        override fun dismissFailure() = Unit
    }

    private class FakeResourceManager : ResourceManager {
        val previewCalls = mutableListOf<Pair<String, KnownWordsSourceFormat>>()
        val importCalls = mutableListOf<Pair<String, KnownWordsSourceFormat>>()
        var confirmCount = 0
        var dismissCount = 0
        var knownWordsRetryCount = 0
        val searchCalls = mutableListOf<Pair<String, Boolean>>()
        val removeCalls = mutableListOf<List<String>>()
        val resetCalls = mutableListOf<KnownWordsResetScope>()
        val exportCalls = mutableListOf<String>()

        val frequencyImports = mutableListOf<Triple<String, String, Boolean>>()
        val frequencySourceNames = mutableListOf<String>()
        val frequencyFormats = mutableListOf<FrequencySourceFormat>()
        val pitchImports = mutableListOf<Pair<String, Boolean>>()
        val pitchSourceNames = mutableListOf<String>()
        val pitchFormats = mutableListOf<PitchAccentSourceFormat>()
        private val mutableState =
            MutableStateFlow(
                ResourceManagerState(startupReadiness = ResourceStartupReadiness.READY),
            )

        override val state: StateFlow<ResourceManagerState> = mutableState.asStateFlow()

        fun setInstalledFrequencySources(sources: List<InstalledFrequencySource>) {
            mutableState.value = mutableState.value.copy(frequencySources = sources)
        }

        fun setInstalledPitchSources(sources: List<InstalledPitchSource>) {
            mutableState.value = mutableState.value.copy(pitchSources = sources)
        }

        fun setFailure(failure: ResourceFailure) {
            mutableState.value = mutableState.value.copy(failure = failure)
        }

        fun setStartupReadiness(readiness: ResourceStartupReadiness) {
            mutableState.value = mutableState.value.copy(startupReadiness = readiness)
        }

        override suspend fun recoverAndRefresh() = Unit

        override suspend fun installUniDic() = Unit

        override suspend fun installCatalogDictionary(resourceId: String, replace: Boolean) = Unit

        override suspend fun importCustomDictionary(uri: String, slotId: String, replace: Boolean) = Unit

        override suspend fun importFrequencySource(
            uri: String,
            sourceId: String,
            sourceName: String,
            format: FrequencySourceFormat,
            replace: Boolean,
        ) {
            frequencyImports += Triple(uri, sourceId, replace)
            frequencySourceNames += sourceName
            frequencyFormats += format
        }

        override suspend fun importPitchAccent(
            uri: String,
            sourceId: String,
            sourceName: String,
            format: PitchAccentSourceFormat,
            replace: Boolean,
        ) {
            pitchImports += uri to replace
            pitchSourceNames += sourceName
            pitchFormats += format
        }

        override suspend fun importAudioPack(uri: String, packId: String, replace: Boolean) = Unit

        override suspend fun importKnownWords(uri: String, format: KnownWordsSourceFormat) {
            importCalls += uri to format
        }

        override suspend fun previewKnownWords(uri: String, format: KnownWordsSourceFormat) {
            previewCalls += uri to format
        }

        val wordListImports = mutableListOf<Pair<String, WordListKind>>()
        val wordListRemovals = mutableListOf<WordListKind>()

        override suspend fun importWordList(uri: String, kind: WordListKind) {
            wordListImports += uri to kind
        }

        override suspend fun removeWordList(kind: WordListKind) {
            wordListRemovals += kind
        }

        override fun wordListPath(kind: WordListKind): String? = null

        override suspend fun confirmKnownWordsImport() {
            confirmCount += 1
        }

        override fun dismissKnownWordsImportPreview() {
            dismissCount += 1
        }

        override suspend fun retryKnownWordsFailure() {
            knownWordsRetryCount += 1
        }

        override suspend fun searchKnownWords(query: String, loadMore: Boolean) {
            searchCalls += query to loadMore
        }

        override suspend fun removeKnownWords(words: List<String>) {
            removeCalls += words
        }

        override suspend fun resetKnownWords(scope: KnownWordsResetScope) {
            resetCalls += scope
        }

        override suspend fun exportKnownWords(uri: String) {
            exportCalls += uri
        }

        override suspend fun lookup(slotId: String, term: String) = Unit

        override fun cancelActive() = Unit

        override fun dismissFailure() = Unit
    }
}
