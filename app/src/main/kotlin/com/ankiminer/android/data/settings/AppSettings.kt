package com.ankiminer.android.data.settings

import com.ankiminer.android.anki.generated.AnkiLimitsV1
import com.ankiminer.android.anki.generated.UnicodeContractV151
import com.ankiminer.android.anki.provider.AnkiFieldMapPolicy
import com.ankiminer.android.anki.provider.AnkiFieldKeys
import com.ankiminer.android.anki.provider.AnkiMinerNoteModel
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.MiningConfigSnapshot

enum class AudioFormat(val wireValue: String) {
    MP3("mp3"),
    OPUS("opus"),
}

/**
 * JP Mining Note card modes. The engine stamps `"x"` into the field mapped for the active mode, and
 * the note type's own templates render that as the card type.
 *
 * [conventionalField] is the name JP Mining Note itself uses, offered as the default pick when the
 * chosen note type has it.
 */
enum class CardType(val wireValue: String, val conventionalField: String) {
    WORD_AND_SENTENCE("word_and_sentence", "IsWordAndSentenceCard"),
    CLICK("click", "IsClickCard"),
    SENTENCE("sentence", "IsSentenceCard"),
    AUDIO("audio", "IsAudioCard"),
    ;

    companion object {
        fun fromWire(stored: String?): CardType? = entries.singleOrNull { it.wireValue == stored }
    }
}

enum class PitchCategoryFormat(val wireValue: String) {
    JAPANESE("jp"),
    ROMAJI("romaji"),
}

enum class ThemeMode(val wireValue: String) {
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** Absent or unrecognised stored values fall back to the dark default. */
        fun fromWire(stored: String?): ThemeMode = entries.singleOrNull { it.wireValue == stored } ?: DARK
    }
}

/** Persisted ordering and enable state for one Android-owned local resource chain. */
data class ResourceChainSelection(
    val resourceId: String,
    val enabled: Boolean = true,
)

/**
 * Android-owned preferences. Nullable processing fields mean "use the current engine default";
 * the Android-owned Anki model contract is always emitted explicitly by the snapshot mapper.
 *
 * [deduplicateSentences] is the one deliberate exception: Android defaults it off while the desktop
 * engine defaults it on. See the field for why.
 *
 * Defaults here are NOT the defaults the app reads. [AppSettingsRepository] names every constructor
 * parameter and supplies each value from `decoder.read(key, <literal>, …)`, which returns that
 * literal when the key is absent — so a default only takes effect once both sites agree.
 */
