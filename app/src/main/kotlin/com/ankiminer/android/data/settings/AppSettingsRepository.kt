package com.ankiminer.android.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ankiMinerSettingsDataStore by preferencesDataStore(name = "anki_miner_settings_v1")

interface AppSettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun update(settings: AppSettings)

    suspend fun update(transform: (AppSettings) -> AppSettings)

    suspend fun snapshot(
        installedDictionaryIds: List<String>,
        installedFrequencyIds: List<String> = emptyList(),
        installedAudioPackIds: List<String> = emptyList(),
        availableWordsetIds: List<String> = emptyList(),
    ) =
        EngineSettingsSnapshotMapper.map(
            settings.first(),
            installedDictionaryIds,
            installedFrequencyIds,
            installedAudioPackIds,
            availableWordsetIds,
        )
}

class DataStoreAppSettingsRepository(context: Context) : AppSettingsRepository {
    private val store = context.applicationContext.ankiMinerSettingsDataStore

    override val settings: Flow<AppSettings> =
        store.data
            .catch { failure ->
                if (failure is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw failure
            }.map(::decodePreferences)

    override suspend fun update(settings: AppSettings) {
        val validated = AppSettingsValidator.validate(settings)
        store.edit { preferences -> encode(validated, preferences) }
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { preferences ->
            encode(AppSettingsValidator.validate(transform(decodePreferences(preferences))), preferences)
        }
    }

    internal companion object {
        fun decodePreferences(preferences: Preferences): AppSettings {
            val setupWizardSeen = preferences[Keys.setupWizardSeen] ?: false
            val theme = ThemeMode.fromWire(preferences[Keys.themeMode])
            return try {
                val decodedFieldMap =
                    try {
                        FieldMapPreferenceCodec.decode(preferences[Keys.fieldMap])
                    } catch (_: InvalidAppSettingException) {
                        emptyMap()
                    }
                val decoded =
                    AppSettings(
                        setupWizardSeen = setupWizardSeen,
                        theme = theme,
                        deckName = preferences[Keys.deckName],
                        noteType = preferences[Keys.noteType],
                        fieldMap = decodedFieldMap,
                        tags = preferences[Keys.tags],
                        audioPaddingSeconds = preferences[Keys.audioPadding],
                        screenshotOffsetSeconds = preferences[Keys.screenshotOffset],
                        subtitleOffsetSeconds = preferences[Keys.subtitleOffset],
                        audioFormat =
                            preferences[Keys.audioFormat]?.let { stored ->
                                AudioFormat.entries.singleOrNull { it.wireValue == stored }
                            },
                        audioBitrateKbps = preferences[Keys.audioBitrate],
                        useKnownWordsDatabase = preferences[Keys.useKnownWordsDatabase],
                        excludeHiraganaOnly = preferences[Keys.excludeHiraganaOnly],
                        excludeKatakanaOnly = preferences[Keys.excludeKatakanaOnly],
                        boldTargetInSentence = preferences[Keys.boldTarget],
                        deduplicateSentences = preferences[Keys.deduplicateSentences],
                        useIPlusOneFilter = preferences[Keys.useIPlusOne],
                        useSentenceLengthFilter = preferences[Keys.useSentenceLength],
                        maxSentenceDurationSeconds = preferences[Keys.maxSentenceDuration],
                        maxSentenceCharacters = preferences[Keys.maxSentenceCharacters],
                        readingMinimumOccurrence = preferences[Keys.readingMinimumOccurrence],
                        maxFrequencyRank = preferences[Keys.maxFrequencyRank],
                        pitchCategoryFormat =
                            preferences[Keys.pitchCategoryFormat]?.let { stored ->
                                PitchCategoryFormat.entries.singleOrNull { it.wireValue == stored }
                            },
                        maxParallelWorkers = preferences[Keys.maxParallelWorkers],
                        dictionarySources =
                            ResourceSelectionPreferenceCodec.decode(preferences[Keys.dictionarySources]),
                        frequencySources =
                            ResourceSelectionPreferenceCodec.decode(preferences[Keys.frequencySources]),
                        audioPacks =
                            ResourceSelectionPreferenceCodec.decode(preferences[Keys.audioPacks]),
                        excludedWordsets =
                            ResourceSelectionPreferenceCodec
                                .decode(preferences[Keys.excludedWordsets])
                                .filter(ResourceChainSelection::enabled)
                                .map(ResourceChainSelection::resourceId),
                        readingTtsEnabled = preferences[Keys.readingTtsEnabled] ?: false,
                        jishoEnabled = preferences[Keys.jishoEnabled] ?: false,
                    )
                try {
                    AppSettingsValidator.validate(decoded)
                } catch (_: InvalidAppSettingException) {
                    // Legacy field maps may violate newer ownership rules. Quarantine only that
                    // map; if any unrelated value is also invalid, the outer fallback remains.
                    AppSettingsValidator.validate(decoded.copy(fieldMap = emptyMap()))
                }
            } catch (_: InvalidAppSettingException) {
                // Preferences are app-private, but a partial/corrupt non-field-map value must
                // never make the settings Flow fail.
                AppSettings(setupWizardSeen = setupWizardSeen, theme = theme)
            }
        }
    }

    private fun encode(
        value: AppSettings,
        preferences: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        preferences.clear()
        preferences[Keys.setupWizardSeen] = value.setupWizardSeen
        preferences[Keys.themeMode] = value.theme.wireValue
        value.deckName?.let { preferences[Keys.deckName] = it }
        value.noteType?.let { preferences[Keys.noteType] = it }
        FieldMapPreferenceCodec.encode(value.fieldMap)?.let { preferences[Keys.fieldMap] = it }
        value.tags?.let { preferences[Keys.tags] = it }
        value.audioPaddingSeconds?.let { preferences[Keys.audioPadding] = it }
        value.screenshotOffsetSeconds?.let { preferences[Keys.screenshotOffset] = it }
        value.subtitleOffsetSeconds?.let { preferences[Keys.subtitleOffset] = it }
        value.audioFormat?.let { preferences[Keys.audioFormat] = it.wireValue }
        value.audioBitrateKbps?.let { preferences[Keys.audioBitrate] = it }
        value.useKnownWordsDatabase?.let { preferences[Keys.useKnownWordsDatabase] = it }
        value.excludeHiraganaOnly?.let { preferences[Keys.excludeHiraganaOnly] = it }
        value.excludeKatakanaOnly?.let { preferences[Keys.excludeKatakanaOnly] = it }
        value.boldTargetInSentence?.let { preferences[Keys.boldTarget] = it }
        value.deduplicateSentences?.let { preferences[Keys.deduplicateSentences] = it }
        value.useIPlusOneFilter?.let { preferences[Keys.useIPlusOne] = it }
        value.useSentenceLengthFilter?.let { preferences[Keys.useSentenceLength] = it }
        value.maxSentenceDurationSeconds?.let { preferences[Keys.maxSentenceDuration] = it }
        value.maxSentenceCharacters?.let { preferences[Keys.maxSentenceCharacters] = it }
        value.readingMinimumOccurrence?.let { preferences[Keys.readingMinimumOccurrence] = it }
        value.maxFrequencyRank?.let { preferences[Keys.maxFrequencyRank] = it }
        value.pitchCategoryFormat?.let { preferences[Keys.pitchCategoryFormat] = it.wireValue }
        value.maxParallelWorkers?.let { preferences[Keys.maxParallelWorkers] = it }
        ResourceSelectionPreferenceCodec.encode(value.dictionarySources)?.let {
            preferences[Keys.dictionarySources] = it
        }
        ResourceSelectionPreferenceCodec.encode(value.frequencySources)?.let {
            preferences[Keys.frequencySources] = it
        }
        ResourceSelectionPreferenceCodec.encode(value.audioPacks)?.let {
            preferences[Keys.audioPacks] = it
        }
        ResourceSelectionPreferenceCodec.encode(
            value.excludedWordsets.map { ResourceChainSelection(it, enabled = true) },
        )?.let { preferences[Keys.excludedWordsets] = it }
        preferences[Keys.readingTtsEnabled] = value.readingTtsEnabled
        preferences[Keys.jishoEnabled] = value.jishoEnabled
    }

    private object Keys {
        val setupWizardSeen = booleanPreferencesKey("setup_wizard_seen")
        val themeMode = stringPreferencesKey("theme_mode")
        val deckName = stringPreferencesKey("deck_name")
        val noteType = stringPreferencesKey("note_type")
        val fieldMap = stringPreferencesKey("field_map_v1")
        val tags = stringPreferencesKey("tags")
        val audioPadding = doublePreferencesKey("audio_padding_seconds")
        val screenshotOffset = doublePreferencesKey("screenshot_offset_seconds")
        val subtitleOffset = doublePreferencesKey("subtitle_offset_seconds")
        val audioFormat = stringPreferencesKey("audio_format")
        val audioBitrate = intPreferencesKey("audio_bitrate_kbps")
        val useKnownWordsDatabase = booleanPreferencesKey("use_known_words_database")
        val excludeHiraganaOnly = booleanPreferencesKey("exclude_hiragana_only")
        val excludeKatakanaOnly = booleanPreferencesKey("exclude_katakana_only")
        val boldTarget = booleanPreferencesKey("bold_target")
        val deduplicateSentences = booleanPreferencesKey("deduplicate_sentences")
        val useIPlusOne = booleanPreferencesKey("use_i_plus_one")
        val useSentenceLength = booleanPreferencesKey("use_sentence_length")
        val maxSentenceDuration = doublePreferencesKey("max_sentence_duration_seconds")
        val maxSentenceCharacters = intPreferencesKey("max_sentence_characters")
        val readingMinimumOccurrence = intPreferencesKey("reading_minimum_occurrence")
        val maxFrequencyRank = intPreferencesKey("max_frequency_rank")
        val pitchCategoryFormat = stringPreferencesKey("pitch_category_format")
        val maxParallelWorkers = intPreferencesKey("max_parallel_workers")
        val dictionarySources = stringPreferencesKey("dictionary_sources_v1")
        val frequencySources = stringPreferencesKey("frequency_sources_v1")
        val audioPacks = stringPreferencesKey("audio_packs_v1")
        val excludedWordsets = stringPreferencesKey("excluded_wordsets_v1")
        val readingTtsEnabled = booleanPreferencesKey("reading_tts_enabled")
        val jishoEnabled = booleanPreferencesKey("jisho_enabled")
    }
}

/** Canonical, bounded ASCII encoding for ordered resource choices stored in Preferences DataStore. */
internal object ResourceSelectionPreferenceCodec {
    private const val HEADER = "resource-selection-v1"
    private const val MAX_ENTRIES = 128
    private const val MAX_BYTES = 16 * 1024
    private val RESOURCE_ID = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")

