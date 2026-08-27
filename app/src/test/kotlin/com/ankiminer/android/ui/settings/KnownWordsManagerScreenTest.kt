package com.ankiminer.android.ui.settings

import com.ankiminer.android.data.resources.KnownWordsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownWordsManagerScreenTest {
    @Test
    fun nullPageIsLoadingWhileACompletedEmptyPageIsEmpty() {
        val loading =
            knownWordsListPresentation(
                page = null,
                operationActive = true,
                // ResourceManager retains the prior failure until a retry succeeds.
                failureVisible = true,
            )
        val empty =
            knownWordsListPresentation(
                page = page(words = emptyList(), hasMore = false),
                operationActive = false,
                failureVisible = false,
            )

        assertEquals(KnownWordsListContent.LOADING, loading.content)
        assertTrue(loading.showProgress)
        assertEquals(KnownWordsListContent.EMPTY, empty.content)
        assertFalse(empty.showProgress)
    }

    @Test
    fun loadMoreKeepsRowsAndReplacesTheActionWithProgress() {
        val presentation =
            knownWordsListPresentation(
                page = page(words = listOf("猫"), hasMore = true),
                operationActive = true,
                failureVisible = false,
            )

        assertEquals(KnownWordsListContent.WORDS, presentation.content)
        assertTrue(presentation.showProgress)
        assertFalse(presentation.showLoadMore)
    }

    @Test
    fun togglingAddsAndRemovesAWord() {
        val once = toggleKnownWordSelection(emptySet(), "食べる")

        assertEquals(setOf("食べる"), once)
        assertEquals(emptySet<String>(), toggleKnownWordSelection(once, "食べる"))
    }

    @Test
    fun selectionStopsAtTheBatchLimit() {
        val full = (1..4).map { "word$it" }.toSet()

        assertEquals(full, toggleKnownWordSelection(full, "word5", limit = 4))
    }

    @Test
    fun deselectingIsAllowedAtTheLimit() {
        val full = (1..4).map { "word$it" }.toSet()

        assertEquals(full - "word2", toggleKnownWordSelection(full, "word2", limit = 4))
    }

    private fun page(
        words: List<String>,
        hasMore: Boolean,
    ) =
        KnownWordsPage(
            query = "",
            offset = 0,
            totalCount = words.size.toLong(),
            words = words,
            hasMore = hasMore,
        )
}