data class AppSettings(
    /** The onboarding wizard was offered once and completed or skipped. */
    val setupWizardSeen: Boolean = false,
    val theme: ThemeMode = ThemeMode.DARK,
    val deckName: String? = null,
    /** Anki deck scopes omitted while collecting known vocabulary. Parent names cover children. */
    val excludedDecks: List<String> = emptyList(),
    val noteType: String? = null,
    val fieldMap: Map<String, String> = emptyMap(),
    /**
     * Card mode and the note-type field that marks it. Desktop keeps a name per mode as a rename
     * escape hatch, but only the active mode's field is ever read, so one destination is enough.
     * Both must be set for either to reach the engine.
     */
    val cardType: CardType? = null,
    val cardTypeMarkerField: String? = null,
    val tags: String? = null,
    val audioPaddingSeconds: Double? = null,
    val screenshotOffsetSeconds: Double? = null,
    val subtitleOffsetSeconds: Double? = null,
    val audioFormat: AudioFormat? = null,
    val audioBitrateKbps: Int? = null,
    /**
     * Animated screenshots: the card's Picture field gets a short looping clip instead of a single
     * frame. Off by default — it is materially slower to mine and the media is far larger.
     *
     * The output format is not a setting: the engine downgrades AVIF to WebP when the AV1 encoder is
     * missing, and [EngineSettingsSnapshotMapper] downgrades it again when the device's MIME table
     * cannot name a `.avif` file.
     */
    val animatedScreenshotsEnabled: Boolean = false,
    val animatedScreenshotDurationSeconds: Double? = null,
    val animatedScreenshotQuality: Int? = null,
    /**
     * Structural subtitle-annotation strip: whole-line sound-effect captions, leading speaker tags,
     * and inline furigana. Engine default is on, and it runs before the user regex filter.
     */
    val stripSubtitleAnnotations: Boolean? = null,
    /** Python `re` pattern removed from subtitle text before mining. See [SubtitleRegexCheck]. */
    val subtitleRegexFilter: String? = null,
    /** Inserted in place of each match. Python backreferences (`\1`), not `$1`. */
    val subtitleRegexReplacement: String? = null,
    val useSubtitleRegexFilter: Boolean? = null,
    /**
     * Word-list toggles. The paths are not persisted: the files live at a fixed location owned by
     * the resource manager, so a stored absolute path could only go stale.
     */
    val useBlacklist: Boolean? = null,
    val useWhitelist: Boolean? = null,
    val useKnownWordsDatabase: Boolean? = null,
    val excludeHiraganaOnly: Boolean? = null,
    val excludeKatakanaOnly: Boolean? = null,
    val boldTargetInSentence: Boolean? = null,
    /**
     * Off by default on Android, against the desktop engine's `deduplicate_sentences = True`.
     * The filter keeps only the first word per sentence and runs BEFORE curation, so on a phone —
     * where curation is the whole interaction — it silently withholds candidates the user never
     * sees. Keep in sync with the decode fallback in [AppSettingsRepository].
     */
    val deduplicateSentences: Boolean? = false,
    val useIPlusOneFilter: Boolean? = null,
    val useSentenceLengthFilter: Boolean? = null,
    val maxSentenceDurationSeconds: Double? = null,
    val maxSentenceCharacters: Int? = null,
    val readingMinimumOccurrence: Int? = null,
    val maxFrequencyRank: Int? = null,
    val pitchCategoryFormat: PitchCategoryFormat? = null,
    val maxParallelWorkers: Int? = null,
    val dictionarySources: List<ResourceChainSelection> = emptyList(),
    val frequencySources: List<ResourceChainSelection> = emptyList(),
    val pitchSources: List<ResourceChainSelection> = emptyList(),
    val audioPacks: List<ResourceChainSelection> = emptyList(),
    /** Bundled proper-noun rejection sets enabled for mining. Wire name stays excluded_wordsets. */
    val enabledWordsets: List<String> = DEFAULT_ENABLED_WORDSETS,
    val readingTtsEnabled: Boolean = false,
    val jishoEnabled: Boolean = false,
) {
    /** Restore processing behavior without changing onboarding, appearance, target, or resources. */
    fun restoreMiningDefaults(): AppSettings =
        copy(
            tags = null,
            audioPaddingSeconds = null,
            screenshotOffsetSeconds = null,
            subtitleOffsetSeconds = null,
            audioFormat = null,
            audioBitrateKbps = null,
            animatedScreenshotsEnabled = false,
            animatedScreenshotDurationSeconds = null,
            animatedScreenshotQuality = null,
            stripSubtitleAnnotations = null,
            subtitleRegexFilter = null,
            subtitleRegexReplacement = null,
            useSubtitleRegexFilter = null,
            useBlacklist = null,
            useWhitelist = null,
            useKnownWordsDatabase = null,
            excludeHiraganaOnly = null,
            excludeKatakanaOnly = null,
            boldTargetInSentence = null,
            deduplicateSentences = false,
            useIPlusOneFilter = null,
            useSentenceLengthFilter = null,
            maxSentenceDurationSeconds = null,
            maxSentenceCharacters = null,
            readingMinimumOccurrence = null,
            maxFrequencyRank = null,
            pitchCategoryFormat = null,
            maxParallelWorkers = null,
            readingTtsEnabled = false,
        )

    /** Clear only the user-owned Anki destination and its mapping. */
    fun resetAnkiTarget(): AppSettings =
        copy(
            deckName = null,
            noteType = null,
            fieldMap = emptyMap(),
            cardType = null,
            cardTypeMarkerField = null,
        )

    /** Clear resource priority/enable choices without removing installed resource files. */
    fun resetResourceChoices(
        dictionaryIds: List<String> = emptyList(),
        frequencyIds: List<String> = emptyList(),
        pitchIds: List<String> = emptyList(),
        audioPackIds: List<String> = emptyList(),
    ): AppSettings =
        copy(
            // Empty chains mean "newly discovered and enabled". Explicit disabled entries make a
            // user-requested reset remain visibly clear for resources already installed.
            dictionarySources = dictionaryIds.map { ResourceChainSelection(it, enabled = false) },
            frequencySources = frequencyIds.map { ResourceChainSelection(it, enabled = false) },
            pitchSources = pitchIds.map { ResourceChainSelection(it, enabled = false) },
            audioPacks = audioPackIds.map { ResourceChainSelection(it, enabled = false) },
            enabledWordsets = DEFAULT_ENABLED_WORDSETS,
            jishoEnabled = false,
        )

    companion object {
        val DEFAULT_ENABLED_WORDSETS =
            listOf("surnames", "given-names", "place-names", "org-product")
    }
}

