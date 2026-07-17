package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiMinerNoteModelTest {
    @Test
    fun `canonical fields and engine mappings are complete ordered and first field safe`() {
        assertEquals("Anki Miner", AnkiMinerNoteModel.MODEL_NAME)
        assertEquals("Anki Miner", AnkiMinerNoteModel.DEFAULT_DECK_NAME)
        assertEquals("Expression", AnkiMinerNoteModel.FIELD_NAMES.first())
        assertEquals(22, AnkiMinerNoteModel.FIELD_NAMES.size)
        assertEquals(AnkiMinerNoteModel.FIELD_NAMES.size, AnkiMinerNoteModel.FIELD_NAMES.distinct().size)
        assertEquals(
            setOf(
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
            ),
            AnkiMinerNoteModel.ENGINE_FIELD_MAPPING.keys,
        )
        assertTrue(
            AnkiMinerNoteModel.ENGINE_FIELD_MAPPING.values.all(AnkiMinerNoteModel.FIELD_NAMES::contains),
        )
        assertTrue(
            AnkiMinerNoteModel.CARD_TYPE_MARKER_FIELDS.values.all(
                AnkiMinerNoteModel.FIELD_NAMES::contains,
            ),
        )
    }

    @Test
    fun `template references only owned fields and keeps sort and marker fields inert`() {
        val wire = AnkiMinerNoteModel.QUESTION_FORMAT + AnkiMinerNoteModel.ANSWER_FORMAT
        val references =
            TEMPLATE_REFERENCE
                .findAll(wire)
                .map { match ->
                    match.groupValues[1]
                        .removePrefix("#")
                        .removePrefix("^")
                        .removePrefix("/")
                        .removePrefix("furigana:")
                }.toSet()
        assertTrue(references.isNotEmpty())
        assertTrue(
            references.all { field ->
                field == "FrontSide" || field in AnkiMinerNoteModel.FIELD_NAMES
            },
        )
        assertFalse("FrequencySort" in references)
        AnkiMinerNoteModel.CARD_TYPE_MARKER_FIELDS.values.forEach { marker ->
            assertFalse(marker, marker in references)
        }
        assertTrue("Glossary" in references)
        assertTrue("MainDefinition" in references)
        assertTrue(AnkiMinerNoteModel.ANSWER_FORMAT.contains("{{^Glossary}}"))
    }

    @Test
    fun `template is offline robust and defines audited mobile and night classes`() {
        val wire = AnkiMinerNoteModel.QUESTION_FORMAT + AnkiMinerNoteModel.ANSWER_FORMAT
        assertFalse(wire.contains("<script", ignoreCase = true))
        assertFalse(wire.contains("http://", ignoreCase = true))
        assertFalse(wire.contains("https://", ignoreCase = true))
        listOf(
            "am-card",
            "am-expression",
            "am-sentence",
            "am-answer",
            "am-definition",
            "am-picture",
            "am-audio",
            "am-meta",
            "am-meta-row",
            "am-label",
            "am-pitch",
        ).forEach { className ->
            assertTrue(className, AnkiMinerNoteModel.CSS.contains(".$className"))
        }
        assertTrue(AnkiMinerNoteModel.CSS.contains(".nightMode.card"))
        assertTrue(AnkiMinerNoteModel.CSS.contains(".night_mode.card"))
        assertTrue(AnkiMinerNoteModel.CSS.contains("@media (max-width: 480px)"))
    }

    @Test
    fun `exact ownership ignores provider defaults but detects every app owned difference`() {
        val exact = canonicalSnapshot()
        assertTrue(AnkiMinerNoteModel.matchesExactly(exact))
        assertTrue(
            AnkiMinerNoteModel.matchesExactly(
                exact.copy(
                    effectiveDefaultDeckId = 999L,
                    latexPre = "provider pre",
                    latexPost = "provider post",
                    templates =
                        listOf(
                            exact.templates.single().copy(
                                browserQuestionFormat = "provider browser question",
                                browserAnswerFormat = "provider browser answer",
                            ),
                        ),
                ),
            ),
        )
        assertFalse(AnkiMinerNoteModel.matchesExactly(exact.copy(css = exact.css + "\n")))
        assertFalse(
            AnkiMinerNoteModel.matchesExactly(
                exact.copy(fieldNames = exact.fieldNames.reversed()),
            ),
        )
        assertFalse(
            AnkiMinerNoteModel.matchesExactly(
                exact.copy(
                    templates =
                        listOf(
                            exact.templates.single().copy(answerFormat = "{{Expression}}"),
                        ),
                ),
            ),
        )
    }

    private fun canonicalSnapshot(): ModelSnapshot =
        ModelSnapshot(
            id = 42L,
            name = AnkiMinerNoteModel.MODEL_NAME,
            type = AnkiMinerNoteModel.MODEL_TYPE,
            fieldNames = AnkiMinerNoteModel.FIELD_NAMES,
            cardCount = AnkiMinerNoteModel.TEMPLATE_COUNT,
            sortFieldIndex = AnkiMinerNoteModel.SORT_FIELD_INDEX,
            effectiveDefaultDeckId = AnkiMinerNoteModel.DEFAULT_DECK_ID,
            css = AnkiMinerNoteModel.CSS,
            latexPre = null,
            latexPost = null,
            templates =
                listOf(
                    TemplateSnapshot(
                        modelId = 42L,
                        ordinal = AnkiMinerNoteModel.TEMPLATE_ORDINAL,
                        name = AnkiMinerNoteModel.TEMPLATE_NAME,
                        questionFormat = AnkiMinerNoteModel.QUESTION_FORMAT,
                        answerFormat = AnkiMinerNoteModel.ANSWER_FORMAT,
                        browserQuestionFormat = null,
                        browserAnswerFormat = null,
                    ),
                ),
        )

    private companion object {
        val TEMPLATE_REFERENCE = Regex("\\{\\{([^{}]+)}}")
    }
}
