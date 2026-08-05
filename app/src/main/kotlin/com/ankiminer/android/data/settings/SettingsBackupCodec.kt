package com.ankiminer.android.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.json.JsonReadFeature
import java.io.StringWriter

internal enum class SettingsBackupFailure {
    NOT_A_BACKUP,
    MALFORMED,
    TOO_LARGE,
}

internal class SettingsBackupException(val reason: SettingsBackupFailure) : Exception(reason.name)

internal data class ParsedSettingsBackup(
    val values: Map<String, Any>,
    val ignoredKeys: List<String>,
)

internal data class AppliedSettingsBackup(
    val settings: AppSettings,
    val appliedCount: Int,
    val ignoredKeys: List<String>,
    val rejectedKeys: List<String>,
)

internal object SettingsBackupCodec {
    const val MAX_DOCUMENT_BYTES = 512 * 1024

    /**
     * Store-local metadata never copied between devices. `setup_wizard_seen` is first-run state;
     * importing it would suppress onboarding on a device that never ran it.
     * `wordset_defaults_policy` records how this store came to hold its wordsets and is meaningless
     * elsewhere. `settings_schema_version` rides in the envelope instead, so a file can never write
     * a schema marker the receiving app has not migrated to.
     */
    val NON_PORTABLE_KEY_NAMES: Set<String> =
        setOf(
            "setup_wizard_seen",
            "wordset_defaults_policy",
            "settings_schema_version",
        )

    private val booleanKeyNames =
        setOf(
            "screenshot_animated_enabled",
            "strip_subtitle_annotations",
            "use_subtitle_regex_filter",
            "use_blacklist",
            "use_whitelist",
            "use_known_words_database",
            "exclude_hiragana_only",
            "exclude_katakana_only",
            "bold_target",
            "deduplicate_sentences",
            "use_i_plus_one",
            "use_sentence_length",
            "reading_tts_enabled",
            "jisho_enabled",
        )

    private val intKeyNames =
        setOf(
            "screenshot_animated_quality",
            "audio_bitrate_kbps",
            "max_sentence_characters",
            "reading_minimum_occurrence",
            "max_frequency_rank",
            "max_parallel_workers",
        )

    private val doubleKeyNames =
        setOf(
            "audio_padding_seconds",
            "screenshot_offset_seconds",
            "screenshot_animated_duration_seconds",
            "subtitle_offset_seconds",
            "max_sentence_duration_seconds",
        )

    private val stringKeyNames =
        setOf(
            "theme_mode",
            "deck_name",
            "excluded_decks_v1",
            "note_type",
            "field_map_v1",
            "card_type",
            "card_type_marker_field",
            "tags",
            "audio_format",
            "subtitle_regex_filter",
            "subtitle_regex_replacement",
            "pitch_category_format",
            "dictionary_sources_v1",
            "frequency_sources_v1",
            "pitch_sources_v1",
            "audio_packs_v1",
            "enabled_wordsets_v2",
        )

    val portableKeyNames: Set<String> =
        booleanKeyNames + intKeyNames + doubleKeyNames + stringKeyNames

