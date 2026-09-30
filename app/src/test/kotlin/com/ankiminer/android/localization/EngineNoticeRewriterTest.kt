package com.ankiminer.android.localization

import com.ankiminer.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineNoticeRewriterTest {
    private val rewriter = EngineNoticeRewriter(testStringResourceResolver)

    @Test
    fun noDefinitionNoticeIsRestatedWithoutTheWordSkipped() {
        val rewritten = rewriter.rewrite("Skipped 2 words with no definition found: 本好き, 編み")

        assertEquals(
            "No dictionary entry for 2 word(s): 本好き, 編み",
            rewritten,
        )
    }

    /** The pre-curation site words the same miss differently; one restatement covers both. */
    @Test
    fun preCurationDefinitionMissIsRestatedTheSameWay() {
        val rewritten = rewriter.rewrite("Skipped 2 words missing from your offline dictionaries: 本好き, 編み")

        assertEquals(
            "No dictionary entry for 2 word(s): 本好き, 編み",
            rewritten,
        )
    }

    /** The engine points at desktop's "Settings → Frequency"; Android restates it in its own words. */
    @Test
    fun frequencyCutoffNoticeIsRestatedAwayFromTheDesktopMenu() {
        val rewritten =
            rewriter.rewrite(
                "Frequency cutoff ignored — no ranked frequency source is loaded (Settings → Frequency).",
            )

        assertEquals("resource:${R.string.mining_notice_frequency_cutoff_ignored}", rewritten)
    }

    /** The engine appends the overflow tail inside its own %3 slot, so it rides along in group 2. */
    @Test
    fun overflowTailStaysAttachedToTheWordList() {
        val rewritten =
            rewriter.rewrite(
                "Skipped 12 words with no definition found: 本好き, 編み, 猫, 犬, 鳥 (+7 more)",
            )

        assertEquals(
            "No dictionary entry for 12 word(s): 本好き, 編み, 猫, 犬, 鳥 (+7 more)",
            rewritten,
        )
    }

    @Test
    fun otherEngineNoticesPassThroughUntouched() {
        assertEquals(
            "Sentence length filter: removed 4 words (cap: 12s)",
            rewriter.rewrite("Sentence length filter: removed 4 words (cap: 12s)"),
        )
        assertEquals("", rewriter.rewrite(""))
    }

    /**
     * Every receipt, spelled the way the engine renders it. The Qt shim expands `%n` to a bare digit
     * with no plural selection, so `word(s)` and `image(s)` stay literal.
     */
    @Test
    fun receiptsAreDropped() {
        listOf(
            "3 word(s) have more than one reading — the parsed reading was kept.",
            "Skipped 3 word(s) Anki flagged as duplicates (same Expression)",
            "Using WebP for animated screenshots — this ffmpeg build has no AVIF encoder.",
            "text-only volume: pages have no paired images",
            "page 12: no image matched 'volume01/012.jpg'",
            "Skipped 4 inline image(s) (gaiji) that carried no text.",
        ).forEach { receipt ->
            assertNull(receipt, rewriter.rewrite(receipt))
        }
    }

    /**
     * The loaders build these from an untrusted filename repr, and presenter text arrives as one
     * JSON string field, so the tail has to span a newline rather than fall through onto the screen.
     */
    @Test
    fun multilineImagePathIsStillAReceipt() {
        assertNull(rewriter.rewrite("page 7: no image matched 'odd\nname.jpg'"))
    }

    /**
     * Receipts are dropped on a full match only. These share a prefix with one and each reports
     * something the user actually lost, so they have to reach the screen.
     */
    @Test
    fun neighboursOfReceiptsSurvive() {
        listOf(
            "Animated screenshots unavailable — this ffmpeg build has no AVIF or WebP encoder " +
                "(Settings → Card Media).",
            "Skipped 4 malformed Mokuro record(s).",
            "Skipped unreadable page image 012.jpg — its card has no picture",
            "3 word(s) have more than one reading — the parsed reading was kept. And then some",
            "Frequency cutoff ignored — no ranked frequency source is loaded (Settings → Frequency). Twice",
        ).forEach { notice ->
            assertEquals(notice, rewriter.rewrite(notice))
        }
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
            "No dictionary entry for 2 word(s): 本好き,\n編み",
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
