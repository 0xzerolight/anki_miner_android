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
