package com.ankiminer.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.ResourceManager
import com.ankiminer.android.data.resources.ResourceManagerState
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.AppSettingsDraftParser
import com.ankiminer.android.data.settings.AppSettingsRepository
import com.ankiminer.android.data.settings.AudioFormat
import com.ankiminer.android.data.settings.EngineSettingsSnapshotMapper
import com.ankiminer.android.data.settings.InvalidAppSettingCode
import com.ankiminer.android.data.settings.InvalidAppSettingException
import com.ankiminer.android.data.settings.PitchCategoryFormat
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.data.settings.ThemeMode
import com.ankiminer.android.localization.LocalizedStringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class SettingsFieldKey {
    AUDIO_PADDING,
    SCREENSHOT_OFFSET,
    SUBTITLE_OFFSET,
    BITRATE,
    MAX_DURATION,
    MAX_CHARACTERS,
    READING_OCCURRENCE,
    MAX_FREQUENCY,
    WORKERS,
}

internal sealed interface SettingsSaveState {
    val revision: Long

    data class Pending(
        override val revision: Long,
    ) : SettingsSaveState

    data class Saving(
        override val revision: Long,
    ) : SettingsSaveState

    data class Saved(
        override val revision: Long,
    ) : SettingsSaveState

    data class Failed(
        override val revision: Long,
    ) : SettingsSaveState
}

private fun validateOptionalDouble(
    value: String,
    nonNegative: Boolean = false,
): LocalizedStringResource? {
    if (value.isEmpty()) return null
    val parsed =
        value.toDoubleOrNull()
            ?: return LocalizedStringResource(R.string.b3_validation_numeric_incomplete)
    if (!parsed.isFinite()) {
        return LocalizedStringResource(R.string.b3_validation_finite)
    }
    return if (nonNegative && parsed < 0) {
        LocalizedStringResource(R.string.b3_validation_non_negative)
    } else {
        null
    }
}

private fun validateOptionalInt(
    value: String,
    nonNegative: Boolean = false,
    positive: Boolean = false,
): LocalizedStringResource? {
    if (value.isEmpty()) return null
    val parsed =
        value.toIntOrNull()
            ?: return LocalizedStringResource(R.string.b3_validation_numeric_incomplete)
    return when {
        positive && parsed <= 0 ->
            LocalizedStringResource(R.string.b3_validation_positive)
        nonNegative && parsed < 0 ->
            LocalizedStringResource(R.string.b3_validation_non_negative)
        else -> null
    }
}

