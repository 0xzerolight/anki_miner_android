package com.ankiminer.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsDraftParser
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.AudioFormat
import com.ankiminer.android.data.settings.EngineSettingsSnapshotMapper
import com.ankiminer.android.data.settings.InvalidAppSettingException
import com.ankiminer.android.data.settings.PitchCategoryFormat
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.data.settings.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SettingsDraft(
    val deckName: String,
    val tags: String,
    val tagsOverride: Boolean,
    val audioPadding: String,
    val screenshotOffset: String,
    val subtitleOffset: String,
    val bitrate: String,
    val maxDuration: String,
    val maxCharacters: String,
    val readingOccurrence: String,
    val maxFrequency: String,
    val workers: String,
    val audioFormat: AudioFormat?,
    val knownWords: Boolean?,
    val hiragana: Boolean?,
    val katakana: Boolean?,
    val boldTarget: Boolean?,
    val deduplicate: Boolean?,
    val iPlusOne: Boolean?,
    val sentenceLength: Boolean?,
    val pitchFormat: PitchCategoryFormat?,
    val theme: ThemeMode,
    val dictionarySources: List<ResourceChainSelection>,
    val frequencySources: List<ResourceChainSelection>,
    val audioPacks: List<ResourceChainSelection>,
    val excludedWordsets: List<String>,
    val readingTts: Boolean,
    val jisho: Boolean,
) {
    val numericValuesValid: Boolean
        get() =
            listOf(audioPadding, screenshotOffset, subtitleOffset, maxDuration)
                .all(AppSettingsDraftParser::isOptionalDouble) &&
                listOf(bitrate, maxCharacters, readingOccurrence, maxFrequency, workers)
                    .all(AppSettingsDraftParser::isOptionalInt)

    fun toSettings(base: AppSettings): AppSettings =
        base.copy(
            theme = theme,
            deckName = deckName.takeIf(String::isNotEmpty),
            tags = tags.takeIf { tagsOverride },
            audioPaddingSeconds = AppSettingsDraftParser.optionalDouble(audioPadding),
            screenshotOffsetSeconds = AppSettingsDraftParser.optionalDouble(screenshotOffset),
            subtitleOffsetSeconds = AppSettingsDraftParser.optionalDouble(subtitleOffset),
            audioFormat = audioFormat,
            audioBitrateKbps = AppSettingsDraftParser.optionalInt(bitrate),
            useKnownWordsDatabase = knownWords,
            excludeHiraganaOnly = hiragana,
            excludeKatakanaOnly = katakana,
            boldTargetInSentence = boldTarget,
            deduplicateSentences = deduplicate,
            useIPlusOneFilter = iPlusOne,
            useSentenceLengthFilter = sentenceLength,
            maxSentenceDurationSeconds = AppSettingsDraftParser.optionalDouble(maxDuration),
            maxSentenceCharacters = AppSettingsDraftParser.optionalInt(maxCharacters),
            readingMinimumOccurrence = AppSettingsDraftParser.optionalInt(readingOccurrence),
            maxFrequencyRank = AppSettingsDraftParser.optionalInt(maxFrequency),
            pitchCategoryFormat = pitchFormat,
            maxParallelWorkers = AppSettingsDraftParser.optionalInt(workers),
            dictionarySources = dictionarySources,
            frequencySources = frequencySources,
            audioPacks = audioPacks,
            excludedWordsets = excludedWordsets,
            readingTtsEnabled = readingTts,
            jishoEnabled = jisho,
        )

    /**
     * Re-derive the four inventory-backed resource fields against [resources] while preserving every
     * scalar edit (including raw numeric text). [EngineSettingsSnapshotMapper.resolveResourceChain]
     * keeps the draft's own order and enable choices and only appends newly installed resources, so
     * merging the same inventory twice is a fixed point.
     */
    fun withInventory(resources: ResourceManagerState): SettingsDraft {
        val wordsetIds = resources.availableWordsetIds()
        return copy(
            dictionarySources =
                EngineSettingsSnapshotMapper.resolveResourceChain(
                    dictionarySources,
                    resources.usableDictionaryIds(),
                ),
            frequencySources =
                EngineSettingsSnapshotMapper.resolveResourceChain(
                    frequencySources,
                    resources.usableFrequencyIds(),
                ),
            audioPacks =
                EngineSettingsSnapshotMapper.resolveResourceChain(
                    audioPacks,
                    resources.usableAudioPackIds(),
                ),
            excludedWordsets = excludedWordsets.filter { it in wordsetIds },
        )
    }

    companion object {
        /** Build a draft from persisted [settings], merging live [resources] into the chains. */
        fun from(
            settings: AppSettings,
            resources: ResourceManagerState,
        ): SettingsDraft =
            SettingsDraft(
                deckName = settings.deckName.orEmpty(),
                tags = settings.tags.orEmpty(),
                tagsOverride = settings.tags != null,
                audioPadding = settings.audioPaddingSeconds?.toString().orEmpty(),
                screenshotOffset = settings.screenshotOffsetSeconds?.toString().orEmpty(),
                subtitleOffset = settings.subtitleOffsetSeconds?.toString().orEmpty(),
                bitrate = settings.audioBitrateKbps?.toString().orEmpty(),
                maxDuration = settings.maxSentenceDurationSeconds?.toString().orEmpty(),
                maxCharacters = settings.maxSentenceCharacters?.toString().orEmpty(),
                readingOccurrence = settings.readingMinimumOccurrence?.toString().orEmpty(),
                maxFrequency = settings.maxFrequencyRank?.toString().orEmpty(),
                workers = settings.maxParallelWorkers?.toString().orEmpty(),
                audioFormat = settings.audioFormat,
                knownWords = settings.useKnownWordsDatabase,
                hiragana = settings.excludeHiraganaOnly,
                katakana = settings.excludeKatakanaOnly,
                boldTarget = settings.boldTargetInSentence,
                deduplicate = settings.deduplicateSentences,
                iPlusOne = settings.useIPlusOneFilter,
                sentenceLength = settings.useSentenceLengthFilter,
                pitchFormat = settings.pitchCategoryFormat,
                theme = settings.theme,
                dictionarySources = settings.dictionarySources,
                frequencySources = settings.frequencySources,
                audioPacks = settings.audioPacks,
                excludedWordsets = settings.excludedWordsets,
                readingTts = settings.readingTtsEnabled,
                jisho = settings.jishoEnabled,
            ).withInventory(resources)
    }
}

