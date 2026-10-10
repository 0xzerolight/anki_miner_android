package com.ankiminer.android.anki.provider

import com.ankiminer.android.engine.LanguageExtraCardField
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
    fun `remap fills the keys a stale map left empty without touching a manual choice`() {
        // The map a Senren user saved before the plural spellings were known: pitch went nowhere,
        // and they hand-picked "notes" for source rather than the miscInfo the table now matches.
        val result =
            AnkiFieldMapPolicy.remap(
                fieldNames = listOf("word", "sentence", "notes", "pitchPositions", "pitchAccents"),
                currentFieldMap =
                    linkedMapOf(
                        "word" to "word",
                        "sentence" to "sentence",
                        "source" to "notes",
                    ),
            )

        assertEquals("pitchPositions", result.fieldMap["pitch_position"])
        assertEquals("pitchAccents", result.fieldMap["pitch_text"])
        // "notes" matches no keyword, so the manual choice survives.
        assertEquals("notes", result.fieldMap["source"])
        assertEquals("word", result.fieldMap["word"])
        assertEquals(
            listOf("pitch_position", "pitch_text"),
            result.changes
                .map(AnkiFieldMappingChange::logicalKey)
                .filter { it.startsWith("pitch") },
        )
    }

    @Test
    fun `remap lets a keyword match take a destination back off a manual owner`() {
        val result =
            AnkiFieldMapPolicy.remap(
                fieldNames = listOf("Expression", "PitchCategories", "Sentence"),
                currentFieldMap =
                    linkedMapOf(
                        "word" to "Expression",
                        // Parked on the category field before the table knew the plural name.
                        "source" to "PitchCategories",
                    ),
            )

        assertEquals("PitchCategories", result.fieldMap["pitch_category"])
        assertEquals("", result.fieldMap["source"])
        val destinations = result.fieldMap.values.filter(String::isNotEmpty)
        assertEquals(destinations.size, destinations.distinct().size)
    }

    @Test
    fun `remap never steals the card type marker field`() {
        val result =
            AnkiFieldMapPolicy.remap(
                fieldNames = listOf("Expression", "Sentence", "Frequency"),
                currentFieldMap = linkedMapOf("word" to "Expression"),
                reservedDestinations = setOf("Frequency"),
            )

        assertEquals("", result.fieldMap["frequency"])
        assertEquals("Sentence", result.fieldMap["sentence"])
    }

    @Test
    fun `remap of an empty note type is a no-op`() {
        val existing = linkedMapOf("word" to "Expression")

        val result = AnkiFieldMapPolicy.remap(fieldNames = emptyList(), currentFieldMap = existing)

        assertSame(existing, result.fieldMap)
        assertTrue(result.changes.isEmpty())
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

    private val transliteration =
        LanguageExtraCardField(
            key = "transliteration",
            capability = "hebrew_transliteration",
            placeholder = "Transliteration",
            rawHtml = false,
        )
    private val root =
        LanguageExtraCardField(
            key = "root",
            capability = "word_root",
            placeholder = "Root",
            rawHtml = false,
        )

    @Test
    fun `a profile field maps to the note field spelled like its placeholder`() {
        val mapped =
            AnkiFieldMapPolicy.autoMapProfileFields(
                fieldNames = listOf("Expression", "transliteration", "Word Root"),
                specs = listOf(transliteration, root),
                claimed = setOf("Expression"),
            )

        // Desktop auto_map_profile_fields: the keyword table's normalisation, and a spec with no
        // match is absent rather than "".
        assertEquals(mapOf("transliteration" to "transliteration"), mapped)
    }

    @Test
    fun `a profile field also maps a note field spelled like one of its aliases`() {
        val pos = LanguageExtraCardField("pos", "pos_tag", "PartOfSpeech", rawHtml = false, aliases = listOf("POS"))

        assertEquals(
            mapOf("pos" to "POS"),
            AnkiFieldMapPolicy.autoMapProfileFields(listOf("Expression", "POS"), listOf(pos), emptySet()),
        )
    }

    @Test
    fun `the first field in field order wins between a placeholder and an alias`() {
        val pos = LanguageExtraCardField("pos", "pos_tag", "PartOfSpeech", rawHtml = false, aliases = listOf("POS"))

        assertEquals(
            mapOf("pos" to "pos"),
            AnkiFieldMapPolicy.autoMapProfileFields(listOf("Expression", "pos", "Part Of Speech"), listOf(pos), emptySet()),
        )
        assertEquals(
            mapOf("pos" to "Part_Of_Speech"),
            AnkiFieldMapPolicy.autoMapProfileFields(listOf("Expression", "Part_Of_Speech", "POS"), listOf(pos), emptySet()),
        )
    }

    @Test
    fun `a profile field never takes a field another key already holds`() {
        val mapped =
            AnkiFieldMapPolicy.autoMapProfileFields(
                fieldNames = listOf("Expression", "Reading"),
                specs = listOf(LanguageExtraCardField("thai_reading", "romanization", "Reading", false)),
                claimed = setOf("Expression", "Reading"),
            )

        assertTrue(mapped.isEmpty())
    }

    @Test
    fun `a new note type maps the language's own fields after the keyword pass`() {
        val result =
            AnkiFieldMapPolicy.merge(
                currentNoteType = null,
                selectedNoteType = "Hebrew",
                fieldNames = listOf("Expression", "Sentence", "Transliteration", "Root"),
                currentFieldMap = emptyMap(),
                extraFields = listOf(transliteration, root),
            )

        assertEquals("Expression", result.fieldMap["word"])
        assertEquals("Sentence", result.fieldMap["sentence"])
        assertEquals("Transliteration", result.fieldMap["transliteration"])
        assertEquals("Root", result.fieldMap["root"])
    }

    @Test
    fun `a note type change keeps a valid manual choice for a language field`() {
        val result =
            AnkiFieldMapPolicy.merge(
                currentNoteType = "Old",
                selectedNoteType = "New",
                fieldNames = listOf("Expression", "Latin", "Transliteration"),
                currentFieldMap = mapOf("word" to "Expression", "transliteration" to "Latin"),
                extraFields = listOf(transliteration),
            )

        assertEquals("Latin", result.fieldMap["transliteration"])
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun `remap overwrites a language field its placeholder matches`() {
        val result =
            AnkiFieldMapPolicy.remap(
                fieldNames = listOf("Expression", "Latin", "Transliteration"),
                currentFieldMap = mapOf("word" to "Expression", "transliteration" to "Latin"),
                extraFields = listOf(transliteration),
            )

        assertEquals("Transliteration", result.fieldMap["transliteration"])
        assertEquals(
            listOf(AnkiFieldMappingChange("transliteration", "Latin", "Transliteration")),
            result.changes.filter { it.logicalKey == "transliteration" },
        )
    }

    @Test
    fun `japanese merge is unchanged by an empty extra field list`() {
        val fields = listOf("Expression", "Sentence", "Reading")
        assertEquals(
            AnkiFieldMapPolicy.merge(null, "Lapis", fields, emptyMap()),
            AnkiFieldMapPolicy.merge(null, "Lapis", fields, emptyMap(), extraFields = emptyList()),
        )
    }

    @Test
    fun `a language field may be assigned only when the language declares it`() {
        val fields = listOf("Expression", "Transliteration")
        val base = mapOf("word" to "Expression")

        assertNull(AnkiFieldMapPolicy.assign(base, "transliteration", "Transliteration", fields))
        assertEquals(
            "Transliteration",
            AnkiFieldMapPolicy
                .assign(
                    base,
                    "transliteration",
                    "Transliteration",
                    fields,
                    extraKeys = setOf("transliteration"),
                )?.get("transliteration"),
        )
    }

    @Test
    fun `a fresh pick of a stock two field note type sends the definition to its second field`() {
        val result =
            AnkiFieldMapPolicy.merge(
                currentNoteType = null,
                selectedNoteType = "Basic",
                fieldNames = listOf("Front", "Back"),
                currentFieldMap = emptyMap(),
            )

        assertEquals("Front", result.fieldMap["word"])
        assertEquals("Back", result.fieldMap["definition"])
    }

    @Test
    fun `the two field default never takes a second field the user already mapped`() {
        val result =
            AnkiFieldMapPolicy.merge(
                currentNoteType = "Old",
                selectedNoteType = "Basic",
                fieldNames = listOf("Front", "Back"),
                currentFieldMap = mapOf("word" to "Front", "sentence" to "Back"),
            )

        assertEquals("Back", result.fieldMap["sentence"])
        assertEquals("", result.fieldMap["definition"])
    }

    @Test
    fun `remap leaves a two field note type alone`() {
        val result = AnkiFieldMapPolicy.remap(fieldNames = listOf("Front", "Back"), currentFieldMap = mapOf("word" to "Front"))

        assertEquals("", result.fieldMap["definition"].orEmpty())
    }
}
