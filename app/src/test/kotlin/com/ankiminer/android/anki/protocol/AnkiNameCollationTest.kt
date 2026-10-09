package com.ankiminer.android.anki.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiNameCollationTest {
    @Test
    fun `tags that Anki stored in an existing tag's or parent's case are the same tags`() {
        assertTrue(AnkiNameCollation.sameTagSet(listOf("Anime"), listOf("anime")))
        assertTrue(AnkiNameCollation.sameTagSet(listOf("Japanese::Anime"), listOf("japanese::anime")))
    }

    @Test
    fun `case-insensitive duplicates collapse like Anki's dedup`() {
        assertTrue(AnkiNameCollation.sameTagSet(listOf("Anime"), listOf("Anime", "anime")))
    }

    @Test
    fun `tags with different content stay different`() {
        assertFalse(AnkiNameCollation.sameTagSet(listOf("anime"), listOf("manga")))
        assertFalse(AnkiNameCollation.sameTagSet(listOf("anime"), listOf("anime", "manga")))
        assertFalse(AnkiNameCollation.sameTagSet(listOf("a::b"), listOf("a::c")))
        assertFalse(AnkiNameCollation.sameTagSet(listOf("kız"), listOf("kiz")))
    }

    @Test
    fun `tags split on ideographic space and trim components as Anki does`() {
        assertTrue(AnkiNameCollation.sameTagSet(listOf("日本語　単語"), listOf("日本語", "単語")))
        assertTrue(AnkiNameCollation.sameTagSet(listOf("a ::b"), listOf("a::b")))
    }

    @Test
    fun `tags compare after Anki's NFC normalisation`() {
        assertTrue(AnkiNameCollation.sameTagSet(listOf("café"), listOf("café")))
    }

    @Test
    fun `deck names equal under Anki's case and component normalisation are one deck`() {
        assertTrue(AnkiNameCollation.sameDeck("Japanese", "japanese"))
        assertTrue(AnkiNameCollation.sameDeck("Japanese :: Mining", "Japanese::Mining"))
        assertTrue(AnkiNameCollation.sameDeck("japanese::Mining", "Japanese::Mining"))
        assertTrue(AnkiNameCollation.sameDeck("Japanese:::Mining", "Japanese::Mining"))
        assertTrue(AnkiNameCollation.sameDeck("Japanese::", "Japanese::blank"))
        assertTrue(AnkiNameCollation.sameDeck("Straße", "STRASSE"))
        assertTrue(AnkiNameCollation.sameDeck("Café", "café"))
    }

    @Test
    fun `genuinely different deck names stay different`() {
        assertFalse(AnkiNameCollation.sameDeck("Japanese", "Japanese::Mining"))
        assertFalse(AnkiNameCollation.sameDeck("Mining", "Minning"))
        assertFalse(AnkiNameCollation.sameDeck("kız", "kiz"))
        assertFalse(AnkiNameCollation.sameDeck("A::B", "A B"))
    }

    @Test
    fun `a created deck takes Anki's normalised components and the deepest existing parent's spelling`() {
        assertEquals("Japanese::Mining", AnkiNameCollation.createdDeckName("Japanese :: Mining", emptyList()))
        assertEquals("A::blank", AnkiNameCollation.createdDeckName("A::", emptyList()))
        assertEquals(
            "Japanese::Mining::Words",
            AnkiNameCollation.createdDeckName("japanese::Mining::Words", listOf("Default", "Japanese")),
        )
        assertEquals(
            "Japanese::Mining::Words",
            AnkiNameCollation.createdDeckName("japanese :: mining::Words", listOf("Japanese", "Japanese::Mining")),
        )
        assertEquals("Mining", AnkiNameCollation.createdDeckName("Mining", listOf("Mining2", "Default")))
    }

    @Test
    fun `case folding is Unicode full folding`() {
        assertTrue(AnkiNameCollation.sameTagSet(listOf("straße"), listOf("STRASSE")))
        assertTrue(AnkiNameCollation.sameTagSet(listOf("ẞ"), listOf("ss")))
        assertTrue(AnkiNameCollation.sameTagSet(listOf("ΣΊΣΥΦΟΣ"), listOf("σίσυφος")))
        assertTrue(AnkiNameCollation.sameTagSet(listOf("İ"), listOf("i̇")))
        assertTrue(AnkiNameCollation.sameTagSet(listOf("K"), listOf("k")))
    }
}
