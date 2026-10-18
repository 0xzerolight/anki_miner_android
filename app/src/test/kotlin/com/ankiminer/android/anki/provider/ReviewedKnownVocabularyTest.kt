package com.ankiminer.android.anki.provider

import com.ankiminer.android.anki.protocol.KnownVocabularyCursor
import com.ankiminer.android.anki.protocol.KnownVocabularyResult
import com.ankiminer.android.anki.protocol.KnownVocabularyScope
import com.ankiminer.android.anki.protocol.ScanFirstFieldsRequest
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reviewed marks on the known-vocabulary walk (desktop F-01, "count a word as known only after its
 * card has been reviewed").
 *
 * Desktop's reviewed set is `deck:* -deck:"X" -is:new`. The fake answers browser searches the way
 * AnkiDroid's `findNotes` does: card by card, so a note matches when any one of its cards matches
 * every term, a deck search covers the deck's children, and a search with no hits is a null cursor.
 */
class ReviewedKnownVocabularyTest {
    @Test
    fun `each note is marked by whether any of its cards has been reviewed`() {
        val collection =
            Collection(
                decks = listOf("Mining"),
                notes =
                    listOf(
                        Note(1L, "猫", Card("Mining", reviewed = true)),
                        Note(2L, "犬", Card("Mining", reviewed = false)),
                        // A note qualifies through any one of its cards.
                        Note(3L, "鳥", Card("Mining", reviewed = false), Card("Mining", reviewed = true)),
                    ),
            )

        assertEquals(
            listOf("猫" to true, "犬" to false, "鳥" to true),
            collection.walk(excluded = emptyList(), markReviewed = true),
        )
        assertEquals(listOf(emptyList<String>()), collection.reviewedSearches())
    }

    @Test
    fun `a card reviewed only inside an excluded deck does not mark its note`() {
        // The note stays known through its unstudied card outside the exclusion, but its only
        // reviewed card sits in the excluded deck, so desktop's card-level search leaves it out.
        val collection =
            Collection(
                decks = listOf("Core 6k", "Core 6k::Part 1", "Mining"),
                notes =
                    listOf(
                        Note(1L, "猫", Card("Core 6k::Part 1", reviewed = true), Card("Mining", reviewed = false)),
                        Note(2L, "犬", Card("Mining", reviewed = true)),
                        Note(3L, "鳥", Card("Core 6k", reviewed = true)),
                    ),
            )

        assertEquals(
            listOf("猫" to false, "犬" to true),
            collection.walk(excluded = listOf("Core 6k", "Core 6k::Part 1"), markReviewed = true),
        )
        // The reviewed search negates the same minimal deck scopes the exclusions resolved to.
        assertEquals(listOf(listOf("Core 6k")), collection.reviewedSearches())
    }

    @Test
    fun `marks cover every page and the reviewed search runs once per walk`() {
        val collection =
            Collection(
                decks = listOf("Mining"),
                notes = (1L..600L).map { id -> Note(id, "w$id", Card("Mining", reviewed = id % 3L == 0L)) },
            )

        val walked = collection.walk(excluded = emptyList(), markReviewed = true)

        assertEquals((1L..600L).map { id -> "w$id" to (id % 3L == 0L) }, walked)
        assertEquals(3, collection.pages)
        assertEquals(1, collection.reviewedSearches().size)
    }

    @Test
    fun `a collection with no reviewed card marks every note unreviewed`() {
        val collection =
            Collection(
                decks = listOf("Mining"),
                notes = listOf(Note(1L, "猫", Card("Mining", reviewed = false))),
            )

        assertEquals(listOf("猫" to false), collection.walk(excluded = emptyList(), markReviewed = true))
    }

    @Test
    fun `an unmarked walk sends no reviewed search and returns no marks`() {
        val collection =
            Collection(
                decks = listOf("Mining"),
                notes = listOf(Note(1L, "猫", Card("Mining", reviewed = true))),
            )

        val result = collection.page(KnownVocabularyScope(emptyList(), null, emptyList()))

        assertEquals(listOf(null), result.notes.map { it.reviewed })
        assertTrue(collection.reviewedSearches().isEmpty())
    }

    @Test
    fun `a continuation that changes the reviewed marks is a different traversal`() {
        val collection =
            Collection(
                decks = listOf("Mining"),
                notes = (1L..300L).map { id -> Note(id, "w$id", Card("Mining", reviewed = true)) },
            )
        val first = collection.page(KnownVocabularyScope(emptyList(), null, emptyList(), markReviewed = true))

        assertThrows(InvalidCapabilityException::class.java) {
            collection.page(KnownVocabularyScope(emptyList(), first.nextCursor, emptyList()))
        }
    }

    private class Card(val deck: String, val reviewed: Boolean)

    private class Note(val id: Long, val word: String, vararg val cards: Card)

    /** A tiny collection served through the fake gateway; notes are listed in ascending ID order. */
    private class Collection(
        private val decks: List<String>,
        private val notes: List<Note>,
    ) {
        private val gateway = FakeAnkiProviderGateway()
        private val registry = AnkiRunStateRegistry()
        private val tokens = ArrayDeque((1..16).map { "cursor_${it.toString().padStart(32, '0')}" })
        private val reads = AnkiProviderReadService(gateway, registry) { _ -> tokens.removeFirst() }
        private var requests = 0
        var pages = 0
            private set

        init {
            assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
            gateway.queryHandler = { query, _ -> answer(query) }
        }

        /** Walks every known-vocabulary page and returns each note's word and reviewed mark. */
        fun walk(
            excluded: List<String>,
            markReviewed: Boolean,
        ): List<Pair<String, Boolean?>> {
            val walked = ArrayList<Pair<String, Boolean?>>()
            var cursor: KnownVocabularyCursor? = null
            do {
                val result = page(KnownVocabularyScope(excluded, cursor, emptyList(), markReviewed))
                walked += result.notes.map { it.fields.single() to it.reviewed }
                cursor = result.nextCursor
            } while (cursor != null)
            return walked
        }

        fun page(scope: KnownVocabularyScope): KnownVocabularyResult {
            requests += 1
            pages += 1
            val requestId = "anki_${requests.toString().padStart(32, '0')}"
            return registry.withOwner(RUN_ID) { owner ->
                reads.scanFirstFields(owner, ScanFirstFieldsRequest(RUN_ID, requestId, scope))
            } as KnownVocabularyResult
        }

        fun reviewedSearches(): List<List<String>> =
            gateway.queries.mapNotNull { (it.selection as? ProviderSelection.ReviewedNotes)?.excludedDeckNames }

        private fun answer(query: ProviderQuery): ProviderCursor? {
            if (query.endpoint == ProviderEndpoint.DECKS) {
                return FakeProviderCursor(
                    query.projection,
                    decks.mapIndexed { index, name -> deckRow(100L + index, name) },
                )
            }
            return when (val selection = query.selection) {
                is ProviderSelection.ExcludedDeck ->
                    noteIds(query, notes.filter { note -> note.cards.any { it.deck.isUnder(selection.deckName) } })
                is ProviderSelection.NotesOutsideDecks ->
                    noteIds(
                        query,
                        notes.filter { note ->
                            note.id in selection.noteIds &&
                                note.cards.any { card -> selection.deckNames.none { card.deck.isUnder(it) } }
                        },
                    )
                is ProviderSelection.ReviewedNotes ->
                    noteIds(
                        query,
                        notes.filter { note ->
                            note.cards.any { card ->
                                card.reviewed && selection.excludedDeckNames.none { card.deck.isUnder(it) }
                            }
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