internal enum class InvalidAppSettingCode {
    NUMERIC_INCOMPLETE,
    EXCLUDED_DECKS_INVALID,
    CANONICAL_NAME_INVALID,
    INVALID_UNICODE,
    NON_FINITE,
    NEGATIVE,
    NOT_POSITIVE,
    PARALLEL_WORKERS_RANGE,
    ANIMATED_SCREENSHOT_DURATION_RANGE,
    ANIMATED_SCREENSHOT_QUALITY_RANGE,
    NETWORK_AUDIO_UNSUPPORTED,
    WORDSETS_INVALID,
    FIELD_MAP_UNKNOWN_KEY,
    FIELD_MAP_CONFLICT,
    RESOURCE_IDS_INVALID,
    CARD_TYPE_MARKER_CONFLICT,
    SUBTITLE_REGEX_TOO_LONG,
    SUBTITLE_REGEX_REPLACEMENT_TOO_LONG,
    SUBTITLE_REGEX_UNBOUNDED_REPEAT,
    SUBTITLE_REGEX_BACKREFERENCE,
    UNKNOWN,
}

internal class InvalidAppSettingException(
    internal val code: InvalidAppSettingCode,
    internal val arguments: List<Any>,
    message: String,
) : IllegalArgumentException(message) {
    constructor(message: String) : this(
        code = InvalidAppSettingCode.UNKNOWN,
        arguments = listOf(message),
        message = message,
    )
}

/**
 * Parsing rules for editable settings fields, kept separate from persisted-value validation.
 *
 * Decimal text is intentionally locale-invariant: a comma is malformed input rather than a decimal
 * separator, so a locale can never silently change a persisted or engine-bound value.
 */
internal object AppSettingsDraftParser {
    fun isOptionalDouble(value: String): Boolean =
        value.isEmpty() || value.toDoubleOrNull()?.isFinite() == true

    fun isOptionalInt(value: String): Boolean = value.isEmpty() || value.toIntOrNull() != null

    fun optionalDouble(value: String): Double? =
        if (value.isEmpty()) {
            null
        } else {
            value.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: throw InvalidAppSettingException(
                    code = InvalidAppSettingCode.NUMERIC_INCOMPLETE,
                    arguments = emptyList(),
                    message = "Complete or clear every numeric value",
                )
        }

    fun optionalInt(value: String): Int? =
        if (value.isEmpty()) {
            null
        } else {
            value.toIntOrNull()
                ?: throw InvalidAppSettingException(
                    code = InvalidAppSettingCode.NUMERIC_INCOMPLETE,
                    arguments = emptyList(),
                    message = "Complete or clear every numeric value",
                )
        }
}