internal data class SettingsDraft(
    val deckName: String,
    val excludedDecks: List<String>,
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
    val stripAnnotations: Boolean?,
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
    val enabledWordsets: List<String>,
    val readingTts: Boolean,
    val jisho: Boolean,
) {
    val validation: Map<SettingsFieldKey, LocalizedStringResource>
        get() =
            buildMap {
                validateOptionalDouble(
                    audioPadding,
                    nonNegative = true,
                )?.let { put(SettingsFieldKey.AUDIO_PADDING, it) }
                validateOptionalDouble(
                    screenshotOffset,
                    nonNegative = true,
                )?.let { put(SettingsFieldKey.SCREENSHOT_OFFSET, it) }
                validateOptionalDouble(
                    subtitleOffset,
                )?.let { put(SettingsFieldKey.SUBTITLE_OFFSET, it) }
                validateOptionalInt(bitrate, positive = true)
                    ?.let { put(SettingsFieldKey.BITRATE, it) }
                validateOptionalDouble(
                    maxDuration,
                    nonNegative = true,
                )?.let { put(SettingsFieldKey.MAX_DURATION, it) }
                validateOptionalInt(
                    maxCharacters,
                    nonNegative = true,
                )?.let { put(SettingsFieldKey.MAX_CHARACTERS, it) }
                validateOptionalInt(
                    readingOccurrence,
                    positive = true,
                )?.let { put(SettingsFieldKey.READING_OCCURRENCE, it) }
                validateOptionalInt(
                    maxFrequency,
                    nonNegative = true,
                )?.let { put(SettingsFieldKey.MAX_FREQUENCY, it) }
                validateOptionalInt(workers)
                    ?.let { put(SettingsFieldKey.WORKERS, it) }
                workers.toIntOrNull()
                    ?.takeIf { it !in 1..32 }
                    ?.let {
                        put(
                            SettingsFieldKey.WORKERS,
                            LocalizedStringResource(
                                R.string.b3_validation_parallel_workers,
                            ),
                        )
                    }
            }

    val numericValuesValid: Boolean
        get() = validation.isEmpty()

    fun toSettings(base: AppSettings): AppSettings =
        base.copy(
            theme = theme,
            deckName = deckName.takeIf(String::isNotEmpty),
            excludedDecks = excludedDecks,
            tags = tags.takeIf { tagsOverride },
            audioPaddingSeconds = AppSettingsDraftParser.optionalDouble(audioPadding),
            screenshotOffsetSeconds = AppSettingsDraftParser.optionalDouble(screenshotOffset),
            subtitleOffsetSeconds = AppSettingsDraftParser.optionalDouble(subtitleOffset),
            audioFormat = audioFormat,
            audioBitrateKbps = AppSettingsDraftParser.optionalInt(bitrate),
            stripSubtitleAnnotations = stripAnnotations,
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
            enabledWordsets = enabledWordsets,
            readingTtsEnabled = readingTts,
            jishoEnabled = jisho,
        )

    /** Apply every valid field while retaining persisted values for invalid numeric text. */
    fun toPersistableSettings(base: AppSettings): AppSettings =
        copy(
            audioPadding =
                audioPadding.takeIf { SettingsFieldKey.AUDIO_PADDING !in validation }
                    ?: base.audioPaddingSeconds?.toString().orEmpty(),
            screenshotOffset =
                screenshotOffset.takeIf { SettingsFieldKey.SCREENSHOT_OFFSET !in validation }
                    ?: base.screenshotOffsetSeconds?.toString().orEmpty(),
            subtitleOffset =
                subtitleOffset.takeIf { SettingsFieldKey.SUBTITLE_OFFSET !in validation }
                    ?: base.subtitleOffsetSeconds?.toString().orEmpty(),
            bitrate =
                bitrate.takeIf { SettingsFieldKey.BITRATE !in validation }
                    ?: base.audioBitrateKbps?.toString().orEmpty(),
            maxDuration =
                maxDuration.takeIf { SettingsFieldKey.MAX_DURATION !in validation }
                    ?: base.maxSentenceDurationSeconds?.toString().orEmpty(),
            maxCharacters =
                maxCharacters.takeIf { SettingsFieldKey.MAX_CHARACTERS !in validation }
                    ?: base.maxSentenceCharacters?.toString().orEmpty(),
            readingOccurrence =
                readingOccurrence.takeIf {
                    SettingsFieldKey.READING_OCCURRENCE !in validation
                }
                    ?: base.readingMinimumOccurrence?.toString().orEmpty(),
            maxFrequency =
                maxFrequency.takeIf { SettingsFieldKey.MAX_FREQUENCY !in validation }
                    ?: base.maxFrequencyRank?.toString().orEmpty(),
            workers =
                workers.takeIf { SettingsFieldKey.WORKERS !in validation }
                    ?: base.maxParallelWorkers?.toString().orEmpty(),
        ).toSettings(base)

    /**
     * Re-derive the three ordered resource-chain fields against [resources] while preserving every
     * scalar edit (including raw numeric text). [EngineSettingsSnapshotMapper.resolveResourceChain]
     * keeps the draft's own order and enable choices and only appends newly installed resources, so
     * merging the same inventory twice is a fixed point.
     */
    fun withInventory(resources: ResourceManagerState): SettingsDraft {
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
                excludedDecks = settings.excludedDecks,
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
                stripAnnotations = settings.stripSubtitleAnnotations,
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
                enabledWordsets = settings.enabledWordsets,
                readingTts = settings.readingTtsEnabled,
                jisho = settings.jishoEnabled,
            ).withInventory(resources)
    }
}

