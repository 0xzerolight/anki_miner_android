package com.ankiminer.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.anki.provider.AnkiExternalReviewOutcome
import com.ankiminer.android.anki.provider.AnkiFieldAutoMap
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.data.anki.AnkiSetupManager
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.mining.MiningRunAdmissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SetupViewModel(
    private val resources: ResourceManager,
    settingsRepository: AppSettingsRepository,
    private val ankiSetup: AnkiSetupManager,
    pythonReadiness: StateFlow<PythonRuntimeReadiness>,
    miningAdmission: StateFlow<MiningRunAdmissionState>,
    private val runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?>,
    private val refreshExternalReadiness: () -> Unit,
) : ViewModel() {
    private data class LocalState(
        val lookupTerm: String = "猫",
        val lookupSlotId: String? = null,
        val customSlotId: String = "custom-dictionary",
        val customReplace: Boolean = false,
        val frequencySourceId: String = "frequency",
        val frequencySourceName: String = "Imported frequency",
        val frequencyFormat: FrequencySourceFormat = FrequencySourceFormat.YOMITAN_ZIP,
        val frequencyReplace: Boolean = false,
        val pitchSourceName: String = "Imported pitch accent",
        val pitchFormat: PitchAccentSourceFormat = PitchAccentSourceFormat.YOMITAN_ZIP,
        val pitchReplace: Boolean = false,
        val audioPackId: String = "audio-pack",
        val audioPackReplace: Boolean = false,
        val knownWordsFormat: KnownWordsSourceFormat = KnownWordsSourceFormat.JSON,
        val pendingReplaceResourceId: String? = null,
    )

    private val local = MutableStateFlow(LocalState())
    private val settings = settingsRepository.settings
    private val repository = settingsRepository
    private val settingsAnkiAndRuntime =
        combine(settings, ankiSetup.state, runtimeWorkState) { appSettings, ankiState, runtimeKind ->
            Triple(appSettings, ankiState, runtimeKind)
        }

    val uiState: StateFlow<SetupUiState> =
        combine(resources.state, settingsAnkiAndRuntime, pythonReadiness, miningAdmission, local) {
                resourceState,
                settingsAnkiRuntimeState,
                python,
                admission,
                localState,
            ->
            val (appSettings, ankiState, runtimeKind) = settingsAnkiRuntimeState
            val selectedSlot =
                localState.lookupSlotId?.takeIf { selected ->
                    resourceState.dictionaries.any { it.isUsable && it.slotId == selected }
                } ?: resourceState.dictionaries.firstOrNull { it.isUsable }?.slotId
            SetupUiState(
                python = python,
                resourceStartup = resourceState.startupReadiness,
                anki = admission.anki,
                notifications = admission.notifications,
                noteTypeStatus = ankiState.noteTypeStatus,
                availableNoteTypes = ankiState.availableNoteTypes,
                noteType = appSettings.noteType,
                fieldMap = appSettings.fieldMap,
                remediations = ankiState.remediations,
                ankiOperation = ankiState.operation,
                ankiFailure = ankiState.failure,
                runtimeWorkKind = runtimeKind,
                wizardSeen = appSettings.setupWizardSeen,
                uniDicInstalled = resourceState.hasUniDic,
                catalogDictionaries = resourceState.catalogDictionaries,
                pendingReplaceResourceId = localState.pendingReplaceResourceId,
                dictionaries = resourceState.dictionaries,
                frequencySources = resourceState.frequencySources,
                pitchAccent = resourceState.pitchAccent,
                audioPacks = resourceState.audioPacks,
                knownWords = resourceState.knownWords,
                wordsets = resourceState.wordsets,
                lastLocalImport = resourceState.lastLocalImport,
                operation = resourceState.activeOperation,
                failure = resourceState.failure,
                lookup = resourceState.lastLookup,
                lookupTerm = localState.lookupTerm,
                lookupSlotId = selectedSlot,
                customSlotId = localState.customSlotId,
                customReplace = localState.customReplace,
                frequencySourceId = localState.frequencySourceId,
                frequencySourceName = localState.frequencySourceName,
                frequencyFormat = localState.frequencyFormat,
                frequencyReplace = localState.frequencyReplace,
                pitchSourceName = localState.pitchSourceName,
                pitchFormat = localState.pitchFormat,
                pitchReplace = localState.pitchReplace,
                audioPackId = localState.audioPackId,
                audioPackReplace = localState.audioPackReplace,
                knownWordsFormat = localState.knownWordsFormat,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SetupUiState(),
        )

    fun refresh() {
        viewModelScope.launch {
            if (runtimeWorkState.value != null) {
                refreshExternalReadiness()
                return@launch
            }
            resources.recoverAndRefresh()
            // A process-start or prior import may already own the resource operation. Await its
            // terminal publication before asking admission to acquire the mutually exclusive
            // mining lease; otherwise Check again can reproduce the startup race.
            resources.state.first { state ->
                state.activeOperation == null &&
                    state.startupReadiness !in
                    setOf(
                        ResourceStartupReadiness.PENDING,
                        ResourceStartupReadiness.RECOVERING,
                    )
            }
            refreshExternalReadiness()
        }
    }

    fun selectNoteType(name: String) {
        if (uiState.value.busy) return
        val fields =
            uiState.value.availableNoteTypes.firstOrNull { it.name == name }?.fieldNames
                ?: emptyList()
        val map = AnkiFieldAutoMap.autoMap(fields)
        viewModelScope.launch {
            repository.update { it.copy(noteType = name, fieldMap = map) }
            ankiSetup.refresh(name, map)
        }
    }

    fun setFieldMapping(key: String, field: String) {
        if (uiState.value.busy) return
        val current = uiState.value.fieldMap.toMutableMap()
        if (field.isEmpty()) current.remove(key) else current[key] = field
        val map = current.toMap()
        val noteType = uiState.value.noteType
        viewModelScope.launch {
            repository.update { it.copy(fieldMap = map) }
            ankiSetup.refresh(noteType, map)
        }
    }

    fun verifyNoteType() {
        if (uiState.value.busy) return
        ankiSetup.refresh(uiState.value.noteType, uiState.value.fieldMap)
    }

    fun reconcileInterruptedWork() {
        if (!uiState.value.busy) ankiSetup.reconcileInterruptedWork()
    }

    fun retryStagingCleanup(remediationId: Long) {
        if (!uiState.value.busy) {
            ankiSetup.performRemediation(AnkiRemediationCommand.RetryStagingCleanup(remediationId))
        }
    }

    fun acknowledgeUnattachedMedia(remediationId: Long) {
        if (!uiState.value.busy) {
            ankiSetup.performRemediation(AnkiRemediationCommand.AcknowledgeUnattachedMedia(remediationId))
        }
    }

    fun acknowledgeUncertainMedia(remediationId: Long) {
        if (!uiState.value.busy) {
            ankiSetup.performRemediation(AnkiRemediationCommand.AcknowledgeUncertainMedia(remediationId))
        }
    }

    fun resolveAfterExternalReview(
        remediationId: Long,
        outcome: AnkiExternalReviewOutcome,
    ) {
        if (!uiState.value.busy) {
            ankiSetup.performRemediation(
                AnkiRemediationCommand.ResolveAfterExternalReview(remediationId, outcome),
            )
        }
    }

    fun dismissAnkiFailure() = ankiSetup.dismissFailure()

    fun installUniDic() {
        if (uiState.value.busy) return
        viewModelScope.launch { resources.installUniDic() }
    }

    fun installCatalogDictionary(resourceId: String) {
        if (uiState.value.busy) return
        val status =
            uiState.value.catalogDictionaries.firstOrNull { it.resource.resourceId == resourceId }
                ?: return
        if (status.slotOccupied) {
            local.update { it.copy(pendingReplaceResourceId = resourceId) }
        } else {
            viewModelScope.launch { resources.installCatalogDictionary(resourceId, replace = false) }
        }
    }

    fun confirmCatalogDictionaryReplace() {
        if (uiState.value.busy) return
        val resourceId = uiState.value.pendingReplaceResourceId ?: return
        local.update { it.copy(pendingReplaceResourceId = null) }
        viewModelScope.launch { resources.installCatalogDictionary(resourceId, replace = true) }
    }

    fun dismissCatalogDictionaryReplace() {
        local.update { it.copy(pendingReplaceResourceId = null) }
    }

    fun importCustomDictionary(uri: String) {
        val state = uiState.value
        if (state.busy || !SLOT_ID.matches(state.customSlotId)) return
        viewModelScope.launch {
            resources.importCustomDictionary(uri, state.customSlotId, state.customReplace)
        }
    }

    fun setCustomSlotId(value: String) {
        if (value.length <= 64) local.update { it.copy(customSlotId = value.lowercase()) }
    }

    fun setCustomReplace(value: Boolean) {
        local.update { it.copy(customReplace = value) }
    }

    fun setFrequencySourceId(value: String) {
        if (value.length <= 64) local.update { it.copy(frequencySourceId = value.lowercase()) }
    }

    fun setFrequencySourceName(value: String) {
        if (value.toByteArray().size <= 1024) local.update { it.copy(frequencySourceName = value) }
    }

    fun setFrequencyFormat(value: FrequencySourceFormat) {
        local.update { it.copy(frequencyFormat = value) }
    }

    fun setFrequencyReplace(value: Boolean) {
        local.update { it.copy(frequencyReplace = value) }
    }

    fun importFrequencySource(uri: String) {
        val state = uiState.value
        if (state.busy || !state.frequencySourceIdValid || state.frequencySourceName.isBlank()) return
        viewModelScope.launch {
            resources.importFrequencySource(
                uri = uri,
                sourceId = state.frequencySourceId,
                sourceName = state.frequencySourceName,
                format = state.frequencyFormat,
                replace = state.frequencyReplace,
            )
        }
    }

    fun setPitchSourceName(value: String) {
        if (value.toByteArray().size <= 1024) local.update { it.copy(pitchSourceName = value) }
    }

    fun setPitchFormat(value: PitchAccentSourceFormat) {
        local.update { it.copy(pitchFormat = value) }
    }

    fun setPitchReplace(value: Boolean) {
        local.update { it.copy(pitchReplace = value) }
    }

    fun importPitchAccent(uri: String) {
        val state = uiState.value
        if (state.busy || state.pitchSourceName.isBlank()) return
        viewModelScope.launch {
            resources.importPitchAccent(
                uri = uri,
                sourceName = state.pitchSourceName,
                format = state.pitchFormat,
                replace = state.pitchReplace,
            )
        }
    }

    fun setAudioPackId(value: String) {
        if (value.length <= 64) local.update { it.copy(audioPackId = value.lowercase()) }
    }

    fun setAudioPackReplace(value: Boolean) {
        local.update { it.copy(audioPackReplace = value) }
    }

    fun importAudioPack(uri: String) {
        val state = uiState.value
        if (state.busy || !state.audioPackIdValid) return
        viewModelScope.launch {
            resources.importAudioPack(uri, state.audioPackId, state.audioPackReplace)
        }
    }

    fun setKnownWordsFormat(value: KnownWordsSourceFormat) {
        local.update { it.copy(knownWordsFormat = value) }
    }

    fun importKnownWords(uri: String) {
        val state = uiState.value
        if (state.busy) return
        val format = state.knownWordsFormat
        viewModelScope.launch { resources.importKnownWords(uri, format) }
    }

    fun setLookupTerm(value: String) {
        if (value.toByteArray().size <= 1024) local.update { it.copy(lookupTerm = value) }
    }

    fun setLookupSlot(value: String) {
        if (uiState.value.dictionaries.any { it.isUsable && it.slotId == value }) {
            local.update { it.copy(lookupSlotId = value) }
        }
    }

    fun lookup() {
        val state = uiState.value
        val slot = state.lookupSlotId ?: return
        if (state.busy || state.lookupTerm.isBlank()) return
        viewModelScope.launch { resources.lookup(slot, state.lookupTerm) }
    }

    fun cancelOperation() = resources.cancelActive()

    fun dismissFailure() = resources.dismissFailure()

    fun permissionsReturned() = refreshExternalReadiness()

    /** Fire-and-forget: the wizard was completed or skipped and must not re-appear. */
    fun markWizardSeen() {
        viewModelScope.launch {
            try {
                repository.update { it.copy(setupWizardSeen = true) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // A failed write re-offers the (skippable) wizard next launch; never crash.
            }
        }
    }

    class Factory(
        private val resources: ResourceManager,
        private val settings: AppSettingsRepository,
        private val ankiSetup: AnkiSetupManager,
        private val python: StateFlow<PythonRuntimeReadiness>,
        private val admission: StateFlow<MiningRunAdmissionState>,
        private val runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?>,
        private val refreshExternalReadiness: () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(SetupViewModel::class.java))
            return SetupViewModel(
                resources,
                settings,
                ankiSetup,
                python,
                admission,
                runtimeWorkState,
                refreshExternalReadiness,
            ) as T
        }
    }

    private companion object {
        val SLOT_ID = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
    }
}