object AppSettingsValidator {
    fun validate(settings: AppSettings): AppSettings =
        settings.also {
            it.deckName?.let { value -> canonicalName("Deck name", value) }
            if (
                it.excludedDecks.size > AnkiLimitsV1.Names.ExcludedDecks.MAX_ITEM_COUNT ||
                    it.excludedDecks.distinct().size != it.excludedDecks.size ||
                    it.excludedDecks.any { deck ->
                        deck.toByteArray(Charsets.UTF_8).size > AnkiLimitsV1.Names.Deck.MAX_UTF8_BYTES ||
                            (UnicodeContractV151.scalarCount(deck) ?: Int.MAX_VALUE) >
                            AnkiLimitsV1.Names.Deck.MAX_CODE_POINTS
                    } ||
                    it.excludedDecks.sumOf { deck -> deck.toByteArray(Charsets.UTF_8).size.toLong() } >
                    AnkiLimitsV1.Names.ExcludedDecks.MAX_TOTAL_UTF8_BYTES.toLong()
            ) {
                invalid(
                    InvalidAppSettingCode.EXCLUDED_DECKS_INVALID,
                    "Excluded Anki decks are invalid",
                )
            }
            it.excludedDecks.forEach { deck -> canonicalName("Excluded deck name", deck) }
            it.noteType?.let { value -> canonicalName("Note type", value) }
            fieldMap(it.fieldMap)
            cardTypeMarker(it.cardTypeMarkerField, it.fieldMap)
            it.tags?.let { value -> validScalarText("Tags", value) }
            it.subtitleRegexFilter?.let { value -> validScalarText("Subtitle regex filter", value) }
            it.subtitleRegexReplacement?.let { value ->
                validScalarText("Subtitle regex replacement", value)
            }
            subtitleRegex(it.subtitleRegexFilter, it.subtitleRegexReplacement)
            nonNegative("Audio padding", it.audioPaddingSeconds)
            nonNegative("Screenshot offset", it.screenshotOffsetSeconds)
            finite("Subtitle offset", it.subtitleOffsetSeconds)
            positive("Audio bitrate", it.audioBitrateKbps)
            nonNegative("Maximum sentence duration", it.maxSentenceDurationSeconds)
            nonNegative("Maximum sentence characters", it.maxSentenceCharacters)
            positive("Reading minimum occurrence", it.readingMinimumOccurrence)
            nonNegative("Maximum frequency rank", it.maxFrequencyRank)
            it.maxParallelWorkers?.let { workers ->
                if (workers !in 1..32) {
                    invalid(
                        InvalidAppSettingCode.PARALLEL_WORKERS_RANGE,
                        "Parallel workers must be between 1 and 32",
                    )
                }
            }
            // Ranges mirror android_bridge/config_map.py exactly; the wire codec rejects the same
            // bounds, so a drift here surfaces as a bridge protocol failure rather than a bad card.
            // Checked under the same condition the mapper emits them, so what is validated is
            // exactly what can reach the wire — a stale value behind a disabled toggle goes nowhere
            // and must not block every other setting from saving.
            if (it.animatedScreenshotsEnabled) {
                it.animatedScreenshotDurationSeconds?.let { seconds ->
                    if (!seconds.isFinite() || seconds < 0.5 || seconds > 10.0) {
                        invalid(
                            InvalidAppSettingCode.ANIMATED_SCREENSHOT_DURATION_RANGE,
                            "Clip length must be between 0.5 and 10 seconds",
                        )
                    }
                }
                it.animatedScreenshotQuality?.let { quality ->
                    if (quality !in 0..100) {
                        invalid(
                            InvalidAppSettingCode.ANIMATED_SCREENSHOT_QUALITY_RANGE,
                            "Clip quality must be between 0 and 100",
                        )
                    }
                }
            }
            resourceChain("Dictionaries", it.dictionarySources)
            resourceChain("Frequency sources", it.frequencySources)
            resourceChain("Pitch sources", it.pitchSources)
            resourceChain("Audio packs", it.audioPacks)
            if (it.audioPacks.any { selection -> selection.resourceId == "jpod101" }) {
                invalid(
                    InvalidAppSettingCode.NETWORK_AUDIO_UNSUPPORTED,
                    "Network audio sources are not supported on Android",
                )
            }
            if (
                it.enabledWordsets.size > MAX_WORDSET_SELECTIONS ||
                    it.enabledWordsets.distinct().size != it.enabledWordsets.size ||
                    it.enabledWordsets.any { id -> !RESOURCE_ID.matches(id) }
            ) {
                invalid(
                    InvalidAppSettingCode.WORDSETS_INVALID,
                    "Selected bundled wordsets are invalid",
                )
            }
        }

