package com.ankiminer.android.anki.provider

/**
 * Keyword-driven auto-mapping of a note type's field names to the engine's logical field keys.
 *
 * This mirrors the engine's `auto_map_fields` / `FIELD_KEYWORDS` in
 * `anki_miner/services/note_presets.py`, plus the setup-wizard's word/sentence special-casing in
 * desktop `.../setup_wizard/pages.py`. It is kept intentionally pure (no Android dependencies) so
 * it can be exercised directly. `tools/engine-sync/tests/test_field_keywords_mirror.py` pins
 * [FIELD_KEYWORDS] to the vendored table, so an engine re-pin that adds a key fails until it lands
 * here too.
 *
 * Matching semantics are the desktop ones exactly: a field matches a key when its normalized name
 * (lowercased, with spaces and underscores stripped) is an EXACT element of that key's keyword
 * list — not a substring test. Exact membership is what keeps e.g. "SentenceFurigana" out of the
 * plain `sentence`/`word` keys while still landing on `sentence_furigana`.
 */
internal object AnkiFieldAutoMap {
    /**
     * Ported verbatim from the engine's `FIELD_KEYWORDS`. Keys are engine logical field keys; values
     * are lowercase/normalized patterns a field name must equal (after normalization) to match.
     *
     * The `word` list is never consulted here ([autoMap] pins word to the first field) but is kept
     * whole, Chinese spellings included, so the mirror test compares the tables verbatim.
     *
     * Card-type marker fields (desktop `_CARD_TYPE_MARKER_DEFAULTS`) are deliberately absent — they
     * are never auto-mapped.
     */
    private val FIELD_KEYWORDS: Map<String, List<String>> =
        linkedMapOf(
            "word" to
                listOf("expression", "word", "vocab", "hanzi", "simplified", "汉字", "漢字", "中文", "单词", "词语"),
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
            // The plurals are the names Lapis / Kiku / Senren actually ship, and Senren spells all
            // three of its pitch fields plural. Without them a Senren note type auto-maps to no
            // pitch data at all, so the note type draws no pitch accent.
            "pitch_position" to listOf("pitchposition", "pitchpositions", "pitchaccent", "pitch"),
            "pitch_category" to listOf("pitchcategory", "pitchcategories", "accenttype", "accentcategory"),
            "pitch_graph" to listOf("pitchgraph", "pitchsvg"),
            "pitch_text" to listOf("pitchtext", "pitchaccents"),
            "frequency" to listOf("frequency", "frequencies", "freq", "rank", "frequencyrank"),
            "frequency_sort" to listOf("freqsort", "frequencysort"),
            "source" to listOf("source", "origin", "miscinfo"),
            "sentence_translation" to listOf("sentencetranslation", "translation", "sentencemeaning"),
            "language" to listOf("language", "lang"),
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

    /** Lowercase, no spaces or underscores: desktop `normalized_field_name`. */
    internal fun normalize(fieldName: String): String =
        fieldName.lowercase()
            .replace(" ", "")
            .replace("_", "")
}