    fun encode(values: List<ResourceChainSelection>): String? {
        if (values.isEmpty()) return null
        if (
            values.size > MAX_ENTRIES ||
                values.map(ResourceChainSelection::resourceId).distinct().size != values.size ||
                values.any { !RESOURCE_ID.matches(it.resourceId) }
        ) {
            throw InvalidAppSettingException("Saved resource choices are invalid")
        }
        val encoded =
            buildString {
                append(HEADER)
                append('\n')
                values.forEach { selection ->
                    append(if (selection.enabled) '+' else '-')
                    append(selection.resourceId)
                    append('\n')
                }
            }
        if (encoded.toByteArray(Charsets.US_ASCII).size > MAX_BYTES) {
            throw InvalidAppSettingException("Saved resource choices are invalid")
        }
        return encoded
    }

    fun decode(raw: String?): List<ResourceChainSelection> {
        if (raw == null) return emptyList()
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES || !raw.endsWith('\n')) {
            throw InvalidAppSettingException("Saved resource choices are invalid")
        }
        val lines = raw.split('\n')
        if (lines.firstOrNull() != HEADER || lines.lastOrNull() != "") {
            throw InvalidAppSettingException("Saved resource choices are invalid")
        }
        val entries = lines.drop(1).dropLast(1)
        if (entries.size !in 1..MAX_ENTRIES) {
            throw InvalidAppSettingException("Saved resource choices are invalid")
        }
        val decoded =
            entries.map { line ->
                if (line.length < 2 || line[0] !in setOf('+', '-')) {
                    throw InvalidAppSettingException("Saved resource choices are invalid")
                }
                val id = line.substring(1)
                if (!RESOURCE_ID.matches(id)) {
                    throw InvalidAppSettingException("Saved resource choices are invalid")
                }
                ResourceChainSelection(id, enabled = line[0] == '+')
            }
        if (decoded.map(ResourceChainSelection::resourceId).distinct().size != decoded.size) {
            throw InvalidAppSettingException("Saved resource choices are invalid")
        }
        return decoded
    }
}