private fun ResourceManagerState.usableDictionaryIds(): List<String> =
    dictionaries.filter { it.isUsable }.map { it.slotId }

private fun ResourceManagerState.usableFrequencyIds(): List<String> =
    frequencySources.filter { it.schemaOk && it.entryCount > 0 }.map { it.sourceId }

private fun ResourceManagerState.usableAudioPackIds(): List<String> =
    audioPacks.filter { it.contentAvailable && it.entryCount > 0 }.map { it.packId }

private fun ResourceManagerState.availableWordsetIds(): List<String> =
    wordsets.map { it.wordsetId }

internal data class SettingsDraftState(
    val draft: SettingsDraft,
    val dirty: Boolean,
    val loaded: Boolean,
)

internal class SettingsDraftStore(
    initial: SettingsDraft,
    initiallyLoaded: Boolean = true,
) {
    private val mutableState =
        MutableStateFlow(
            SettingsDraftState(initial, dirty = false, loaded = initiallyLoaded),
        )
    val state: StateFlow<SettingsDraftState> = mutableState.asStateFlow()

    fun update(value: SettingsDraft) {
        mutableState.update { current ->
            if (current.loaded) SettingsDraftState(value, dirty = true, loaded = true) else current
        }
    }

    fun reconcile(
        settings: AppSettings,
        resources: ResourceManagerState,
    ) {
        mutableState.update { current ->
            if (current.loaded && current.dirty) {
                // Auto-save keeps the draft dirty for the rest of the activity-scoped session, so
                // this branch must still merge newly installed resources into the pending edit
                // instead of hiding them; scalar edits stay authoritative and are never overwritten.
                SettingsDraftState(
                    current.draft.withInventory(resources),
                    dirty = true,
                    loaded = true,
                )
            } else {
                SettingsDraftState(
                    SettingsDraft.from(settings, resources),
                    dirty = false,
                    loaded = true,
                )
            }
        }
    }

    fun markClean(
        settings: AppSettings,
        resources: ResourceManagerState,
    ) {
        mutableState.value =
            SettingsDraftState(
                SettingsDraft.from(settings, resources),
                dirty = false,
                loaded = true,
            )
    }
}