    fun canonicalName(label: String, value: String): String {
        validScalarText(label, value)
        if (
            value.isEmpty() ||
                !UnicodeContractV151.isNfc(value) ||
                UnicodeContractV151.hasLeadingOrTrailingPythonWhitespace(value)
        ) {
            invalid(
                InvalidAppSettingCode.CANONICAL_NAME_INVALID,
                "$label must be non-empty, NFC text without surrounding whitespace",
                label,
            )
        }
        return value
    }

    private fun validScalarText(label: String, value: String) {
        if (UnicodeContractV151.scalarCount(value) == null || containsCategoryC(value)) {
            invalid(
                InvalidAppSettingCode.INVALID_UNICODE,
                "$label contains unsupported control or malformed Unicode characters",
                label,
            )
        }
    }

    private fun containsCategoryC(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val first = value[index]
            val codePoint =
                if (first.isHighSurrogate()) {
                    Character.toCodePoint(first, value[index + 1]).also { index += 2 }
                } else {
                    first.code.also { index += 1 }
                }
            if (UnicodeContractV151.isCategoryC(codePoint)) return true
        }
        return false
    }

    private fun finite(label: String, value: Double?) {
        if (value != null && !value.isFinite()) {
            invalid(InvalidAppSettingCode.NON_FINITE, "$label must be finite", label)
        }
    }

    private fun nonNegative(label: String, value: Double?) {
        finite(label, value)
        if (value != null && value < 0.0) {
            invalid(InvalidAppSettingCode.NEGATIVE, "$label must not be negative", label)
        }
    }

    private fun nonNegative(label: String, value: Int?) {
        if (value != null && value < 0) {
            invalid(InvalidAppSettingCode.NEGATIVE, "$label must not be negative", label)
        }
    }

    private fun positive(label: String, value: Int?) {
        if (value != null && value <= 0) {
            invalid(InvalidAppSettingCode.NOT_POSITIVE, "$label must be positive", label)
        }
    }

    private fun fieldMap(values: Map<String, String>) {
        values.forEach { (key, value) ->
            if (key !in AnkiFieldKeys.ALL) {
                invalid(
                    InvalidAppSettingCode.FIELD_MAP_UNKNOWN_KEY,
                    "Field map contains an unknown key",
                )
            }
            if (value.isNotEmpty()) canonicalName("Field name", value)
        }
        AnkiFieldMapPolicy.firstConflict(values)?.let { conflict ->
            invalid(
                InvalidAppSettingCode.FIELD_MAP_CONFLICT,
                "Anki field '${conflict.destination}' is mapped from multiple logical fields: " +
                    conflict.logicalKeys.joinToString(),
                conflict.destination,
                conflict.logicalKeys.joinToString(),
            )
        }
    }

    /**
     * The marker destination is not part of `anki_fields`, so the field-map uniqueness rule does not
     * cover it. Python enforces the combined uniqueness at the snapshot boundary; reject it here
     * first so the collision surfaces as a settings error rather than a failed run.
     */
    private fun cardTypeMarker(
        marker: String?,
        fieldMap: Map<String, String>,
    ) {
        val destination = marker?.takeIf { it.isNotEmpty() } ?: return
        canonicalName("Card type marker field", destination)
        fieldMap.entries
            .firstOrNull { (_, mapped) -> mapped == destination }
            ?.let { (key, _) ->
                invalid(
                    InvalidAppSettingCode.CARD_TYPE_MARKER_CONFLICT,
                    "Anki field '$destination' is already mapped from '$key'",
                    destination,
                    key,
                )
            }
    }

    /**
     * The two size caps, the nested-repeat reject, and the replacement's group references. Checked
     * even when the filter is switched off, so a stored pattern can never become dangerous by
     * flipping one toggle — the same reason desktop validates the trio together.
     */
    private fun subtitleRegex(
        pattern: String?,
        replacement: String?,
    ) {
        if (pattern == null && replacement == null) return
        when (val code = SubtitleRegexCheck.rejection(pattern, replacement.orEmpty())) {
            null -> Unit
            InvalidAppSettingCode.SUBTITLE_REGEX_TOO_LONG ->
                invalid(
                    code,
                    "Subtitle filter exceeds ${SubtitleRegexCheck.MAX_PATTERN_CHARS} characters",
                    SubtitleRegexCheck.MAX_PATTERN_CHARS,
                )
            InvalidAppSettingCode.SUBTITLE_REGEX_REPLACEMENT_TOO_LONG ->
                invalid(
                    code,
                    "Replacement exceeds ${SubtitleRegexCheck.MAX_REPLACEMENT_CHARS} characters",
                    SubtitleRegexCheck.MAX_REPLACEMENT_CHARS,
                )
            InvalidAppSettingCode.SUBTITLE_REGEX_UNBOUNDED_REPEAT ->
                invalid(code, "Subtitle filter must not nest unbounded repeats")
            else ->
                invalid(code, "Replacement references a group the subtitle filter does not capture")
        }
    }

    private fun resourceChain(
        label: String,
        values: List<ResourceChainSelection>,
    ) {
        if (
            values.size > MAX_CHAIN_ENTRIES ||
                values.map(ResourceChainSelection::resourceId).distinct().size != values.size ||
                values.any { !RESOURCE_ID.matches(it.resourceId) }
        ) {
            invalid(
                InvalidAppSettingCode.RESOURCE_IDS_INVALID,
                "$label contain invalid or duplicate resource IDs",
                label,
            )
        }
    }

    private fun invalid(
        code: InvalidAppSettingCode,
        message: String,
        vararg arguments: Any,
    ): Nothing = throw InvalidAppSettingException(code, arguments.toList(), message)

    private val RESOURCE_ID = Regex("(?!.*(?:\\.\\.|--))[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?")
    private const val MAX_CHAIN_ENTRIES = 128
    private const val MAX_WORDSET_SELECTIONS = 32
}

