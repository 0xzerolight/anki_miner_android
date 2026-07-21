package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop-parity fixture for [AnkiFieldAutoMap]. The field sets and expected mappings mirror the
 * desktop `_FIELD_KEYWORDS` table plus the setup wizard's word-is-always-first special-casing.
 */
class AnkiFieldAutoMapTest {
    @Test
    fun `word is forced to the first field even when a later field matches a word keyword`() {
        // "Front" does not match any word keyword; the later "Expression" does. Word must still be
        // field[0] (AnkiDroid dedups on the first field), overriding the keyword match.
        val map = AnkiFieldAutoMap.autoMap(listOf("Front", "Expression", "Sentence"))

        assertEquals("Front", map[AnkiFieldKeys.WORD])
        assertNotEquals("Expression", map[AnkiFieldKeys.WORD])
        // Non-word keys are still keyword-matched normally.
        assertEquals("Sentence", map["sentence"])
    }

    @Test
    fun `first field is reserved for word before optional fields are auto mapped`() {
        val map = AnkiFieldAutoMap.autoMap(listOf("Sentence", "Meaning"))

        assertEquals("Sentence", map[AnkiFieldKeys.WORD])
        assertEquals("", map["sentence"])
        assertEquals("Meaning", map["definition"])
        assertEquals(
            map.values.filter(String::isNotEmpty).size,
            map.values.filter(String::isNotEmpty).distinct().size,
        )
    }

    @Test
    fun `a Lapis-shaped field list maps the required keys and never mis-maps sentence furigana onto sentence`() {
        val lapisFields =
            listOf(
                "Expression",
                "Sentence",
                "MainDefinition",
                "Picture",
                "SentenceAudio",
                "ExpressionFurigana",
                "SentenceFurigana",
            )

        val map = AnkiFieldAutoMap.autoMap(lapisFields)

        val expected =
            mapOf(
                "word" to "Expression",
                "sentence" to "Sentence",
                "definition" to "MainDefinition",
                "glossary" to "",
                "picture" to "Picture",
                "audio" to "SentenceAudio",
                "expression_furigana" to "ExpressionFurigana",
                "expression_reading" to "",
                "sentence_furigana" to "SentenceFurigana",
                "sentence_reading" to "",
                "pitch_position" to "",
                "pitch_category" to "",
                "pitch_graph" to "",
                "pitch_text" to "",
                "frequency" to "",
                "frequency_sort" to "",
                "source" to "",
                "expression_audio" to "",
            )
        assertEquals(expected, map)
        // The plain `sentence` key must land on "Sentence", NOT on "SentenceFurigana" — exact
        // normalized membership, not a substring match, is what keeps them apart.
        assertEquals("Sentence", map["sentence"])
        assertNotEquals("SentenceFurigana", map["sentence"])
        assertEquals("SentenceFurigana", map["sentence_furigana"])
        // Keys are exactly the 18 logical keys, in ALL order.
        assertEquals(AnkiFieldKeys.ALL, map.keys.toList())
    }

    @Test
    fun `keys with no matching field map to empty`() {
        // A field list that matches only `word` (first) and `sentence` leaves every other key blank.
        val map = AnkiFieldAutoMap.autoMap(listOf("Front", "Sentence"))

        assertEquals("Front", map[AnkiFieldKeys.WORD])
        assertEquals("Sentence", map["sentence"])
        assertEquals("", map["definition"])
        assertEquals("", map["picture"])
        assertEquals("", map["audio"])
        assertEquals("", map["source"])
        assertEquals("", map["expression_audio"])
    }

    @Test
    fun `an empty field list maps every key to empty`() {
        val map = AnkiFieldAutoMap.autoMap(emptyList())

        assertEquals(AnkiFieldKeys.ALL, map.keys.toList())
        assertTrue("every key must map to \"\" for an empty field list", map.values.all { it == "" })
        assertEquals(AnkiFieldKeys.ALL.associateWith { "" }, map)
    }
}
