package com.ankiminer.android.ui.settings

import com.ankiminer.android.R
import com.ankiminer.android.data.RuntimeWorkCoordinator
import com.ankiminer.android.data.resources.KnownWordsFailureOperation
import com.ankiminer.android.data.resources.KnownWordsInventory
import com.ankiminer.android.data.resources.KnownWordsPage
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.data.resources.ResourceOperationPhase
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.vm.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownWordsManagerScreenTest {
    @Test
    fun anInvalidStoreIsNotDescribedAsEmpty() {
        // The bridge reports a store that fails its schema check with every count at zero.
        assertEquals(
            R.string.known_words_manager_empty,
            knownWordsEmptyMessage(KnownWordsInventory(0, 0, 0, 0, schemaOk = true)),
        )
        assertEquals(
            R.string.known_words_inventory_invalid,
            knownWordsEmptyMessage(KnownWordsInventory(0, 0, 0, 0, schemaOk = false)),
        )
    }

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
    fun aMissingListLoadsOnceNothingHoldsTheRuntime() {
        // A removal or a reset drops the page; so does a fresh process.
        val idle = SetupUiState(resourceStartup = ResourceStartupReadiness.READY)

        assertTrue(knownWordsListNeedsLoad(idle))
        // The mutation that dropped the page is still finishing: the search would be refused.
        assertFalse(
            knownWordsListNeedsLoad(
                idle.copy(operation = ResourceOperationProgress("op", "Removing", ResourceOperationPhase.IMPORTING)),
            ),
        )
        // Opened during a run: load when it ends, not never.
        assertFalse(knownWordsListNeedsLoad(idle.copy(runtimeWorkKind = RuntimeWorkCoordinator.Kind.MINING)))
        assertFalse(knownWordsListNeedsLoad(idle.copy(knownWordsPage = page(words = listOf("猫"), hasMore = false))))
    }

    @Test
    fun aFailedSearchShowsItsFailureInsteadOfSearchingAgain() {
        val searchFailure =
            ResourceFailure(
                code = "resource_operation_failed",
                message = "failed",
                retryable = true,
                origin = ResourceFailureOrigin.KNOWN_WORDS,
            )
        val failed = SetupUiState(resourceStartup = ResourceStartupReadiness.READY, failure = searchFailure)

        // Searching again would fail again, and every failure would re-key the reload.
        assertFalse(knownWordsListNeedsLoad(failed))
        // A failed preview from Word filters is not the list's own failure; the list still loads.
        assertTrue(
            knownWordsListNeedsLoad(
                failed.copy(failure = searchFailure.copy(knownWordsOperation = KnownWordsFailureOperation.PREVIEW)),
            ),
        )
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