private fun SettingsDraft.rebaseChangesSince(
    baseline: SettingsDraft,
    persisted: SettingsDraft,
): SettingsDraft =
    persisted.copy(
        deckName = changedValue(baseline.deckName, deckName, persisted.deckName),
        excludedDecks =
            changedValue(baseline.excludedDecks, excludedDecks, persisted.excludedDecks),
        tags = changedValue(baseline.tags, tags, persisted.tags),
        tagsOverride = changedValue(baseline.tagsOverride, tagsOverride, persisted.tagsOverride),
        audioPadding = changedValue(baseline.audioPadding, audioPadding, persisted.audioPadding),
        screenshotOffset =
            changedValue(baseline.screenshotOffset, screenshotOffset, persisted.screenshotOffset),
        subtitleOffset =
            changedValue(baseline.subtitleOffset, subtitleOffset, persisted.subtitleOffset),
        bitrate = changedValue(baseline.bitrate, bitrate, persisted.bitrate),
        maxDuration = changedValue(baseline.maxDuration, maxDuration, persisted.maxDuration),
        maxCharacters =
            changedValue(baseline.maxCharacters, maxCharacters, persisted.maxCharacters),
        readingOccurrence =
            changedValue(
                baseline.readingOccurrence,
                readingOccurrence,
                persisted.readingOccurrence,
            ),
        maxFrequency = changedValue(baseline.maxFrequency, maxFrequency, persisted.maxFrequency),
        workers = changedValue(baseline.workers, workers, persisted.workers),
        audioFormat = changedValue(baseline.audioFormat, audioFormat, persisted.audioFormat),
        stripAnnotations =
            changedValue(baseline.stripAnnotations, stripAnnotations, persisted.stripAnnotations),
        knownWords = changedValue(baseline.knownWords, knownWords, persisted.knownWords),
        hiragana = changedValue(baseline.hiragana, hiragana, persisted.hiragana),
        katakana = changedValue(baseline.katakana, katakana, persisted.katakana),
        boldTarget = changedValue(baseline.boldTarget, boldTarget, persisted.boldTarget),
        deduplicate = changedValue(baseline.deduplicate, deduplicate, persisted.deduplicate),
        iPlusOne = changedValue(baseline.iPlusOne, iPlusOne, persisted.iPlusOne),
        sentenceLength =
            changedValue(baseline.sentenceLength, sentenceLength, persisted.sentenceLength),
        pitchFormat = changedValue(baseline.pitchFormat, pitchFormat, persisted.pitchFormat),
        theme = changedValue(baseline.theme, theme, persisted.theme),
        dictionarySources =
            changedValue(
                baseline.dictionarySources,
                dictionarySources,
                persisted.dictionarySources,
            ),
        frequencySources =
            changedValue(
                baseline.frequencySources,
                frequencySources,
                persisted.frequencySources,
            ),
        audioPacks = changedValue(baseline.audioPacks, audioPacks, persisted.audioPacks),
        enabledWordsets =
            changedValue(
                baseline.enabledWordsets,
                enabledWordsets,
                persisted.enabledWordsets,
            ),
        readingTts = changedValue(baseline.readingTts, readingTts, persisted.readingTts),
        jisho = changedValue(baseline.jisho, jisho, persisted.jisho),
    )

private fun <T> changedValue(
    baseline: T,
    current: T,
    persisted: T,
): T = if (current != baseline) current else persisted

private fun ResourceManagerState.usableDictionaryIds(): List<String> =
    dictionaries.filter { it.isUsable }.map { it.slotId }

private fun ResourceManagerState.usableFrequencyIds(): List<String> =
    frequencySources.filter { it.schemaOk && it.entryCount > 0 }.map { it.sourceId }

private fun ResourceManagerState.usableAudioPackIds(): List<String> =
    audioPacks.filter { it.contentAvailable && it.entryCount > 0 }.map { it.packId }

internal data class SettingsDraftState(
    val draft: SettingsDraft,
    val dirty: Boolean,
    val loaded: Boolean,
    val deckDirty: Boolean,
    val dictionarySourcesDirty: Boolean,
    val frequencySourcesDirty: Boolean,
    val audioPacksDirty: Boolean,
    val editRevision: Long,
    val writeCadence: SettingsWriteCadence,
)

