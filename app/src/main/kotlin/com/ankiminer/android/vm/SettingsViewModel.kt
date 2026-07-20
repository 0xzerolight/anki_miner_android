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
     * Re-derive only the three resource chains against live inventory, preserving every other
     * edit. Reuses the clean-path merge so an in-progress (dirty) draft surfaces newly installed
     * resources and drops uninstalled ones while keeping the user's order and enable choices.
     */
    fun withReconciledChains(resources: ResourceManagerState): SettingsDraft =
        copy(
            dictionarySources =
                EngineSettingsSnapshotMapper.resolveResourceChain(
                    dictionarySources,
                    usableDictionaryIds(resources),
                ),
            frequencySources =
                EngineSettingsSnapshotMapper.resolveResourceChain(
                    frequencySources,
                    usableFrequencyIds(resources),
                ),
            audioPacks =
                EngineSettingsSnapshotMapper.resolveResourceChain(
                    audioPacks,
                    usableAudioPackIds(resources),
                ),
        )

    companion object {
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
                dictionarySources =
                    EngineSettingsSnapshotMapper.resolveResourceChain(
                        settings.dictionarySources,
                        usableDictionaryIds(resources),
                    ),
                frequencySources =
                    EngineSettingsSnapshotMapper.resolveResourceChain(
                        settings.frequencySources,
                        usableFrequencyIds(resources),
                    ),
                audioPacks =
                    EngineSettingsSnapshotMapper.resolveResourceChain(
                        settings.audioPacks,
                        usableAudioPackIds(resources),
                    ),
                excludedWordsets =
                    settings.excludedWordsets.filter { selected ->
                        resources.wordsets.any { it.wordsetId == selected }
                    },
                readingTts = settings.readingTtsEnabled,
                jisho = settings.jishoEnabled,
            )

        private fun usableDictionaryIds(resources: ResourceManagerState): List<String> =
            resources.dictionaries.filter { it.isUsable }.map { it.slotId }

        private fun usableFrequencyIds(resources: ResourceManagerState): List<String> =
            resources.frequencySources
                .filter { it.schemaOk && it.entryCount > 0 }
                .map { it.sourceId }

        private fun usableAudioPackIds(resources: ResourceManagerState): List<String> =
            resources.audioPacks
                .filter { it.contentAvailable && it.entryCount > 0 }
                .map { it.packId }
    }
}

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
                // Keep the user's unsaved edits, but still surface newly installed (or dropped)
                // resources in the chains so the priority list stays reactive without a restart.
                current.copy(draft = current.draft.withReconciledChains(resources))
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
    }

    fun updateDraft(value: SettingsDraft) = draftStore.update(value)

    fun save() {
        if (!draftState.value.loaded) return
        val persisted = settings.value ?: return
        val value = draftState.value.draft.toSettings(persisted)
        save(value)
    }

    private fun save(value: AppSettings) {
        if (saving.value) return
        saving.value = true
        error.value = null
        viewModelScope.launch {
            try {
                repository.update(value)
                draftStore.markClean(value, resources.state.value)
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

    fun restoreDefaults() {
        if (!draftState.value.loaded) return
        val current = settings.value ?: return
        save(
            AppSettings(
                // Restoring mining defaults must never re-open the onboarding wizard or
                // change the user's chosen look.
                setupWizardSeen = current.setupWizardSeen,
                theme = current.theme,
                // An empty persisted chain means newly imported resources should become active.
                // Record current installs as disabled so "restore defaults" still means no local
                // frequency or expression-audio override at this point in time.
                frequencySources =
                    resources.installedFrequencyIds().map { ResourceChainSelection(it, false) },
                audioPacks =
                    resources.installedAudioPackIds().map { ResourceChainSelection(it, false) },
            ),
        )
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
