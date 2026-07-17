package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMutationContractTest {
    @Test
    fun `model receipts accept only exact canonical positive model item URI`() {
        val raw = "content://com.ichi2.anki.flashcards/models/42"
        assertEquals(ModelCreateReceipt(42L, raw), ModelCreateReceiptValidator.validate(raw))

        listOf<String?>(
            null,
            "",
            "content://com.ichi2.anki.flashcards/models",
            "content://com.ichi2.anki.flashcards/models/0",
            "content://com.ichi2.anki.flashcards/models/042",
            "content://com.ichi2.anki.flashcards/models/-1",
            "content://com.ichi2.anki.flashcards/models/1/templates",
            "content://com.ichi2.anki.flashcards/models/1?x=1",
            "content://other/models/1",
        ).forEach { assertNull(it, ModelCreateReceiptValidator.validate(it)) }
        assertTrue(ModelTemplateUpdateReceiptValidator.validate(1))
        listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE).forEach { count ->
            assertFalse(count.toString(), ModelTemplateUpdateReceiptValidator.validate(count))
        }
    }

    @Test
    fun `media receipt retains one safe canonical decoded filename`() {
        val raw = "file:///anki_miner_%E6%97%A5.mp3"

        assertEquals(
            MediaInsertReceipt("anki_miner_日.mp3", raw),
            MediaInsertReceiptValidator.validate(raw),
        )
    }

    @Test
    fun `media receipt rejects noncanonical unsafe and non-item URI shapes`() {
        listOf<String?>(
            null,
            "",
            "file:",
            "file:/a.mp3",
            "file://host/a.mp3",
            "file:///",
            "file:////a.mp3",
            "file:///tmp/a.mp3",
            "file:///a%2Fb.mp3",
            "file:///a%3Ab.mp3",
            "file:///%20a.mp3",
            "file:///..",
            "file:///%61.mp3",
            "file:///%e6%97%a5.mp3",
            "file:///%FF.mp3",
            "file:///日.mp3",
            "file:///a.mp3?x=1",
            "file:///a.mp3#x",
            "FILE:///a.mp3",
            "content://com.ichi2.anki.flashcards/media/a.mp3",
        ).forEach { assertNull(it, MediaInsertReceiptValidator.validate(it)) }
    }

    @Test
    fun `note receipt accepts only exact canonical positive note item URI`() {
        val raw = "content://com.ichi2.anki.flashcards/notes/42"
        assertEquals(NoteInsertReceipt(42L, raw), NoteInsertReceiptValidator.validate(raw))

        listOf<String?>(
            null,
            "",
            "content://com.ichi2.anki.flashcards/notes",
            "content://com.ichi2.anki.flashcards/notes/0",
            "content://com.ichi2.anki.flashcards/notes/01",
            "content://com.ichi2.anki.flashcards/notes/-1",
            "content://com.ichi2.anki.flashcards/notes/1/cards",
            "content://com.ichi2.anki.flashcards/notes/1?x=1",
            "content://com.ichi2.anki.flashcards/notes/9223372036854775808",
            "content://other/notes/1",
        ).forEach { assertNull(it, NoteInsertReceiptValidator.validate(it)) }
    }

    @Test
    fun `card receipt requires exactly one affected row`() {
        assertSame(CardDeckUpdateReceipt, CardDeckUpdateReceiptValidator.validate(1))
        listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE).forEach { count ->
            assertNull(count.toString(), CardDeckUpdateReceiptValidator.validate(count))
        }
    }

    @Test
    fun `mutation boundary preserves access failures but normalizes impossible read failures`() {
        val expected =
            mapOf(
                ProviderFailureKind.API_DISABLED to ProviderFailureKind.API_DISABLED,
                ProviderFailureKind.PERMISSION_REQUIRED to ProviderFailureKind.PERMISSION_REQUIRED,
                ProviderFailureKind.PROVIDER_UNAVAILABLE to ProviderFailureKind.PROVIDER_UNAVAILABLE,
                ProviderFailureKind.MUTATION_FAILED to ProviderFailureKind.MUTATION_FAILED,
                ProviderFailureKind.QUERY_FAILED to ProviderFailureKind.MUTATION_FAILED,
                ProviderFailureKind.TIMEOUT to ProviderFailureKind.MUTATION_FAILED,
                ProviderFailureKind.CANCELLED to ProviderFailureKind.MUTATION_FAILED,
            )

        assertEquals(
            expected,
            ProviderFailureKind.entries.associateWith(ProviderFailureKind::normalizedForMutationBoundary),
        )
    }

    @Test
    fun `sealed mutation commands reject malformed provider values before entry`() {
        assertEquals(
            "anki_miner_audio",
            AnkiProviderMutationCommand.StoreMedia(
                fileUri = "content://com.ankiminer.android.staging/assets/token_1",
                preferredName = "anki_miner_audio",
            ).preferredName,
        )
        assertEquals(
            7L,
            AnkiProviderMutationCommand.InsertNote(
                modelId = 7L,
                joinedFields = "expression\u001fmeaning",
                providerTagsWire = "mined japanese",
            ).modelId,
        )
        assertEquals(
            0,
            AnkiProviderMutationCommand.RouteCard(
                expectedCardId = 11L,
                noteId = 12L,
                ordinal = 0,
                targetDeckId = 13L,
            ).ordinal,
        )

        listOf<() -> Unit>(
            { AnkiProviderMutationCommand.StoreMedia("file:///tmp/a.mp3", "audio") },
            { AnkiProviderMutationCommand.StoreMedia("content://authority", "audio") },
            { AnkiProviderMutationCommand.StoreMedia("content://authority/a", "a") },
            { AnkiProviderMutationCommand.StoreMedia("content://authority/a", "../audio") },
            { AnkiProviderMutationCommand.StoreMedia("content://authority/a", "\uD800") },
            { AnkiProviderMutationCommand.InsertNote(0L, "expression", "tag") },
            { AnkiProviderMutationCommand.InsertNote(1L, "\uD800", "tag") },
            { AnkiProviderMutationCommand.InsertNote(1L, "expression", "bad\u001ftag") },
            { AnkiProviderMutationCommand.RouteCard(0L, 1L, 0, 1L) },
            { AnkiProviderMutationCommand.RouteCard(1L, 1L, -1, 1L) },
            { AnkiProviderMutationCommand.RouteCard(1L, 1L, 64, 1L) },
        ).forEach { build ->
            assertThrows(IllegalArgumentException::class.java, build)
        }
    }

    @Test
    fun `fake gateway captures every sealed raw provider primitive`() {
        val gateway = FakeAnkiProviderGateway()
        val media = AnkiProviderMutationCommand.StoreMedia("content://staging/assets/1", "audio")
        val note = AnkiProviderMutationCommand.InsertNote(7L, "expression", "tag")
        val card = AnkiProviderMutationCommand.RouteCard(11L, 12L, 0, 13L)
        gateway.storeMediaHandler = { "file:///audio_1.mp3" }
        gateway.insertNoteHandler = { "content://com.ichi2.anki.flashcards/notes/21" }
        gateway.routeCardHandler = { 1 }

        assertEquals(
            "file:///audio_1.mp3",
            gateway.storeMedia(media),
        )
        assertEquals("content://com.ichi2.anki.flashcards/notes/21", gateway.insertNote(note))
        assertEquals(1, gateway.routeCard(card))
        assertEquals(listOf(media), gateway.mediaCommands)
        assertEquals(listOf(note), gateway.noteCommands)
        assertEquals(listOf(card), gateway.cardCommands)
    }

    @Test
    fun `standard template count is an upper bound for an observed card subset`() {
        val gateway = FakeAnkiProviderGateway()
        gateway.queryHandler = { query, _ ->
            when (query.endpoint) {
                ProviderEndpoint.CARDS ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(mapOf(ProviderColumn.CARD_ID to integer(101L))),
                    )
                ProviderEndpoint.CARD_BY_ID ->
                    FakeProviderCursor(
                        query.projection,
                        listOf(cardRow(id = 101L, noteId = 42L, ordinal = 1L, deckId = 7L)),
                    )
                else -> error("unexpected query $query")
            }
        }

        val cards =
            GlobalCardReader(CheckedProvider(gateway)).readForNote(
                noteId = 42L,
                templateCount = 2,
                cancellation = AnkiCancellation.NONE,
            )

        assertEquals(listOf(CardIdentity(101L, 42L, 1, 7L)), cards)
    }
}
