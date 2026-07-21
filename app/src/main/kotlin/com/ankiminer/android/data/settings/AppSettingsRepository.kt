package com.ankiminer.android.data.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ankiMinerSettingsDataStore by
    preferencesDataStore(
        name = "anki_miner_settings_v1",
        produceMigrations = { listOf(AppSettingsPreferencesMigration) },
    )

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

class DataStoreAppSettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
) : AppSettingsRepository {
    constructor(context: Context) : this(context.applicationContext.ankiMinerSettingsDataStore)

    override val settings: Flow<AppSettings> =
        store.data
            .catch { failure ->
                if (failure is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw failure
            }.map(::decodePreferences)

    override suspend fun update(settings: AppSettings) {
        val validated = AppSettingsValidator.validate(settings)
        store.updateData { preferences -> encodePreferences(validated, preferences) }
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData { preferences ->
            encodePreferences(
                AppSettingsValidator.validate(transform(decodePreferences(preferences))),
                preferences,
            )
        }
    }

    internal companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        internal val persistedPreferenceKeyNames: Set<String>
            get() = Keys.all.mapTo(linkedSetOf()) { it.name }

        fun decodePreferences(preferences: Preferences): AppSettings {
            return decodeWithReport(preferences).settings
        }

        internal fun migratePreferences(preferences: Preferences): Preferences {
            val decoded = decodeWithReport(preferences)
            return stagePreferenceWrite(preferences) { candidate ->
                decoded.invalidKeys.forEach { candidate -= it }
                if (decoded.schemaVersion == null || decoded.schemaVersion < CURRENT_SCHEMA_VERSION) {
                    candidate[Keys.schemaVersion] = CURRENT_SCHEMA_VERSION
                }
            }
        }

        internal fun migrationRequired(preferences: Preferences): Boolean {
            val decoded = decodeWithReport(preferences)
            return decoded.invalidKeys.isNotEmpty() ||
                decoded.schemaVersion == null ||
                decoded.schemaVersion < CURRENT_SCHEMA_VERSION
        }

        internal inline fun stagePreferenceWrite(
            preferences: Preferences,
            mutate: (MutablePreferences) -> Unit,
        ): Preferences {
            val candidate = preferences.toMutablePreferences()
            mutate(candidate)
            return candidate.toPreferences()
        }

        private fun encodePreferences(
            value: AppSettings,
            preferences: Preferences,
        ): Preferences =
            stagePreferenceWrite(preferences) { candidate ->
                candidate[Keys.schemaVersion] =
                    maxOf(readSchemaVersion(preferences) ?: 0, CURRENT_SCHEMA_VERSION)
                candidate[Keys.setupWizardSeen] = value.setupWizardSeen
                candidate[Keys.themeMode] = value.theme.wireValue
                candidate.setOrRemove(Keys.deckName, value.deckName)
                candidate.setOrRemove(Keys.noteType, value.noteType)
                candidate.setOrRemove(Keys.fieldMap, FieldMapPreferenceCodec.encode(value.fieldMap))
                candidate.setOrRemove(Keys.tags, value.tags)
                candidate.setOrRemove(Keys.audioPadding, value.audioPaddingSeconds)
                candidate.setOrRemove(Keys.screenshotOffset, value.screenshotOffsetSeconds)
                candidate.setOrRemove(Keys.subtitleOffset, value.subtitleOffsetSeconds)
                candidate.setOrRemove(Keys.audioFormat, value.audioFormat?.wireValue)
                candidate.setOrRemove(Keys.audioBitrate, value.audioBitrateKbps)
                candidate.setOrRemove(Keys.useKnownWordsDatabase, value.useKnownWordsDatabase)
                candidate.setOrRemove(Keys.excludeHiraganaOnly, value.excludeHiraganaOnly)
                candidate.setOrRemove(Keys.excludeKatakanaOnly, value.excludeKatakanaOnly)
                candidate.setOrRemove(Keys.boldTarget, value.boldTargetInSentence)
                candidate.setOrRemove(Keys.deduplicateSentences, value.deduplicateSentences)
                candidate.setOrRemove(Keys.useIPlusOne, value.useIPlusOneFilter)
                candidate.setOrRemove(Keys.useSentenceLength, value.useSentenceLengthFilter)
                candidate.setOrRemove(Keys.maxSentenceDuration, value.maxSentenceDurationSeconds)
                candidate.setOrRemove(Keys.maxSentenceCharacters, value.maxSentenceCharacters)
                candidate.setOrRemove(Keys.readingMinimumOccurrence, value.readingMinimumOccurrence)
                candidate.setOrRemove(Keys.maxFrequencyRank, value.maxFrequencyRank)
                candidate.setOrRemove(Keys.pitchCategoryFormat, value.pitchCategoryFormat?.wireValue)
                candidate.setOrRemove(Keys.maxParallelWorkers, value.maxParallelWorkers)
                candidate.setOrRemove(
                    Keys.dictionarySources,
                    ResourceSelectionPreferenceCodec.encode(value.dictionarySources),
                )
                candidate.setOrRemove(
                    Keys.frequencySources,
                    ResourceSelectionPreferenceCodec.encode(value.frequencySources),
                )
                candidate.setOrRemove(
                    Keys.audioPacks,
                    ResourceSelectionPreferenceCodec.encode(value.audioPacks),
                )
                candidate.setOrRemove(
                    Keys.excludedWordsets,
                    ResourceSelectionPreferenceCodec.encode(
                        value.excludedWordsets.map { ResourceChainSelection(it, enabled = true) },
                    ),
                )
                candidate[Keys.readingTtsEnabled] = value.readingTtsEnabled
                candidate[Keys.jishoEnabled] = value.jishoEnabled
            }

        private fun decodeWithReport(preferences: Preferences): DecodedPreferences {
            val decoder = IndependentPreferenceDecoder(preferences)
            val schemaVersion =
                decoder.read(Keys.schemaVersion, null, { it }) { version ->
                    if (version != null && version < 1) invalidStoredPreference()
                }
            val settings =
                AppSettings(
                    setupWizardSeen = decoder.read(Keys.setupWizardSeen, false, { it }),
                    theme =
                        decoder.read(Keys.themeMode, ThemeMode.DARK, { stored ->
                            ThemeMode.entries.singleOrNull { it.wireValue == stored }
                                ?: invalidStoredPreference()
                        }),
                    deckName =
                        decoder.read(Keys.deckName, null, { it }) { value ->
                            value?.let { AppSettingsValidator.validate(AppSettings(deckName = it)) }
                        },
                    noteType =
                        decoder.read(Keys.noteType, null, { it }) { value ->
                            value?.let { AppSettingsValidator.validate(AppSettings(noteType = it)) }
                        },
                    fieldMap =
                        decoder.read(Keys.fieldMap, emptyMap(), FieldMapPreferenceCodec::decode) {
                            AppSettingsValidator.validate(AppSettings(fieldMap = it))
                        },
                    tags =
                        decoder.read(Keys.tags, null, { it }) { value ->
                            value?.let { AppSettingsValidator.validate(AppSettings(tags = it)) }
                        },
                    audioPaddingSeconds =
                        decoder.validated(Keys.audioPadding) { AppSettings(audioPaddingSeconds = it) },
                    screenshotOffsetSeconds =
                        decoder.validated(Keys.screenshotOffset) {
                            AppSettings(screenshotOffsetSeconds = it)
                        },
                    subtitleOffsetSeconds =
                        decoder.validated(Keys.subtitleOffset) {
                            AppSettings(subtitleOffsetSeconds = it)
                        },
                    audioFormat =
                        decoder.read(Keys.audioFormat, null, { stored ->
                            AudioFormat.entries.singleOrNull { it.wireValue == stored }
                                ?: invalidStoredPreference()
                        }),
                    audioBitrateKbps =
                        decoder.validated(Keys.audioBitrate) { AppSettings(audioBitrateKbps = it) },
                    useKnownWordsDatabase = decoder.read(Keys.useKnownWordsDatabase, null, { it }),
                    excludeHiraganaOnly = decoder.read(Keys.excludeHiraganaOnly, null, { it }),
                    excludeKatakanaOnly = decoder.read(Keys.excludeKatakanaOnly, null, { it }),
                    boldTargetInSentence = decoder.read(Keys.boldTarget, null, { it }),
                    deduplicateSentences = decoder.read(Keys.deduplicateSentences, null, { it }),
                    useIPlusOneFilter = decoder.read(Keys.useIPlusOne, null, { it }),
                    useSentenceLengthFilter = decoder.read(Keys.useSentenceLength, null, { it }),
                    maxSentenceDurationSeconds =
                        decoder.validated(Keys.maxSentenceDuration) {
                            AppSettings(maxSentenceDurationSeconds = it)
                        },
                    maxSentenceCharacters =
                        decoder.validated(Keys.maxSentenceCharacters) {
                            AppSettings(maxSentenceCharacters = it)
                        },
                    readingMinimumOccurrence =
                        decoder.validated(Keys.readingMinimumOccurrence) {
                            AppSettings(readingMinimumOccurrence = it)
                        },
                    maxFrequencyRank =
                        decoder.validated(Keys.maxFrequencyRank) { AppSettings(maxFrequencyRank = it) },
                    pitchCategoryFormat =
                        decoder.read(Keys.pitchCategoryFormat, null, { stored ->
                            PitchCategoryFormat.entries.singleOrNull { it.wireValue == stored }
                                ?: invalidStoredPreference()
                        }),
                    maxParallelWorkers =
                        decoder.validated(Keys.maxParallelWorkers) {
                            AppSettings(maxParallelWorkers = it)
                        },
                    dictionarySources =
                        decoder.read(
                            Keys.dictionarySources,
                            emptyList(),
                            ResourceSelectionPreferenceCodec::decode,
                        ) { AppSettingsValidator.validate(AppSettings(dictionarySources = it)) },
                    frequencySources =
                        decoder.read(
                            Keys.frequencySources,
                            emptyList(),
                            ResourceSelectionPreferenceCodec::decode,
                        ) { AppSettingsValidator.validate(AppSettings(frequencySources = it)) },
                    audioPacks =
                        decoder.read(
                            Keys.audioPacks,
                            emptyList(),
                            ResourceSelectionPreferenceCodec::decode,
                        ) { AppSettingsValidator.validate(AppSettings(audioPacks = it)) },
                    excludedWordsets =
                        decoder.read(
                            Keys.excludedWordsets,
                            emptyList(),
                            { raw ->
                                ResourceSelectionPreferenceCodec
                                    .decode(raw)
                                    .filter(ResourceChainSelection::enabled)
                                    .map(ResourceChainSelection::resourceId)
                            },
                        ) { AppSettingsValidator.validate(AppSettings(excludedWordsets = it)) },
                    readingTtsEnabled = decoder.read(Keys.readingTtsEnabled, false, { it }),
                    jishoEnabled = decoder.read(Keys.jishoEnabled, false, { it }),
                )
            return DecodedPreferences(
                settings = AppSettingsValidator.validate(settings),
                invalidKeys = decoder.invalidKeys,
                schemaVersion = schemaVersion,
            )
        }

        private fun readSchemaVersion(preferences: Preferences): Int? =
            try {
                preferences[Keys.schemaVersion]
            } catch (_: ClassCastException) {
                null
            }

        private fun <T : Any> MutablePreferences.setOrRemove(
            key: Preferences.Key<T>,
            value: T?,
        ) {
            if (value == null) remove(key) else this[key] = value
        }

        private class IndependentPreferenceDecoder(private val preferences: Preferences) {
            private val mutableInvalidKeys = linkedSetOf<Preferences.Key<*>>()
            val invalidKeys: Set<Preferences.Key<*>>
                get() = mutableInvalidKeys

            fun <T : Any, R> read(
                key: Preferences.Key<T>,
                default: R,
                decode: (T) -> R,
                validate: (R) -> Unit = {},
            ): R {
                return try {
                    val stored = preferences[key] ?: return default
                    decode(stored).also(validate)
                } catch (_: ClassCastException) {
                    mutableInvalidKeys += key
                    default
                } catch (_: InvalidAppSettingException) {
                    mutableInvalidKeys += key
                    default
                }
            }

            fun <T : Any> validated(
                key: Preferences.Key<T>,
                setting: (T) -> AppSettings,
            ): T? =
                read(key, null, { it }) { value ->
                    value?.let { AppSettingsValidator.validate(setting(it)) }
                }
        }

        private data class DecodedPreferences(
            val settings: AppSettings,
            val invalidKeys: Set<Preferences.Key<*>>,
            val schemaVersion: Int?,
        )

        private fun invalidStoredPreference(): Nothing =
            throw InvalidAppSettingException("Saved setting is invalid")

        private object Keys {
            private val registered = linkedSetOf<Preferences.Key<*>>()

            private fun <T : Any> register(key: Preferences.Key<T>): Preferences.Key<T> =
                key.also { registered += it }

            val schemaVersion = register(intPreferencesKey("settings_schema_version"))
            val setupWizardSeen = register(booleanPreferencesKey("setup_wizard_seen"))
            val themeMode = register(stringPreferencesKey("theme_mode"))
            val deckName = register(stringPreferencesKey("deck_name"))
            val noteType = register(stringPreferencesKey("note_type"))
            val fieldMap = register(stringPreferencesKey("field_map_v1"))
            val tags = register(stringPreferencesKey("tags"))
            val audioPadding = register(doublePreferencesKey("audio_padding_seconds"))
            val screenshotOffset = register(doublePreferencesKey("screenshot_offset_seconds"))
            val subtitleOffset = register(doublePreferencesKey("subtitle_offset_seconds"))
            val audioFormat = register(stringPreferencesKey("audio_format"))
            val audioBitrate = register(intPreferencesKey("audio_bitrate_kbps"))
            val useKnownWordsDatabase = register(booleanPreferencesKey("use_known_words_database"))
            val excludeHiraganaOnly = register(booleanPreferencesKey("exclude_hiragana_only"))
            val excludeKatakanaOnly = register(booleanPreferencesKey("exclude_katakana_only"))
            val boldTarget = register(booleanPreferencesKey("bold_target"))
            val deduplicateSentences = register(booleanPreferencesKey("deduplicate_sentences"))
            val useIPlusOne = register(booleanPreferencesKey("use_i_plus_one"))
            val useSentenceLength = register(booleanPreferencesKey("use_sentence_length"))
            val maxSentenceDuration = register(doublePreferencesKey("max_sentence_duration_seconds"))
            val maxSentenceCharacters = register(intPreferencesKey("max_sentence_characters"))
            val readingMinimumOccurrence = register(intPreferencesKey("reading_minimum_occurrence"))
            val maxFrequencyRank = register(intPreferencesKey("max_frequency_rank"))
            val pitchCategoryFormat = register(stringPreferencesKey("pitch_category_format"))
            val maxParallelWorkers = register(intPreferencesKey("max_parallel_workers"))
            val dictionarySources = register(stringPreferencesKey("dictionary_sources_v1"))
            val frequencySources = register(stringPreferencesKey("frequency_sources_v1"))
            val audioPacks = register(stringPreferencesKey("audio_packs_v1"))
            val excludedWordsets = register(stringPreferencesKey("excluded_wordsets_v1"))
            val readingTtsEnabled = register(booleanPreferencesKey("reading_tts_enabled"))
            val jishoEnabled = register(booleanPreferencesKey("jisho_enabled"))

            val all: Set<Preferences.Key<*>>
                get() = registered
        }
    }
}

private object AppSettingsPreferencesMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        DataStoreAppSettingsRepository.migrationRequired(currentData)

    override suspend fun migrate(currentData: Preferences): Preferences =
        DataStoreAppSettingsRepository.migratePreferences(currentData)

    override suspend fun cleanUp() = Unit
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
