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
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.ResourceFailureAction
import com.ankiminer.android.data.resources.ResourceFailureOrigin
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
