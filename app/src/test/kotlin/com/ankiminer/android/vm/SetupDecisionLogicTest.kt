package com.ankiminer.android.vm

import com.ankiminer.android.anki.provider.NoteTypeSetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDecisionLogicTest {
    @Test
    fun `null deck resolves to explicit create-or-use default choice`() {
        val resolution = resolveDeckSelection(null, listOf("Default", "Japanese"))

        assertEquals("Anki Miner", resolution.selectedDeckName)
        assertEquals(
            DeckChoiceKind.CREATE_OR_USE_DEFAULT,
            resolution.choices.first().kind,
        )
        assertEquals("Anki Miner", resolution.choices.first().deckName)
        assertTrue(resolution.choices.first().selected)
        assertEquals(
            listOf("Anki Miner", "Default", "Japanese"),
            resolution.choices.map { it.deckName },
        )
    }

    @Test
    fun `saved undiscovered deck remains selected and visibly distinct`() {
        val resolution = resolveDeckSelection("Archived", listOf("Default"))

        assertEquals("Archived", resolution.selectedDeckName)
        assertEquals(
            DeckChoiceKind.SAVED_UNAVAILABLE,
            resolution.choices.single { it.deckName == "Archived" }.kind,
        )
        assertTrue(resolution.choices.single { it.deckName == "Archived" }.selected)
    }

    @Test
    fun `one-field verified note type is writable but not useful or enriched`() {
        val quality =
            classifyNoteTypeQuality(
                NoteTypeSetupStatus.Verified(24L),
                mapOf("word" to "Expression"),
            )

        assertTrue(quality.writableAndDedupSafe)
        assertFalse(quality.usefulForMining)
        assertFalse(quality.fullyEnriched)
        assertEquals("Expression", quality.fields.word)
        assertEquals(emptyList<String>(), quality.fields.audio)
    }

    @Test
    fun `quality distinguishes useful cards from full enrichment`() {
        val useful =
            classifyNoteTypeQuality(
                NoteTypeSetupStatus.Verified(24L),
                mapOf(
                    "word" to "Expression",
                    "sentence" to "Sentence",
                    "definition" to "Meaning",
                ),
            )
        assertTrue(useful.usefulForMining)
        assertFalse(useful.fullyEnriched)

        val enriched =
            classifyNoteTypeQuality(
                NoteTypeSetupStatus.Verified(24L),
                mapOf(
                    "word" to "Expression",
                    "sentence" to "Sentence",
                    "glossary" to "Glossary",
                    "audio" to "SentenceAudio",
                    "expression_audio" to "WordAudio",
                    "picture" to "Picture",
                ),
            )
        assertTrue(enriched.usefulForMining)
        assertTrue(enriched.fullyEnriched)
        assertEquals(listOf("SentenceAudio", "WordAudio"), enriched.fields.audio)
    }
}
