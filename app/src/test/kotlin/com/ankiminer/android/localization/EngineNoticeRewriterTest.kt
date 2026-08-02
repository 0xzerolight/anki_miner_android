package com.ankiminer.android.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineNoticeRewriterTest {
    private val rewriter = EngineNoticeRewriter(testStringResourceResolver)

    @Test
    fun noDefinitionNoticeIsRestatedWithoutTheWordSkipped() {
        val rewritten = rewriter.rewrite("Skipped 2 words with no definition found: 本好き, 編み")

        assertEquals(
            "No dictionary entry for 2 word(s), so no card was made: 本好き, 編み",
            rewritten,
        )
    }

    /** The engine appends the overflow tail inside its own %3 slot, so it rides along in group 2. */
    @Test
    fun overflowTailStaysAttachedToTheWordList() {
        val rewritten =
            rewriter.rewrite(
                "Skipped 12 words with no definition found: 本好き, 編み, 猫, 犬, 鳥 (+7 more)",
            )

        assertEquals(
            "No dictionary entry for 12 word(s), so no card was made: 本好き, 編み, 猫, 犬, 鳥 (+7 more)",
            rewritten,
        )
    }

    @Test
    fun otherEngineNoticesPassThroughUntouched() {
        val duplicates = "Skipped 3 words Anki flagged as duplicates (same Expression)"

        assertEquals(duplicates, rewriter.rewrite(duplicates))
        assertEquals(
            "Sentence length filter: removed 4 words (cap: 12s)",
            rewriter.rewrite("Sentence length filter: removed 4 words (cap: 12s)"),
        )
        assertEquals("", rewriter.rewrite(""))
    }

    /** A partial match must not be rewritten into a message with an empty word list. */
    @Test
    fun truncatedNoticeIsNotRewritten() {
        val truncated = "Skipped 2 words with no definition found: "

        assertEquals(truncated, rewriter.rewrite(truncated))
    }

    /**
     * Presenter text arrives as one JSON string field, so nothing stops a word list from carrying a
     * newline. The rule has to span it rather than fall through to the raw English.
     */
    @Test
    fun multilineWordListStillMatches() {
        val rewritten = rewriter.rewrite("Skipped 2 words with no definition found: 本好き,\n編み")

        assertEquals(
            "No dictionary entry for 2 word(s), so no card was made: 本好き,\n編み",
            rewritten,
        )
    }

    /** Guards the `toIntOrNull` fallback: an unparseable count leaves the notice alone. */
    @Test
    fun oversizedCountFallsBackToTheOriginalNotice() {
        val huge = "Skipped 99999999999999999999 words with no definition found: 本好き"

        assertEquals(huge, rewriter.rewrite(huge))
    }
}
