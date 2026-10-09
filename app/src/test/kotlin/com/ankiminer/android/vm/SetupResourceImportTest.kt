package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import com.ankiminer.android.MainDispatcherRule
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.AnkiSetupManager
import com.ankiminer.android.data.anki.AnkiSetupManagerState
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceFailureRetry
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.RetainedResourceImport
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.resources.detectResourceImportFileKind
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.localization.testStringResourceResolver
import com.ankiminer.android.media.ProviderIoTimeoutException
import com.ankiminer.android.media.SafSelectionPersistenceException
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.mining.MiningRunAdmissionState
import com.ankiminer.android.mining.NotificationPermissionReadiness
import java.io.FileNotFoundException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Picking, retaining and retrying resource imports from Settings. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupResourceImportTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `an I-O failure while retaining a pick offers another file instead of crashing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val pickers =
                listOf<Triple<ResourceFailureOrigin, (SetupViewModel, String) -> Unit, (SetupViewModel) -> Boolean>>(
                    Triple(
                        ResourceFailureOrigin.CUSTOM_DICTIONARY,
                        { model, uri -> model.onCustomDictionaryPicked(uri) },
                        SetupViewModel::beginCustomDictionaryPicker,
                    ),
                    Triple(
                        ResourceFailureOrigin.FREQUENCY,
                        { model, uri -> model.onFrequencyPicked(uri) },
                        SetupViewModel::beginFrequencyPicker,
                    ),
                    Triple(
                        ResourceFailureOrigin.PITCH,
                        { model, uri -> model.onPitchPicked(uri) },
                        SetupViewModel::beginPitchPicker,
                    ),
                    Triple(
                        ResourceFailureOrigin.KNOWN_WORDS,
                        { model, uri -> model.onKnownWordsPicked(uri) },
                        SetupViewModel::beginKnownWordsPicker,
                    ),
                )
            // The leading-byte read of an extension-less file, a provider stall, and the SAF
            // selection-ledger commit: none of them is a SafAccessException.
            val failures =
                listOf(
                    FileNotFoundException("deleted after picking"),
                    ProviderIoTimeoutException(),
                    SafSelectionPersistenceException("selection commit failed"),
                )

            pickers.forEach { (origin, pick, begin) ->
                failures.forEach { failure ->
                    val resources = ImportResources().apply { retainFailure = failure }
                    val model = viewModel(resources)
                    advanceUntilIdle()

                    pick(model, "content://test/list")
                    advanceUntilIdle()

                    val published = requireNotNull(model.uiState.value.failure)
                    assertEquals("saf_provider_unavailable", published.code)
                    assertEquals("resource:${R.string.resource_failure_saf_provider}", published.message)
                    assertEquals(origin, published.origin)
                    assertEquals(ResourceFailureAction.CHOOSE_ANOTHER, published.retry.action)
                    // The slot is free again, so "Choose another" can open the picker.
                    assertTrue(begin(model))
                }
            }
        }

    @Test
    fun `retrying a failed word-list removal removes that list again`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = ImportResources()
            val model = viewModel(resources)
            advanceUntilIdle()
            model.removeWordList(WordListKind.WHITELIST)
            advanceUntilIdle()
            resources.update {
                it.copy(
                    failure =
                        ResourceFailure(
                            code = "word_list_remove_failed",
                            message = "remove failed",
                            retryable = true,
                            origin = ResourceFailureOrigin.WORD_LIST,
                            retry = ResourceFailureRetry(ResourceFailureAction.RETRY),
                        ),
                )
            }
            advanceUntilIdle()

            model.retryResourceFailure()
            advanceUntilIdle()

            assertEquals(listOf(WordListKind.WHITELIST, WordListKind.WHITELIST), resources.wordListRemovals)
            assertTrue(resources.wordListImports.isEmpty())
        }

    @Test
    fun `a failed word-list import retry does not remove the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources = ImportResources()
            val model = viewModel(resources)
            advanceUntilIdle()
            resources.update {
                it.copy(
                    failure =
                        ResourceFailure(
                            code = "word_list_invalid",
                            message = "import failed",
                            retryable = false,
                            origin = ResourceFailureOrigin.WORD_LIST,
                            retry = ResourceFailureRetry(ResourceFailureAction.CHOOSE_ANOTHER),
                        ),
                )
            }
            advanceUntilIdle()

            model.retryResourceFailure()
            advanceUntilIdle()

            assertTrue(resources.wordListRemovals.isEmpty())
        }

    @Test
    fun `a frequency list whose id another language holds takes the next free id`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources =
                ImportResources().apply {
                    update {
                        it.copy(frequencySources = listOf(installedFrequency("frequency", "frequency", "ja")))
                    }
                }
            val model = viewModel(resources, AppSettings(language = "he"))
            advanceUntilIdle()

            assertTrue(model.beginFrequencyPicker())
            model.onFrequencyPicked("content://test/frequency.csv")
            advanceUntilIdle()

            assertNull(model.uiState.value.pendingReplace)
            assertEquals(
                listOf(Triple("content://test/frequency.csv", "frequency-2", false)),
                resources.frequencyImports,
            )
        }

    @Test
    fun `a pitch list whose id another language holds takes the next free id`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resources =
                ImportResources().apply {
                    update { it.copy(pitchSources = listOf(installedPitch("pitch", "pitch", "ja"))) }
                }
            val model = viewModel(resources, AppSettings(language = "he"))
            advanceUntilIdle()

            assertTrue(model.beginPitchPicker())
            model.onPitchPicked("content://test/pitch.csv")
            advanceUntilIdle()

            assertNull(model.uiState.value.pendingReplace)
            assertEquals(listOf(Triple("content://test/pitch.csv", "pitch-2", false)), resources.pitchImports)
        }

    @Test
    fun `process death during the custom dictionary preflight leaves the pickers usable`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val dying = ImportResources().apply { preflightGate = CompletableDeferred() }
            val original = viewModel(dying, savedStateHandle = savedState)
            advanceUntilIdle()
            assertTrue(original.beginCustomDictionaryPicker())
            original.onCustomDictionaryPicked("content://test/dictionary.zip")
            advanceUntilIdle()
            assertEquals(listOf("content://test/dictionary.zip"), dying.customDictionaryPreflights)

            // The SAF result was consumed by the dead process and is never delivered again.
            val restored = viewModel(ImportResources(), savedStateHandle = savedState.processDeathCopy())
            advanceUntilIdle()

            assertTrue(restored.beginCustomDictionaryPicker())
        }

    @Test
    fun `a picker still open across process death keeps its reservation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val original = viewModel(ImportResources(), savedStateHandle = savedState)
            advanceUntilIdle()
            assertTrue(original.beginFrequencyPicker())

            val resources = ImportResources()
            val restored = viewModel(resources, savedStateHandle = savedState.processDeathCopy())
            advanceUntilIdle()
            assertFalse(restored.beginPitchPicker())
            restored.onFrequencyPicked("content://test/frequency.tsv")
            advanceUntilIdle()

            assertEquals(
                listOf(Triple("content://test/frequency.tsv", "frequency", false)),
                resources.frequencyImports,
            )
        }

    /** What onSaveInstanceState captured, handed to the next process's ViewModel. */
    private fun SavedStateHandle.processDeathCopy(): SavedStateHandle =
        SavedStateHandle(keys().associateWith { get<Any?>(it) })

    private fun installedFrequency(
        sourceId: String,
        sourceName: String,
        language: String,
    ) = InstalledFrequencySource(
        sourceId = sourceId,
        sourceName = sourceName,
        format = "csv",
        entryCount = 100,
        schemaOk = true,
        schemaVersion = 1,
        isCategorical = false,
        rebuildSourcePath = null,
        language = language,
    )

    private fun installedPitch(
        sourceId: String,
        sourceName: String,
        language: String,
    ) = InstalledPitchSource(
        sourceId = sourceId,
        sourceName = sourceName,
        sourceRevision = "1",
        format = "csv",
        entryCount = 100,
        schemaOk = true,
        schemaVersion = 1,
        rebuildSourcePath = null,
        language = language,
    )

    private fun viewModel(
        resources: ImportResources,
        settings: AppSettings = AppSettings(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): SetupViewModel =
        SetupViewModel(
            resources = resources,
            settingsRepository = SessionSettingsRepository(settings),
            ankiSetup = NoAnkiSetup,
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

    private object NoAnkiSetup : AnkiSetupManager {
        override val state: StateFlow<AnkiSetupManagerState> =
            MutableStateFlow(AnkiSetupManagerState()).asStateFlow()

        override fun refresh(
            noteType: String?,
            fieldMap: Map<String, String>,
            cardTypeMarkerField: String?,
        ) = Unit

        override fun dismissFailure() = Unit
    }

    /** Records the calls these flows make; everything else is the shared no-op session fake. */
    private class ImportResources : ResourceManager by SessionResourceManager() {
        private val mutableState =
            MutableStateFlow(ResourceManagerState(startupReadiness = ResourceStartupReadiness.READY))
        override val state: StateFlow<ResourceManagerState> = mutableState.asStateFlow()

        var retainFailure: Throwable? = null
        var preflightGate: CompletableDeferred<Unit>? = null
        val customDictionaryPreflights = mutableListOf<String>()
        val frequencyImports = mutableListOf<Triple<String, String, Boolean>>()
        val pitchImports = mutableListOf<Triple<String, String, Boolean>>()
        val wordListRemovals = mutableListOf<WordListKind>()
        val wordListImports = mutableListOf<WordListKind>()

        fun update(transform: (ResourceManagerState) -> ResourceManagerState) = mutableState.update(transform)

        override suspend fun retainResourceImport(uri: String): RetainedResourceImport {
            retainFailure?.let { throw it }
            val displayName = uri.substringAfterLast('/')
            return RetainedResourceImport(
                uri = uri,
                displayName = displayName,
                fileKind = detectResourceImportFileKind(displayName, null) { byteArrayOf() },
            )
        }

        override suspend fun preflightCustomDictionary(uri: String): String {
            customDictionaryPreflights += uri
            preflightGate?.await()
            return "custom-dictionary"
        }

        override suspend fun importFrequencySource(
            uri: String,
            sourceId: String,
            sourceName: String,
            format: FrequencySourceFormat,
            replace: Boolean,
        ) {
            frequencyImports += Triple(uri, sourceId, replace)
        }

        override suspend fun importPitchAccent(
            uri: String,
            sourceId: String,
            sourceName: String,
            format: PitchAccentSourceFormat,
            replace: Boolean,
        ) {
            pitchImports += Triple(uri, sourceId, replace)
        }

        override suspend fun removeWordList(kind: WordListKind) {
            wordListRemovals += kind
        }

        override suspend fun importWordList(uri: String, kind: WordListKind) {
            wordListImports += kind
        }
    }
}
