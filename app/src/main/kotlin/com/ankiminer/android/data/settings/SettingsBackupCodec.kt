package com.ankiminer.android.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ankiminer.android.data.resources.InstalledDictionary
import com.ankiminer.android.data.resources.InstalledFrequencySource
import com.ankiminer.android.data.resources.InstalledPitchSource
import com.ankiminer.android.data.resources.ResourceManagerState
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.json.JsonReadFeature
import java.io.StringWriter
import java.security.MessageDigest

internal enum class SettingsBackupFailure {
    NOT_A_BACKUP,
    MALFORMED,
    TOO_LARGE,
}

internal class SettingsBackupException(val reason: SettingsBackupFailure) : Exception(reason.name)

internal data class ParsedSettingsBackup(
    val values: Map<String, Any>,
    val ignoredKeys: List<String>,
    val formatVersion: Int,
    val resourceChains: Map<String, List<PortableResourceSelection>>,
)

internal data class PortableResourceSelection(
    val resourceId: String,
    val enabled: Boolean,
    val matchKey: String?,
)

internal data class AppliedSettingsBackup(
    val settings: AppSettings,
    val appliedCount: Int,
    val ignoredKeys: List<String>,
    val rejectedKeys: List<String>,
)

internal object SettingsBackupCodec {
    const val MAX_DOCUMENT_BYTES = 512 * 1024
    private const val BACKUP_FORMAT_VERSION = 3
    private const val LEGACY_BACKUP_FORMAT_VERSION = 2

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
            "screenshot_animated_match_audio",
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
            // Appearance travels: a built-in palette always resolves on the receiving device, and
            // dynamic colour simply falls back where the platform cannot supply it.
            "theme_dynamic_color",
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
            "theme_light_palette",
            "theme_dark_palette",
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