internal object EngineSettingsSnapshotMapper {
    private val dictionaryId = Regex("(?!.*\\.\\.)[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9_-])?")

    fun map(
        rawSettings: AppSettings,
        installedDictionaryIds: List<String>,
        installedFrequencyIds: List<String> = emptyList(),
        installedPitchIds: List<String> = emptyList(),
        installedAudioPackIds: List<String> = emptyList(),
        availableWordsetIds: List<String> = emptyList(),
        blacklistPath: String? = null,
        whitelistPath: String? = null,
        /**
         * Whether this device's MIME table can name a `.avif` file. Defaults to false so every
         * existing caller and test keeps the format that works everywhere; only the production
         * resolver passes the measured value. See [AnkiMediaMimeCapability] — API 26 answers no,
         * API 36 answers yes, and a `.avif` AnkiDroid cannot name is stored as `.bin`.
         */
        avifNameable: Boolean = false,
    ): MiningConfigSnapshot {
        val settings = AppSettingsValidator.validate(rawSettings)
        require(installedDictionaryIds.distinct() == installedDictionaryIds)
        require(installedDictionaryIds.all(dictionaryId::matches))
        require(installedFrequencyIds.distinct() == installedFrequencyIds)
        require(installedFrequencyIds.all(dictionaryId::matches))
        require(installedPitchIds.distinct() == installedPitchIds)
        require(installedPitchIds.all(dictionaryId::matches))
        require(installedAudioPackIds.distinct() == installedAudioPackIds)
        require(installedAudioPackIds.all(dictionaryId::matches))
        require(installedAudioPackIds.none { it == "jpod101" })
        require(availableWordsetIds.distinct() == availableWordsetIds)
        require(availableWordsetIds.all(dictionaryId::matches))
        val values = linkedMapOf<String, BridgeJsonValue>()
        // The deck keeps an Android-owned default, but the note type and field map are the user's.
        // Emit them fail-closed rather than inheriting the desktop Lapis default or the first-party
        // model, so an unconfigured target can never silently mine into "Anki Miner".
        values["anki_deck_name"] =
            text(settings.deckName ?: AnkiMinerNoteModel.DEFAULT_DECK_NAME)
        values["excluded_decks"] = BridgeJsonValue.ArrayValue(settings.excludedDecks.map(::text))
        // No first-party fallback: an empty note type is fail-closed. config_map rejects it and
        // mining admission blocks upstream, so a blank here never injects "Anki Miner".
        values["anki_note_type"] = text(settings.noteType ?: "")
        // Emit a complete map over every logical key so config_map's {**defaults, **value} overlay
        // cannot let an unmapped key inherit a desktop default. Unmatched keys emit "".
        values["anki_fields"] =
            stringMap(AnkiFieldKeys.ALL.associateWith { settings.fieldMap[it] ?: "" })
        // Emit all four modes explicitly, blank unless the user picked one. An absent key would let
        // config_map's overlay reinstate the engine's JP Mining Note field names on a note type that
        // may not have them.
        val marker = settings.cardTypeMarkerField?.takeIf { it.isNotEmpty() }
        val activeCardType = settings.cardType?.takeIf { marker != null }
        values["card_type_marker_fields"] =
            stringMap(
                CardType.entries.associate { type ->
                    type.wireValue to if (type == activeCardType) marker.orEmpty() else ""
                },
            )
        values["card_type"] = text(activeCardType?.wireValue ?: "")
        settings.tags?.let { values["anki_tags"] = text(it) }
        settings.audioPaddingSeconds?.let { values["audio_padding"] = decimal(it) }
        settings.screenshotOffsetSeconds?.let { values["screenshot_offset"] = decimal(it) }
        settings.subtitleOffsetSeconds?.let { values["subtitle_offset"] = decimal(it) }
        settings.audioFormat?.let { values["audio_format"] = text(it.wireValue) }
        settings.audioBitrateKbps?.let { values["audio_bitrate"] = integer(it) }
        settings.stripSubtitleAnnotations?.let { values["strip_subtitle_annotations"] = bool(it) }
        settings.subtitleRegexFilter?.let { values["subtitle_regex_filter"] = text(it) }
        settings.subtitleRegexReplacement?.let { values["subtitle_regex_replacement"] = text(it) }
        settings.useSubtitleRegexFilter?.let { values["use_subtitle_regex_filter"] = bool(it) }
        // Fail closed: a toggle without an installed file would make the engine raise on every run,
        // so the toggle only reaches the engine once the path it needs exists.
        wordList(values, "blacklist_path", "use_blacklist", blacklistPath, settings.useBlacklist)
        wordList(values, "whitelist_path", "use_whitelist", whitelistPath, settings.useWhitelist)
        settings.useKnownWordsDatabase?.let { values["use_known_words_db"] = bool(it) }
        settings.excludeHiraganaOnly?.let { values["exclude_hiragana_only_words"] = bool(it) }
        settings.excludeKatakanaOnly?.let { values["exclude_katakana_only_words"] = bool(it) }
        settings.boldTargetInSentence?.let { values["bold_target_in_sentence"] = bool(it) }
        settings.deduplicateSentences?.let { values["deduplicate_sentences"] = bool(it) }
        settings.useIPlusOneFilter?.let { values["use_i_plus_one_filter"] = bool(it) }
        settings.useSentenceLengthFilter?.let { values["use_sentence_length_filter"] = bool(it) }
        settings.maxSentenceDurationSeconds?.let {
            values["max_sentence_duration_seconds"] = decimal(it)
        }
        settings.maxSentenceCharacters?.let { values["max_sentence_chars"] = integer(it) }
        settings.readingMinimumOccurrence?.let { values["reading_min_occurrence"] = integer(it) }
        settings.maxFrequencyRank?.let { values["max_frequency_rank"] = integer(it) }
        settings.pitchCategoryFormat?.let { values["pitch_category_format"] = text(it.wireValue) }
        settings.maxParallelWorkers?.let { values["max_parallel_workers"] = integer(it) }
        values["excluded_wordsets"] =
            BridgeJsonValue.ArrayValue(
                settings.enabledWordsets
                    .filter { it in availableWordsetIds }
                    .map(::text),
            )

        // Resource-backed chains are Android-owned. Never retain the desktop placeholder slot,
        // and keep Jisho opt-in because lookup terms leave the device.
        val dictionaries =
            buildList {
                resolveResourceChain(settings.dictionarySources, installedDictionaryIds)
                    .forEach { selection ->
                    add(
                        BridgeJsonValue.ObjectValue(
                            mapOf(
                                "kind" to text("indexed"),
                                "dict_id" to text(selection.resourceId),
                                "enabled" to bool(selection.enabled),
                            ),
                        ),
                    )
                    }
                if (settings.jishoEnabled) {
                    // Android's settled network budget is at most 10 requests per 10 seconds.
                    // The desktop 0.5-second floor is intentionally tightened for this port.
                    values["jisho_delay"] = decimal(1.0)
                    add(
                        BridgeJsonValue.ObjectValue(
                            mapOf(
                                "kind" to text("jisho"),
                                "dict_id" to BridgeJsonValue.Null,
                                "enabled" to bool(true),
                            ),
                        ),
                    )
                }
            }
        values["dictionary_chain"] = BridgeJsonValue.ArrayValue(dictionaries)

        val frequencyChain =
            resolveResourceChain(settings.frequencySources, installedFrequencyIds).map { selection ->
                BridgeJsonValue.ObjectValue(
                    mapOf(
                        "source_id" to text(selection.resourceId),
                        "enabled" to bool(selection.enabled),
                    ),
                )
            }
        values["frequency_chain"] = BridgeJsonValue.ArrayValue(frequencyChain)

        // Pitch became a first-hit-wins chain of per-source indexes with the
        // engine re-pin; it carries the same source_id/enabled shape as frequency.
        val pitchChain =
            resolveResourceChain(settings.pitchSources, installedPitchIds).map { selection ->
                BridgeJsonValue.ObjectValue(
                    mapOf(
                        "source_id" to text(selection.resourceId),
                        "enabled" to bool(selection.enabled),
                    ),
                )
            }
        values["pitch_chain"] = BridgeJsonValue.ArrayValue(pitchChain)

        // Only private local packs cross this boundary. Network audio kinds remain mechanically
        // unrepresentable even if a desktop default or stale preference tries to introduce one.
        val expressionAudioChain =
            resolveResourceChain(settings.audioPacks, installedAudioPackIds).map { selection ->
                BridgeJsonValue.ObjectValue(
                    mapOf(
                        "kind" to text("pack"),
                        "pack_id" to text(selection.resourceId),
                        "enabled" to bool(selection.enabled),
                    ),
                )
            }
        values["expression_audio_chain"] = BridgeJsonValue.ArrayValue(expressionAudioChain)
        // Emitted unconditionally so the key set does not depend on user settings; the tuning is
        // emitted only when the feature is on, because the bridge pins fps/height/match_audio and
        // would reject a stray value anyway.
        values["screenshot_animated"] = bool(settings.animatedScreenshotsEnabled)
        if (settings.animatedScreenshotsEnabled) {
            values["screenshot_animated_format"] = text(if (avifNameable) "avif" else "webp")
            settings.animatedScreenshotDurationSeconds?.let {
                values["screenshot_animated_clip_duration"] = decimal(it)
            }
            settings.animatedScreenshotQuality?.let {
                values["screenshot_animated_quality"] = integer(it)
            }
        }
        return MiningConfigSnapshot(
            settings = values,
            androidTtsEnabled = settings.readingTtsEnabled,
        )
    }

