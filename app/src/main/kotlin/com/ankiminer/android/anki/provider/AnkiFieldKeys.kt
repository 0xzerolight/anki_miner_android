package com.ankiminer.android.anki.provider

/**
 * The engine field-map logical keys, mirrored on the Kotlin side.
 *
 * The right-hand side of `config.anki_fields` is a user note-type field name; the left-hand side
 * is one of these fixed logical keys. This object is the single Kotlin source of truth for that key
 * set and is pinned against `BridgeJsonCodec.ANKI_FIELDS` / `config_map` by test.
 */
internal object AnkiFieldKeys {
    /** The word/expression key. By contract it maps to the note type's FIRST field (dedup key). */
    const val WORD = "word"

    /** Where a stock two-field note type's second field goes on a fresh pick. */
    const val DEFINITION = "definition"

    /** All 20 logical keys the engine can populate, in a stable order for UI. */
    val ALL: List<String> =
        listOf(
            "word",
            "sentence",
            "definition",
            "glossary",
            "picture",
            "audio",
            "expression_furigana",
            "expression_reading",
            "sentence_furigana",
            "sentence_reading",
            "pitch_position",
            "pitch_category",
            "pitch_graph",
            "pitch_text",
            "frequency",
            "frequency_sort",
            "source",
            "expression_audio",
            "sentence_translation",
            "language",
        )

    /**
     * The user must-map set: the only logical keys a user MUST resolve to an existing field before
     * a note type verifies. It is deliberately just [WORD] (word/expression) — the AnkiDroid dedup
     * key, which by contract must be the note type's FIRST field. Every other mapped field is
     * optional: the user may leave it unmapped and still reach mining-ready.
     *
     * This is DELIBERATELY narrower than the engine's `anki_note_builder.REQUIRED_FIELD_KEYS` /
     * `config_map._REQUIRED_ANKI_FIELD_KEYS`. Those govern which keys must be PRESENT in the emitted
     * `anki_fields` dict — a separate concern already satisfied unconditionally, because the settings
     * mapper emits every [ALL] key (empty when unmapped). Do NOT re-widen this to mirror the Python
     * required-key sets: doing so would wrongly gate optional fields (reading, expression_furigana,
     * sentence, definition, picture, audio, sentence_furigana) as mandatory in the UI and verify gate.
     */
    val REQUIRED: Set<String> = setOf(WORD)

    val OPTIONAL: List<String> = ALL.filterNot(REQUIRED::contains)

    /**
     * Keys only a language with the named profile capability fills (desktop
     * `note_presets.CAPABILITY_FIELD_KEYS`). Settings hides their rows everywhere else, so a fill
     * leaves them unmapped there: a hidden mapping would outlive a switch to a note type without
     * that field and fail the pre-run check on a row nobody can see.
     */
    val CAPABILITY_GATED: Map<String, String> =
        mapOf(
            "expression_furigana" to "furigana",
            "sentence_furigana" to "furigana",
            "pitch_position" to "pitch",
            "pitch_category" to "pitch",
            "pitch_graph" to "pitch",
            "pitch_text" to "pitch",
        )

    /** The gated keys [capabilities] cannot fill; null is Japanese, which fills every key. */
    fun unfillable(capabilities: Set<String>?): Set<String> =
        if (capabilities == null) {
            emptySet()
        } else {
            CAPABILITY_GATED.filterValues { it !in capabilities }.keys
        }
}
