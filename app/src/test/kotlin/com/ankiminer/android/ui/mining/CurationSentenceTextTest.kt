package com.ankiminer.android.ui.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurationSentenceTextTest {
    @Test
    fun highlightsTheInflectedFormWhereItOccurs() {
        val highlighted = highlightMinedForm("毎日パンを食べる。", "食べる")

        assertEquals("毎日パンを食べる。", highlighted.text)
        val span = highlighted.spanStyles.single()
        assertEquals(5, span.start)
        assertEquals(8, span.end)
    }

    @Test
    fun leavesTheSentencePlainWhenTheFormDoesNotOccur() {
        // Conjugation and normalisation both produce this: the engine's surface is not always a
        // literal substring of the sentence it came from.
        val highlighted = highlightMinedForm("何を食べますか。", "食べる")

        assertEquals("何を食べますか。", highlighted.text)
        assertTrue(highlighted.spanStyles.isEmpty())
    }

    @Test
    fun leavesTheSentencePlainForABlankForm() {
        val highlighted = highlightMinedForm("何を食べますか。", "")

        assertEquals("何を食べますか。", highlighted.text)
        assertTrue(highlighted.spanStyles.isEmpty())
    }

    @Test
    fun highlightsOnlyTheFirstOccurrence() {
        val highlighted = highlightMinedForm("水を飲む、お茶を飲む。", "飲む")

        assertEquals(1, highlighted.spanStyles.size)
        assertEquals(2, highlighted.spanStyles.single().start)
    }
}
