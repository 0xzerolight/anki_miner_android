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

    /** All 18 logical keys the engine can populate, in a stable order for UI. */
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
        )

    /** Keys that MUST resolve to an existing field before a note type verifies (mirror of
     * `config_map._REQUIRED_ANKI_FIELD_KEYS` / `anki_note_builder.REQUIRED_FIELD_KEYS`). */
    val REQUIRED: Set<String> =
        setOf(
            "word",
            "sentence",
            "definition",
            "picture",
            "audio",
            "expression_furigana",
            "sentence_furigana",
        )

    val OPTIONAL: List<String> = ALL.filterNot(REQUIRED::contains)
}