    private val factory: JsonFactory =
        JsonFactoryBuilder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(8)
                    .maxDocumentLength(MAX_DOCUMENT_BYTES.toLong())
                    .maxStringLength(MAX_DOCUMENT_BYTES)
                    .maxNameLength(1024)
                    .maxNumberLength(64)
                    .build(),
            ).also { builder -> JsonReadFeature.entries.forEach { builder.disable(it) } }
            .build()

    private object RejectedValue

    private data class DecodeAttempt(
        val settings: AppSettings?,
        val invalidKeyNames: Set<String>,
        val failure: InvalidAppSettingException?,
    ) {
        val isClean: Boolean
            get() = settings != null && invalidKeyNames.isEmpty()

        val score: Int
            get() = if (settings == null) Int.MAX_VALUE else invalidKeyNames.size
    }

    private data class RejectionCandidate(
        val name: String,
        val without: DecodeAttempt,
        val isolated: DecodeAttempt,
    )

    fun encode(settings: AppSettings, appVersion: String): String {
        val preferences =
            DataStoreAppSettingsRepository.encodePreferences(settings, emptyPreferences())
        val output = StringWriter()
        factory.createGenerator(output).use { generator ->
            generator.useDefaultPrettyPrinter()
            generator.writeStartObject()
            generator.writeNumberField("ankiMinerAndroidSettings", 1)
            generator.writeStringField("appVersion", appVersion)
            generator.writeNumberField(
                "schemaVersion",
                DataStoreAppSettingsRepository.CURRENT_SCHEMA_VERSION,
            )
            generator.writeObjectFieldStart("settings")
            preferences.asMap()
                .asSequence()
                .filter { (key, _) -> key.name in portableKeyNames }
                .sortedBy { (key, _) -> key.name }
                .forEach { (key, value) ->
                    when (value) {
                        is Boolean -> generator.writeBooleanField(key.name, value)
                        is Int -> generator.writeNumberField(key.name, value)
                        is Double -> generator.writeNumberField(key.name, value)
                        is String -> generator.writeStringField(key.name, value)
                        else -> error("Unsupported portable preference type for ${key.name}")
                    }
                }
            generator.writeEndObject()
            generator.writeEndObject()
        }
        return output.toString()
    }

    fun parse(json: String): ParsedSettingsBackup {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) {
            throw SettingsBackupException(SettingsBackupFailure.TOO_LARGE)
        }
        return try {
            factory.createParser(json).use(::readDocument)
        } catch (failure: SettingsBackupException) {
            throw failure
        } catch (failure: StreamConstraintsException) {
            throw SettingsBackupException(SettingsBackupFailure.TOO_LARGE).also {
                it.initCause(failure)
            }
        } catch (failure: JsonParseException) {
            throw SettingsBackupException(SettingsBackupFailure.MALFORMED).also {
                it.initCause(failure)
            }
        }
    }

    fun ParsedSettingsBackup.applyTo(current: AppSettings): AppliedSettingsBackup {
        val base =
            DataStoreAppSettingsRepository.encodePreferences(current, emptyPreferences())
        val rejectedNames =
            values.keys
                .filterTo(linkedSetOf()) { name -> values.getValue(name) === RejectedValue }
        val activeNames =
            values.keys
                .filterTo(mutableListOf()) { name -> values.getValue(name) !== RejectedValue }

        while (true) {
            val attempt = decodeCandidate(base, values, activeNames)
            val directInvalidNames = activeNames.filter { it in attempt.invalidKeyNames }
            if (directInvalidNames.isNotEmpty()) {
                rejectedNames += directInvalidNames
                activeNames.removeAll(directInvalidNames.toSet())
                continue
            }
            if (attempt.isClean || activeNames.isEmpty()) {
                val settings =
                    attempt.settings
                        ?: throw checkNotNull(attempt.failure)
                val rejectedKeys = values.keys.filter { it in rejectedNames }
                return AppliedSettingsBackup(
                    settings = settings,
                    appliedCount = values.size - rejectedKeys.size,
                    ignoredKeys = ignoredKeys,
                    rejectedKeys = rejectedKeys,
                )
            }
            val rejectedName = selectConflictKey(base, values, activeNames, attempt)
            rejectedNames += rejectedName
            activeNames -= rejectedName
        }
    }

    private fun readDocument(parser: JsonParser): ParsedSettingsBackup {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw SettingsBackupException(SettingsBackupFailure.NOT_A_BACKUP)
        }
        var validMarker = false
        var settingsIsObject = false
        var settingsSeen = false
        val values = linkedMapOf<String, Any>()
        val ignoredKeys = mutableListOf<String>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val name = parser.currentName()
            parser.nextToken()
            when (name) {
                "ankiMinerAndroidSettings" -> {
                    validMarker =
                        parser.currentToken() == JsonToken.VALUE_NUMBER_INT && parser.text == "1"
                    parser.skipChildren()
                }
                "settings" -> {
                    settingsSeen = true
                    settingsIsObject = parser.currentToken() == JsonToken.START_OBJECT
                    if (settingsIsObject) {
                        readSettings(parser, values, ignoredKeys)
                    } else {
                        parser.skipChildren()
                    }
                }
                else -> parser.skipChildren()
            }
        }
        if (parser.nextToken() != null) {
            throw SettingsBackupException(SettingsBackupFailure.MALFORMED)
        }
        if (!validMarker || !settingsSeen || !settingsIsObject) {
            throw SettingsBackupException(SettingsBackupFailure.NOT_A_BACKUP)
        }
        return ParsedSettingsBackup(values = values, ignoredKeys = ignoredKeys)
    }

    private fun readSettings(
        parser: JsonParser,
        values: MutableMap<String, Any>,
        ignoredKeys: MutableList<String>,
    ) {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val name = parser.currentName()
            parser.nextToken()
            if (name in portableKeyNames) {
                values[name] = readPortableValue(parser, name)
            } else {
                ignoredKeys += name
                parser.skipChildren()
            }
        }
    }

    private fun readPortableValue(parser: JsonParser, name: String): Any =
        when {
            name in booleanKeyNames &&
                parser.currentToken() in setOf(JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE) ->
                parser.booleanValue
            name in intKeyNames && parser.currentToken() == JsonToken.VALUE_NUMBER_INT ->
                parser.text.toIntOrNull() ?: RejectedValue
            name in doubleKeyNames &&
                parser.currentToken() in setOf(JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT) ->
                parser.text.toDoubleOrNull() ?: RejectedValue
            name in stringKeyNames && parser.currentToken() == JsonToken.VALUE_STRING -> parser.text
            else -> {
                parser.skipChildren()
                RejectedValue
            }
        }

    private fun decodeCandidate(
        base: Preferences,
        values: Map<String, Any>,
        activeNames: List<String>,
    ): DecodeAttempt {
        val candidate =
            DataStoreAppSettingsRepository.stagePreferenceWrite(base) { preferences ->
                activeNames.forEach { name ->
                    preferences.writePortable(name, values.getValue(name))
                }
            }
        return try {
            val decoded = DataStoreAppSettingsRepository.decodeWithReport(candidate)
            DecodeAttempt(
                settings = decoded.settings,
                invalidKeyNames = decoded.invalidKeys.mapTo(linkedSetOf()) { it.name },
                failure = null,
            )
        } catch (failure: InvalidAppSettingException) {
            DecodeAttempt(settings = null, invalidKeyNames = emptySet(), failure = failure)
        }
    }

    private fun selectConflictKey(
        base: Preferences,
        values: Map<String, Any>,
        activeNames: List<String>,
        current: DecodeAttempt,
    ): String {
        val candidates =
            activeNames.map { name ->
                RejectionCandidate(
                    name = name,
                    without = decodeCandidate(base, values, activeNames - name),
                    isolated = decodeCandidate(base, values, listOf(name)),
                )
            }
        val improving = candidates.filter { it.without.score < current.score }
        val improvingAndInvalidAlone = improving.filter { !it.isolated.isClean }
        val invalidAlone = candidates.filter { !it.isolated.isClean }
        val preferred =
            when {
                improvingAndInvalidAlone.isNotEmpty() -> improvingAndInvalidAlone
                improving.isNotEmpty() -> improving
                invalidAlone.isNotEmpty() -> invalidAlone
                else -> return activeNames.last()
            }
        val bestRemainingScore = preferred.minOf { it.without.score }
        return preferred.last { it.without.score == bestRemainingScore }.name
    }

    private fun MutablePreferences.writePortable(name: String, value: Any) {
        when (name) {
            in booleanKeyNames -> this[booleanPreferencesKey(name)] = value as Boolean
            in intKeyNames -> this[intPreferencesKey(name)] = value as Int
            in doubleKeyNames -> this[doublePreferencesKey(name)] = value as Double
            in stringKeyNames -> this[stringPreferencesKey(name)] = value as String
            else -> error("Unknown portable preference key: $name")
        }
    }
}
