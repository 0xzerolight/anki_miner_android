package com.ankiminer.android.data.settings

import com.ankiminer.android.anki.provider.AnkiMinerNoteModel
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.LanguageProfileInfo
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.exc.StreamReadException
import java.io.StringWriter

/**
 * The settings that belong to the active mining language.
 *
 * Mirrors desktop `anki_miner/languages/switching.py`: a switch parks the outgoing language's scoped
 * values in [AppSettings.languageStash], brings the incoming language's parked values back, and
 * starts a first visit from that language's profile. The stash is Kotlin's alone and never crosses
 * the bridge; only [AppSettings.language] does.
 */
internal object LanguageScope {
    const val JAPANESE = "ja"

    /** A registry code, the shape the bridge accepts. */
    val LANGUAGE_CODE = Regex("[a-z]{2,3}")

    /**
     * The vendored `LANGUAGE_SCOPED_FIELDS`, verbatim and in order.
     * `tools/engine-sync/tests/test_language_scoped_fields_mirror.py` fails when the two drift.
     */
    val ENGINE_FIELDS: List<String> =
        listOf(
            "dictionary_chain",
            "frequency_chain",
            "pitch_chain",
            "expression_audio_chain",
            "allowed_pos",
            "excluded_subtypes",
            "excluded_wordsets",
            "exclude_hiragana_only_words",
            "exclude_katakana_only_words",
            "known_words_match_kana_variants",
            "anki_fields",
            "anki_deck_name",
            "anki_note_type",
            "card_type",
            "blacklist_path",
            "whitelist_path",
            "use_blacklist",
            "use_whitelist",
            "downloader_subtitle_langs",
            "downloader_audio_lang",
            "excluded_decks",
            "script_variant",
            "reading_tone_color",
            "use_subtitle_regex_filter",
            "subtitle_regex_filter",
            "subtitle_regex_replacement",
            "min_frequency_rank",
            "max_frequency_rank",
            "frequency_keep_unranked",
            "pitch_category_format",
            "card_type_marker_fields",
            "max_sentence_chars",
        )

    /**
     * Each [AppSettings] property the active language owns, with the preference it is stored under.
     *
     * Membership is measured, not chosen: `LanguageScopeTest` changes every [AppSettings] property
     * in turn and requires this map to hold exactly those that move a snapshot key in
     * [ENGINE_FIELDS]. That is how indirect feeders get here — `audioPacks` writes the whole
     * `expression_audio_chain`, and `cardTypeMarkerField` decides whether `card_type` reaches the
     * engine at all.
     */
    val SETTINGS: Map<String, String> =
        linkedMapOf(
            "deckName" to "deck_name",
            "excludedDecks" to "excluded_decks_v1",
            "noteType" to "note_type",
            "fieldMap" to "field_map_v1",
            "cardType" to "card_type",
            "cardTypeMarkerField" to "card_type_marker_field",
            "subtitleRegexFilter" to "subtitle_regex_filter",
            "subtitleRegexReplacement" to "subtitle_regex_replacement",
            "useSubtitleRegexFilter" to "use_subtitle_regex_filter",
            "useBlacklist" to "use_blacklist",
            "useWhitelist" to "use_whitelist",
            "excludeHiraganaOnly" to "exclude_hiragana_only",
            "excludeKatakanaOnly" to "exclude_katakana_only",
            "maxFrequencyRank" to "max_frequency_rank",
            "minFrequencyRank" to "min_frequency_rank",
            "frequencyKeepUnranked" to "frequency_keep_unranked",
            "knownWordsMatchKanaVariants" to "known_words_match_kana_variants",
            "scriptVariant" to "script_variant",
            "readingToneColor" to "reading_tone_color",
            "dictionarySources" to "dictionary_sources_v1",
            "frequencySources" to "frequency_sources_v1",
            "pitchSources" to "pitch_sources_v1",
            "audioPacks" to "audio_packs_v1",
            "enabledWordsets" to "enabled_wordsets_v2",
            "maxSentenceCharacters" to "max_sentence_characters",
            "pitchCategoryFormat" to "pitch_category_format",
        )

