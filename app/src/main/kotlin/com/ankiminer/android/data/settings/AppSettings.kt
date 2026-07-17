package com.ankiminer.android.data.settings

import com.ankiminer.android.anki.generated.UnicodeContractV151
import com.ankiminer.android.anki.provider.AnkiMinerNoteModel
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.MiningConfigSnapshot

enum class AudioFormat(val wireValue: String) {
    MP3("mp3"),
    OPUS("opus"),
}

enum class PitchCategoryFormat(val wireValue: String) {
    JAPANESE("jp"),
    ROMAJI("romaji"),
}

/** Persisted ordering and enable state for one Android-owned local resource chain. */
data class ResourceChainSelection(
    val resourceId: String,
    val enabled: Boolean = true,
)

/**
 * Android-owned preferences. Nullable processing fields mean "use the current engine default";
 * the Android-owned Anki model contract is always emitted explicitly by the snapshot mapper.
 * This distinction keeps engine defaults current without allowing the desktop Lapis target to
 * leak into Android jobs.
 */
data class AppSettings(
    val firstRunComplete: Boolean = false,
    val deckName: String? = null,
    /** Pre-first-party persisted target retained until the user explicitly accepts migration. */
    val legacyNoteType: String? = null,
    val tags: String? = null,
    val audioPaddingSeconds: Double? = null,
    val screenshotOffsetSeconds: Double? = null,
    val subtitleOffsetSeconds: Double? = null,
    val audioFormat: AudioFormat? = null,
    val audioBitrateKbps: Int? = null,
    val useKnownWordsDatabase: Boolean? = null,
    val excludeHiraganaOnly: Boolean? = null,
    val excludeKatakanaOnly: Boolean? = null,
    val boldTargetInSentence: Boolean? = null,
    val deduplicateSentences: Boolean? = null,
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
    val audioPacks: List<ResourceChainSelection> = emptyList(),
    val excludedWordsets: List<String> = emptyList(),
    val readingTtsEnabled: Boolean = false,
    val jishoEnabled: Boolean = false,
)

class InvalidAppSettingException(message: String) : IllegalArgumentException(message)

/** Parsing rules for editable settings fields, kept separate from persisted-value validation. */
internal object AppSettingsDraftParser {
    fun isOptionalDouble(value: String): Boolean =
        value.isEmpty() || value.toDoubleOrNull()?.isFinite() == true

    fun isOptionalInt(value: String): Boolean = value.isEmpty() || value.toIntOrNull() != null

    fun optionalDouble(value: String): Double? =
        if (value.isEmpty()) {
            null
        } else {
            value.toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: throw InvalidAppSettingException("Complete or clear every numeric value")
        }

    fun optionalInt(value: String): Int? =
        if (value.isEmpty()) {
            null
        } else {
            value.toIntOrNull()
                ?: throw InvalidAppSettingException("Complete or clear every numeric value")
        }
}

object AppSettingsValidator {
    fun validate(settings: AppSettings): AppSettings =
        settings.also {
            it.deckName?.let { value -> canonicalName("Deck name", value) }
            it.legacyNoteType?.let { value -> canonicalName("Legacy note type", value) }
            it.tags?.let { value -> validScalarText("Tags", value) }
            nonNegative("Audio padding", it.audioPaddingSeconds)
            nonNegative("Screenshot offset", it.screenshotOffsetSeconds)
            finite("Subtitle offset", it.subtitleOffsetSeconds)
            positive("Audio bitrate", it.audioBitrateKbps)
            nonNegative("Maximum sentence duration", it.maxSentenceDurationSeconds)
            nonNegative("Maximum sentence characters", it.maxSentenceCharacters)
            positive("Reading minimum occurrence", it.readingMinimumOccurrence)
            nonNegative("Maximum frequency rank", it.maxFrequencyRank)
            it.maxParallelWorkers?.let { workers ->
                if (workers !in 1..32) invalid("Parallel workers must be between 1 and 32")
            }
            resourceChain("Dictionaries", it.dictionarySources)
            resourceChain("Frequency sources", it.frequencySources)
            resourceChain("Audio packs", it.audioPacks)
            if (it.audioPacks.any { selection -> selection.resourceId == "jpod101" }) {
                invalid("Network audio sources are not supported on Android")
            }
            if (
                it.excludedWordsets.size > MAX_WORDSET_SELECTIONS ||
                    it.excludedWordsets.distinct().size != it.excludedWordsets.size ||
                    it.excludedWordsets.any { id -> !RESOURCE_ID.matches(id) }
            ) {
                invalid("Selected bundled wordsets are invalid")
            }
        }