/**
 * Canonical, bounded encoding for the user field map stored in Preferences DataStore.
 *
 * One `key=value` line per entry after a version header. Logical keys never contain `=` or a
 * newline and validated field-name values never contain control characters, so splitting on the
 * first `=` round-trips every accepted map. Key/value semantics are enforced by the validator, not
 * here; this codec only owns the serialization.
 */
internal object FieldMapPreferenceCodec {
    private const val HEADER = "field-map-v1"
    private const val MAX_ENTRIES = 128
    private const val MAX_BYTES = 16 * 1024

    fun encode(values: Map<String, String>): String? {
        if (values.isEmpty()) return null
        if (values.size > MAX_ENTRIES) {
            throw InvalidAppSettingException("Saved field map is invalid")
        }
        val encoded =
            buildString {
                append(HEADER)
                append('\n')
                values.forEach { (key, value) ->
                    append(key)
                    append('=')
                    append(value)
                    append('\n')
                }
            }
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw InvalidAppSettingException("Saved field map is invalid")
        }
        return encoded
    }

    fun decode(raw: String?): Map<String, String> {
        if (raw == null) return emptyMap()
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES || !raw.endsWith('\n')) {
            throw InvalidAppSettingException("Saved field map is invalid")
        }
        val lines = raw.split('\n')
        if (lines.firstOrNull() != HEADER || lines.lastOrNull() != "") {
            throw InvalidAppSettingException("Saved field map is invalid")
        }
        val entries = lines.drop(1).dropLast(1)
        if (entries.size > MAX_ENTRIES) {
            throw InvalidAppSettingException("Saved field map is invalid")
        }
        val decoded = linkedMapOf<String, String>()
        entries.forEach { line ->
            val separator = line.indexOf('=')
            if (separator < 1) {
                throw InvalidAppSettingException("Saved field map is invalid")
            }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            if (decoded.put(key, value) != null) {
                throw InvalidAppSettingException("Saved field map is invalid")
            }
        }
        return decoded
    }
}
