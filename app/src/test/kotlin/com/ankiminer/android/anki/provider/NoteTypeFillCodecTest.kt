package com.ankiminer.android.anki.provider

import com.ankiminer.android.data.settings.CardType
import com.ankiminer.android.data.settings.PitchCategoryFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteTypeFillCodecTest {
    @Test
    fun `the request names every field and the mining language`() {
        assertEquals(
            """{"schemaVersion":1,"type":"anki.notetype.fill","payload":{"fieldNames":["Front","Rückseite"],"language":"de"}}""",
            NoteTypeFillCodec.encodeRequest(listOf("Front", "Rückseite"), "de"),
        )
    }

    @Test
    fun `a recognised note type decodes into its preset and whole map`() {
        val fill = NoteTypeFillCodec.decodeResult(SENREN_RESULT, SENREN_FIELDS)

        val preset = checkNotNull(fill.preset)
        assertEquals("senren", preset.id)
        assertEquals("Senren", preset.name)
        assertEquals(PitchCategoryFormat.ROMAJI, preset.pitchCategoryFormat)
        assertEquals(setOf(CardType.SENTENCE, CardType.AUDIO), preset.supportedCardTypes)
        assertEquals("sentenceCard", preset.cardTypeMarkerFields[CardType.SENTENCE])
        assertEquals("", preset.cardTypeMarkerFields[CardType.CLICK])
        assertEquals(false, preset.boldTargetInSentence)
        assertEquals("pitchAccents", fill.fields["pitch_text"])
        assertEquals("", fill.fields["sentence_reading"])
        assertEquals(AnkiFieldKeys.ALL.toSet(), fill.fields.keys)
        assertEquals(emptyMap<String, String>(), fill.extraFields)
    }

    @Test
    fun `an unrecognised note type decodes with no preset`() {
        val fill = NoteTypeFillCodec.decodeResult(KEYWORD_RESULT, listOf("Front", "POS"))

        assertNull(fill.preset)
        assertEquals(mapOf("pos" to "POS"), fill.extraFields)
    }

    @Test
    fun `a bridge error or a malformed answer fails`() {
        val malformed =
            listOf(
                """{"schemaVersion":1,"type":"bridge.error","payload":{"code":"invalid_note_type_fill_request","message":"x","requestType":"anki.notetype.fill"}}""",
                // A field the note type does not have.
                KEYWORD_RESULT.replace("\"pos\":\"POS\"", "\"pos\":\"Gender\""),
                // The disabled card type is always supported.
                SENREN_RESULT.replace("[\"\",\"sentence\",\"audio\"]", "[\"sentence\",\"audio\"]"),
                SENREN_RESULT.replace("\"romaji\"", "\"kana\""),
                // A logical key the engine does not have.
                KEYWORD_RESULT.replace("\"language\":\"\"", "\"language\":\"\",\"lemma\":\"\""),
                KEYWORD_RESULT.replace("\"extraFields\"", "\"extraFields\":{},\"other\""),
                KEYWORD_RESULT.replace("\"preset\":null,", ""),
                "$KEYWORD_RESULT{}",
            )
        malformed.forEach { raw ->
            assertThrows(raw, NoteTypeFillException::class.java) {
                NoteTypeFillCodec.decodeResult(raw, listOf("Front", "POS") + SENREN_FIELDS)
            }
        }
    }

    private companion object {
        val SENREN_FIELDS =
            listOf(
                "word", "reading", "sentence", "sentenceFurigana", "sentenceTranslation", "sentenceCard",
                "audioCard", "notes", "selectionText", "definition", "wordAudio", "sentenceAudio", "picture",
                "glossary", "hint", "pitchAccents", "pitchPositions", "pitchCategories", "frequencies",
                "freqSort", "miscInfo", "dictionaryPreference",
            )

        /** What the bridge answers for Senren's field list (runtime lane, ja). */
        const val SENREN_RESULT =
            """{"schemaVersion":1,"type":"anki.notetype.fill.result","payload":{"preset":{"id":"senren","name":"Senren","pitchCategoryFormat":"romaji","cardTypeMarkerFields":{"word_and_sentence":"","click":"","sentence":"sentenceCard","audio":"audioCard"},"supportedCardTypes":["","sentence","audio"],"boldTargetInSentence":false},"fields":{"word":"word","sentence":"sentence","definition":"definition","glossary":"glossary","picture":"picture","audio":"sentenceAudio","expression_audio":"wordAudio","expression_furigana":"","expression_reading":"reading","sentence_furigana":"sentenceFurigana","sentence_reading":"","pitch_position":"pitchPositions","pitch_category":"pitchCategories","pitch_graph":"","pitch_text":"pitchAccents","frequency":"frequencies","frequency_sort":"freqSort","source":"miscInfo","sentence_translation":"sentenceTranslation","language":""},"extraFields":{}}}"""

        const val KEYWORD_RESULT =
            """{"schemaVersion":1,"type":"anki.notetype.fill.result","payload":{"preset":null,"fields":{"word":"","sentence":"","definition":"","glossary":"","picture":"","audio":"","expression_audio":"","expression_furigana":"","expression_reading":"","sentence_furigana":"","sentence_reading":"","pitch_position":"","pitch_category":"","pitch_graph":"","pitch_text":"","frequency":"","frequency_sort":"","source":"","sentence_translation":"","language":""},"extraFields":{"pos":"POS"}}}"""
    }
}