    private val resourceChainKeyNames =
        linkedSetOf(
            "dictionary_sources_v1",
            "frequency_sources_v1",
            "pitch_sources_v1",
            "audio_packs_v1",
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

    private object ClearedValue

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

    fun encode(
        settings: AppSettings,
        appVersion: String,
        resources: ResourceManagerState? = null,
    ): String {
        val preferences =
            DataStoreAppSettingsRepository.encodePreferences(settings, emptyPreferences())
        val formatVersion =
            if (resources == null) LEGACY_BACKUP_FORMAT_VERSION else BACKUP_FORMAT_VERSION
        val output = StringWriter()
        factory.createGenerator(output).use { generator ->
            generator.useDefaultPrettyPrinter()
            generator.writeStartObject()
            generator.writeNumberField("ankiMinerAndroidSettings", formatVersion)
            generator.writeStringField("appVersion", appVersion)
            generator.writeNumberField(
                "schemaVersion",
                DataStoreAppSettingsRepository.CURRENT_SCHEMA_VERSION,
            )
            generator.writeObjectFieldStart("settings")
            val preferenceValues = preferences.asMap().mapKeys { (key, _) -> key.name }
            portableKeyNames.sorted().forEach { name ->
                when (val value = preferenceValues[name]) {
                    null -> generator.writeNullField(name)
                    is Boolean -> generator.writeBooleanField(name, value)
                    is Int -> generator.writeNumberField(name, value)
                    is Double -> generator.writeNumberField(name, value)
                    is String -> generator.writeStringField(name, value)
                    else -> error("Unsupported portable preference type for $name")
                }
            }
            generator.writeEndObject()
            resources?.let { inventory ->
                generator.writeObjectFieldStart("resourceChains")
                portableResourceChains(settings, inventory).forEach { (name, selections) ->
                    generator.writeArrayFieldStart(name)
                    selections.forEach { selection ->
                        generator.writeStartObject()
                        generator.writeStringField("resourceId", selection.resourceId)
                        generator.writeBooleanField("enabled", selection.enabled)
                        if (selection.matchKey == null) {
                            generator.writeNullField("matchKey")
                        } else {
                            generator.writeStringField("matchKey", selection.matchKey)
                        }
                        generator.writeEndObject()
                    }
                    generator.writeEndArray()
                }
                generator.writeEndObject()
            }
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

    fun ParsedSettingsBackup.applyTo(
        current: AppSettings,
        resources: ResourceManagerState? = null,
    ): AppliedSettingsBackup {
        val base =
            DataStoreAppSettingsRepository.encodePreferences(current, emptyPreferences())
        val effectiveValues = values.toMutableMap()
        if (formatVersion >= BACKUP_FORMAT_VERSION) {
            if (resources == null) {
                resourceChainKeyNames.forEach { name -> effectiveValues[name] = RejectedValue }
            } else {
                val installed = portableResourceInventory(resources)
                resourceChainKeyNames.forEach { name ->
                    val resolved =
                        resolvePortableResourceChain(
                            persisted = resourceChains.getValue(name),
                            installed = installed.getValue(name),
                        )
                    effectiveValues[name] =
                        ResourceSelectionPreferenceCodec.encode(resolved) ?: ClearedValue
                }
            }
        }
        val rejectedNames =
            effectiveValues.keys
                .filterTo(linkedSetOf()) { name -> effectiveValues.getValue(name) === RejectedValue }
        val activeNames =
            effectiveValues.keys
                .filterTo(mutableListOf()) { name -> effectiveValues.getValue(name) !== RejectedValue }

        while (true) {
            val attempt = decodeCandidate(base, effectiveValues, activeNames)
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
                val rejectedKeys = effectiveValues.keys.filter { it in rejectedNames }
                return AppliedSettingsBackup(
                    settings = settings,
                    appliedCount = effectiveValues.size - rejectedKeys.size,
                    ignoredKeys = ignoredKeys,
                    rejectedKeys = rejectedKeys,
                )
            }
            val rejectedName = selectConflictKey(base, effectiveValues, activeNames, attempt)
            rejectedNames += rejectedName
            activeNames -= rejectedName
        }
    }

    private fun readDocument(parser: JsonParser): ParsedSettingsBackup {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw SettingsBackupException(SettingsBackupFailure.NOT_A_BACKUP)
        }
        var backupVersion: Int? = null
        var settingsIsObject = false
        var settingsSeen = false
        var resourceChainsIsObject = false
        var resourceChainsSeen = false
        val values = linkedMapOf<String, Any>()
        val ignoredKeys = mutableListOf<String>()
        val resourceChains = linkedMapOf<String, List<PortableResourceSelection>>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val name = parser.currentName()
            parser.nextToken()
            when (name) {
                "ankiMinerAndroidSettings" -> {
                    backupVersion =
                        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                            parser.text.toIntOrNull()?.takeIf { it in 1..BACKUP_FORMAT_VERSION }
                        } else {
                            null
                        }
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
                "resourceChains" -> {
                    resourceChainsSeen = true
                    resourceChainsIsObject = parser.currentToken() == JsonToken.START_OBJECT
                    if (resourceChainsIsObject) {
                        readResourceChains(parser, resourceChains)
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
        val version = backupVersion
        if (version == null || !settingsSeen || !settingsIsObject) {
            throw SettingsBackupException(SettingsBackupFailure.NOT_A_BACKUP)
        }
        if (
            version >= BACKUP_FORMAT_VERSION &&
                (
                    !resourceChainsSeen ||
                        !resourceChainsIsObject ||
                        resourceChains.keys != resourceChainKeyNames ||
                        resourceChainKeyNames.any { it !in values }
                )
        ) {
            throw SettingsBackupException(SettingsBackupFailure.MALFORMED)
        }
        if (version < 2) {
            values.keys
                .filter { name -> values.getValue(name) === ClearedValue }
                .forEach { name -> values[name] = RejectedValue }
        }
        return ParsedSettingsBackup(
            values = values,
            ignoredKeys = ignoredKeys,
            formatVersion = version,
            resourceChains = resourceChains,
        )
    }

    private fun readResourceChains(
        parser: JsonParser,
        chains: MutableMap<String, List<PortableResourceSelection>>,
    ) {
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val name = parser.currentName()
            parser.nextToken()
            if (
                name !in resourceChainKeyNames ||
                    parser.currentToken() != JsonToken.START_ARRAY ||
                    chains.containsKey(name)
            ) {
                throw SettingsBackupException(SettingsBackupFailure.MALFORMED)
            }
            chains[name] = readResourceChain(parser)
        }
    }

    private fun readResourceChain(parser: JsonParser): List<PortableResourceSelection> {
        val selections = mutableListOf<PortableResourceSelection>()
        val ids = mutableSetOf<String>()
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (
                parser.currentToken() != JsonToken.START_OBJECT ||
                    selections.size >= MAX_CHAIN_ENTRIES
            ) {
                throw SettingsBackupException(SettingsBackupFailure.MALFORMED)
            }
            var resourceId: String? = null
            var enabled: Boolean? = null
            var matchKeySeen = false
            var matchKey: String? = null
            val fields = mutableSetOf<String>()
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val name = parser.currentName()
                parser.nextToken()
                if (!fields.add(name)) throw SettingsBackupException(SettingsBackupFailure.MALFORMED)
                when (name) {
                    "resourceId" ->
                        if (parser.currentToken() == JsonToken.VALUE_STRING) {
                            resourceId = parser.text
                        } else {
                            throw SettingsBackupException(SettingsBackupFailure.MALFORMED)
                        }
                    "enabled" ->
                        if (
                            parser.currentToken() in
                            setOf(JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE)
                        ) {
                            enabled = parser.booleanValue
                        } else {
                            throw SettingsBackupException(SettingsBackupFailure.MALFORMED)
                        }
                    "matchKey" -> {
                        matchKeySeen = true
                        matchKey =
                            when (parser.currentToken()) {
                                JsonToken.VALUE_NULL -> null
                                JsonToken.VALUE_STRING -> parser.text
                                else -> throw SettingsBackupException(SettingsBackupFailure.MALFORMED)
                            }
                    }
                    else -> throw SettingsBackupException(SettingsBackupFailure.MALFORMED)
                }
            }
            val id = resourceId
            val active = enabled
            if (
                id == null ||
                    !RESOURCE_ID.matches(id) ||
                    !ids.add(id) ||
                    active == null ||
                    !matchKeySeen ||
                    (matchKey != null && !MATCH_KEY.matches(checkNotNull(matchKey)))
            ) {
                throw SettingsBackupException(SettingsBackupFailure.MALFORMED)
            }
            selections += PortableResourceSelection(id, active, matchKey)
        }
        return selections
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
            parser.currentToken() == JsonToken.VALUE_NULL -> ClearedValue
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

    private data class ResourceInventoryEntry(
        val resourceId: String,
        val matchKey: String,
    )

    private fun portableResourceChains(
        settings: AppSettings,
        resources: ResourceManagerState,
    ): Map<String, List<PortableResourceSelection>> {
        val inventory = portableResourceInventory(resources)
        return linkedMapOf(
            "dictionary_sources_v1" to
                portableSelections(
                    settings.dictionarySources,
                    inventory.getValue("dictionary_sources_v1"),
                ),
            "frequency_sources_v1" to
                portableSelections(
                    settings.frequencySources,
                    inventory.getValue("frequency_sources_v1"),
                ),
            "pitch_sources_v1" to
                portableSelections(
                    settings.pitchSources,
                    inventory.getValue("pitch_sources_v1"),
                ),
            "audio_packs_v1" to
                portableSelections(
                    settings.audioPacks,
                    inventory.getValue("audio_packs_v1"),
                ),
        )
    }

    private fun portableSelections(
        persisted: List<ResourceChainSelection>,
        installed: List<ResourceInventoryEntry>,
    ): List<PortableResourceSelection> {
        val matchKeys = installed.associate { it.resourceId to it.matchKey }
        return persisted.map { selection ->
            PortableResourceSelection(
                resourceId = selection.resourceId,
                enabled = selection.enabled,
                matchKey = matchKeys[selection.resourceId],
            )
        }
    }

    private fun portableResourceInventory(
        resources: ResourceManagerState,
    ): Map<String, List<ResourceInventoryEntry>> =
        linkedMapOf(
            "dictionary_sources_v1" to
                resources.dictionaries
                    .filter { it.isUsable }
                    .map { dictionary ->
                        ResourceInventoryEntry(
                            resourceId = dictionary.slotId,
                            matchKey = dictionaryMatchKey(dictionary),
                        )
                    },
            "frequency_sources_v1" to
                resources.frequencySources
                    .filter { it.schemaOk && it.entryCount > 0 }
                    .map { frequency ->
                        ResourceInventoryEntry(
                            resourceId = frequency.sourceId,
                            matchKey = frequencyMatchKey(frequency),
                        )
                    },
            "pitch_sources_v1" to
                resources.pitchSources
                    .filter { it.schemaOk && it.entryCount > 0 }
                    .map { pitch ->
                        ResourceInventoryEntry(
                            resourceId = pitch.sourceId,
                            matchKey = pitchMatchKey(pitch),
                        )
                    },
            "audio_packs_v1" to
                resources.audioPacks
                    .filter { it.contentAvailable && it.entryCount > 0 }
                    .map { pack ->
                        ResourceInventoryEntry(
                            resourceId = pack.packId,
                            matchKey = semanticMatchKey("audio-pack", pack.packId),
                        )
                    },
        )

    private fun dictionaryMatchKey(dictionary: InstalledDictionary): String =
        dictionary.catalogResourceId?.let { resourceId ->
            semanticMatchKey("dictionary-catalog", resourceId)
        } ?: semanticMatchKey(
            "dictionary-custom",
            dictionary.sourceName,
            dictionary.sourceRevision,
            dictionary.format,
            dictionary.entryCount.toString(),
            dictionary.embeddedAttribution.entries
                .sortedBy { (key, _) -> key }
                .joinToString(separator = "\u0000") { (key, value) -> "$key\u0000$value" },
        )

    /** Filename-derived ids and display names may change; content-shape fields remain portable. */
    private fun frequencyMatchKey(source: InstalledFrequencySource): String =
        semanticMatchKey(
            "frequency",
            source.format,
            source.entryCount.toString(),
            source.isCategorical.toString(),
        )

    /** Filename-derived ids and display names may change; revision and content shape do not. */
    private fun pitchMatchKey(source: InstalledPitchSource): String =
        semanticMatchKey(
            "pitch",
            source.sourceRevision,
            source.format,
            source.entryCount.toString(),
        )

    private fun semanticMatchKey(
        kind: String,
        vararg parts: String,
    ): String {
        val canonical =
            buildString {
                parts.forEach { part ->
                    val bytes = part.toByteArray(Charsets.UTF_8)
                    append(bytes.size)
                    append(':')
                    append(part)
                }
            }
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte ->
                    val unsigned = byte.toInt() and 0xff
                    buildString(2) {
                        append(HEX_DIGITS[unsigned ushr 4])
                        append(HEX_DIGITS[unsigned and 0x0f])
                    }
                }
        return "$kind:$digest"
    }

    /**
     * Resolve only unique semantic matches. Ambiguous candidates remain in inventory order and
     * disabled; a raw slot hint with no semantic match may identify an unrelated install, so it is
     * retained only disabled. Newly installed resources append enabled.
     */
    private fun resolvePortableResourceChain(
        persisted: List<PortableResourceSelection>,
        installed: List<ResourceInventoryEntry>,
    ): List<ResourceChainSelection> {
        val unused = installed.toMutableList()
        val resolved = mutableListOf<ResourceChainSelection>()
        persisted.forEach { selection ->
            val matches =
                selection.matchKey?.let { matchKey ->
                    unused.filter { it.matchKey == matchKey }
                }.orEmpty()
            when (matches.size) {
                1 -> {
                    val match = matches.single()
                    resolved += ResourceChainSelection(match.resourceId, selection.enabled)
                    unused.remove(match)
                }
                0 -> {
                    unused.firstOrNull { it.resourceId == selection.resourceId }?.let { hint ->
                        resolved += ResourceChainSelection(hint.resourceId, enabled = false)
                        unused.remove(hint)
                    }
                }
                else -> {
                    matches.forEach { match ->
                        resolved += ResourceChainSelection(match.resourceId, enabled = false)
                        unused.remove(match)
                    }
                }
            }
        }
        resolved += unused.map { entry -> ResourceChainSelection(entry.resourceId, enabled = true) }
        return resolved
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
            in booleanKeyNames -> writeOrClear(booleanPreferencesKey(name), value)
            in intKeyNames -> writeOrClear(intPreferencesKey(name), value)
            in doubleKeyNames -> writeOrClear(doublePreferencesKey(name), value)
            in stringKeyNames -> writeOrClear(stringPreferencesKey(name), value)
            else -> error("Unknown portable preference key: $name")
        }
    }

    private inline fun <reified T : Any> MutablePreferences.writeOrClear(
        key: Preferences.Key<T>,
        value: Any,
    ) {
        if (value === ClearedValue) remove(key) else this[key] = value as T
    }

    private val RESOURCE_ID = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
    private val MATCH_KEY = Regex("[a-z-]+:[0-9a-f]{64}")
    private const val MAX_CHAIN_ENTRIES = 128
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}