internal class SettingsDraftStore(
    initial: SettingsDraft,
    initiallyLoaded: Boolean = true,
) {
    private val mutableState =
        MutableStateFlow(
            SettingsDraftState(
                initial,
                dirty = false,
                loaded = initiallyLoaded,
                deckDirty = false,
                dictionarySourcesDirty = false,
                frequencySourcesDirty = false,
                audioPacksDirty = false,
                editRevision = 0,
                writeCadence = SettingsWriteCadence.IMMEDIATE,
            ),
        )
    val state: StateFlow<SettingsDraftState> = mutableState.asStateFlow()

    fun update(value: SettingsDraft) {
        mutableState.update { current ->
            if (current.loaded && value != current.draft) {
                SettingsDraftState(
                    draft = value,
                    dirty = true,
                    loaded = true,
                    deckDirty = current.deckDirty || value.deckName != current.draft.deckName,
                    dictionarySourcesDirty =
                        current.dictionarySourcesDirty ||
                            value.dictionarySources != current.draft.dictionarySources,
                    frequencySourcesDirty =
                        current.frequencySourcesDirty ||
                            value.frequencySources != current.draft.frequencySources,
                    audioPacksDirty =
                        current.audioPacksDirty ||
                            value.audioPacks != current.draft.audioPacks,
                    editRevision = current.editRevision + 1,
                    writeCadence = settingsWriteCadence(current.draft, value),
                )
            } else {
                current
            }
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
                // instead of hiding them. Only explicit local edits own the deck or resource-chain
                // fields; otherwise adopt persisted state so projections cannot copy display-only
                // inventory merges or out-of-band wizard selections back into storage.
                val persistedDraft = SettingsDraft.from(settings, resources)
                val persistedDeckName = persistedDraft.deckName
                val deckDirty = current.deckDirty && current.draft.deckName != persistedDeckName
                val mergedDraft = current.draft.withInventory(resources)
                SettingsDraftState(
                    draft =
                        if (deckDirty) {
                            mergedDraft
                        } else {
                            mergedDraft.copy(deckName = persistedDeckName)
                        },
                    dirty = true,
                    loaded = true,
                    deckDirty = deckDirty,
                    dictionarySourcesDirty =
                        current.dictionarySourcesDirty &&
                            mergedDraft.dictionarySources != persistedDraft.dictionarySources,
                    frequencySourcesDirty =
                        current.frequencySourcesDirty &&
                            mergedDraft.frequencySources != persistedDraft.frequencySources,
                    audioPacksDirty =
                        current.audioPacksDirty &&
                            mergedDraft.audioPacks != persistedDraft.audioPacks,
                    editRevision = current.editRevision,
                    writeCadence = current.writeCadence,
                )
            } else {
                SettingsDraftState(
                    draft = SettingsDraft.from(settings, resources),
                    dirty = false,
                    loaded = true,
                    deckDirty = false,
                    dictionarySourcesDirty = false,
                    frequencySourcesDirty = false,
                    audioPacksDirty = false,
                    editRevision = current.editRevision,
                    writeCadence = SettingsWriteCadence.IMMEDIATE,
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
                draft = SettingsDraft.from(settings, resources),
                dirty = false,
                loaded = true,
                deckDirty = false,
                dictionarySourcesDirty = false,
                frequencySourcesDirty = false,
                audioPacksDirty = false,
                editRevision = mutableState.value.editRevision,
                writeCadence = SettingsWriteCadence.IMMEDIATE,
            )
    }

    /** Rebuild after a scoped save, carrying only edits whose revision arrived during that save. */
    fun completeScopedSave(
        started: SettingsDraftState,
        settings: AppSettings,
        resources: ResourceManagerState,
    ) {
        while (true) {
            val current = mutableState.value
            val persistedDraft = SettingsDraft.from(settings, resources)
            val completed =
                if (current.editRevision == started.editRevision) {
                    SettingsDraftState(
                        draft = persistedDraft,
                        dirty = false,
                        loaded = true,
                        deckDirty = false,
                        dictionarySourcesDirty = false,
                        frequencySourcesDirty = false,
                        audioPacksDirty = false,
                        editRevision = current.editRevision,
                        writeCadence = SettingsWriteCadence.IMMEDIATE,
                    )
                } else {
                    val baseline = started.draft.withInventory(resources)
                    val currentDraft = current.draft.withInventory(resources)
                    val rebased = currentDraft.rebaseChangesSince(baseline, persistedDraft)
                    val dirty = rebased != persistedDraft
                    SettingsDraftState(
                        draft = rebased,
                        dirty = dirty,
                        loaded = true,
                        deckDirty =
                            dirty &&
                                currentDraft.deckName != baseline.deckName &&
                                rebased.deckName != persistedDraft.deckName,
                        dictionarySourcesDirty =
                            dirty &&
                                currentDraft.dictionarySources != baseline.dictionarySources &&
                                rebased.dictionarySources != persistedDraft.dictionarySources,
                        frequencySourcesDirty =
                            dirty &&
                                currentDraft.frequencySources != baseline.frequencySources &&
                                rebased.frequencySources != persistedDraft.frequencySources,
                        audioPacksDirty =
                            dirty &&
                                currentDraft.audioPacks != baseline.audioPacks &&
                                rebased.audioPacks != persistedDraft.audioPacks,
                        editRevision = current.editRevision,
                        writeCadence =
                            if (dirty) {
                                settingsWriteCadence(persistedDraft, rebased)
                            } else {
                                SettingsWriteCadence.IMMEDIATE
                            },
                    )
                }
            if (mutableState.compareAndSet(current, completed)) return
        }
    }
}

private data class ProjectedSettingsWrite(
    val state: SettingsDraftState,
    val value: AppSettings,
)

private fun settingsValidationError(
    failure: InvalidAppSettingException,
): LocalizedStringResource =
    when (failure.code) {
        InvalidAppSettingCode.NUMERIC_INCOMPLETE ->
            LocalizedStringResource(R.string.settings_validation_numeric_incomplete)
        InvalidAppSettingCode.EXCLUDED_DECKS_INVALID ->
            LocalizedStringResource(R.string.settings_validation_excluded_decks)
        InvalidAppSettingCode.CANONICAL_NAME_INVALID ->
            LocalizedStringResource(
                R.string.settings_validation_canonical_name,
                failure.arguments,
            )
        InvalidAppSettingCode.INVALID_UNICODE ->
            LocalizedStringResource(
                R.string.settings_validation_invalid_unicode,
                failure.arguments,
            )
        InvalidAppSettingCode.NON_FINITE ->
            LocalizedStringResource(
                R.string.settings_validation_finite,
                failure.arguments,
            )
        InvalidAppSettingCode.NEGATIVE ->
            LocalizedStringResource(
                R.string.settings_validation_non_negative,
                failure.arguments,
            )
        InvalidAppSettingCode.NOT_POSITIVE ->
            LocalizedStringResource(
                R.string.settings_validation_positive,
                failure.arguments,
            )
        InvalidAppSettingCode.PARALLEL_WORKERS_RANGE ->
            LocalizedStringResource(R.string.settings_validation_parallel_workers)
        InvalidAppSettingCode.NETWORK_AUDIO_UNSUPPORTED ->
            LocalizedStringResource(R.string.settings_validation_network_audio)
        InvalidAppSettingCode.WORDSETS_INVALID ->
            LocalizedStringResource(R.string.settings_validation_wordsets)
        InvalidAppSettingCode.FIELD_MAP_UNKNOWN_KEY ->
            LocalizedStringResource(R.string.settings_validation_field_map_unknown_key)
        InvalidAppSettingCode.FIELD_MAP_CONFLICT ->
            LocalizedStringResource(
                R.string.settings_validation_field_map_conflict,
                failure.arguments,
            )
        InvalidAppSettingCode.RESOURCE_IDS_INVALID ->
            LocalizedStringResource(
                R.string.settings_validation_resource_ids,
                failure.arguments,
            )
        InvalidAppSettingCode.UNKNOWN ->
            LocalizedStringResource(
                R.string.settings_validation_unknown,
                failure.arguments.ifEmpty { listOf(failure.message.orEmpty()) },
            )
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
    val error = MutableStateFlow<LocalizedStringResource?>(null)
    private val mutableSaveState =
        MutableStateFlow<SettingsSaveState>(SettingsSaveState.Saved(0))
    val saveState: StateFlow<SettingsSaveState> = mutableSaveState.asStateFlow()
    val resourceState: StateFlow<ResourceManagerState> = resources.state
    private val persistenceMutex = Mutex()
    private val successfulWrites = SuccessfulSettingsWriteTracker()
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
        // Invalid numeric fields are retained in the draft and omitted from the write candidate.
        // Continuous edits coalesce; discrete actions remain immediate.
        viewModelScope.launch {
            combine(draftStore.state, settings) { state, persisted ->
                if (state.loaded && persisted != null) {
                    ProjectedSettingsWrite(state, applyDraft(state, persisted))
                } else {
                    null
                }
            }
                .filterNotNull()
                .distinctUntilChangedBy { projected ->
                    projected.value to projected.state.editRevision
                }
                .filter { it.state.dirty }
                .map { it.state }
                .coalesceSettingsWrites()
                .collect { persist(it) }
        }
    }

    fun updateDraft(value: SettingsDraft) {
        val before = draftStore.state.value.editRevision
        draftStore.update(value)
        val after = draftStore.state.value.editRevision
        if (after != before) {
            mutableSaveState.value = SettingsSaveState.Pending(after)
        }
    }

    /**
     * Start a lifecycle flush synchronously, then finish its bounded repository transaction even if
     * ViewModel cancellation follows the stop callback.
     */
    fun flushPendingWrites() {
        val current = draftStore.state.value
        if (current.loaded && current.dirty) {
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { persistLatest() }
            }
        }
    }

    private suspend fun persist(state: SettingsDraftState) {
        persistenceMutex.withLock { persistLocked(state) }
    }

    private suspend fun persistLatest() {
        persistenceMutex.withLock {
            val latest = draftStore.state.value
            if (latest.loaded && latest.dirty) persistLocked(latest)
        }
    }

    private suspend fun persistLocked(state: SettingsDraftState) {
        // Scoped reset completion explicitly flushes any revision skipped by this guard.
        if (saving.value || !successfulWrites.shouldWrite(state)) return
        val currentState = draftStore.state.value
        if (
            !currentState.dirty ||
                currentState.editRevision != state.editRevision ||
                currentState.draft != state.draft ||
                currentState.deckDirty != state.deckDirty ||
                currentState.dictionarySourcesDirty != state.dictionarySourcesDirty ||
                currentState.frequencySourcesDirty != state.frequencySourcesDirty ||
                currentState.audioPacksDirty != state.audioPacksDirty
        ) {
            return
        }
        error.value = null
        val persisted = settings.value ?: return
        if (applyDraft(state, persisted) == persisted) {
            successfulWrites.markSuccessful(state)
            mutableSaveState.value =
                if (state.draft.validation.isEmpty()) {
                    SettingsSaveState.Saved(state.editRevision)
                } else {
                    SettingsSaveState.Pending(state.editRevision)
                }
            return
        }
        saving.value = true
        mutableSaveState.value = SettingsSaveState.Saving(state.editRevision)
        try {
            // Transactional transform reads the freshest persisted value. Deck is applied only
            // when explicitly edited, so wizard and note-type writes cannot be copied back.
            repository.update { current -> applyDraft(state, current) }
            successfulWrites.markSuccessful(state)
            mutableSaveState.value =
                if (state.draft.validation.isEmpty()) {
                    SettingsSaveState.Saved(state.editRevision)
                } else {
                    // Unrelated valid edits were saved, but malformed numeric text remains local.
                    SettingsSaveState.Pending(state.editRevision)
                }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: InvalidAppSettingException) {
            error.value = settingsValidationError(failure)
            mutableSaveState.value = SettingsSaveState.Failed(state.editRevision)
        } catch (_: Exception) {
            error.value = LocalizedStringResource(R.string.b3_settings_save_failed)
            mutableSaveState.value = SettingsSaveState.Failed(state.editRevision)
        } finally {
            saving.value = false
        }
    }

    private fun applyDraft(
        state: SettingsDraftState,
        current: AppSettings,
    ): AppSettings =
        state.draft.toPersistableSettings(current).let { candidate ->
            candidate.copy(
                deckName = if (state.deckDirty) candidate.deckName else current.deckName,
                dictionarySources =
                    if (state.dictionarySourcesDirty) {
                        candidate.dictionarySources
                    } else {
                        current.dictionarySources
                    },
                frequencySources =
                    if (state.frequencySourcesDirty) {
                        candidate.frequencySources
                    } else {
                        current.frequencySources
                    },
                audioPacks =
                    if (state.audioPacksDirty) {
                        candidate.audioPacks
                    } else {
                        current.audioPacks
                    },
            )
        }

    private fun save(transform: (AppSettings) -> AppSettings) {
        if (saving.value) return
        val started = draftStore.state.value
        saving.value = true
        mutableSaveState.value = SettingsSaveState.Saving(started.editRevision)
        error.value = null
        viewModelScope.launch {
            try {
                // Fold all persistable draft fields into the transaction, then apply the reset.
                persistenceMutex.withLock {
                    repository.update { current ->
                        transform(if (started.dirty) applyDraft(started, current) else current)
                    }
                }
                draftStore.completeScopedSave(
                    started = started,
                    settings = repository.settings.first(),
                    resources = resources.state.value,
                )
                val completedRevision = draftStore.state.value.editRevision
                if (completedRevision == started.editRevision) {
                    mutableSaveState.value = SettingsSaveState.Saved(completedRevision)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: InvalidAppSettingException) {
                error.value = settingsValidationError(failure)
                mutableSaveState.value = SettingsSaveState.Failed(started.editRevision)
            } catch (_: Exception) {
                error.value = LocalizedStringResource(R.string.b3_settings_save_failed)
                mutableSaveState.value = SettingsSaveState.Failed(started.editRevision)
            } finally {
                saving.value = false
                if (draftStore.state.value.dirty) flushPendingWrites()
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

    fun retrySave() {
        if (saving.value) return
        viewModelScope.launch { persistLatest() }
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
