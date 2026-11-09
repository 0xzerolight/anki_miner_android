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
 * "Ignore suspended cards" on the known-vocabulary walk (desktop 3.12.0).
 *
 * Desktop's known set is then `deck:* -deck:"X" -is:suspended`: a note stays known while any one of
 * its cards is unsuspended and outside every excluded deck. The fake answers browser searches the
 * way AnkiDroid's `findNotes` does: card by card, a deck search covers the deck's children, and a
 * search with no hits is a null cursor.
 */
class SuspendedKnownVocabularyTest {
    @Test
    fun `a note whose every card is suspended leaves the known words`() {
        val collection =
            Collection(
                decks = listOf("Mining"),
                notes =
                    listOf(
                        Note(1L, "猫", Card("Mining", suspended = true)),
                        // Any one unsuspended card keeps the note known.
                        Note(2L, "犬", Card("Mining", suspended = true), Card("Mining")),
                        Note(3L, "鳥", Card("Mining")),
                        Note(4L, "魚", Card("Mining", suspended = true), Card("Mining", suspended = true)),
                    ),
            )

        assertEquals(listOf("犬", "鳥"), collection.walk(excluded = emptyList(), ignoreSuspended = true).words())
        assertEquals(1, collection.suspendedSearches)
    }

    @Test
    fun `with excluded decks a note needs an unsuspended card outside them`() {
        val collection =
            Collection(
                decks = listOf("Archive", "Archive::Old", "Mining"),
                notes =
                    listOf(
                        // Excluded deck plus a suspended card elsewhere: nothing keeps it.
                        Note(1L, "猫", Card("Archive::Old"), Card("Mining", suspended = true)),
                        // An unsuspended card outside the exclusions keeps it.
                        Note(2L, "犬", Card("Archive"), Card("Mining")),
                        // A suspended card inside the excluded deck changes nothing.
                        Note(3L, "鳥", Card("Archive", suspended = true), Card("Mining")),
                        Note(4L, "魚", Card("Archive")),
                        Note(5L, "馬", Card("Mining", suspended = true)),
                        Note(6L, "牛", Card("Mining")),
                    ),
            )

        assertEquals(
            listOf("犬", "鳥", "牛"),
            collection.walk(excluded = listOf("Archive"), ignoreSuspended = true).words(),
        )
        // The outside-the-exclusions check negates the same minimal scopes and the suspended cards.
        assertTrue(
            collection.outsideSearches().all { (decks, excludeSuspended) -> decks == listOf("Archive") && excludeSuspended },
        )
    }

    @Test
    fun `off, a suspended note stays known and no suspended search is sent`() {
        val collection =
            Collection(
                decks = listOf("Mining"),
                notes = listOf(Note(1L, "猫", Card("Mining", suspended = true)), Note(2L, "犬", Card("Mining"))),
            )

        assertEquals(listOf("猫", "犬"), collection.walk(excluded = emptyList(), ignoreSuspended = false).words())
        assertEquals(0, collection.suspendedSearches)
        assertTrue(collection.outsideSearches().isEmpty())
    }

    @Test
    fun `off, excluded decks keep the searches they always sent`() {
        val collection =
            Collection(
                decks = listOf("Archive", "Mining"),
                notes =
                    listOf(
                        Note(1L, "猫", Card("Archive"), Card("Mining", suspended = true)),
                        Note(2L, "犬", Card("Archive")),
                    ),
            )

        assertEquals(listOf("猫"), collection.walk(excluded = listOf("Archive"), ignoreSuspended = false).words())
        assertEquals(0, collection.suspendedSearches)
        assertEquals(listOf(listOf("Archive") to false), collection.outsideSearches())
    }

    @Test
    fun `reviewed marks ignore a suspended reviewed card when on`() {
        val collection =
            Collection(
                decks = listOf("Mining"),
                notes =
                    listOf(
                        // Reviewed, later suspended, beside an unsuspended new card: known, not reviewed.
                        Note(1L, "猫", Card("Mining", reviewed = true, suspended = true), Card("Mining")),
                        Note(2L, "犬", Card("Mining", reviewed = true)),
                        Note(3L, "鳥", Card("Mining", reviewed = true, suspended = true)),
                    ),
            )

        assertEquals(
            listOf("猫" to false, "犬" to true),
            collection.walk(excluded = emptyList(), ignoreSuspended = true, markReviewed = true),
        )
        assertEquals(listOf(true), collection.reviewedSearches())
    }

