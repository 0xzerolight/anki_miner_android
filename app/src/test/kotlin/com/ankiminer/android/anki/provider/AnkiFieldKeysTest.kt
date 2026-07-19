package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [AnkiFieldKeys] against the engine field-map contract the bridge enforces.
 *
 * [BRIDGE_ANKI_FIELDS] is transcribed from `BridgeJsonCodec.ANKI_FIELDS` (a `private` constant, so
 * it cannot be referenced directly from a test) and the Python `config_map` field-key set. If either
 * side changes, this test must fail — keep the two literals in step.
 */
class AnkiFieldKeysTest {
    /** The exact 18 logical keys `BridgeJsonCodec.validateSettings`/`ANKI_FIELDS` accepts. */
    private val bridgeAnkiFields =
        setOf(
            "word", "sentence", "definition", "glossary", "picture", "audio", "expression_furigana",
            "expression_reading", "sentence_furigana", "sentence_reading", "pitch_position",
            "pitch_category", "pitch_graph", "pitch_text", "frequency", "frequency_sort", "source",
            "expression_audio",
        )

    @Test
    fun `ALL is exactly the 18 duplicate-free keys the bridge validates`() {
        assertEquals(18, AnkiFieldKeys.ALL.size)
        assertEquals("ALL must contain no duplicates", AnkiFieldKeys.ALL.size, AnkiFieldKeys.ALL.toSet().size)
        assertEquals(bridgeAnkiFields, AnkiFieldKeys.ALL.toSet())
    }

    @Test
    fun `REQUIRED is the exact seven keys and a subset of ALL`() {
        val expected =
            setOf(
                "word",
                "sentence",
                "definition",
                "picture",
                "audio",
                "expression_furigana",
                "sentence_furigana",
            )
        assertEquals(expected, AnkiFieldKeys.REQUIRED)
        assertEquals(7, AnkiFieldKeys.REQUIRED.size)
        assertTrue(
            "REQUIRED must be a subset of ALL",
            AnkiFieldKeys.ALL.toSet().containsAll(AnkiFieldKeys.REQUIRED),
        )
    }

    @Test
    fun `OPTIONAL is ALL minus REQUIRED and partitions ALL with REQUIRED`() {
        assertEquals(AnkiFieldKeys.ALL.toSet() - AnkiFieldKeys.REQUIRED, AnkiFieldKeys.OPTIONAL.toSet())
        assertEquals(11, AnkiFieldKeys.OPTIONAL.size)
        assertTrue(
            "OPTIONAL must be disjoint from REQUIRED",
            AnkiFieldKeys.OPTIONAL.none { it in AnkiFieldKeys.REQUIRED },
        )
        assertEquals(
            "REQUIRED and OPTIONAL together must equal ALL",
            AnkiFieldKeys.ALL.toSet(),
            AnkiFieldKeys.OPTIONAL.toSet() + AnkiFieldKeys.REQUIRED,
        )
    }

    @Test
    fun `WORD is the first ALL key and a required key`() {
        assertEquals("word", AnkiFieldKeys.WORD)
        assertEquals(AnkiFieldKeys.WORD, AnkiFieldKeys.ALL.first())
        assertTrue("WORD must be a required key", AnkiFieldKeys.WORD in AnkiFieldKeys.REQUIRED)
    }
}
