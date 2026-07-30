package com.ankiminer.android.vm

import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.anki.provider.AnkiRecoveryReadiness
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.AnkiSetupManager
import com.ankiminer.android.data.anki.AnkiSetupManagerState
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.KnownWordsResetScope
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.AppSettingsValidator
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.AnkiMiningTargetReadiness
import com.ankiminer.android.mining.MiningRunAdmissionState
import com.ankiminer.android.mining.NotificationPermissionReadiness
import com.ankiminer.android.localization.testStringResourceResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun setupSessionViewModel(
    repository: SessionSettingsRepository,
    resources: SessionResourceManager = SessionResourceManager(),
    deckNames: List<String> = emptyList(),
): SetupViewModel =
    SetupViewModel(
        resources = resources,
        settingsRepository = repository,
        ankiSetup = SessionAnkiSetupManager(deckNames),
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
    )

internal class SessionSettingsRepository(initial: AppSettings) : AppSettingsRepository {
    private val mutableSettings = MutableStateFlow(AppSettingsValidator.validate(initial))
    override val settings: Flow<AppSettings> = mutableSettings.asStateFlow()

    var failWrites: Boolean = false
    var writeAttempts: Int = 0
        private set

    val current: AppSettings
        get() = mutableSettings.value

    override suspend fun update(settings: AppSettings) {
        writeAttempts += 1
        if (failWrites) error("settings unavailable")
        mutableSettings.value = AppSettingsValidator.validate(settings)
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        writeAttempts += 1
        if (failWrites) error("settings unavailable")
        mutableSettings.value = AppSettingsValidator.validate(transform(mutableSettings.value))
    }
}

internal class SessionResourceManager(
    initial: ResourceManagerState =
        ResourceManagerState(startupReadiness = ResourceStartupReadiness.READY),
) : ResourceManager {
    override val state: StateFlow<ResourceManagerState> = MutableStateFlow(initial).asStateFlow()

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
        sourceId: String,
        sourceName: String,
        format: PitchAccentSourceFormat,
        replace: Boolean,
    ) = Unit

    override suspend fun importAudioPack(uri: String, packId: String, replace: Boolean) = Unit

    override suspend fun importKnownWords(uri: String, format: KnownWordsSourceFormat) = Unit

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

private class SessionAnkiSetupManager(deckNames: List<String>) : AnkiSetupManager {
    override val state: StateFlow<AnkiSetupManagerState> =
        MutableStateFlow(AnkiSetupManagerState(availableDeckNames = deckNames)).asStateFlow()

    override fun refresh(noteType: String?, fieldMap: Map<String, String>) = Unit

    override fun reconcileInterruptedWork() = Unit

    override fun performRemediation(command: AnkiRemediationCommand) = Unit

    override fun dismissFailure() = Unit
}
