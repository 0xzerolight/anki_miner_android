package com.ankiminer.android.data.settings

import com.ankiminer.android.anki.generated.UnicodeContractV151
import com.ankiminer.android.engine.BridgeJsonValue
import com.ankiminer.android.engine.MiningConfigSnapshot

enum class AudioFormat(val wireValue: String) {
    MP3("mp3"),
    OPUS("opus"),
}

/**
 * Android-owned preferences. Nullable engine fields mean "use the desktop engine default".
 * This distinction is load-bearing: copying all desktop defaults into DataStore would silently
 * pin stale values after an engine sync.
 */
data class AppSettings(
    val firstRunComplete: Boolean = false,
    val deckName: String? = null,
    val noteType: String? = null,
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
    val maxParallelWorkers: Int? = null,
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
            it.noteType?.let { value -> canonicalName("Note type", value) }
            it.tags?.let { value -> validScalarText("Tags", value) }
            nonNegative("Audio padding", it.audioPaddingSeconds)
            nonNegative("Screenshot offset", it.screenshotOffsetSeconds)
            finite("Subtitle offset", it.subtitleOffsetSeconds)
            positive("Audio bitrate", it.audioBitrateKbps)
            nonNegative("Maximum sentence duration", it.maxSentenceDurationSeconds)
            nonNegative("Maximum sentence characters", it.maxSentenceCharacters)
            it.maxParallelWorkers?.let { workers ->
                if (workers !in 1..32) invalid("Parallel workers must be between 1 and 32")
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

    private fun invalid(message: String): Nothing = throw InvalidAppSettingException(message)
}

internal object EngineSettingsSnapshotMapper {
    private val dictionaryId = Regex("(?!.*\\.\\.)[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9_-])?")

    fun map(
        rawSettings: AppSettings,
        installedDictionaryIds: List<String>,
    ): MiningConfigSnapshot {
        val settings = AppSettingsValidator.validate(rawSettings)
        require(installedDictionaryIds.distinct() == installedDictionaryIds)
        require(installedDictionaryIds.all(dictionaryId::matches))
        val values = linkedMapOf<String, BridgeJsonValue>()
        settings.deckName?.let { values["anki_deck_name"] = text(it) }
        settings.noteType?.let { values["anki_note_type"] = text(it) }
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
        settings.maxParallelWorkers?.let { values["max_parallel_workers"] = integer(it) }

        // Resource-backed chains are Android-owned. Never retain the desktop placeholder slot,
        // and keep Jisho opt-in because lookup terms leave the device.
        val dictionaries =
            buildList {
                installedDictionaryIds.forEach { id ->
                    add(
                        BridgeJsonValue.ObjectValue(
                            mapOf(
                                "kind" to text("indexed"),
                                "dict_id" to text(id),
                                "enabled" to bool(true),
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

        // Python independently forces the desktop network-audio chains off. Repeating these
        // supported constraints in every immutable job snapshot makes intent visible on the wire.
        values["expression_audio_chain"] = BridgeJsonValue.ArrayValue(emptyList())
        values["screenshot_animated"] = bool(false)
        return MiningConfigSnapshot(settings = values, androidTtsEnabled = false)
    }

    private fun text(value: String) = BridgeJsonValue.Text(value)

    private fun bool(value: Boolean) = BridgeJsonValue.Bool(value)

    private fun integer(value: Int) = BridgeJsonValue.Integer(value.toLong())

    private fun decimal(value: Double) = BridgeJsonValue.Decimal(value)
}