    @Test
    fun `reviewed marks keep a suspended reviewed card when off`() {
        val collection =
            Collection(
                decks = listOf("Mining"),
                notes = listOf(Note(1L, "猫", Card("Mining", reviewed = true, suspended = true))),
            )

        assertEquals(
            listOf("猫" to true),
            collection.walk(excluded = emptyList(), ignoreSuspended = false, markReviewed = true),
        )
        assertEquals(listOf(false), collection.reviewedSearches())
    }

    @Test
    fun `the suspended candidates count toward the excluded-note budget once each`() {
        val collection =
            Collection(
                decks = listOf("Archive", "Mining"),
                notes = listOf(Note(1L, "猫", Card("Archive"), Card("Mining", suspended = true))),
            )

        assertEquals(emptyList<String>(), collection.walk(excluded = listOf("Archive"), ignoreSuspended = true).words())
        // The note came back from both candidate searches and is checked once.
        assertEquals(listOf(listOf(1L)), collection.outsideNoteIds())
    }

    @Test
    fun `a continuation that changes the suspended switch is a different traversal`() {
        val collection =
            Collection(
                decks = listOf("Mining"),
                notes = (1L..300L).map { id -> Note(id, "w$id", Card("Mining")) },
            )
        val first = collection.page(KnownVocabularyScope(emptyList(), null, emptyList(), ignoreSuspended = true))

        assertThrows(InvalidCapabilityException::class.java) {
            collection.page(KnownVocabularyScope(emptyList(), first.nextCursor, emptyList()))
        }
    }

    private fun List<Pair<String, Boolean?>>.words() = map { it.first }

    private class Card(
        val deck: String,
        val reviewed: Boolean = false,
        val suspended: Boolean = false,
    )

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

        init {
            assertTrue(registry.register(RUN_ID, AnkiCancellation.NONE))
            gateway.queryHandler = { query, _ -> answer(query) }
        }

        /** Walks every known-vocabulary page and returns each note's word and reviewed mark. */
        fun walk(
            excluded: List<String>,
            ignoreSuspended: Boolean,
            markReviewed: Boolean = false,
        ): List<Pair<String, Boolean?>> {
            val walked = ArrayList<Pair<String, Boolean?>>()
            var cursor: KnownVocabularyCursor? = null
            do {
                val result =
                    page(KnownVocabularyScope(excluded, cursor, emptyList(), markReviewed, ignoreSuspended))
                walked += result.notes.map { it.fields.single() to it.reviewed }
                cursor = result.nextCursor
            } while (cursor != null)
            return walked
        }

        fun page(scope: KnownVocabularyScope): KnownVocabularyResult {
            requests += 1
            val requestId = "anki_${requests.toString().padStart(32, '0')}"
            return registry.withOwner(RUN_ID) { owner ->
                reads.scanFirstFields(owner, ScanFirstFieldsRequest(RUN_ID, requestId, scope))
            } as KnownVocabularyResult
        }

        val suspendedSearches: Int
            get() = gateway.queries.count { it.selection == ProviderSelection.SuspendedNotes }

        fun outsideSearches(): List<Pair<List<String>, Boolean>> =
            gateway.queries.mapNotNull { query ->
                (query.selection as? ProviderSelection.NotesOutsideDecks)?.let { it.deckNames to it.excludeSuspended }
            }

        fun outsideNoteIds(): List<List<Long>> =
            gateway.queries.mapNotNull { (it.selection as? ProviderSelection.NotesOutsideDecks)?.noteIds }

        fun reviewedSearches(): List<Boolean> =
            gateway.queries.mapNotNull { (it.selection as? ProviderSelection.ReviewedNotes)?.excludeSuspended }

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
                ProviderSelection.SuspendedNotes ->
                    noteIds(query, notes.filter { note -> note.cards.any { it.suspended } })
                is ProviderSelection.NotesOutsideDecks ->
                    noteIds(
                        query,
                        notes.filter { note ->
                            note.id in selection.noteIds &&
                                note.cards.any { card ->
                                    selection.deckNames.none { card.deck.isUnder(it) } &&
                                        !(selection.excludeSuspended && card.suspended)
                                }
                        },
                    )
                is ProviderSelection.ReviewedNotes ->
                    noteIds(
                        query,
                        notes.filter { note ->
                            note.cards.any { card ->
                                card.reviewed &&
                                    selection.excludedDeckNames.none { card.deck.isUnder(it) } &&
                                    !(selection.excludeSuspended && card.suspended)
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