    private fun wordList(
        values: MutableMap<String, BridgeJsonValue>,
        pathKey: String,
        toggleKey: String,
        path: String?,
        enabled: Boolean?,
    ) {
        val usable = path != null && enabled == true
        if (usable) values[pathKey] = text(path)
        enabled?.let { values[toggleKey] = bool(usable) }
    }

    /** Preserve saved order/disable choices and append only newly installed resources enabled. */
    internal fun resolveResourceChain(
        persisted: List<ResourceChainSelection>,
        installedIds: List<String>,
    ): List<ResourceChainSelection> {
        val installed = installedIds.toSet()
        val retained = persisted.filter { it.resourceId in installed }
        val retainedIds = retained.mapTo(mutableSetOf(), ResourceChainSelection::resourceId)
        return retained +
            installedIds
                .filterNot(retainedIds::contains)
                .map { ResourceChainSelection(it, enabled = true) }
    }

    private fun text(value: String) = BridgeJsonValue.Text(value)

    private fun bool(value: Boolean) = BridgeJsonValue.Bool(value)

    private fun integer(value: Int) = BridgeJsonValue.Integer(value.toLong())

    private fun decimal(value: Double) = BridgeJsonValue.Decimal(value)

    private fun stringMap(values: Map<String, String>) =
        BridgeJsonValue.ObjectValue(values.mapValues { (_, value) -> text(value) })
}
