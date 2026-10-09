package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.KnownVocabularyCursor
import com.ankiminer.android.anki.protocol.KnownVocabularyResult
import com.ankiminer.android.anki.protocol.KnownVocabularyScope
import com.ankiminer.android.anki.protocol.ScanFirstFieldsRequest
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Excluded decks against notes whose cards span several decks (AU-023).
 *
 * The fake answers both browser searches the way AnkiDroid's `findNotes` does: card by card, so a
 * note matches when any one of its cards matches, a deck search covers the deck's children, and a
 * search with no hits is a null cursor.
 */
class ExcludedDeckKnownVocabularyTest {
    @Test
    fun `a note with a card outside the excluded decks stays known`() {
        // The user moved the recognition card of a word they know into "Known"; its other card is
        // still in the unstudied premade deck they excluded.
        val collection =
            Collection(
                decks = listOf("Core 6k", "Known"),
                notes =
                    listOf(
                        Note(1L, "猫", "Known", "Core 6k"),
                        Note(2L, "犬", "Core 6k", "Core 6k"),
                        Note(3L, "鳥", "Known"),
                    ),
            )

        assertEquals(listOf("猫", "鳥"), collection.knownWords(excluded = listOf("Core 6k")))
    }

    @Test
    fun `a note whose cards all sit in excluded decks stays excluded`() {
        val collection =
            Collection(
                decks = listOf("Split::A", "Split::B", "Mining"),
                notes =
                    listOf(
                        Note(1L, "both excluded", "Split::A", "Split::B"),
                        Note(2L, "one outside", "Split::A", "Mining"),
                        Note(3L, "outside only", "Mining"),
                    ),
            )

        assertEquals(
            listOf("one outside", "outside only"),
            collection.knownWords(excluded = listOf("Split::A", "Split::B")),
        )
    }

    @Test
    fun `candidate notes reach the outside search in bounded ascending chunks`() {
        val max = ProviderQueryShapes.NOTES_OUTSIDE_DECKS_MAX_NOTE_IDS.toLong()

        val exact = Collection(listOf("Core"), (1L..max).map { id -> Note(id, "w$id", "Core") })
        assertEquals(emptyList<String>(), exact.knownWords(excluded = listOf("Core")))
        assertEquals(listOf((1L..max).toList()), exact.outsideSearches())

        // One past the bound: a full chunk, then a one-ID chunk. That last note also has a card
        // outside the exclusion, so a hit in the second chunk is subtracted like any other.
        val over =
            Collection(
                listOf("Core", "Known"),
                (1L..max + 1).map { id ->
                    if (id == max + 1) Note(id, "split", "Core", "Known") else Note(id, "w$id", "Core")
                },
            )
        assertEquals(listOf("split"), over.knownWords(excluded = listOf("Core")))
        assertEquals(listOf((1L..max).toList(), listOf(max + 1)), over.outsideSearches())
    }

    @Test
    fun `an excluded deck with no notes sends no outside search`() {
        val collection = Collection(listOf("Empty", "Mining"), listOf(Note(1L, "one", "Mining")))

        assertEquals(listOf("one"), collection.knownWords(excluded = listOf("Empty")))
        assertTrue(collection.outsideSearches().isEmpty())
    }

    private class Note(val id: Long, val word: String, vararg val cardDecks: String)

    /** A tiny collection served through the fake gateway; notes are listed in ascending ID order. */
    private class Collection(
        private val decks: List<String>,
        private val notes: List<Note>,
    ) {
        private val gateway = FakeAnkiProviderGateway()
        private val registry = AnkiRunStateRegistry()
        private val tokens = ArrayDeque((1..16).map { "cursor_${it.toString().padStart(32, '0')}" })
        private val reads = AnkiProviderReadService(gateway, registry) { _ -> tokens.removeFirst() }

        init {
            assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
            gateway.queryHandler = { query, _ -> answer(query) }
        }

        /** Walks every known-vocabulary page; the exclusions are resolved on the first one. */
        fun knownWords(excluded: List<String>): List<String> {
            val words = ArrayList<String>()
            var cursor: KnownVocabularyCursor? = null
            var page = 0
            do {
                page += 1
                val requestId = "anki_${page.toString().padStart(32, '0')}"
                val scope = KnownVocabularyScope(excluded, cursor, emptyList())
                val result =
                    registry.withOwner(RUN_ID) { owner ->
                        reads.scanFirstFields(owner, ScanFirstFieldsRequest(RUN_ID, requestId, scope))
                    } as KnownVocabularyResult
                words += result.notes.map { it.fields.single() }
                cursor = result.nextCursor
            } while (cursor != null)
            return words
        }

        fun outsideSearches(): List<List<Long>> =
            gateway.queries.mapNotNull { (it.selection as? ProviderSelection.NotesOutsideDecks)?.noteIds }

        private fun answer(query: ProviderQuery): ProviderCursor? {
            if (query.endpoint == ProviderEndpoint.DECKS) {
                return FakeProviderCursor(
                    query.projection,
                    decks.mapIndexed { index, name -> deckRow(100L + index, name) },
                )
            }
            return when (val selection = query.selection) {
                is ProviderSelection.ExcludedDeck ->
                    noteIds(query, notes.filter { note -> note.cardDecks.any { it.isUnder(selection.deckName) } })
                is ProviderSelection.NotesOutsideDecks ->
                    noteIds(
                        query,
                        notes.filter { note ->
                            note.id in selection.noteIds &&
                                note.cardDecks.any { deck -> selection.deckNames.none { deck.isUnder(it) } }
                        },
                    )
                is ProviderSelection.NoteIdsAfter ->
                    FakeProviderCursor(
                        query.projection,
                        notes.filter { it.id > selection.fromId }.map { note ->
                            mapOf(
                                ProviderColumn.NOTE_ID to integer(note.id),
                                ProviderColumn.NOTE_MODEL_ID to integer(NOTE_TYPE_ID),
                                ProviderColumn.NOTE_FIELDS to text(note.word),
                            )
                        },
                    )
                else -> error("unexpected query $query")
            }
        }

        // AnkiDroid answers a browser search with no hits with a null cursor.
        private fun noteIds(
            query: ProviderQuery,
            matches: List<Note>,
        ): ProviderCursor? =
            if (matches.isEmpty()) {
                null
            } else {
                FakeProviderCursor(query.projection, matches.map { mapOf(ProviderColumn.NOTE_ID to integer(it.id)) })
            }

        private fun String.isUnder(deck: String) = this == deck || startsWith("$deck::")
    }

    private companion object {
        const val RUN_ID = "run_11111111111111111111111111111111"
        const val NOTE_TYPE_ID = 1_700_000_000_001L
    }
}
