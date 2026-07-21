package com.ankiminer.android.vm

import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.anki.provider.ModelSummary
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.AnkiSetupManager
import com.ankiminer.android.data.anki.AnkiSetupManagerState
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.KnownWordsResetScope
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.AppSettingsValidator
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.mining.MiningRunAdmissionState
import com.ankiminer.android.mining.NotificationPermissionReadiness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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

    private fun viewModel(
        repository: FakeSettingsRepository,
        setup: FakeAnkiSetupManager,
        resources: FakeResourceManager = FakeResourceManager(),
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

        override fun refresh(noteType: String?, fieldMap: Map<String, String>) {
            refreshCount += 1
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
        val searchCalls = mutableListOf<Pair<String, Boolean>>()
        val removeCalls = mutableListOf<List<String>>()
        val resetCalls = mutableListOf<KnownWordsResetScope>()
        val exportCalls = mutableListOf<String>()

        override val state: StateFlow<ResourceManagerState> =
            MutableStateFlow(ResourceManagerState()).asStateFlow()

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
        ) = Unit

        override suspend fun importPitchAccent(
            uri: String,
            sourceName: String,
            format: PitchAccentSourceFormat,
            replace: Boolean,
        ) = Unit

        override suspend fun importAudioPack(uri: String, packId: String, replace: Boolean) = Unit

        override suspend fun importKnownWords(uri: String, format: KnownWordsSourceFormat) {
            importCalls += uri to format
        }

        override suspend fun previewKnownWords(uri: String, format: KnownWordsSourceFormat) {
            previewCalls += uri to format
        }

        override suspend fun confirmKnownWordsImport() {
            confirmCount += 1
        }

        override fun dismissKnownWordsImportPreview() {
            dismissCount += 1
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
