package com.ankiminer.android.anki.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DeckCreateReceiptValidatorTest {
    @Test
    fun `accepts only the exact canonical positive deck item URI`() {
        val raw = "content://com.ichi2.anki.flashcards/decks/42"

        assertEquals(DeckCreateReceipt(42L, raw), DeckCreateReceiptValidator.validate(raw))

        listOf(
            null,
            "",
            "content://com.ichi2.anki.flashcards/decks",
            "content://com.ichi2.anki.flashcards/decks/0",
            "content://com.ichi2.anki.flashcards/decks/-1",
            "content://com.ichi2.anki.flashcards/decks/01",
            "content://com.ichi2.anki.flashcards/decks/+1",
            "content://com.ichi2.anki.flashcards/decks/1/",
            "content://com.ichi2.anki.flashcards/decks/1?x=1",
            "content://com.ichi2.anki.flashcards/decks/1#x",
            "content://com.ichi2.anki.flashcards/decks/9223372036854775808",
            "content://other/decks/1",
            "CONTENT://com.ichi2.anki.flashcards/decks/1",
        ).forEach { assertNull(it, DeckCreateReceiptValidator.validate(it)) }
    }

    @Test
    fun `create deck command rejects malformed names before provider entry`() {
        assertEquals(
            "Mining::Japanese",
            AnkiProviderMutationCommand.CreateDeck("Mining::Japanese").deckName,
        )
        listOf("", "\uD800", "x".repeat(1025)).forEach { name ->
            assertThrows(name, IllegalArgumentException::class.java) {
                AnkiProviderMutationCommand.CreateDeck(name)
            }
        }
    }
}