internal class SettingsViewModel(
    private val repository: AppSettingsRepository,
    private val resources: ResourceManager,
) : ViewModel() {
    private val settings: StateFlow<AppSettings?> =
        repository.settings
            .map<AppSettings, AppSettings?> { it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val saving = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val resourceState: StateFlow<ResourceManagerState> = resources.state
    private val draftStore =
        SettingsDraftStore(
            SettingsDraft.from(AppSettings(), resources.state.value),
            initiallyLoaded = false,
        )
    val draftState: StateFlow<SettingsDraftState> = draftStore.state

    init {
        viewModelScope.launch {
            combine(settings, resources.state) { persisted, inventory -> persisted to inventory }
                .collect { (persisted, inventory) ->
                    persisted?.let { draftStore.reconcile(it, inventory) }
                }
        }
        // Auto-save mirrors the desktop app: every valid edit persists immediately. Invalid
        // intermediate numeric text is gated out here, and the validator throw-path inside the
        // repository blocks any value that passes the numeric gate but fails deeper validation.
        viewModelScope.launch {
            draftStore.state
                .filter { it.loaded && it.dirty && it.draft.numericValuesValid }
                .map { it.draft }
                .collect { persist(it) }
        }
    }

    fun updateDraft(value: SettingsDraft) = draftStore.update(value)

    private suspend fun persist(draft: SettingsDraft) {
        // Skip while a scoped reset holds the flag: markClean supersedes any in-flight edit,
        // and this preserves the save-vs-restore mutual exclusion without per-keystroke flicker.
        if (saving.value) return
        error.value = null
        try {
            // Transactional transform: apply draft fields onto the freshest persisted value inside
            // the write lock, so out-of-band writes (noteType/fieldMap) are never clobbered and the
            // validator still rejects values the numeric gate cannot catch.
            repository.update { current -> draft.toSettings(current) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: InvalidAppSettingException) {
            error.value = failure.message
        } catch (_: Exception) {
            error.value = "Settings could not be saved"
        }
    }

    private fun save(transform: (AppSettings) -> AppSettings) {
        if (saving.value) return
        saving.value = true
        error.value = null
        viewModelScope.launch {
            try {
                repository.update(transform)
                draftStore.markClean(repository.settings.first(), resources.state.value)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: InvalidAppSettingException) {
                error.value = failure.message
            } catch (_: Exception) {
                error.value = "Settings could not be saved"
            } finally {
                saving.value = false
            }
        }
    }

    fun restoreMiningDefaults() {
        if (!draftState.value.loaded) return
        save(AppSettings::restoreMiningDefaults)
    }

    fun resetAnkiTarget() {
        if (!draftState.value.loaded) return
        save(AppSettings::resetAnkiTarget)
    }

    fun resetResourceChoices() {
        if (!draftState.value.loaded) return
        val inventory = resources.state.value
        save { current ->
            current.resetResourceChoices(
                dictionaryIds = inventory.usableDictionaryIds(),
                frequencyIds = inventory.usableFrequencyIds(),
                audioPackIds = inventory.usableAudioPackIds(),
            )
        }
    }

    fun dismissError() {
        error.value = null
    }

    class Factory(
        private val repository: AppSettingsRepository,
        private val resources: ResourceManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
            return SettingsViewModel(repository, resources) as T
        }
    }
}
