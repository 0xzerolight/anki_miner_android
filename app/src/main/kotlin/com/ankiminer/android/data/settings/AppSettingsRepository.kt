package com.ankiminer.android.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ankiminer.android.anki.provider.AnkiMinerNoteModel
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

internal object AppSettingsTargetMigration {
    const val LEGACY_DEFAULT_NOTE_TYPE = "Lapis"

    fun legacyTarget(
        firstRunComplete: Boolean,
        persistedNoteType: String?,
    ): String? =
        when (persistedNoteType) {
            AnkiMinerNoteModel.MODEL_NAME -> null
            null -> LEGACY_DEFAULT_NOTE_TYPE.takeIf { firstRunComplete }
            else -> persistedNoteType
        }
}

class DataStoreAppSettingsRepository(context: Context) : AppSettingsRepository {
    private val store = context.applicationContext.ankiMinerSettingsDataStore

    override val settings: Flow<AppSettings> =
        store.data
            .catch { failure ->
                if (failure is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw failure
            }.map(::decode)

    override suspend fun update(settings: AppSettings) {
        val validated = AppSettingsValidator.validate(settings)
        store.edit { preferences -> encode(validated, preferences) }
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { preferences ->
            encode(AppSettingsValidator.validate(transform(decode(preferences))), preferences)
        }
    }

    private fun decode(preferences: Preferences): AppSettings {
        val firstRunComplete = preferences[Keys.firstRunComplete] ?: false
        val legacyTarget =
            AppSettingsTargetMigration.legacyTarget(
                firstRunComplete = firstRunComplete,
                persistedNoteType = preferences[Keys.noteType],
            )
        return try {
            AppSettings(
            firstRunComplete = firstRunComplete,
            theme = ThemeMode.fromWire(preferences[Keys.themeMode]),
            deckName = preferences[Keys.deckName],
            legacyNoteType = legacyTarget,
            tags = preferences[Keys.tags],
            audioPaddingSeconds = preferences[Keys.audioPadding],
            screenshotOffsetSeconds = preferences[Keys.screenshotOffset],
            subtitleOffsetSeconds = preferences[Keys.subtitleOffset],
            audioFormat = preferences[Keys.audioFormat]?.let { stored -> AudioFormat.entries.singleOrNull { it.wireValue == stored } },
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
            ).let(AppSettingsValidator::validate)
        } catch (_: InvalidAppSettingException) {
            // Preferences are app-private, but a partial/corrupt write must never make the
            // settings Flow fail or silently admit an old target. Retain only migration state.
            AppSettings(
                firstRunComplete = firstRunComplete,
                theme = ThemeMode.fromWire(preferences[Keys.themeMode]),
                legacyNoteType = legacyTarget,
            )
        }
    }

    private fun encode(
        value: AppSettings,
        preferences: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        preferences.clear()
        preferences[Keys.firstRunComplete] = value.firstRunComplete
        preferences[Keys.themeMode] = value.theme.wireValue
        value.deckName?.let { preferences[Keys.deckName] = it }
        // Persist the canonical target as an acceptance marker. A completed setup from an older
        // build may have no note_type key because it inherited the desktop Lapis default.
        preferences[Keys.noteType] = value.legacyNoteType ?: AnkiMinerNoteModel.MODEL_NAME
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
        val firstRunComplete = booleanPreferencesKey("first_run_complete")
        val themeMode = stringPreferencesKey("theme_mode")
        val deckName = stringPreferencesKey("deck_name")
        val noteType = stringPreferencesKey("note_type")
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
