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
import com.ankiminer.android.data.anki.AnkiSetupManager
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.ResourceIdentity
import com.ankiminer.android.data.resources.ResourceImportTarget
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.KnownWordsResetScope
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.localization.StringResourceResolver
import com.ankiminer.android.mining.MiningRunAdmissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val strings: StringResourceResolver,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private data class LocalState(
        val lookupTerm: String = "猫",
        val lookupSlotId: String? = null,
        val customSlotId: String = "custom-dictionary",
        val frequencySourceName: String,
        val frequencyFormat: FrequencySourceFormat = FrequencySourceFormat.YOMITAN_ZIP,
        val pitchSourceName: String,
        val pitchFormat: PitchAccentSourceFormat = PitchAccentSourceFormat.YOMITAN_ZIP,
        val audioPackId: String = "audio-pack",
        val knownWordsFormat: KnownWordsSourceFormat = KnownWordsSourceFormat.JSON,
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
                frequencySourceName = strings.resolve(R.string.setup_default_frequency_name),
                pitchSourceName = strings.resolve(R.string.setup_default_pitch_name),
            ),
        )
    /** In-memory only: failed persistence must re-open the wizard in a fresh ViewModel. */
    private val _wizardDismissedForSession = MutableStateFlow(false)
    val wizardDismissedForSession: StateFlow<Boolean> = _wizardDismissedForSession.asStateFlow()
    private val settings = settingsRepository.settings
    private val repository = settingsRepository

    private enum class PendingSettingsImportKind {
        CUSTOM_DICTIONARY,
        FREQUENCY,
        PITCH,
        AUDIO_PACK,
        KNOWN_WORDS,
    }

    private data class PendingSettingsImport(
        val kind: PendingSettingsImportKind,
        val targetIdentity: String? = null,
        val sourceName: String? = null,
        val format: String? = null,
    )

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
                frequencySourceName = localState.frequencySourceName,
                frequencyFormat = localState.frequencyFormat,
                pitchSourceName = localState.pitchSourceName,
                pitchFormat = localState.pitchFormat,
                audioPackId = localState.audioPackId,
                knownWordsFormat = localState.knownWordsFormat,
                wordListTarget = localState.wordListTarget,
                knownWordsSearch = localState.knownWordsSearch,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SetupUiState(),
        )

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
            frequencySourceName = localState.frequencySourceName,
            frequencyFormat = localState.frequencyFormat,
            pitchSourceName = localState.pitchSourceName,
            pitchFormat = localState.pitchFormat,
            audioPackId = localState.audioPackId,
            knownWordsFormat = localState.knownWordsFormat,
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
        val merged =
            AnkiFieldMapPolicy.merge(
                currentNoteType = state.noteType,
                selectedNoteType = name,
                fieldNames = fields,
                currentFieldMap = state.fieldMap,
            )
        viewModelScope.launch {
            repository.update { it.copy(noteType = name, fieldMap = merged.fieldMap) }
            local.update { it.copy(fieldMapChanges = merged.changes) }
            ankiSetup.refresh(name, merged.fieldMap)
        }
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
        val map = AnkiFieldMapPolicy.assign(state.fieldMap, key, field, fields) ?: return
        if (map == state.fieldMap) return
        val noteType = state.noteType
        viewModelScope.launch {
            repository.update { it.copy(fieldMap = map) }
            local.update { it.copy(fieldMapChanges = emptyList()) }
            ankiSetup.refresh(noteType, map)
        }
    }

    /**
     * Pick a card mode. The conventional JP Mining Note field is preselected when the note type has
     * it and nothing else is mapped to it; otherwise the marker stays unset and the mode stays off
     * until the user chooses a field.
     */
    fun selectCardType(cardType: CardType?) {
        val state = currentState()
        if (state.busy) return
        if (cardType == null) {
            viewModelScope.launch {
                repository.update { it.copy(cardType = null, cardTypeMarkerField = null) }
            }
            return
        }
        val fields =
            state.availableNoteTypes.firstOrNull { it.name == state.noteType }?.fieldNames.orEmpty()
        val conventional =
            cardType.conventionalField.takeIf { candidate ->
                candidate in fields && state.fieldMap.none { (_, mapped) -> mapped == candidate }
            }
        // A marker belongs to one mode, so switching modes re-derives it instead of carrying the
        // previous mode's field over.
        val marker =
            if (state.cardType == cardType) {
                state.cardTypeMarkerField?.takeIf { it in fields }
            } else {
                conventional
            }
        viewModelScope.launch {
            repository.update { it.copy(cardType = cardType, cardTypeMarkerField = marker) }
        }
    }

    fun setCardTypeMarkerField(field: String) {
        val state = currentState()
        if (state.busy) return
        val destination = field.takeIf { it.isNotEmpty() }
        if (destination != null && state.fieldMap.any { (_, mapped) -> mapped == destination }) return
        viewModelScope.launch { repository.update { it.copy(cardTypeMarkerField = destination) } }
    }

    fun verifyNoteType() {
        if (currentState().busy) return
        ankiSetup.refresh(currentState().noteType, currentState().fieldMap)
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
            local.update {
                it.copy(
                    pendingReplace =
                        PendingResourceReplace(
                            kind = ResourceReplaceKind.CATALOG_DICTIONARY,
                            identity = resourceId,
                            installedLabel = occupant,
                            repair = status.needsRepair,
                        ),
                )
            }
        } else {
            viewModelScope.launch { resources.installCatalogDictionary(resourceId, replace = false) }
        }
    }

    /** Dispatches the import the pending record describes, this time authorised to overwrite. */
    fun confirmPendingReplace() {
        val state = currentState()
        if (state.busy) return
        val pending = state.pendingReplace ?: return
        local.update { it.copy(pendingReplace = null) }
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
                        sourceName = requireNotNull(pending.sourceName),
                        format = requireNotNull(pending.frequencyFormat),
                        replace = true,
                    )
                ResourceReplaceKind.PITCH ->
                    resources.importPitchAccent(
                        uri = requireNotNull(pending.uri),
                        // As with frequency: the record's identity, so a name match
                        // replaces the slot already on disk and keeps its chain entry.
                        sourceId = pending.identity,
                        sourceName = requireNotNull(pending.sourceName),
                        format = requireNotNull(pending.pitchFormat),
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
        local.update { it.copy(pendingReplace = null) }
    }

    /** Snapshot picker metadata before Android may recreate this ViewModel. */
    fun prepareCustomDictionaryImport(): Boolean {
        val state = currentState()
        if (state.busy || !SLOT_ID.matches(state.customSlotId)) return false
        savePendingSettingsImport(
            PendingSettingsImport(
                kind = PendingSettingsImportKind.CUSTOM_DICTIONARY,
                targetIdentity = state.customSlotId,
            ),
        )
        return true
    }

    /** Snapshot picker metadata before Android may recreate this ViewModel. */
    fun prepareFrequencyImport(): Boolean {
        val state = currentState()
        if (state.busy || state.frequencySourceName.isBlank()) return false
        val target = ResourceIdentity.frequencyTarget(state.frequencySourceName, state.frequencySources)
        savePendingSettingsImport(
            PendingSettingsImport(
                kind = PendingSettingsImportKind.FREQUENCY,
                targetIdentity = target.identity,
                sourceName = state.frequencySourceName,
                format = state.frequencyFormat.name,
            ),
        )
        return true
    }

    /** Snapshot picker metadata before Android may recreate this ViewModel. */
    fun preparePitchAccentImport(): Boolean {
        val state = currentState()
        if (state.busy || state.pitchSourceName.isBlank()) return false
        val target = ResourceIdentity.pitchTarget(state.pitchSourceName, state.pitchSources)
        savePendingSettingsImport(
            PendingSettingsImport(
                kind = PendingSettingsImportKind.PITCH,
                targetIdentity = target.identity,
                sourceName = state.pitchSourceName,
                format = state.pitchFormat.name,
            ),
        )
        return true
    }

    /** Snapshot picker metadata before Android may recreate this ViewModel. */
    fun prepareAudioPackImport(): Boolean {
        val state = currentState()
        if (state.busy || !state.audioPackIdValid) return false
        savePendingSettingsImport(
            PendingSettingsImport(
                kind = PendingSettingsImportKind.AUDIO_PACK,
                targetIdentity = state.audioPackId,
            ),
        )
        return true
    }

    /** Snapshot picker metadata before Android may recreate this ViewModel. */
    fun prepareKnownWordsImport(): Boolean {
        val state = currentState()
        if (state.busy) return false
        savePendingSettingsImport(
            PendingSettingsImport(
                kind = PendingSettingsImportKind.KNOWN_WORDS,
                format = state.knownWordsFormat.name,
            ),
        )
        return true
    }

    /** Consume a restored picker result using exactly the metadata visible when it was launched. */
    fun completePendingSettingsImport(uri: String?) {
        val pending = pendingSettingsImport() ?: return
        if (uri == null) {
            clearPendingSettingsImport()
            return
        }
        val state = currentState()
        if (state.busy) return
        val resourceState = resources.state.value
        when (pending.kind) {
            PendingSettingsImportKind.CUSTOM_DICTIONARY -> {
                val target =
                    ResourceImportTarget(
                        identity = requireNotNull(pending.targetIdentity),
                        installedName =
                            resourceState.dictionaries
                                .firstOrNull { it.occupied && it.slotId == pending.targetIdentity }
                                ?.sourceName,
                    )
                dispatchCustomDictionaryImport(uri, target)
            }
            PendingSettingsImportKind.FREQUENCY -> {
                val target =
                    ResourceImportTarget(
                        identity = requireNotNull(pending.targetIdentity),
                        installedName =
                            resourceState.frequencySources
                                .firstOrNull { it.sourceId == pending.targetIdentity }
                                ?.sourceName,
                    )
                dispatchFrequencyImport(
                    uri = uri,
                    target = target,
                    sourceName = requireNotNull(pending.sourceName),
                    format = FrequencySourceFormat.valueOf(requireNotNull(pending.format)),
                )
            }
            PendingSettingsImportKind.PITCH -> {
                val target =
                    ResourceImportTarget(
                        identity = requireNotNull(pending.targetIdentity),
                        installedName =
                            resourceState.pitchSources
                                .firstOrNull { it.sourceId == pending.targetIdentity }
                                ?.sourceName,
                    )
                dispatchPitchImport(
                    uri = uri,
                    target = target,
                    sourceName = requireNotNull(pending.sourceName),
                    format = PitchAccentSourceFormat.valueOf(requireNotNull(pending.format)),
                )
            }
            PendingSettingsImportKind.AUDIO_PACK -> {
                val target =
                    ResourceImportTarget(
                        identity = requireNotNull(pending.targetIdentity),
                        installedName =
                            resourceState.audioPacks
                                .firstOrNull { it.packId == pending.targetIdentity }
                                ?.sourceName,
                    )
                dispatchAudioPackImport(uri, target)
            }
            PendingSettingsImportKind.KNOWN_WORDS ->
                viewModelScope.launch {
                    resources.previewKnownWords(
                        uri,
                        KnownWordsSourceFormat.valueOf(requireNotNull(pending.format)),
                    )
                }
        }
        clearPendingSettingsImport()
    }

    fun importCustomDictionary(uri: String) {
        val state = currentState()
        if (state.busy || !SLOT_ID.matches(state.customSlotId)) return
        val target = ResourceIdentity.customDictionaryTarget(state.customSlotId, state.dictionaries)
        dispatchCustomDictionaryImport(uri, target)
    }

    fun setCustomSlotId(value: String) {
        if (value.length <= 64) local.update { it.copy(customSlotId = value.lowercase()) }
    }

    fun setFrequencySourceName(value: String) {
        local.update { it.copy(frequencySourceName = sanitizeDisplayName(value)) }
    }

    fun setFrequencyFormat(value: FrequencySourceFormat) {
        local.update { it.copy(frequencyFormat = value) }
    }

    fun importFrequencySource(uri: String) {
        val state = currentState()
        if (state.busy || state.frequencySourceName.isBlank()) return
        val target =
            ResourceIdentity.frequencyTarget(state.frequencySourceName, state.frequencySources)
        dispatchFrequencyImport(uri, target, state.frequencySourceName, state.frequencyFormat)
    }

    fun setPitchSourceName(value: String) {
        local.update { it.copy(pitchSourceName = sanitizeDisplayName(value)) }
    }

    fun setPitchFormat(value: PitchAccentSourceFormat) {
        local.update { it.copy(pitchFormat = value) }
    }

    fun importPitchAccent(uri: String) {
        val state = currentState()
        if (state.busy || state.pitchSourceName.isBlank()) return
        val target = ResourceIdentity.pitchTarget(state.pitchSourceName, state.pitchSources)
        dispatchPitchImport(uri, target, state.pitchSourceName, state.pitchFormat)
    }

    fun setAudioPackId(value: String) {
        if (value.length <= 64) local.update { it.copy(audioPackId = value.lowercase()) }
    }

    fun importAudioPack(uri: String) {
        val state = currentState()
        if (state.busy || !state.audioPackIdValid) return
        val target = ResourceIdentity.audioPackTarget(state.audioPackId, state.audioPacks)
        dispatchAudioPackImport(uri, target)
    }

    private fun dispatchCustomDictionaryImport(
        uri: String,
        target: ResourceImportTarget,
    ) {
        if (stagePendingReplace(ResourceReplaceKind.CUSTOM_DICTIONARY, target, uri)) return
        viewModelScope.launch {
            resources.importCustomDictionary(uri, target.identity, replace = false)
        }
    }

    private fun dispatchFrequencyImport(
        uri: String,
        target: ResourceImportTarget,
        sourceName: String,
        format: FrequencySourceFormat,
    ) {
        if (
            stagePendingReplace(
                kind = ResourceReplaceKind.FREQUENCY,
                target = target,
                uri = uri,
                sourceName = sourceName,
                frequencyFormat = format,
            )
        ) {
            return
        }
        viewModelScope.launch {
            resources.importFrequencySource(
                uri = uri,
                sourceId = target.identity,
                sourceName = sourceName,
                format = format,
                replace = false,
            )
        }
    }

    private fun dispatchPitchImport(
        uri: String,
        target: ResourceImportTarget,
        sourceName: String,
        format: PitchAccentSourceFormat,
    ) {
        if (
            stagePendingReplace(
                kind = ResourceReplaceKind.PITCH,
                target = target,
                uri = uri,
                sourceName = sourceName,
                pitchFormat = format,
            )
        ) {
            return
        }
        viewModelScope.launch {
            resources.importPitchAccent(
                uri = uri,
                sourceId = target.identity,
                sourceName = sourceName,
                format = format,
                replace = false,
            )
        }
    }

    private fun dispatchAudioPackImport(
        uri: String,
        target: ResourceImportTarget,
    ) {
        if (stagePendingReplace(ResourceReplaceKind.AUDIO_PACK, target, uri)) return
        viewModelScope.launch {
            resources.importAudioPack(uri, target.identity, replace = false)
        }
    }

    /**
     * Holds the import for confirmation when [target] collides. Returns true when it did, so the
     * caller must not also dispatch.
     */
    private fun stagePendingReplace(
        kind: ResourceReplaceKind,
        target: ResourceImportTarget,
        uri: String,
        sourceName: String? = null,
        frequencyFormat: FrequencySourceFormat? = null,
        pitchFormat: PitchAccentSourceFormat? = null,
    ): Boolean {
        val installedLabel = target.installedName ?: return false
        local.update {
            it.copy(
                pendingReplace =
                    PendingResourceReplace(
                        kind = kind,
                        identity = target.identity,
                        installedLabel = installedLabel,
                        uri = uri,
                        sourceName = sourceName,
                        frequencyFormat = frequencyFormat,
                        pitchFormat = pitchFormat,
                    ),
            )
        }
        return true
    }

    private fun savePendingSettingsImport(pending: PendingSettingsImport) {
        savedStateHandle[PENDING_SETTINGS_IMPORT_KIND_KEY] = pending.kind.name
        savedStateHandle[PENDING_SETTINGS_IMPORT_TARGET_KEY] = pending.targetIdentity
        savedStateHandle[PENDING_SETTINGS_IMPORT_SOURCE_NAME_KEY] = pending.sourceName
        savedStateHandle[PENDING_SETTINGS_IMPORT_FORMAT_KEY] = pending.format
    }

    private fun pendingSettingsImport(): PendingSettingsImport? {
        val kind =
            savedStateHandle.get<String>(PENDING_SETTINGS_IMPORT_KIND_KEY)?.let { rawKind ->
                runCatching { PendingSettingsImportKind.valueOf(rawKind) }.getOrNull()
            } ?: run {
                clearPendingSettingsImport()
                return null
            }
        val pending =
            PendingSettingsImport(
                kind = kind,
                targetIdentity = savedStateHandle[PENDING_SETTINGS_IMPORT_TARGET_KEY],
                sourceName = savedStateHandle[PENDING_SETTINGS_IMPORT_SOURCE_NAME_KEY],
                format = savedStateHandle[PENDING_SETTINGS_IMPORT_FORMAT_KEY],
            )
        val valid =
            when (kind) {
                PendingSettingsImportKind.CUSTOM_DICTIONARY,
                PendingSettingsImportKind.AUDIO_PACK,
                -> !pending.targetIdentity.isNullOrBlank()
                PendingSettingsImportKind.FREQUENCY ->
                    !pending.targetIdentity.isNullOrBlank() &&
                        !pending.sourceName.isNullOrBlank() &&
                        enumValueOrNull<FrequencySourceFormat>(pending.format) != null
                PendingSettingsImportKind.PITCH ->
                    !pending.targetIdentity.isNullOrBlank() &&
                        !pending.sourceName.isNullOrBlank() &&
                        enumValueOrNull<PitchAccentSourceFormat>(pending.format) != null
                PendingSettingsImportKind.KNOWN_WORDS ->
                    enumValueOrNull<KnownWordsSourceFormat>(pending.format) != null
            }
        if (!valid) {
            clearPendingSettingsImport()
            return null
        }
        return pending
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

    private fun clearPendingSettingsImport() {
        savedStateHandle.remove<String>(PENDING_SETTINGS_IMPORT_KIND_KEY)
        savedStateHandle.remove<String>(PENDING_SETTINGS_IMPORT_TARGET_KEY)
        savedStateHandle.remove<String>(PENDING_SETTINGS_IMPORT_SOURCE_NAME_KEY)
        savedStateHandle.remove<String>(PENDING_SETTINGS_IMPORT_FORMAT_KEY)
    }

    /**
     * Trim and cap to what [ResourceBridgeCodec] accepts. It requires a trimmed, non-blank name of
     * at most 512 bytes with no control characters, and trim() alone leaves interior ones - the
     * resulting IllegalArgumentException would surface as a generic operation failure.
     */
    private fun sanitizeDisplayName(value: String): String {
        val stripped = value.filter { it.code >= 0x20 }.trim()
        var candidate = UnicodeContractV151.normalizeNfc(stripped) ?: return ""
        while (candidate.toByteArray(Charsets.UTF_8).size > 512) {
            candidate = candidate.dropLast(1)
        }
        return candidate.trim()
    }

    fun setKnownWordsFormat(value: KnownWordsSourceFormat) {
        local.update { it.copy(knownWordsFormat = value) }
    }

    fun importKnownWords(uri: String) {
        val state = currentState()
        if (state.busy) return
        val format = state.knownWordsFormat
        viewModelScope.launch { resources.previewKnownWords(uri, format) }
    }

    fun importWordList(uri: String, kind: WordListKind) {
        if (currentState().busy) return
        local.update { it.copy(wordListTarget = kind) }
        viewModelScope.launch { resources.importWordList(uri, kind) }
    }

    fun removeWordList(kind: WordListKind) {
        if (currentState().busy) return
        local.update { it.copy(wordListTarget = kind) }
        viewModelScope.launch { resources.removeWordList(kind) }
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
                resources = resources,
                settingsRepository = settings,
                ankiSetup = ankiSetup,
                pythonReadiness = python,
                miningAdmission = admission,
                runtimeWorkState = runtimeWorkState,
                refreshExternalReadiness = refreshExternalReadiness,
                strings = strings,
                savedStateHandle = savedStateHandleFactory(extras),
            ) as T
        }
    }

    private companion object {
        val SLOT_ID = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
        const val PENDING_SETTINGS_IMPORT_KIND_KEY = "settingsImport.kind"
        const val PENDING_SETTINGS_IMPORT_TARGET_KEY = "settingsImport.target"
        const val PENDING_SETTINGS_IMPORT_SOURCE_NAME_KEY = "settingsImport.sourceName"
        const val PENDING_SETTINGS_IMPORT_FORMAT_KEY = "settingsImport.format"
    }
}
