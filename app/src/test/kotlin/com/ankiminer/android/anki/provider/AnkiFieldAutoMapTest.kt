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
    fun `the field names Lapis actually ships map the plural pitch category and MiscInfo`() {
        // Verbatim from donkuri/lapis build/anki_fields.yaml, minus the card-type marker fields
        // (never auto-mapped). PitchCategories is plural and MiscInfo matches no `source` word —
        // the two names desktop note_presets calls out as the ones the keyword table has to know.
        val lapisFields =
            listOf(
                "Expression",
                "ExpressionFurigana",
                "ExpressionReading",
                "ExpressionAudio",
                "SelectionText",
                "MainDefinition",
                "DefinitionPicture",
                "Sentence",
                "SentenceFurigana",
                "SentenceAudio",
                "Picture",
                "Glossary",
                "Hint",
                "PitchPosition",
                "PitchCategories",
                "Frequency",
                "FreqSort",
                "MiscInfo",
            )

        val map = AnkiFieldAutoMap.autoMap(lapisFields)

        assertEquals("PitchPosition", map["pitch_position"])
        assertEquals("PitchCategories", map["pitch_category"])
        assertEquals("MiscInfo", map["source"])
        assertEquals("Frequency", map["frequency"])
        assertEquals("FreqSort", map["frequency_sort"])
        assertEquals("ExpressionReading", map["expression_reading"])
        assertEquals("ExpressionAudio", map["expression_audio"])
        // Lapis draws the pitch graph itself from PitchPosition and ships no field for a rendered
        // SVG or overline, so both stay unmapped.
        assertEquals("", map["pitch_graph"])
        assertEquals("", map["pitch_text"])
    }

    @Test
    fun `the field names Senren actually ships map every plural pitch and frequency name`() {
        // Verbatim from BrenoAqua/Senren docs/yomitan.md, minus the card-type marker fields.
        // Senren spells all three pitch fields plural; before desktop 466b2047's spellings landed
        // here, pitchPositions and pitchAccents matched nothing and the note type got no pitch data
        // at all.
        val senrenFields =
            listOf(
                "word",
                "reading",
                "sentence",
                "sentenceFurigana",
                "sentenceTranslation",
                "notes",
                "selectionText",
                "definition",
                "wordAudio",
                "sentenceAudio",
                "picture",
                "glossary",
                "hint",
                "pitchAccents",
                "pitchPositions",
                "pitchCategories",
                "frequencies",
                "freqSort",
                "miscInfo",
                "dictionaryPreference",
            )

        val map = AnkiFieldAutoMap.autoMap(senrenFields)

        val expected =
            mapOf(
                "word" to "word",
                "sentence" to "sentence",
                "definition" to "definition",
                "glossary" to "glossary",
                "picture" to "picture",
                "audio" to "sentenceAudio",
                "expression_furigana" to "",
                "expression_reading" to "reading",
                "sentence_furigana" to "sentenceFurigana",
                "sentence_reading" to "",
                "pitch_position" to "pitchPositions",
                "pitch_category" to "pitchCategories",
                "pitch_graph" to "",
                "pitch_text" to "pitchAccents",
                "frequency" to "frequencies",
                "frequency_sort" to "freqSort",
                "source" to "miscInfo",
                "expression_audio" to "wordAudio",
            )
        assertEquals(expected, map)
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

    @Test
    fun `a hyphenated field name does not match, as on desktop`() {
        val mapping =
            AnkiFieldAutoMap.autoMap(
                listOf("Word", "Sentence-Audio", "Expression-Furigana", "Picture"),
            )

        assertEquals("", mapping.getValue("audio"))
        assertEquals("", mapping.getValue("expression_furigana"))
        assertEquals("Picture", mapping.getValue("picture"))
    }

    @Test
    fun `spaces and underscores still normalise away`() {
        val mapping =
            AnkiFieldAutoMap.autoMap(
                listOf("Word", "Sentence Audio", "expression_furigana"),
            )

        assertEquals("Sentence Audio", mapping.getValue("audio"))
        assertEquals("expression_furigana", mapping.getValue("expression_furigana"))
    }
}
