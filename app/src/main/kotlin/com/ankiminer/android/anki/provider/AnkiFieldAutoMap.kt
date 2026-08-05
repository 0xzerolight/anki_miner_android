package com.ankiminer.android.anki.provider

/**
 * Keyword-driven auto-mapping of a note type's field names to the engine's logical field keys.
 *
 * This mirrors the desktop `auto_map_fields` /
 * `_FIELD_KEYWORDS` table in
 * `anki_miner/gui/widgets/panels/anki_settings_panel.py`, plus the setup-wizard's
 * word/sentence special-casing in `.../setup_wizard/pages.py`. It is kept intentionally pure (no
 * Android dependencies) so it can be exercised directly. The two repositories are separate, so
 * nothing mechanically pins [FIELD_KEYWORDS] to desktop `_FIELD_KEYWORDS` — keep them in step by
 * hand when either moves.
 *
 * Matching semantics are the desktop ones exactly: a field matches a key when its normalized name
 * (lowercased, with spaces and underscores stripped) is an EXACT element of that key's keyword
 * list — not a substring test. Exact membership is what keeps e.g. "SentenceFurigana" out of the
 * plain `sentence`/`word` keys while still landing on `sentence_furigana`.
 */
internal object AnkiFieldAutoMap {
    /**
     * Ported verbatim from desktop `_FIELD_KEYWORDS`. Keys are engine logical field keys; values
     * are lowercase/normalized patterns a field name must equal (after normalization) to match.
     *
     * Card-type marker fields (desktop `_CARD_TYPE_MARKER_DEFAULTS`) are deliberately absent — they
     * are never auto-mapped.
     */
    private val FIELD_KEYWORDS: Map<String, List<String>> =
        linkedMapOf(
            "word" to listOf("expression", "word", "vocab"),
            "sentence" to listOf("sentence", "context", "example"),
            "definition" to listOf("definition", "meaning", "maindefinition"),
            "glossary" to listOf("glossary", "definitions", "dictionary"),
            "picture" to listOf("picture", "image", "screenshot", "photo"),
            "audio" to listOf("audio", "sound", "sentenceaudio"),
            "expression_audio" to listOf("expressionaudio", "wordaudio"),
            "expression_furigana" to listOf("expressionfurigana", "wordfurigana"),
            "expression_reading" to listOf("expressionreading", "wordreading", "reading"),
            "sentence_furigana" to listOf("sentencefurigana", "contextfurigana"),
            "sentence_reading" to listOf("sentencereading", "contextreading"),
            "pitch_position" to listOf("pitchposition", "pitchaccent", "pitch"),
            "pitch_category" to listOf("pitchcategory", "accenttype", "accentcategory"),
            "pitch_graph" to listOf("pitchgraph", "pitchsvg"),
            "pitch_text" to listOf("pitchtext"),
            "frequency" to listOf("frequency", "freq", "rank", "frequencyrank"),
            "frequency_sort" to listOf("freqsort", "frequencysort"),
            "source" to listOf("source", "origin"),
        )

    /**
     * Map every logical key in [AnkiFieldKeys.ALL] to a field drawn from [fieldNames], or `""` when
     * nothing matches.
     *
     * - [AnkiFieldKeys.WORD] is forced to the FIRST field (or `""` when [fieldNames] is empty). This
     *   is the AnkiDroid dedup contract: dedup keys on field[0], so the word key must be field[0]
     *   regardless of keyword matches, overriding any keyword-based pick.
     * - Every other key is keyword-matched against [FIELD_KEYWORDS]: the first still-unowned field
     *   (in [fieldNames] order) whose normalized name exactly matches one of the key's keywords
     *   wins. This keeps all non-empty destinations unique and reserves field[0] for word.
     */
    fun autoMap(fieldNames: List<String>): Map<String, String> {
        val mapping = LinkedHashMap<String, String>(AnkiFieldKeys.ALL.size)
        val usedDestinations = mutableSetOf<String>()
        for (key in AnkiFieldKeys.ALL) {
            mapping[key] =
                if (key == AnkiFieldKeys.WORD) {
                    fieldNames.firstOrNull().orEmpty().also { destination ->
                        if (destination.isNotEmpty()) usedDestinations += destination
                    }
                } else {
                    firstAvailableMatch(key, fieldNames, usedDestinations).also { destination ->
                        if (destination.isNotEmpty()) usedDestinations += destination
                    }
                }
        }
        return mapping
    }

    internal fun firstAvailableMatch(
        key: String,
        fieldNames: List<String>,
        usedDestinations: Set<String>,
    ): String {
        val keywords = FIELD_KEYWORDS[key] ?: return ""
        return fieldNames
            .firstOrNull { fieldName ->
                fieldName !in usedDestinations && normalize(fieldName) in keywords
            }.orEmpty()
    }

    private fun normalize(fieldName: String): String =
        fieldName.lowercase()
            .replace(" ", "")
            .replace("_", "")
}
