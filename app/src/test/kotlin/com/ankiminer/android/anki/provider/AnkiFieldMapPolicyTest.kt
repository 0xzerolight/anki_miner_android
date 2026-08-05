package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiFieldMapPolicyTest {
    @Test
    fun `same note type returns the exact existing map`() {
        val existing =
            linkedMapOf(
                "word" to "Expression",
                "sentence" to "Custom Sentence",
                "source" to "Source",
            )

        val result =
            AnkiFieldMapPolicy.merge(
                currentNoteType = "Lapis",
                selectedNoteType = "Lapis",
                fieldNames = listOf("Expression", "Sentence", "Custom Sentence", "Source"),
                currentFieldMap = existing,
            )

        assertSame(existing, result.fieldMap)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `changed note type retains compatible manual mappings`() {
        val result =
            AnkiFieldMapPolicy.merge(
                currentNoteType = "Old",
                selectedNoteType = "New",
                fieldNames = listOf("Expression", "Sentence", "Custom Sentence", "Meaning"),
                currentFieldMap =
                    linkedMapOf(
                        "word" to "Expression",
                        "sentence" to "Custom Sentence",
                        "definition" to "Meaning",
                    ),
            )

        assertEquals("Expression", result.fieldMap["word"])
        assertEquals("Custom Sentence", result.fieldMap["sentence"])
        assertEquals("Meaning", result.fieldMap["definition"])
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `removed mappings are auto filled when a collision free match exists`() {
        val result =
            AnkiFieldMapPolicy.merge(
                currentNoteType = "Old",
                selectedNoteType = "New",
                fieldNames = listOf("Expression", "Sentence", "Meaning"),
                currentFieldMap =
                    linkedMapOf(
                        "word" to "Old Front",
                        "sentence" to "Old Sentence",
                        "definition" to "Meaning",
                        "source" to "Removed Source",
                    ),
            )

        assertEquals("Expression", result.fieldMap["word"])
        assertEquals("Sentence", result.fieldMap["sentence"])
        assertEquals("Meaning", result.fieldMap["definition"])
        assertEquals("", result.fieldMap["source"])
        assertEquals(
            listOf("word", "sentence", "source"),
            result.changes.map(AnkiFieldMappingChange::logicalKey),
        )
    }

    @Test
    fun `preserved fields win and auto fill never creates a collision`() {
        val result =
            AnkiFieldMapPolicy.merge(
                currentNoteType = "Old",
                selectedNoteType = "New",
                fieldNames = listOf("Expression", "Sentence", "Meaning"),
                currentFieldMap =
                    linkedMapOf(
                        "word" to "Expression",
                        "sentence" to "Meaning",
                        "definition" to "Removed Definition",
                    ),
            )

        assertEquals("Meaning", result.fieldMap["sentence"])
        assertEquals("", result.fieldMap["definition"])
        val destinations = result.fieldMap.values.filter(String::isNotEmpty)
        assertEquals(destinations.size, destinations.distinct().size)
    }

    @Test
    fun `manual assignment rejects a destination owned by another logical field`() {
        val existing = mapOf("word" to "Expression", "sentence" to "Sentence")

        val assigned =
            AnkiFieldMapPolicy.assign(
                currentFieldMap = existing,
                logicalKey = "definition",
                destination = "Sentence",
                fieldNames = listOf("Expression", "Sentence", "Meaning"),
            )

        assertNull(assigned)
        assertEquals(
            AnkiFieldMapConflict("Sentence", listOf("sentence", "definition")),
            AnkiFieldMapPolicy.conflictAfterAssignment(existing, "definition", "Sentence"),
        )
    }

    @Test
    fun `manual assignment rejects the active card type marker destination`() {
        val existing = mapOf("word" to "Word")

        val assigned =
            AnkiFieldMapPolicy.assign(
                currentFieldMap = existing,
                logicalKey = "definition",
                destination = "IsClickCard",
                fieldNames = listOf("Word", "IsClickCard", "Meaning"),
                reservedDestinations = setOf("IsClickCard"),
            )

        assertNull(assigned)
        assertTrue(
            !AnkiFieldMapPolicy.isDestinationAvailable(
                currentFieldMap = existing,
                logicalKey = "definition",
                destination = "IsClickCard",
                fieldNames = listOf("Word", "IsClickCard", "Meaning"),
                reservedDestinations = setOf("IsClickCard"),
            ),
        )
    }

    @Test
    fun `note type merge keeps an active marker out of retained and automatic mappings`() {
        val result =
            AnkiFieldMapPolicy.merge(
                currentNoteType = "Old",
                selectedNoteType = "New",
                fieldNames = listOf("Word", "IsClickCard", "Meaning"),
                currentFieldMap =
                    mapOf(
                        "word" to "Old Word",
                        "definition" to "IsClickCard",
                    ),
                reservedDestinations = setOf("IsClickCard"),
            )

        assertEquals("Word", result.fieldMap["word"])
        assertTrue(result.fieldMap.values.none { it == "IsClickCard" })
    }

    @Test
    fun `blank word selection cannot remove the first field owner`() {
        assertNull(
            AnkiFieldMapPolicy.assign(
                currentFieldMap = mapOf("word" to "Expression"),
                logicalKey = AnkiFieldKeys.WORD,
                destination = "",
                fieldNames = listOf("Expression", "Meaning"),
            ),
        )
    }

    @Test
    fun `word destination is reserved even if a malformed map left word blank`() {
        val assigned =
            AnkiFieldMapPolicy.assign(
                currentFieldMap = mapOf("word" to ""),
                logicalKey = "sentence",
                destination = "Meaning",
                fieldNames = listOf("Expression", "Meaning"),
            )

        assertEquals("Expression", assigned?.get(AnkiFieldKeys.WORD))
        assertEquals("Meaning", assigned?.get("sentence"))
    }

    @Test
    fun `word field destinations contain only the required first field and never None`() {
        assertEquals(
            listOf("Expression"),
            AnkiFieldMapPolicy.destinationOptions(
                AnkiFieldKeys.WORD,
                listOf("Expression", "Meaning"),
            ),
        )
        assertEquals(
            listOf("", "Expression", "Meaning"),
            AnkiFieldMapPolicy.destinationOptions(
                "sentence",
                listOf("Expression", "Meaning"),
            ),
        )
    }
}
