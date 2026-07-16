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

    suspend fun snapshot(installedDictionaryIds: List<String>) =
        EngineSettingsSnapshotMapper.map(settings.first(), installedDictionaryIds)
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

    private fun decode(preferences: Preferences): AppSettings =
        AppSettings(
            firstRunComplete = preferences[Keys.firstRunComplete] ?: false,
            deckName = preferences[Keys.deckName],
            noteType = preferences[Keys.noteType],
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
            maxParallelWorkers = preferences[Keys.maxParallelWorkers],
            jishoEnabled = preferences[Keys.jishoEnabled] ?: false,
        ).let { decoded ->
            try {
                AppSettingsValidator.validate(decoded)
            } catch (_: InvalidAppSettingException) {
                AppSettings(firstRunComplete = decoded.firstRunComplete)
            }
        }

    private fun encode(
        value: AppSettings,
        preferences: androidx.datastore.preferences.core.MutablePreferences,
    ) {
        preferences.clear()
        preferences[Keys.firstRunComplete] = value.firstRunComplete
        value.deckName?.let { preferences[Keys.deckName] = it }
        value.noteType?.let { preferences[Keys.noteType] = it }
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
        value.maxParallelWorkers?.let { preferences[Keys.maxParallelWorkers] = it }
        preferences[Keys.jishoEnabled] = value.jishoEnabled
    }

    private object Keys {
        val firstRunComplete = booleanPreferencesKey("first_run_complete")
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
        val maxParallelWorkers = intPreferencesKey("max_parallel_workers")
        val jishoEnabled = booleanPreferencesKey("jisho_enabled")
    }
}