    val PREFERENCE_NAMES: Set<String> = SETTINGS.values.toSet()

    /**
     * Scoped preferences that were global on Android when stashes could already exist: desktop
     * `_FORMERLY_GLOBAL_FIELDS`, minus the names Android scoped together with the stash itself (the
     * regex trio, the frequency band and `card_type_marker_field` were scoped from day one).
     */
    internal val FORMERLY_GLOBAL_PREFERENCES: Set<String> =
        setOf("max_sentence_characters", "pitch_category_format")

    /**
     * What a first visit to [profile] starts from, before anything parked for it is laid over.
     *
     * Every scoped setting not named here keeps its unset value. For the nullable processing
     * settings that is the profile's own value: the bridge overlays the snapshot on the language's
     * first-visit config, so an omitted key resolves to `scoped_defaults` there. Three settings
     * deliberately ignore the profile, as they do on a fresh Japanese install: the note type, the
     * field map and the card type stay the user's to pick (no run starts until they do, rather than
     * mining into a desktop default).
     */
    fun firstVisit(profile: LanguageProfileInfo): AppSettings {
        val defaults = profile.scopedDefaults
        return AppSettings(
            language = profile.code,
            deckName =
                defaults.text("anki_deck_name")
                    .takeUnless { it.isEmpty() || it == AnkiMinerNoteModel.DEFAULT_DECK_NAME },
            excludedDecks = defaults.texts("excluded_decks"),
            dictionarySources =
                defaults.chain("dictionary_chain", "dict_id") { entry ->
                    entry["kind"] == BridgeJsonValue.Text("indexed")
                },
            frequencySources = defaults.chain("frequency_chain", "source_id"),
            pitchSources = defaults.chain("pitch_chain", "source_id"),
            audioPacks = defaults.chain("expression_audio_chain", "pack_id"),
            enabledWordsets = defaults.texts("excluded_wordsets"),
        )
    }

    private fun Map<String, BridgeJsonValue>.text(key: String): String =
        (get(key) as? BridgeJsonValue.Text)?.value.orEmpty()

    private fun Map<String, BridgeJsonValue>.texts(key: String): List<String> =
        (get(key) as? BridgeJsonValue.ArrayValue)
            ?.values
            ?.mapNotNull { (it as? BridgeJsonValue.Text)?.value }
            .orEmpty()

    private fun Map<String, BridgeJsonValue>.chain(
        key: String,
        idKey: String,
        include: (Map<String, BridgeJsonValue>) -> Boolean = { true },
    ): List<ResourceChainSelection> =
        (get(key) as? BridgeJsonValue.ArrayValue)
            ?.values
            ?.mapNotNull { (it as? BridgeJsonValue.ObjectValue)?.values }
            ?.filter(include)
            ?.mapNotNull { entry ->
                val id = (entry[idKey] as? BridgeJsonValue.Text)?.value ?: return@mapNotNull null
                ResourceChainSelection(id, (entry["enabled"] as? BridgeJsonValue.Bool)?.value ?: true)
            }
            .orEmpty()
}

/**
 * Make [target] the active mining language, desktop `switch_language` step for step.
 *
 * The outgoing language's scoped settings are parked under its code; the incoming language's
 * parked settings are laid over its [LanguageScope.firstVisit] values, so a setting the parked
 * snapshot lacks takes the profile default rather than keeping the outgoing language's value. An
 * entry parked for the language that is already active is stale (a settings import can change the
 * language without touching the machine-local stash), so it is discarded.
 *
 * A [LanguageScope.FORMERLY_GLOBAL_PREFERENCES] name that no parked snapshot carries yet was global
 * when they were parked, so its live value is the one every language shared: each snapshot is
 * completed with it once. From then on every snapshot carries its own, and a first visit still
 * starts from the profile.
 */
