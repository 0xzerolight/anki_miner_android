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
import com.ankiminer.android.data.resources.AudioPackCandidate
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.InstalledAudioPack
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.resources.InstalledResourceKind
import com.ankiminer.android.data.resources.ResourceDeleteTarget
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
import com.ankiminer.android.data.resources.ResourceImportFileKind
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.RetainedResourceImport
import com.ankiminer.android.data.resources.detectResourceImportFileKind
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.AppSettingsValidator
import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.mining.MiningRunAdmissionState
import com.ankiminer.android.mining.NotificationPermissionReadiness
import com.ankiminer.android.localization.testStringResourceResolver
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                listOf(Triple("content://test/frequency.tsv", "frequency", false)),
                resources.frequencyImports,
            )
            assertEquals(listOf("frequency"), resources.frequencySourceNames)
            assertEquals(listOf(FrequencySourceFormat.TSV), resources.frequencyFormats)
        }

    @Test
    fun `requesting a delete stages the confirmation and deletes nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setInstalledPitchSources(listOf(installedPitch("kanjium", "Kanjium")))
            val model =
                viewModel(
                    repository = FakeSettingsRepository(AppSettings()),
                    setup = FakeAnkiSetupManager(emptyList()),
                    resources = resources,
                )
            advanceUntilIdle()

            model.requestResourceDelete(InstalledResourceKind.PITCH, "kanjium")
            advanceUntilIdle()

            val pending = requireNotNull(model.uiState.value.pendingDelete)
            assertEquals(InstalledResourceKind.PITCH, pending.kind)
            assertEquals("kanjium", pending.identity)
            assertEquals("Kanjium", pending.installedLabel)
            assertEquals(emptyList<Pair<InstalledResourceKind, String>>(), resources.deletedResources)
        }

    @Test
    fun `confirming a pending delete dispatches exactly one delete`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setInstalledPitchSources(listOf(installedPitch("kanjium", "Kanjium")))
            val model =
                viewModel(
                    repository = FakeSettingsRepository(AppSettings()),
                    setup = FakeAnkiSetupManager(emptyList()),
                    resources = resources,
                )
            advanceUntilIdle()
            model.requestResourceDelete(InstalledResourceKind.PITCH, "kanjium")
            advanceUntilIdle()

            model.confirmPendingDelete()
            advanceUntilIdle()

            assertEquals(listOf(InstalledResourceKind.PITCH to "kanjium"), resources.deletedResources)
            assertNull(model.uiState.value.pendingDelete)
        }

    @Test
    fun `dismissing a pending delete deletes nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setInstalledPitchSources(listOf(installedPitch("kanjium", "Kanjium")))
            val model =
                viewModel(
                    repository = FakeSettingsRepository(AppSettings()),
                    setup = FakeAnkiSetupManager(emptyList()),
                    resources = resources,
                )
            advanceUntilIdle()
            model.requestResourceDelete(InstalledResourceKind.PITCH, "kanjium")
            advanceUntilIdle()

            model.dismissPendingDelete()
            advanceUntilIdle()

            assertNull(model.uiState.value.pendingDelete)
            assertEquals(emptyList<Pair<InstalledResourceKind, String>>(), resources.deletedResources)
        }

    @Test
    fun `pending delete survives process death through saved state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val resources = FakeResourceManager()
            resources.setInstalledPitchSources(listOf(installedPitch("kanjium", "Kanjium")))
            val repository = FakeSettingsRepository(AppSettings())
            val original =
                viewModel(
                    repository = repository,
                    setup = FakeAnkiSetupManager(emptyList()),
                    resources = resources,
                    savedStateHandle = savedState,
                )
            advanceUntilIdle()
            original.requestResourceDelete(InstalledResourceKind.PITCH, "kanjium")
            advanceUntilIdle()

            val restored =
                viewModel(
                    repository = repository,
                    setup = FakeAnkiSetupManager(emptyList()),
                    resources = resources,
                    savedStateHandle = savedState,
                )
            advanceUntilIdle()

            assertEquals("kanjium", restored.uiState.value.pendingDelete?.identity)
        }

    @Test
    fun `requesting a delete for an uninstalled id is ignored`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            val model =
                viewModel(
                    repository = FakeSettingsRepository(AppSettings()),
                    setup = FakeAnkiSetupManager(emptyList()),
                    resources = resources,
                )
            advanceUntilIdle()

            model.requestResourceDelete(InstalledResourceKind.PITCH, "ghost")
            advanceUntilIdle()

            assertNull(model.uiState.value.pendingDelete)
            assertEquals(emptyList<Pair<InstalledResourceKind, String>>(), resources.deletedResources)
        }

    @Test
    fun `retry after a failed delete redispatches the same delete`() =
        runTest(mainDispatcherRule.dispatcher) {
            // A failed delete and a failed import share ResourceFailureOrigin.PITCH, whose retry
            // branch can only re-open a file picker; only the delete target can route this.
            val resources = FakeResourceManager()
            resources.setFailure(
                ResourceFailure(
                    code = "resource_cleanup_failed",
                    message = "resource:delete",
                    retryable = true,
                    origin = ResourceFailureOrigin.PITCH,
                    retry = ResourceFailureRetry(ResourceFailureAction.RETRY, targetId = "kanjium"),
                    deleteTarget = ResourceDeleteTarget(InstalledResourceKind.PITCH, "kanjium"),
                ),
            )
            val model =
                viewModel(
                    repository = FakeSettingsRepository(AppSettings()),
                    setup = FakeAnkiSetupManager(emptyList()),
                    resources = resources,
                )
            advanceUntilIdle()

            model.retryResourceFailure()
            advanceUntilIdle()

            assertEquals(listOf(InstalledResourceKind.PITCH to "kanjium"), resources.deletedResources)
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
            resources.setImportDocument(
                uri = "content://known",
                displayName = "known-words.json",
                mimeType = "application/json",
            )
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

            assertEquals(listOf("content://known"), resources.retainedResourceImports)
            assertEquals(
                listOf("content://known" to ResourceImportFileKind.JSON),
                resources.previewCalls,
            )
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

            resources.setImportDocument(
                uri = "content://import",
                displayName = "JPDB v2.1",
                mimeType = "application/octet-stream",
                leadingBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04),
            )
            model.importFrequencySource("content://import")
            advanceUntilIdle()

            assertEquals(listOf(Triple("content://import", "jpdb-v2-1", false)), resources.frequencyImports)
            assertEquals(listOf("JPDB v2.1"), resources.frequencySourceNames)
            assertEquals(listOf(FrequencySourceFormat.YOMITAN_ZIP), resources.frequencyFormats)
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

            resources.setImportDocument(
                uri = "content://import.csv",
                displayName = "\u306F\u3099.csv",
                mimeType = "text/csv",
            )
            model.importFrequencySource("content://import.csv")
            advanceUntilIdle()

            assertEquals(listOf("\u3070"), resources.frequencySourceNames)
            assertEquals(listOf(FrequencySourceFormat.CSV), resources.frequencyFormats)
        }

    @Test
    fun `a colliding frequency name asks before importing and dispatches nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setInstalledFrequencySources(listOf(installedFrequency("jpdb-v2-1", "JPDB v2.1")))
            val model = viewModel(FakeSettingsRepository(AppSettings()), FakeAnkiSetupManager(emptyList()), resources)
            advanceUntilIdle()

            resources.setImportDocument("content://import.zip", "JPDB v2.1.zip")
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

            resources.setImportDocument("content://import.zip", "Imported frequency.zip")
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

            resources.setImportDocument("content://import.zip", "JPDB v2.1.zip")
            model.importFrequencySource("content://import.zip")
            advanceUntilIdle()
            model.dismissPendingReplace()
            advanceUntilIdle()

            assertEquals(emptyList<Triple<String, String, Boolean>>(), resources.frequencyImports)
            assertEquals(null, model.uiState.value.pendingReplace)
            assertEquals(listOf("content://import.zip"), resources.releasedResourceImports)
        }

    @Test
    fun `pitch imports directly and asks only when the name lands on an installed slot`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            val model = viewModel(FakeSettingsRepository(AppSettings()), FakeAnkiSetupManager(emptyList()), resources)
            advanceUntilIdle()

            resources.setImportDocument("content://first.csv", "Kanjium.csv")
            model.importPitchAccent("content://first.csv")
            advanceUntilIdle()
            assertEquals(listOf("content://first.csv" to false), resources.pitchImports)

            // Pitch is a chain of named slots now: only a name that derives onto an
            // installed slot collides.
            resources.setInstalledPitchSources(listOf(installedPitch("kanjium", "Kanjium")))
            resources.setImportDocument("content://second.csv", "Kanjium.tsv")
            advanceUntilIdle()
            model.importPitchAccent("content://second.csv")
            advanceUntilIdle()
            assertEquals(listOf("content://first.csv" to false), resources.pitchImports)
            assertEquals("Kanjium", model.uiState.value.pendingReplace?.installedLabel)

            // A different name is a new slot, so it imports without asking.
            model.confirmPendingReplace()
            advanceUntilIdle()
            resources.setImportDocument("content://third.csv", "NHK 2016.txt", "text/plain")
            model.importPitchAccent("content://third.csv")
            advanceUntilIdle()
            assertEquals(
                listOf(
                    "content://first.csv" to false,
                    "content://second.csv" to true,
                    "content://third.csv" to false,
                ),
                resources.pitchImports,
            )
            assertEquals(
                listOf(
                    PitchAccentSourceFormat.CSV,
                    PitchAccentSourceFormat.TSV,
                    PitchAccentSourceFormat.CSV,
                ),
                resources.pitchFormats,
            )
        }

    @Test
    fun `display names are trimmed and stripped of control characters for the bridge`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                )
            advanceUntilIdle()

            resources.setImportDocument(
                uri = "content://controlled.csv",
                displayName = "  JPDB\tv2  .csv",
                mimeType = "text/csv",
            )
            model.importFrequencySource("content://controlled.csv")
            advanceUntilIdle()
            resources.setImportDocument(
                uri = "content://blank.csv",
                displayName = "\t.csv",
                mimeType = "text/csv",
            )
            model.importFrequencySource("content://blank.csv")
            advanceUntilIdle()
            resources.setImportDocument(
                uri = "content://extensionless",
                displayName = "plain text",
                mimeType = "application/octet-stream",
                leadingBytes = "word".encodeToByteArray(),
            )
            model.importFrequencySource("content://extensionless")
            advanceUntilIdle()

            assertEquals(
                listOf("JPDBv2", "Imported frequency", "plain text"),
                resources.frequencySourceNames,
            )
            assertEquals(
                listOf(
                    FrequencySourceFormat.CSV,
                    FrequencySourceFormat.CSV,
                    FrequencySourceFormat.TEXT,
                ),
                resources.frequencyFormats,
            )
        }

    @Test
    fun `display name UTF-8 truncation never splits an astral scalar`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                )
            advanceUntilIdle()

            resources.setImportDocument(
                "content://frequency.csv",
                "${"a".repeat(509)}😀.csv",
            )
            model.importFrequencySource("content://frequency.csv")
            advanceUntilIdle()
            assertEquals(listOf("a".repeat(509)), resources.frequencySourceNames)

            resources.setImportDocument(
                "content://pitch",
                "${"a".repeat(508)}😀",
                mimeType = "application/octet-stream",
                leadingBytes = "text".encodeToByteArray(),
            )
            model.importPitchAccent("content://pitch")
            advanceUntilIdle()
            assertEquals(listOf("a".repeat(508) + "😀"), resources.pitchSourceNames)
            assertEquals(listOf(PitchAccentSourceFormat.CSV), resources.pitchFormats)
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
            restoredResources.setImportDocument("content://pitch.csv", "Kanjium CSV.csv")
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
            assertEquals(true, first.beginPitchPicker())
            firstResources.setImportDocument("content://kanjium.csv", "Kanjium.csv")
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

    @Test
    fun `audio pack preflight target survives recreation without deriving again`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val firstResources =
                FakeResourceManager().apply {
                    setInstalledAudioPacks(
                        listOf(InstalledAudioPack("jpod", "JPod", "jpod_legacy", 12, true)),
                    )
                }
            val first =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    firstResources,
                    savedState,
                )
            advanceUntilIdle()

            assertTrue(first.beginAudioPackPicker())
            first.onAudioPackPicked("content://jpod.zip")
            advanceUntilIdle()

            assertEquals(listOf("content://jpod.zip"), firstResources.audioPreflights)
            assertEquals("jpod", first.uiState.value.pendingReplace?.identity)

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

            assertEquals(emptyList<String>(), restoredResources.audioPreflights)
            assertEquals(
                listOf(Triple("content://jpod.zip", "jpod", true)),
                restoredResources.audioImports,
            )
        }

    @Test
    fun `an archive holding several packs asks which one before importing any of them`() =
        runTest {
            val resources =
                FakeResourceManager().apply {
                    audioPacksInArchive =
                        listOf(
                            AudioPackCandidate("jpod", "user_files/jpod_files", "ajt"),
                            AudioPackCandidate("nhk16", "user_files/nhk16_files", "nhk16"),
                        )
                }
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                )
            advanceUntilIdle()

            assertTrue(model.beginAudioPackPicker())
            model.onAudioPackPicked("content://collection.tar.xz")
            advanceUntilIdle()

            // Nothing is extracted until the user names a pack: the upstream
            // collection is four packs and tens of gigabytes in one file.
            assertEquals(
                listOf("jpod", "nhk16"),
                model.uiState.value.audioPackChoices.map { it.packId },
            )
            assertTrue(resources.audioImports.isEmpty())

            model.chooseAudioPack("nhk16")
            advanceUntilIdle()

            assertTrue(model.uiState.value.audioPackChoices.isEmpty())
            assertEquals(
                listOf(Triple("content://collection.tar.xz", "nhk16", false)),
                resources.audioImports,
            )
            // Only the chosen subtree is extracted, so jpod's bytes are never touched.
            assertEquals(listOf("user_files/nhk16_files"), resources.audioImportPaths)
        }

    @Test
    fun `dismissing the pack choice imports nothing and drops the retained archive`() =
        runTest {
            val resources =
                FakeResourceManager().apply {
                    audioPacksInArchive =
                        listOf(
                            AudioPackCandidate("jpod", "user_files/jpod_files", "ajt"),
                            AudioPackCandidate("forvo", "user_files/forvo_files", "forvo"),
                        )
                }
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                )
            advanceUntilIdle()

            assertTrue(model.beginAudioPackPicker())
            model.onAudioPackPicked("content://collection.tar.xz")
            advanceUntilIdle()
            model.dismissAudioPackChoice()
            advanceUntilIdle()

            assertTrue(model.uiState.value.audioPackChoices.isEmpty())
            assertTrue(resources.audioImports.isEmpty())
        }

    @Test
    fun `an archive holding one pack imports it without asking`() =
        runTest {
            val resources =
                FakeResourceManager().apply {
                    audioPacksInArchive = listOf(AudioPackCandidate("nhk16", "", "nhk16"))
                }
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                )
            advanceUntilIdle()

            assertTrue(model.beginAudioPackPicker())
            model.onAudioPackPicked("content://nhk16.zip")
            advanceUntilIdle()

            assertTrue(model.uiState.value.audioPackChoices.isEmpty())
            assertEquals(
                listOf(Triple("content://nhk16.zip", "nhk16", false)),
                resources.audioImports,
            )
            // An archive that is itself the pack has no sub-path.
            assertEquals(listOf(""), resources.audioImportPaths)
        }

    @Test
    fun `a word list pick delivered to a recreated ViewModel lands on the list it was made for`() =
        runTest(mainDispatcherRule.dispatcher) {
            listOf(WordListKind.BLACKLIST, WordListKind.WHITELIST).forEach { kind ->
                // Process death between launching the picker and the result: the recreated
                // ViewModel is still recovering, which used to drop the pick on the busy guard.
                val resources = FakeResourceManager()
                resources.setStartupReadiness(ResourceStartupReadiness.RECOVERING)
                val restored =
                    viewModel(
                        FakeSettingsRepository(AppSettings()),
                        FakeAnkiSetupManager(emptyList()),
                        resources,
                        SavedStateHandle(),
                    )
                advanceUntilIdle()

                restored.importWordList("content://$kind.txt", kind)
                advanceUntilIdle()
                assertEquals(emptyList<Pair<String, WordListKind>>(), resources.wordListImports)

                resources.setStartupReadiness(ResourceStartupReadiness.READY)
                advanceUntilIdle()

                assertEquals(listOf("content://$kind.txt" to kind), resources.wordListImports)
                assertEquals(kind, restored.uiState.value.wordListTarget)
            }
        }

    @Test
    fun `a word list pick never displaces another import already queued for dispatch`() =
        runTest(mainDispatcherRule.dispatcher) {
            // One pending slot: the pitch pick is confirmed work waiting on startup, and a
            // word-list result is the only one that can arrive without its own picker gate.
            val resources = FakeResourceManager()
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                    SavedStateHandle(),
                )
            advanceUntilIdle()
            assertTrue(model.beginPitchPicker())
            // Recovery starts while the picker is up, so the pitch result queues instead of running.
            resources.setStartupReadiness(ResourceStartupReadiness.RECOVERING)
            resources.setImportDocument("content://pitch.csv", "Kanjium CSV.csv")
            model.onPitchPicked("content://pitch.csv")
            advanceUntilIdle()
            assertEquals(emptyList<Pair<String, Boolean>>(), resources.pitchImports)

            model.importWordList("content://blacklist.txt", WordListKind.BLACKLIST)
            resources.setStartupReadiness(ResourceStartupReadiness.READY)
            advanceUntilIdle()

            assertEquals(listOf("content://pitch.csv" to false), resources.pitchImports)
            assertEquals(emptyList<Pair<String, WordListKind>>(), resources.wordListImports)
        }

    @Test
    fun `a word list pick never displaces a queued pick for the other list`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setStartupReadiness(ResourceStartupReadiness.RECOVERING)
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                    SavedStateHandle(),
                )
            advanceUntilIdle()

            model.importWordList("content://blacklist.txt", WordListKind.BLACKLIST)
            advanceUntilIdle()
            assertEquals(emptyList<Pair<String, WordListKind>>(), resources.wordListImports)

            // Both launchers stay live while a pick is queued, so the whitelist one can arrive here.
            model.importWordList("content://whitelist.txt", WordListKind.WHITELIST)
            resources.setStartupReadiness(ResourceStartupReadiness.READY)
            advanceUntilIdle()

            assertEquals(
                listOf("content://blacklist.txt" to WordListKind.BLACKLIST),
                resources.wordListImports,
            )
        }

    @Test
    fun `a repeated pick for the same word list replaces the queued one`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()
            resources.setStartupReadiness(ResourceStartupReadiness.RECOVERING)
            val model =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    resources,
                    SavedStateHandle(),
                )
            advanceUntilIdle()

            model.importWordList("content://blacklist-old.txt", WordListKind.BLACKLIST)
            advanceUntilIdle()
            model.importWordList("content://blacklist-new.txt", WordListKind.BLACKLIST)
            resources.setStartupReadiness(ResourceStartupReadiness.READY)
            advanceUntilIdle()

            // The slot being answered is its own, so the newer file is what imports.
            assertEquals(
                listOf("content://blacklist-new.txt" to WordListKind.BLACKLIST),
                resources.wordListImports,
            )
        }

    @Test
    fun `an interrupted whitelist import still offers the whitelist picker after recreation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val first =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    FakeResourceManager(),
                    savedState,
                )
            advanceUntilIdle()
            first.importWordList("content://whitelist.txt", WordListKind.WHITELIST)
            advanceUntilIdle()

            val restored =
                viewModel(
                    FakeSettingsRepository(AppSettings()),
                    FakeAnkiSetupManager(emptyList()),
                    FakeResourceManager(),
                    savedState,
                )
            advanceUntilIdle()

            // Retry for a WORD_LIST failure opens the picker for this target, not the default one.
            assertEquals(WordListKind.WHITELIST, restored.uiState.value.wordListTarget)
        }

    @Test
    fun `an unreadable settings store still renders setup`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = FakeResourceManager()

            // uiState is combine(...).stateIn(viewModelScope): a settings read which throws would
            // reach Android's uncaught handler instead of rendering. Setup shows defaults, and
            // every write here stays a read-modify-write, so nothing on screen can be persisted.
            val viewModel =
                viewModel(
                    repository = UnreadableSettingsRepository(),
                    setup = FakeAnkiSetupManager(emptyList()),
                    resources = resources,
                )
            advanceUntilIdle()

            assertEquals(ResourceStartupReadiness.READY, viewModel.uiState.value.resourceStartup)
            assertNull(viewModel.uiState.value.noteType)
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
        repository: AppSettingsRepository,
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

    private class UnreadableSettingsRepository : AppSettingsRepository {
        override val settings: Flow<AppSettings> =
            flow { throw IOException("transient read failure") }

        override suspend fun update(settings: AppSettings) = error("write not expected")

        override suspend fun update(transform: (AppSettings) -> AppSettings) =
            error("write not expected")
    }

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
        private data class ImportDocument(
            val displayName: String,
            val mimeType: String?,
            val leadingBytes: ByteArray,
        )

        val previewCalls = mutableListOf<Pair<String, ResourceImportFileKind>>()
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
        val audioPreflights = mutableListOf<String>()
        val audioImports = mutableListOf<Triple<String, String, Boolean>>()
        val audioImportPaths = mutableListOf<String>()
        var audioPacksInArchive = listOf(AudioPackCandidate("jpod", "jpod_files", "ajt"))
        val retainedResourceImports = mutableListOf<String>()
        val releasedResourceImports = mutableListOf<String>()
        val deletedResources = mutableListOf<Pair<InstalledResourceKind, String>>()
        private val importDocuments = mutableMapOf<String, ImportDocument>()
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

        fun setInstalledAudioPacks(packs: List<InstalledAudioPack>) {
            mutableState.value = mutableState.value.copy(audioPacks = packs)
        }

        fun setFailure(failure: ResourceFailure) {
            mutableState.value = mutableState.value.copy(failure = failure)
        }

        fun setStartupReadiness(readiness: ResourceStartupReadiness) {
            mutableState.value = mutableState.value.copy(startupReadiness = readiness)
        }

        fun setImportDocument(
            uri: String,
            displayName: String,
            mimeType: String? = null,
            leadingBytes: ByteArray = byteArrayOf(),
        ) {
            importDocuments[uri] = ImportDocument(displayName, mimeType, leadingBytes)
        }

        override suspend fun recoverAndRefresh() = Unit

        override suspend fun installUniDic() = Unit

        override suspend fun installCatalogDictionary(resourceId: String, replace: Boolean) = Unit

        override suspend fun importCustomDictionary(uri: String, slotId: String, replace: Boolean) = Unit

        override suspend fun retainResourceImport(uri: String): RetainedResourceImport {
            retainedResourceImports += uri
            val document =
                importDocuments[uri]
                    ?: ImportDocument(
                        displayName = uri.substringAfterLast('/'),
                        mimeType = null,
                        leadingBytes = byteArrayOf(),
                    )
            return RetainedResourceImport(
                uri = uri,
                displayName = document.displayName,
                fileKind =
                    detectResourceImportFileKind(
                        displayName = document.displayName,
                        mimeType = document.mimeType,
                        readLeadingBytes = { document.leadingBytes },
                    ),
            )
        }

        override suspend fun releaseResourceImport(uri: String) {
            releasedResourceImports += uri
        }

        override suspend fun deleteInstalledResource(
            kind: InstalledResourceKind,
            id: String,
        ) {
            deletedResources += kind to id
        }

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

        override suspend fun preflightAudioPack(uri: String): List<AudioPackCandidate> {
            audioPreflights += uri
            return audioPacksInArchive
        }

        override suspend fun importAudioPack(uri: String, pack: AudioPackCandidate, replace: Boolean) {
            audioImports += Triple(uri, pack.packId, replace)
            audioImportPaths += pack.packPath
        }

        override suspend fun discardAudioPackPreflight() = Unit

        override suspend fun importKnownWords(uri: String, format: KnownWordsSourceFormat) {
            importCalls += uri to format
        }

        override suspend fun previewKnownWords(uri: String, fileKind: ResourceImportFileKind) {
            previewCalls += uri to fileKind
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
