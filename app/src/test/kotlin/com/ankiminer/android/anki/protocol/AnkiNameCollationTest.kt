package com.ankiminer.android.anki.protocol

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
    fun `case folding is Unicode full folding`() {
        assertTrue(AnkiNameCollation.sameTagSet(listOf("straße"), listOf("STRASSE")))
        assertTrue(AnkiNameCollation.sameTagSet(listOf("ẞ"), listOf("ss")))
        assertTrue(AnkiNameCollation.sameTagSet(listOf("ΣΊΣΥΦΟΣ"), listOf("σίσυφος")))
        assertTrue(AnkiNameCollation.sameTagSet(listOf("İ"), listOf("i̇")))
        assertTrue(AnkiNameCollation.sameTagSet(listOf("K"), listOf("k")))
    }
}