    fun canonicalName(label: String, value: String): String {
        validScalarText(label, value)
        if (
            value.isEmpty() ||
                !UnicodeContractV151.isNfc(value) ||
                UnicodeContractV151.hasLeadingOrTrailingPythonWhitespace(value)
        ) {
            invalid("$label must be non-empty, NFC text without surrounding whitespace")
        }
        return value
    }

    private fun validScalarText(label: String, value: String) {
        if (UnicodeContractV151.scalarCount(value) == null || containsCategoryC(value)) {
            invalid("$label contains unsupported control or malformed Unicode characters")
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
        if (value != null && !value.isFinite()) invalid("$label must be finite")
    }

    private fun nonNegative(label: String, value: Double?) {
        finite(label, value)
        if (value != null && value < 0.0) invalid("$label must not be negative")
    }

    private fun nonNegative(label: String, value: Int?) {
        if (value != null && value < 0) invalid("$label must not be negative")
    }

    private fun positive(label: String, value: Int?) {
        if (value != null && value <= 0) invalid("$label must be positive")
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
            invalid("$label contain invalid or duplicate resource IDs")
        }
    }

    private fun invalid(message: String): Nothing = throw InvalidAppSettingException(message)

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
        installedAudioPackIds: List<String> = emptyList(),
        availableWordsetIds: List<String> = emptyList(),
    ): MiningConfigSnapshot {
        val settings = AppSettingsValidator.validate(rawSettings)
        require(installedDictionaryIds.distinct() == installedDictionaryIds)
        require(installedDictionaryIds.all(dictionaryId::matches))
        require(installedFrequencyIds.distinct() == installedFrequencyIds)
        require(installedFrequencyIds.all(dictionaryId::matches))
        require(installedAudioPackIds.distinct() == installedAudioPackIds)
        require(installedAudioPackIds.all(dictionaryId::matches))
        require(installedAudioPackIds.none { it == "jpod101" })
        require(availableWordsetIds.distinct() == availableWordsetIds)
        require(availableWordsetIds.all(dictionaryId::matches))
        val values = linkedMapOf<String, BridgeJsonValue>()
        // Android owns a complete first-party target. Always freeze its identity and mappings in
        // the immutable job snapshot instead of inheriting the desktop Lapis default.
        values["anki_deck_name"] =
            text(settings.deckName ?: AnkiMinerNoteModel.DEFAULT_DECK_NAME)
        values["anki_note_type"] = text(AnkiMinerNoteModel.MODEL_NAME)
        values["anki_fields"] = stringMap(AnkiMinerNoteModel.ENGINE_FIELD_MAPPING)
        values["card_type_marker_fields"] =
            stringMap(AnkiMinerNoteModel.CARD_TYPE_MARKER_FIELDS)
        // Marker fields are reserved for future first-party card modes. Activating one now would
        // falsely claim JP Mining Note rendering semantics which this template does not provide.
        values["card_type"] = text("")
        settings.tags?.let { values["anki_tags"] = text(it) }
        settings.audioPaddingSeconds?.let { values["audio_padding"] = decimal(it) }
        settings.screenshotOffsetSeconds?.let { values["screenshot_offset"] = decimal(it) }
        settings.subtitleOffsetSeconds?.let { values["subtitle_offset"] = decimal(it) }
        settings.audioFormat?.let { values["audio_format"] = text(it.wireValue) }
        settings.audioBitrateKbps?.let { values["audio_bitrate"] = integer(it) }
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
                settings.excludedWordsets
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
        values["screenshot_animated"] = bool(false)
        return MiningConfigSnapshot(
            settings = values,
            androidTtsEnabled = settings.readingTtsEnabled,
        )
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
