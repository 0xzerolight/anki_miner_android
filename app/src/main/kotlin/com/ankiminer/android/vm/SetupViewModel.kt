package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.R
import com.ankiminer.android.anki.generated.UnicodeContractV151
import com.ankiminer.android.anki.provider.AnkiExternalReviewOutcome
import com.ankiminer.android.anki.provider.AnkiFieldMapPolicy
import com.ankiminer.android.anki.provider.AnkiFieldMappingChange
import com.ankiminer.android.anki.provider.AnkiRemediationCommand
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.anki.AnkiSetupManager
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.KnownWordsResetScope
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceIdentity
import com.ankiminer.android.data.resources.ResourceImportFileKind
import com.ankiminer.android.data.resources.ResourceImportTarget
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.RetainedResourceImport
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.localization.StringResourceResolver
import com.ankiminer.android.mining.MiningRunAdmissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SetupViewModel(
    private val resources: ResourceManager,
    settingsRepository: AppSettingsRepository,
    private val ankiSetup: AnkiSetupManager,
    pythonReadiness: StateFlow<PythonRuntimeReadiness>,
    miningAdmission: StateFlow<MiningRunAdmissionState>,
    private val runtimeWorkState: StateFlow<RuntimeWorkCoordinator.Kind?>,
    private val refreshExternalReadiness: () -> Unit,
    private val strings: StringResourceResolver,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private enum class ResourcePickerKind {
        CUSTOM_DICTIONARY,
        FREQUENCY,
        PITCH,
        AUDIO_PACK,
        KNOWN_WORDS,
        WORD_LIST,
    }

    private data class PendingResourcePicker(
        val kind: ResourcePickerKind,
        val target: ResourceImportTarget? = null,
        val sourceName: String? = null,
        val frequencyFormat: FrequencySourceFormat? = null,
        val pitchFormat: PitchAccentSourceFormat? = null,
        val resourceFileKind: ResourceImportFileKind? = null,
        val wordListKind: WordListKind? = null,
        val uri: String? = null,
    )

    private data class LocalState(
        val lookupTerm: String = "猫",
        val lookupSlotId: String? = null,
        val customSlotId: String = "custom-dictionary",
        // Which list the last word-list operation touched, so a failed import can offer the right picker.
        val wordListTarget: WordListKind = WordListKind.BLACKLIST,
        val knownWordsSearch: String = "",
        val pendingReplace: PendingResourceReplace? = null,
        val fieldMapChanges: List<AnkiFieldMappingChange> = emptyList(),
        val deckPersistence: DeckPersistenceStatus = DeckPersistenceStatus.IDLE,
        val failedDeckName: String? = null,
        val wizardCompletion: WizardCompletionStatus = WizardCompletionStatus.IDLE,
    )

    private val local =
        MutableStateFlow(
            LocalState(
                customSlotId =
                    savedStateHandle[STATE_CUSTOM_SLOT_ID]
                        ?: "custom-dictionary",
                wordListTarget =
                    savedEnum<WordListKind>(STATE_WORD_LIST_TARGET)
                        ?: WordListKind.BLACKLIST,
                pendingReplace = restorePendingReplace(),
            ),
        )
    /** In-memory only: failed persistence must re-open the wizard in a fresh ViewModel. */
    private val _wizardDismissedForSession = MutableStateFlow(false)
    val wizardDismissedForSession: StateFlow<Boolean> = _wizardDismissedForSession.asStateFlow()
    // uiState is an eagerly shared combine: an unreadable store must not throw into viewModelScope,
    // which would take the process down instead of rendering setup. Falling back to defaults is
    // display only — every write below is a read-modify-write through the strict flow, so a
    // fallback can never be persisted, and mining stays blocked by the admission target probe.
    private val settings = settingsRepository.settingsOrNull.map { it ?: AppSettings() }
    private val repository = settingsRepository
    private val settingsMutationMutex = Mutex()
    private var pendingPicker = restorePendingPicker()
    private var pendingPickerJob: Job? = null
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
                ankiRecovery = admission.ankiRecovery,
                miningTarget = admission.target,
                notifications = admission.notifications,
                noteTypeStatus = ankiState.noteTypeStatus,
                availableNoteTypes = ankiState.availableNoteTypes,
                availableDeckNames = ankiState.availableDeckNames,
                deckName = appSettings.deckName,
                deckPersistence = localState.deckPersistence,
                failedDeckName = localState.failedDeckName,
                noteType = appSettings.noteType,
                fieldMap = appSettings.fieldMap,
                cardType = appSettings.cardType,
                cardTypeMarkerField = appSettings.cardTypeMarkerField,
                fieldMapChanges = localState.fieldMapChanges,
                remediations = ankiState.remediations,
                recoveryInventoryStatus = ankiState.recoveryInventoryStatus,
                ankiOperation = ankiState.operation,
                ankiFailure = ankiState.failure,
                ankiRecoveryFailure = ankiState.recoveryFailure,
                runtimeWorkKind = runtimeKind,
                wizardSeen = appSettings.setupWizardSeen,
                wizardCompletion = localState.wizardCompletion,
                uniDicInstalled = resourceState.hasUniDic,
                catalogDictionaries = resourceState.catalogDictionaries,
                pendingReplace = localState.pendingReplace,
                dictionaries = resourceState.dictionaries,
                frequencySources = resourceState.frequencySources,
                pitchSources = resourceState.pitchSources,
                audioPacks = resourceState.audioPacks,
                knownWords = resourceState.knownWords,
                knownWordsImportPreview = resourceState.knownWordsImportPreview,
                knownWordsPage = resourceState.knownWordsPage,
                wordsets = resourceState.wordsets,
                wordLists = resourceState.wordLists,
                lastLocalImport = resourceState.lastLocalImport,
                operation = resourceState.activeOperation,
                failure = resourceState.failure,
                lookup = resourceState.lastLookup,
                lookupTerm = localState.lookupTerm,
                lookupSlotId = selectedSlot,
                customSlotId = localState.customSlotId,
                wordListTarget = localState.wordListTarget,
                knownWordsSearch = localState.knownWordsSearch,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SetupUiState(),
        )

    init {
        val retainedAudioPreflight =
            pendingPicker?.let {
                it.kind == ResourcePickerKind.AUDIO_PACK && it.target != null
            } == true
        if (!retainedAudioPreflight) {
            viewModelScope.launch { resources.discardAudioPackPreflight() }
        }
        resumePendingPicker()
    }

    /**
     * [uiState] is a `combine(...).stateIn(...)`, so its value lags one dispatch behind
     * [local]: a `set…` followed by a dispatch in the same frame would otherwise act on
     * the previous input. Command handlers read through this so the fields the user just
     * edited are always current. [SetupUiState.lookupSlotId] is deliberately not merged —
     * the combine resolves it against the installed dictionaries.
     */
    private fun currentState(): SetupUiState {
        val localState = local.value
        return uiState.value.copy(
            deckPersistence = localState.deckPersistence,
            failedDeckName = localState.failedDeckName,
            fieldMapChanges = localState.fieldMapChanges,
            wizardCompletion = localState.wizardCompletion,
            pendingReplace = localState.pendingReplace,
            lookupTerm = localState.lookupTerm,
            customSlotId = localState.customSlotId,
            wordListTarget = localState.wordListTarget,
            knownWordsSearch = localState.knownWordsSearch,
        )
    }

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
        val state = currentState()
        if (state.busy || state.noteType == name) return
        val fields = state.availableNoteTypes.firstOrNull { it.name == name }?.fieldNames ?: return
        var changes = emptyList<AnkiFieldMappingChange>()
        persistAnkiSettings(
            transform = { current ->
                if (current.noteType == name) {
                    current
                } else {
                    val retainedMarker =
                        current.cardTypeMarkerField?.takeIf { marker ->
                            marker in fields && marker != fields.firstOrNull()
                        }
                    val merged =
                        AnkiFieldMapPolicy.merge(
                            currentNoteType = current.noteType,
                            selectedNoteType = name,
                            fieldNames = fields,
                            currentFieldMap = current.fieldMap,
                            reservedDestinations = setOfNotNull(retainedMarker),
                        )
                    changes = merged.changes
                    current.copy(
                        noteType = name,
                        fieldMap = merged.fieldMap,
                        cardTypeMarkerField = retainedMarker,
                    )
                }
            },
            afterSuccess = { persisted ->
                local.update { it.copy(fieldMapChanges = changes) }
                refreshPersistedTarget(persisted)
            },
        )
    }

    fun selectDeck(deckName: String) {
        val state = currentState()
        if (state.busy || deckName !in state.deckSelection.choices.map(DeckChoice::deckName)) return
        if (state.deckName == deckName) return
        persistDeckSelection(deckName)
    }

    fun retryDeckSelection() {
        val state = currentState()
        if (state.busy || state.deckPersistence != DeckPersistenceStatus.FAILED) return
        persistDeckSelection(state.failedDeckName ?: return)
    }

    private fun persistDeckSelection(deckName: String) {
        local.update {
            it.copy(
                deckPersistence = DeckPersistenceStatus.SAVING,
                failedDeckName = null,
            )
        }
        viewModelScope.launch {
            try {
                repository.update { it.copy(deckName = deckName) }
                local.update {
                    it.copy(
                        deckPersistence = DeckPersistenceStatus.IDLE,
                        failedDeckName = null,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                local.update {
                    it.copy(
                        deckPersistence = DeckPersistenceStatus.FAILED,
                        failedDeckName = deckName,
                    )
                }
            }
        }
    }

    fun setFieldMapping(key: String, field: String) {
        val state = currentState()
        if (state.busy) return
        val fields =
            state.availableNoteTypes.firstOrNull { it.name == state.noteType }?.fieldNames
                ?: return
        val map =
            AnkiFieldMapPolicy.assign(
                state.fieldMap,
                key,
                field,
                fields,
                setOfNotNull(state.cardTypeMarkerField),
            ) ?: return
        if (map == state.fieldMap) return
        val noteTypeAtIntent = state.noteType
        persistAnkiSettings(
            transform = { current ->
                if (current.noteType != noteTypeAtIntent) {
                    current
                } else {
                    val updated =
                        AnkiFieldMapPolicy.assign(
                            current.fieldMap,
                            key,
                            field,
                            fields,
                            setOfNotNull(current.cardTypeMarkerField),
                        )
                    if (updated == null || updated == current.fieldMap) {
                        current
                    } else {
                        current.copy(fieldMap = updated)
                    }
                }
            },
            afterSuccess = { persisted ->
                local.update { it.copy(fieldMapChanges = emptyList()) }
                refreshPersistedTarget(persisted)
            },
        )
    }

    /**
     * Pick a card mode. The conventional JP Mining Note field is preselected when the note type has
     * it and nothing else is mapped to it; otherwise the marker stays unset and the mode stays off
     * until the user chooses a field.
     */
    fun selectCardType(cardType: CardType?) {
        val state = currentState()
        if (state.busy) return
        if (cardType == null && state.cardType == null && state.cardTypeMarkerField == null) return
        persistAnkiSettings(
            transform = { current ->
                if (cardType == null) {
                    current.copy(cardType = null, cardTypeMarkerField = null)
                } else {
                    val fields =
                        ankiSetup.state.value.availableNoteTypes
                            .firstOrNull { it.name == current.noteType }
                            ?.fieldNames
                            .orEmpty()
                    val conventional =
                        cardType.conventionalField.takeIf { candidate ->
                            candidate in fields &&
                                current.fieldMap.none { (_, mapped) -> mapped == candidate }
                        }
                    val marker =
                        if (current.cardType == cardType) {
                            current.cardTypeMarkerField?.takeIf { candidate ->
                                candidate in fields &&
                                    current.fieldMap.none { (_, mapped) -> mapped == candidate }
                            }
                        } else {
                            conventional
                        }
                    current.copy(cardType = cardType, cardTypeMarkerField = marker)
                }
            },
            afterSuccess = ::refreshPersistedTarget,
        )
    }

    fun setCardTypeMarkerField(field: String) {
        val state = currentState()
        if (state.busy) return
        val destination = field.takeIf { it.isNotEmpty() }
        if (destination != null && state.fieldMap.any { (_, mapped) -> mapped == destination }) return
        val fields =
            state.availableNoteTypes.firstOrNull { it.name == state.noteType }?.fieldNames.orEmpty()
        if (destination != null && destination !in fields) return
        if (destination == state.cardTypeMarkerField) return
        persistAnkiSettings(
            transform = { current ->
                if (
                    current.noteType != state.noteType ||
                        (
                            destination != null &&
                                (
                                    destination !in fields ||
                                        current.fieldMap.any { (_, mapped) -> mapped == destination }
                                )
                        )
                ) {
                    current
                } else {
                    current.copy(cardTypeMarkerField = destination)
                }
            },
            afterSuccess = ::refreshPersistedTarget,
        )
    }

    fun verifyNoteType() {
        if (currentState().busy) return
        val state = currentState()
        ankiSetup.refresh(
            state.noteType,
            state.fieldMap,
            state.cardType?.let { state.cardTypeMarkerField },
        )
    }

    private fun persistAnkiSettings(
        transform: (AppSettings) -> AppSettings,
        afterSuccess: (AppSettings) -> Unit,
    ) {
        viewModelScope.launch {
            settingsMutationMutex.withLock {
                try {
                    repository.update(transform)
                    afterSuccess(repository.settings.first())
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    // Keep the prior persisted state. Repeating the same UI action retries it.
                }
            }
        }
    }

    private fun refreshPersistedTarget(settings: AppSettings) {
        ankiSetup.refresh(
            settings.noteType,
            settings.fieldMap,
            settings.cardType?.let { settings.cardTypeMarkerField },
        )
    }

    fun reconcileInterruptedWork() {
        if (!currentState().busy) ankiSetup.reconcileInterruptedWork()
    }

    fun retryStagingCleanup(remediationId: Long) {
        if (!currentState().busy) {
            ankiSetup.performRemediation(AnkiRemediationCommand.RetryStagingCleanup(remediationId))
        }
    }

    fun acknowledgeUnattachedMedia(remediationId: Long) {
        if (!currentState().busy) {
            ankiSetup.performRemediation(AnkiRemediationCommand.AcknowledgeUnattachedMedia(remediationId))
        }
    }

    fun acknowledgeUncertainMedia(remediationId: Long) {
        if (!currentState().busy) {
            ankiSetup.performRemediation(AnkiRemediationCommand.AcknowledgeUncertainMedia(remediationId))
        }
    }

    fun resolveAfterExternalReview(
        remediationId: Long,
        outcome: AnkiExternalReviewOutcome,
    ) {
        if (!currentState().busy) {
            ankiSetup.performRemediation(
                AnkiRemediationCommand.ResolveAfterExternalReview(remediationId, outcome),
            )
        }
    }

    fun dismissAnkiFailure() = ankiSetup.dismissFailure()

    fun installUniDic() {
        if (currentState().busy) return
        viewModelScope.launch { resources.installUniDic() }
    }

    fun installCatalogDictionary(resourceId: String) {
        if (currentState().busy) return
        val status =
            currentState().catalogDictionaries.firstOrNull { it.resource.resourceId == resourceId }
                ?: return
        if (status.slotOccupied) {
            val occupant =
                currentState().dictionaries
                    .firstOrNull { it.occupied && it.slotId == status.resource.slotId }
                    ?.sourceName
                    ?: status.resource.slotId
            setPendingReplace(
                PendingResourceReplace(
                    kind = ResourceReplaceKind.CATALOG_DICTIONARY,
                    identity = resourceId,
                    installedLabel = occupant,
                    repair = status.needsRepair,
                ),
            )
        } else {
            viewModelScope.launch { resources.installCatalogDictionary(resourceId, replace = false) }
        }
    }

    /** Dispatches the import the pending record describes, this time authorised to overwrite. */
    fun confirmPendingReplace() {
        val state = currentState()
        if (state.busy) return
        val pending = state.pendingReplace ?: return
        val picker = pendingPicker?.takeIf { it.kind.toReplaceKindOrNull() == pending.kind }
        clearPendingReplace(clearPicker = true)
        viewModelScope.launch {
            when (pending.kind) {
                ResourceReplaceKind.CATALOG_DICTIONARY ->
                    resources.installCatalogDictionary(pending.identity, replace = true)
                ResourceReplaceKind.CUSTOM_DICTIONARY ->
                    resources.importCustomDictionary(
                        requireNotNull(pending.uri),
                        pending.identity,
                        replace = true,
                    )
                ResourceReplaceKind.FREQUENCY ->
                    resources.importFrequencySource(
                        uri = requireNotNull(pending.uri),
                        // The record's identity, not a freshly derived one: a name match targets
                        // the id already on disk so the priority chain entry survives.
                        sourceId = pending.identity,
                        sourceName = requireNotNull(picker?.sourceName),
                        format = requireNotNull(picker?.frequencyFormat),
                        replace = true,
                    )
                ResourceReplaceKind.PITCH ->
                    resources.importPitchAccent(
                        uri = requireNotNull(pending.uri),
                        // As with frequency: the record's identity, so a name match
                        // replaces the slot already on disk and keeps its chain entry.
                        sourceId = pending.identity,
                        sourceName = requireNotNull(picker?.sourceName),
                        format = requireNotNull(picker?.pitchFormat),
                        replace = true,
                    )
                ResourceReplaceKind.AUDIO_PACK ->
                    resources.importAudioPack(
                        requireNotNull(pending.uri),
                        pending.identity,
                        replace = true,
                    )
            }
        }
    }

    fun dismissPendingReplace() {
        val discardAudioPreflight =
            currentState().pendingReplace?.kind == ResourceReplaceKind.AUDIO_PACK
        val picker = pendingPicker
        clearPendingReplace(clearPicker = true)
        releaseRetainedResourceImport(picker)
        if (discardAudioPreflight) {
            viewModelScope.launch { resources.discardAudioPackPreflight() }
        }
    }

    fun beginCustomDictionaryPicker(): Boolean {
        val state = currentState()
        if (state.busy) return false
        val request = customDictionaryPickerRequest(state) ?: return false
        savePendingPicker(request)
        return true
    }

    fun onCustomDictionaryPicked(uri: String?) =
        finishPicker(
            ResourcePickerKind.CUSTOM_DICTIONARY,
            uri,
            fallback = { customDictionaryPickerRequest(currentState()) },
        )

    fun importCustomDictionary(uri: String) {
        savePendingPicker(customDictionaryPickerRequest(currentState()) ?: return)
        onCustomDictionaryPicked(uri)
    }

    fun setCustomSlotId(value: String) {
        if (value.length <= 64) {
            val normalized = value.lowercase()
            savedStateHandle[STATE_CUSTOM_SLOT_ID] = normalized
            local.update { it.copy(customSlotId = normalized) }
        }
    }

    fun beginFrequencyPicker(): Boolean {
        if (currentState().busy) return false
        return true
    }

    fun onFrequencyPicked(uri: String?) {
        if (uri == null) {
            discardPendingResourceImport(ResourcePickerKind.FREQUENCY)
            return
        }
        viewModelScope.launch {
            val source = resources.retainResourceImport(uri)
            savePendingPicker(frequencyPickerRequest(source, currentState()))
            resumePendingPicker()
        }
    }

    fun importFrequencySource(uri: String) = onFrequencyPicked(uri)

    fun beginPitchPicker(): Boolean {
        if (currentState().busy) return false
        return true
    }

    fun onPitchPicked(uri: String?) {
        if (uri == null) {
            discardPendingResourceImport(ResourcePickerKind.PITCH)
            return
        }
        viewModelScope.launch {
            val source = resources.retainResourceImport(uri)
            savePendingPicker(pitchPickerRequest(source, currentState()))
            resumePendingPicker()
        }
    }

    fun importPitchAccent(uri: String) = onPitchPicked(uri)

    fun beginAudioPackPicker(): Boolean {
        if (currentState().busy) return false
        savePendingPicker(PendingResourcePicker(kind = ResourcePickerKind.AUDIO_PACK))
        return true
    }

    fun onAudioPackPicked(uri: String?) =
        finishPicker(
            ResourcePickerKind.AUDIO_PACK,
            uri,
            fallback = { PendingResourcePicker(kind = ResourcePickerKind.AUDIO_PACK) },
        )

    fun importAudioPack(uri: String) {
        savePendingPicker(PendingResourcePicker(kind = ResourcePickerKind.AUDIO_PACK))
        onAudioPackPicked(uri)
    }

    /**
     * Holds the import for confirmation when [target] collides. Returns true when it did, so the
     * caller must not also dispatch.
     */
    private fun stagePendingReplace(
        kind: ResourceReplaceKind,
        target: ResourceImportTarget,
        uri: String,
    ): Boolean {
        val installedLabel = target.installedName ?: return false
        setPendingReplace(
            PendingResourceReplace(
                kind = kind,
                identity = target.identity,
                installedLabel = installedLabel,
                uri = uri,
            ),
        )
        return true
    }

    private fun customDictionaryPickerRequest(state: SetupUiState): PendingResourcePicker? {
        if (!SLOT_ID.matches(state.customSlotId)) return null
        return PendingResourcePicker(
            kind = ResourcePickerKind.CUSTOM_DICTIONARY,
            target =
                ResourceIdentity.customDictionaryTarget(
                    state.customSlotId,
                    state.dictionaries,
                ),
        )
    }

    private fun frequencyPickerRequest(
        source: RetainedResourceImport,
        state: SetupUiState,
    ): PendingResourcePicker {
        val sourceName = derivedSourceName(source, R.string.setup_default_frequency_name)
        return PendingResourcePicker(
            kind = ResourcePickerKind.FREQUENCY,
            target =
                ResourceIdentity.frequencyTarget(
                    sourceName,
                    state.frequencySources,
                ),
            sourceName = sourceName,
            frequencyFormat = source.fileKind.toFrequencyFormat(),
            uri = source.uri,
        )
    }

    private fun pitchPickerRequest(
        source: RetainedResourceImport,
        state: SetupUiState,
    ): PendingResourcePicker {
        val sourceName = derivedSourceName(source, R.string.setup_default_pitch_name)
        return PendingResourcePicker(
            kind = ResourcePickerKind.PITCH,
            target = ResourceIdentity.pitchTarget(sourceName, state.pitchSources),
            sourceName = sourceName,
            pitchFormat = source.fileKind.toPitchFormat(),
            uri = source.uri,
        )
    }

    private fun finishPicker(
        kind: ResourcePickerKind,
        uri: String?,
        matches: (PendingResourcePicker) -> Boolean = { true },
        fallback: () -> PendingResourcePicker?,
    ) {
        if (uri == null) {
            if (pendingPicker?.kind == kind) clearPendingPicker()
            return
        }
        val request =
            pendingPicker?.takeIf { it.kind == kind && matches(it) } ?: fallback() ?: return
        savePendingPicker(request.copy(uri = uri))
        resumePendingPicker()
    }

    private fun resumePendingPicker() {
        if (pendingPicker?.uri == null || pendingPickerJob?.isActive == true) return
        pendingPickerJob =
            viewModelScope.launch {
                var request = pendingPicker?.takeIf { it.uri != null } ?: return@launch
                if (request.kind == ResourcePickerKind.AUDIO_PACK && request.target == null) {
                    val uri = requireNotNull(request.uri)
                    val packId = resources.preflightAudioPack(uri)
                    if (packId == null) {
                        clearPendingPicker()
                        return@launch
                    }
                    request =
                        request.copy(
                            target =
                                ResourceIdentity.audioPackTarget(
                                    packId,
                                    resources.state.value.audioPacks,
                                ),
                        )
                    savePendingPicker(request)
                }
                resources.state.first { state ->
                    state.startupReadiness == ResourceStartupReadiness.READY &&
                        state.activeOperation == null
                }
                runtimeWorkState.first { it == null }
                val persisted = pendingPicker?.takeIf { it.uri != null } ?: return@launch
                dispatchPendingPicker(persisted)
            }
    }

    private suspend fun dispatchPendingPicker(request: PendingResourcePicker) {
        val uri = requireNotNull(request.uri)
        val replaceKind = request.kind.toReplaceKindOrNull()
        if (
            replaceKind != null &&
                stagePendingReplace(
                    replaceKind,
                    requireNotNull(request.target),
                    uri,
                )
        ) {
            return
        }
        clearPendingPicker()
        when (request.kind) {
            ResourcePickerKind.CUSTOM_DICTIONARY ->
                resources.importCustomDictionary(
                    uri,
                    requireNotNull(request.target).identity,
                    replace = false,
                )
            ResourcePickerKind.FREQUENCY ->
                resources.importFrequencySource(
                    uri = uri,
                    sourceId = requireNotNull(request.target).identity,
                    sourceName = requireNotNull(request.sourceName),
                    format = requireNotNull(request.frequencyFormat),
                    replace = false,
                )
            ResourcePickerKind.PITCH ->
                resources.importPitchAccent(
                    uri = uri,
                    sourceId = requireNotNull(request.target).identity,
                    sourceName = requireNotNull(request.sourceName),
                    format = requireNotNull(request.pitchFormat),
                    replace = false,
                )
            ResourcePickerKind.AUDIO_PACK ->
                resources.importAudioPack(
                    uri,
                    requireNotNull(request.target).identity,
                    replace = false,
                )
            ResourcePickerKind.KNOWN_WORDS ->
                resources.previewKnownWords(
                    uri,
                    requireNotNull(request.resourceFileKind),
                )
            ResourcePickerKind.WORD_LIST ->
                resources.importWordList(uri, requireNotNull(request.wordListKind))
        }
    }

    private fun ResourcePickerKind.toReplaceKindOrNull(): ResourceReplaceKind? =
        when (this) {
            ResourcePickerKind.CUSTOM_DICTIONARY -> ResourceReplaceKind.CUSTOM_DICTIONARY
            ResourcePickerKind.FREQUENCY -> ResourceReplaceKind.FREQUENCY
            ResourcePickerKind.PITCH -> ResourceReplaceKind.PITCH
            ResourcePickerKind.AUDIO_PACK -> ResourceReplaceKind.AUDIO_PACK
            // Word lists and known words overwrite in place; neither has a replace prompt.
            ResourcePickerKind.KNOWN_WORDS, ResourcePickerKind.WORD_LIST -> null
        }

    private fun savePendingPicker(request: PendingResourcePicker) {
        pendingPicker = request
        saveString(STATE_PICKER_KIND, request.kind.name)
        saveString(STATE_PICKER_TARGET_ID, request.target?.identity)
        saveString(STATE_PICKER_INSTALLED_LABEL, request.target?.installedName)
        saveString(STATE_PICKER_SOURCE_NAME, request.sourceName)
        saveString(STATE_PICKER_FREQUENCY_FORMAT, request.frequencyFormat?.name)
        saveString(STATE_PICKER_PITCH_FORMAT, request.pitchFormat?.name)
        saveString(STATE_PICKER_RESOURCE_FILE_KIND, request.resourceFileKind?.name)
        saveString(STATE_PICKER_WORD_LIST_KIND, request.wordListKind?.name)
        saveString(STATE_PICKER_URI, request.uri)
    }

    private fun restorePendingPicker(): PendingResourcePicker? {
        val kind = savedEnum<ResourcePickerKind>(STATE_PICKER_KIND) ?: return null
        val targetId = savedStateHandle.get<String>(STATE_PICKER_TARGET_ID)
        val target =
            targetId?.let {
                ResourceImportTarget(
                    identity = it,
                    installedName = savedStateHandle[STATE_PICKER_INSTALLED_LABEL],
                )
            }
        val restored =
            PendingResourcePicker(
                kind = kind,
                target = target,
                sourceName = savedStateHandle[STATE_PICKER_SOURCE_NAME],
                frequencyFormat = savedEnum<FrequencySourceFormat>(STATE_PICKER_FREQUENCY_FORMAT),
                pitchFormat = savedEnum<PitchAccentSourceFormat>(STATE_PICKER_PITCH_FORMAT),
                resourceFileKind = savedEnum<ResourceImportFileKind>(STATE_PICKER_RESOURCE_FILE_KIND),
                wordListKind = savedEnum<WordListKind>(STATE_PICKER_WORD_LIST_KIND),
                uri = savedStateHandle[STATE_PICKER_URI],
            )
        return restored.takeIf { request ->
            when (request.kind) {
                ResourcePickerKind.CUSTOM_DICTIONARY -> request.target != null
                ResourcePickerKind.AUDIO_PACK -> true
                ResourcePickerKind.FREQUENCY ->
                    request.target != null &&
                        request.sourceName != null &&
                        request.frequencyFormat != null
                ResourcePickerKind.PITCH ->
                    request.target != null &&
                        request.sourceName != null &&
                        request.pitchFormat != null
                ResourcePickerKind.KNOWN_WORDS -> request.resourceFileKind != null
                ResourcePickerKind.WORD_LIST -> request.wordListKind != null
            }
        }
    }

    private fun clearPendingPicker() {
        pendingPicker = null
        listOf(
            STATE_PICKER_KIND,
            STATE_PICKER_TARGET_ID,
            STATE_PICKER_INSTALLED_LABEL,
            STATE_PICKER_SOURCE_NAME,
            STATE_PICKER_FREQUENCY_FORMAT,
            STATE_PICKER_PITCH_FORMAT,
            STATE_PICKER_RESOURCE_FILE_KIND,
            STATE_PICKER_WORD_LIST_KIND,
            STATE_PICKER_URI,
        ).forEach { savedStateHandle.remove<Any>(it) }
    }

    private fun discardPendingResourceImport(kind: ResourcePickerKind) {
        val picker = pendingPicker?.takeIf { it.kind == kind } ?: return
        clearPendingPicker()
        releaseRetainedResourceImport(picker)
    }

    private fun releaseRetainedResourceImport(picker: PendingResourcePicker?) {
        val uri =
            picker
                ?.takeIf {
                    it.kind == ResourcePickerKind.FREQUENCY ||
                        it.kind == ResourcePickerKind.PITCH ||
                        it.kind == ResourcePickerKind.KNOWN_WORDS
                }
                ?.uri
                ?: return
        viewModelScope.launch { resources.releaseResourceImport(uri) }
    }

    private fun setPendingReplace(pending: PendingResourceReplace) {
        savedStateHandle[STATE_REPLACE_KIND] = pending.kind.name
        savedStateHandle[STATE_REPLACE_IDENTITY] = pending.identity
        savedStateHandle[STATE_REPLACE_LABEL] = pending.installedLabel
        saveString(STATE_REPLACE_URI, pending.uri)
        savedStateHandle[STATE_REPLACE_REPAIR] = pending.repair
        local.update { it.copy(pendingReplace = pending) }
    }

    private fun restorePendingReplace(): PendingResourceReplace? {
        val kind = savedEnum<ResourceReplaceKind>(STATE_REPLACE_KIND) ?: return null
        val identity = savedStateHandle.get<String>(STATE_REPLACE_IDENTITY) ?: return null
        val label = savedStateHandle.get<String>(STATE_REPLACE_LABEL) ?: return null
        return PendingResourceReplace(
            kind = kind,
            identity = identity,
            installedLabel = label,
            uri = savedStateHandle[STATE_REPLACE_URI],
            repair = savedStateHandle[STATE_REPLACE_REPAIR] ?: false,
        )
    }

    private fun clearPendingReplace(clearPicker: Boolean) {
        listOf(
            STATE_REPLACE_KIND,
            STATE_REPLACE_IDENTITY,
            STATE_REPLACE_LABEL,
            STATE_REPLACE_URI,
            STATE_REPLACE_REPAIR,
        ).forEach { savedStateHandle.remove<Any>(it) }
        local.update { it.copy(pendingReplace = null) }
        if (clearPicker) clearPendingPicker()
    }

    private inline fun <reified T : Enum<T>> savedEnum(key: String): T? =
        savedStateHandle.get<String>(key)?.let { saved ->
            enumValues<T>().firstOrNull { it.name == saved }
        }

    private fun saveString(
        key: String,
        value: String?,
    ) {
        if (value == null) {
            savedStateHandle.remove<String>(key)
        } else {
            savedStateHandle[key] = value
        }
    }

    /**
     * Trim and cap to what [ResourceBridgeCodec] accepts. It requires a trimmed, non-blank name of
     * at most 512 bytes with no control characters, and trim() alone leaves interior ones - the
     * resulting IllegalArgumentException would surface as a generic operation failure.
     */
    private fun sanitizeDisplayName(value: String): String {
        val stripped = value.filter { it.code >= 0x20 }.trim()
        var candidate = UnicodeContractV151.normalizeNfc(stripped) ?: return ""
        while ((UnicodeContractV151.strictUtf8Length(candidate) ?: Int.MAX_VALUE) > 512) {
            if (candidate.isEmpty()) return ""
            candidate =
                candidate.dropLast(
                    Character.charCount(candidate.codePointBefore(candidate.length)),
                )
        }
        return candidate.trim()
    }

    private fun derivedSourceName(
        source: RetainedResourceImport,
        defaultNameResource: Int,
    ): String {
        val extension =
            source.displayName.substringAfterLast(
                delimiter = '.',
                missingDelimiterValue = "",
            )
        val stem =
            if (
                extension.equals("zip", ignoreCase = true) ||
                    extension.equals("csv", ignoreCase = true) ||
                    extension.equals("tsv", ignoreCase = true) ||
                    extension.equals("txt", ignoreCase = true) ||
                    extension.equals("text", ignoreCase = true) ||
                    extension.equals("json", ignoreCase = true)
            ) {
                source.displayName.substringBeforeLast('.')
            } else {
                source.displayName
            }
        return sanitizeDisplayName(stem).ifBlank { strings.resolve(defaultNameResource) }
    }

    private fun ResourceImportFileKind.toFrequencyFormat(): FrequencySourceFormat =
        when (this) {
            ResourceImportFileKind.YOMITAN_ZIP -> FrequencySourceFormat.YOMITAN_ZIP
            ResourceImportFileKind.JSON -> FrequencySourceFormat.TEXT
            ResourceImportFileKind.CSV -> FrequencySourceFormat.CSV
            ResourceImportFileKind.TSV -> FrequencySourceFormat.TSV
            ResourceImportFileKind.TEXT -> FrequencySourceFormat.TEXT
        }

    private fun ResourceImportFileKind.toPitchFormat(): PitchAccentSourceFormat =
        when (this) {
            ResourceImportFileKind.YOMITAN_ZIP -> PitchAccentSourceFormat.YOMITAN_ZIP
            ResourceImportFileKind.TSV -> PitchAccentSourceFormat.TSV
            ResourceImportFileKind.JSON,
            ResourceImportFileKind.CSV,
            ResourceImportFileKind.TEXT,
            -> PitchAccentSourceFormat.CSV
        }

    fun beginKnownWordsPicker(): Boolean {
        if (currentState().busy) return false
        return true
    }

    fun onKnownWordsPicked(uri: String?) {
        if (uri == null) {
            discardPendingResourceImport(ResourcePickerKind.KNOWN_WORDS)
            return
        }
        viewModelScope.launch {
            val source = resources.retainResourceImport(uri)
            savePendingPicker(
                PendingResourcePicker(
                    kind = ResourcePickerKind.KNOWN_WORDS,
                    resourceFileKind = source.fileKind,
                    uri = source.uri,
                ),
            )
            resumePendingPicker()
        }
    }

    fun importKnownWords(uri: String) = onKnownWordsPicked(uri)

    /**
     * The SAF result, not a picker launch: a recreated ViewModel is still recovering when it
     * arrives, so this holds the pick like every other import instead of dropping it on [busy].
     *
     * Word lists are the only kind reaching the single pending slot without a `begin…Picker`
     * gate, so this is also the only entry point that can meet a queued pick. That one is already
     * confirmed work waiting on [resumePendingPicker]; it wins — including a queued pick for the
     * other list, which the whitelist and blacklist launchers can otherwise displace. Only a
     * repeat pick for the same list gets through, because that replaces its own slot.
     */
    fun importWordList(uri: String, kind: WordListKind) {
        setWordListTarget(kind)
        val queued =
            pendingPicker?.takeIf {
                it.uri != null &&
                    (it.kind != ResourcePickerKind.WORD_LIST || it.wordListKind != kind)
            }
        if (queued != null) {
            AppLog.i(
                LogComponent.UI,
                "picker.result",
                "picker" to "word_list",
                "list" to kind.name,
                "queued" to queued.kind.name,
                "queuedList" to queued.wordListKind?.name,
                "outcome" to "skip",
            )
            return
        }
        finishPicker(
            kind = ResourcePickerKind.WORD_LIST,
            uri = uri,
            // The launcher knows which list this result belongs to; a restored pick for the
            // other list must not capture it.
            matches = { it.wordListKind == kind },
            fallback = {
                PendingResourcePicker(kind = ResourcePickerKind.WORD_LIST, wordListKind = kind)
            },
        )
    }

    fun removeWordList(kind: WordListKind) {
        if (currentState().busy) return
        setWordListTarget(kind)
        viewModelScope.launch { resources.removeWordList(kind) }
    }

    /** Persisted: a failed word-list operation offers the picker for [kind] after process death. */
    private fun setWordListTarget(kind: WordListKind) {
        savedStateHandle[STATE_WORD_LIST_TARGET] = kind.name
        local.update { it.copy(wordListTarget = kind) }
    }

    fun confirmKnownWordsImport() {
        if (!currentState().busy) viewModelScope.launch { resources.confirmKnownWordsImport() }
    }

    fun dismissKnownWordsImportPreview() = resources.dismissKnownWordsImportPreview()

    fun setKnownWordsSearch(value: String) {
        if (value.toByteArray().size <= 1024) local.update { it.copy(knownWordsSearch = value) }
    }

    fun searchKnownWords() {
        val state = currentState()
        if (!state.busy) viewModelScope.launch { resources.searchKnownWords(state.knownWordsSearch) }
    }

    fun loadMoreKnownWords() {
        val state = currentState()
        if (!state.busy) {
            viewModelScope.launch { resources.searchKnownWords(state.knownWordsSearch, loadMore = true) }
        }
    }

    fun removeKnownWord(word: String) {
        if (!currentState().busy) viewModelScope.launch { resources.removeKnownWords(listOf(word)) }
    }

    fun resetKnownWords(scope: KnownWordsResetScope) {
        if (!currentState().busy) viewModelScope.launch { resources.resetKnownWords(scope) }
    }

    fun exportKnownWords(uri: String) {
        if (!currentState().busy) viewModelScope.launch { resources.exportKnownWords(uri) }
    }

    fun setLookupTerm(value: String) {
        if (value.toByteArray().size <= 1024) local.update { it.copy(lookupTerm = value) }
    }

    fun setLookupSlot(value: String) {
        if (currentState().dictionaries.any { it.isUsable && it.slotId == value }) {
            local.update { it.copy(lookupSlotId = value) }
        }
    }

    fun lookup() {
        val state = currentState()
        val slot = state.lookupSlotId ?: return
        if (state.busy || state.lookupTerm.isBlank()) return
        viewModelScope.launch { resources.lookup(slot, state.lookupTerm) }
    }

    fun cancelOperation() = resources.cancelActive()

    fun dismissFailure() = resources.dismissFailure()

    fun retryResourceFailure() {
        val state = currentState()
        val failure = state.failure ?: return
        if (
            state.busy &&
                !(
                    failure.origin == ResourceFailureOrigin.SETUP &&
                        state.resourceStartup == ResourceStartupReadiness.FAILED
                )
        ) {
            return
        }
        when (failure.origin) {
            ResourceFailureOrigin.SETUP -> refresh()
            ResourceFailureOrigin.UNIDIC ->
                viewModelScope.launch { resources.installUniDic() }
            ResourceFailureOrigin.CATALOG_DICTIONARY -> {
                val resourceId = failure.retry.targetId ?: return
                viewModelScope.launch {
                    resources.installCatalogDictionary(
                        resourceId,
                        replace = failure.retry.replace,
                    )
                }
            }
            ResourceFailureOrigin.DICTIONARY_LOOKUP -> lookup()
            ResourceFailureOrigin.KNOWN_WORDS ->
                when (failure.knownWordsOperation) {
                    KnownWordsFailureOperation.IMPORT,
                    null,
                    -> viewModelScope.launch { resources.retryKnownWordsFailure() }
                    KnownWordsFailureOperation.PREVIEW,
                    KnownWordsFailureOperation.EXPORT,
                    -> searchKnownWords()
                }
            // Both offer a file picker instead, which only the composable can open.
            ResourceFailureOrigin.CUSTOM_DICTIONARY,
            ResourceFailureOrigin.PITCH,
            ResourceFailureOrigin.AUDIO,
            ResourceFailureOrigin.FREQUENCY,
            ResourceFailureOrigin.WORD_LIST,
            -> Unit
        }
    }

    fun permissionsReturned() = refreshExternalReadiness()

    /** Persist completion; failure remains in state so first launch can retry or escape this session. */
    fun markWizardSeen() {
        val current = local.value.wizardCompletion
        if (
            current == WizardCompletionStatus.SAVING ||
                current == WizardCompletionStatus.PERSISTED
        ) {
            return
        }
        val requested =
            reduceWizardCompletion(
                current,
                WizardCompletionEvent.REQUEST_PERSISTENCE,
            )
        if (requested != WizardCompletionStatus.SAVING) return
        local.update { it.copy(wizardCompletion = requested) }
        viewModelScope.launch {
            try {
                repository.update { it.copy(setupWizardSeen = true) }
                local.update {
                    it.copy(
                        wizardCompletion =
                            reduceWizardCompletion(
                                it.wizardCompletion,
                                WizardCompletionEvent.PERSISTENCE_SUCCEEDED,
                            ),
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                local.update {
                    it.copy(
                        wizardCompletion =
                            reduceWizardCompletion(
                                it.wizardCompletion,
                                WizardCompletionEvent.PERSISTENCE_FAILED,
                            ),
                    )
                }
            }
        }
    }

    fun retryWizardCompletion() = markWizardSeen()

    fun dismissWizardForSession() {
        if (local.value.wizardCompletion != WizardCompletionStatus.FAILED) return
        _wizardDismissedForSession.value = true
        local.update {
            it.copy(
                wizardCompletion =
                    reduceWizardCompletion(
                        it.wizardCompletion,
                        WizardCompletionEvent.DISMISS_FOR_SESSION,
                    ),
            )
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
        private val strings: StringResourceResolver,
        private val savedStateHandleFactory: (CreationExtras) -> SavedStateHandle =
            { extras -> extras.createSavedStateHandle() },
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
                strings,
                savedStateHandleFactory(extras),
            ) as T
        }
    }

    private companion object {
        val SLOT_ID = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
        const val STATE_CUSTOM_SLOT_ID = "setup.customSlotId"
        const val STATE_WORD_LIST_TARGET = "setup.wordListTarget"
        const val STATE_PICKER_KIND = "setup.picker.kind"
        const val STATE_PICKER_TARGET_ID = "setup.picker.targetId"
        const val STATE_PICKER_INSTALLED_LABEL = "setup.picker.installedLabel"
        const val STATE_PICKER_SOURCE_NAME = "setup.picker.sourceName"
        const val STATE_PICKER_FREQUENCY_FORMAT = "setup.picker.frequencyFormat"
        const val STATE_PICKER_PITCH_FORMAT = "setup.picker.pitchFormat"
        const val STATE_PICKER_RESOURCE_FILE_KIND = "setup.picker.resourceFileKind"
        const val STATE_PICKER_WORD_LIST_KIND = "setup.picker.wordListKind"
        const val STATE_PICKER_URI = "setup.picker.uri"
        const val STATE_REPLACE_KIND = "setup.replace.kind"
        const val STATE_REPLACE_IDENTITY = "setup.replace.identity"
        const val STATE_REPLACE_LABEL = "setup.replace.label"
        const val STATE_REPLACE_URI = "setup.replace.uri"
        const val STATE_REPLACE_REPAIR = "setup.replace.repair"
    }
}