internal fun AppSettings.switchLanguage(target: LanguageProfileInfo): AppSettings {
    val code = target.code
    if (code == language) {
        return if (code in languageStash) copy(languageStash = languageStash - code) else this
    }
    val live = DataStoreAppSettingsRepository.parkScoped(this)
    val alreadyParked = languageStash.values.flatMapTo(mutableSetOf()) { it.keys }
    val shared = (LanguageScope.FORMERLY_GLOBAL_PREFERENCES - alreadyParked).associateWith { live[it] }
    val stash = languageStash.mapValues { (_, parked) -> shared + parked }.toMutableMap()
    stash[language] = live
    val parked = stash.remove(code).orEmpty()
    return DataStoreAppSettingsRepository.overlayScoped(
        copy(language = code, languageStash = stash),
        DataStoreAppSettingsRepository.parkScoped(LanguageScope.firstVisit(target)) + parked,
    )
}

/**
 * Each parked language's scoped settings, decoded as settings of their own. A diagnostics export
 * redacts their deck, note type and field names like the active language's: log lines written
 * while that language was active still carry them.
 */
internal fun AppSettings.parkedLanguages(): List<AppSettings> =
    languageStash.map { (code, parked) ->
        DataStoreAppSettingsRepository.overlayScoped(AppSettings(language = code), parked)
    }

/**
 * Storage for [AppSettings.languageStash]: one JSON object keyed by language code, each value the
 * parked preference values of that language's scoped settings, `null` where a setting was unset.
 *
 * Names outside [LanguageScope.PREFERENCE_NAMES] are dropped on read, as desktop filters a parked
 * snapshot to the scoped names before restoring it. Values are only checked for shape here; the
 * restore decodes them through the ordinary preference validation.
 */
internal object LanguageStashPreferenceCodec {
    private const val MAX_LANGUAGES = 64
    private val factory = JsonFactory()

    fun encode(stash: Map<String, Map<String, Any?>>): String? {
        if (stash.isEmpty()) return null
        validate(stash)
        val output = StringWriter()
        factory.createGenerator(output).use { generator ->
            generator.writeStartObject()
            stash.toSortedMap().forEach { (code, values) ->
                generator.writeObjectFieldStart(code)
                values.toSortedMap().forEach { (name, value) ->
                    when (value) {
                        null -> generator.writeNullField(name)
                        is Boolean -> generator.writeBooleanField(name, value)
                        is Int -> generator.writeNumberField(name, value)
                        is Double -> generator.writeNumberField(name, value)
                        is String -> generator.writeStringField(name, value)
                        else -> invalid()
                    }
                }
                generator.writeEndObject()
            }
            generator.writeEndObject()
        }
        return output.toString()
    }

    fun decode(raw: String): Map<String, Map<String, Any?>> =
        try {
            factory.createParser(raw).use(::readStash).also(::validate)
        } catch (failure: StreamReadException) {
            invalid(failure)
        }

    private fun readStash(parser: JsonParser): Map<String, Map<String, Any?>> {
        if (parser.nextToken() != JsonToken.START_OBJECT) invalid()
        val stash = linkedMapOf<String, Map<String, Any?>>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val code = parser.currentName()
            if (parser.nextToken() != JsonToken.START_OBJECT || code in stash) invalid()
            val values = linkedMapOf<String, Any?>()
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val name = parser.currentName()
                val value =
                    when (parser.nextToken()) {
                        JsonToken.VALUE_NULL -> null
                        JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE -> parser.booleanValue
                        JsonToken.VALUE_NUMBER_INT -> parser.text.toIntOrNull() ?: invalid()
                        JsonToken.VALUE_NUMBER_FLOAT -> parser.doubleValue
                        JsonToken.VALUE_STRING -> parser.text
                        else -> invalid()
                    }
                if (name in values) invalid()
                if (name in LanguageScope.PREFERENCE_NAMES) values[name] = value
            }
            stash[code] = values
        }
        if (parser.nextToken() != null) invalid()
        return stash
    }

    private fun validate(stash: Map<String, Map<String, Any?>>) {
        if (stash.size > MAX_LANGUAGES || stash.keys.any { !LanguageScope.LANGUAGE_CODE.matches(it) }) {
            invalid()
        }
    }

    private fun invalid(cause: Throwable? = null): Nothing =
        throw InvalidAppSettingException("Saved language settings are invalid").apply { cause?.let(::initCause) }
}
