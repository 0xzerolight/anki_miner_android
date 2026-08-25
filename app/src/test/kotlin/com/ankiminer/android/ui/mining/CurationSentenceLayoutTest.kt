package com.ankiminer.android.ui.mining

import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurationSentenceLayoutTest {
    @Test
    fun resolvesTheSelectedSentenceAndKeepsAlternativesInSourceOrder() {
        val candidate = candidate(sentenceCount = 5)
        val layout = curationSentenceLayout(candidate, selectedSentenceId = "s-2")

        assertEquals("s-2", layout.chosen.sentenceId)
        assertEquals(listOf("s-0", "s-1", "s-3", "s-4"), layout.alternatives.map { it.sentenceId })
    }

    @Test
    fun fallsBackToTheDefaultSentenceForNullOrUnknownSelection() {
        val candidate = candidate(sentenceCount = 5)

        assertEquals("s-0", curationSentenceLayout(candidate, null).chosen.sentenceId)
        assertEquals("s-0", curationSentenceLayout(candidate, "missing").chosen.sentenceId)
    }

    @Test
    fun disclosesOnlyAboveTheInlineAlternativeCap() {
        // 4 sentences = 3 alternatives = MAX_INLINE_ALTERNATIVES: inline.
        assertFalse(curationSentenceLayout(candidate(sentenceCount = 4), "s-0").disclose)
        // 5 sentences = 4 alternatives: behind the disclosure.
        assertTrue(curationSentenceLayout(candidate(sentenceCount = 5), "s-0").disclose)
    }

    @Test
    fun aSingleSentenceHasNoAlternativesAndNoDisclosure() {
        val layout = curationSentenceLayout(candidate(sentenceCount = 1), "s-0")

        assertTrue(layout.alternatives.isEmpty())
        assertFalse(layout.disclose)
    }

    private fun candidate(sentenceCount: Int): CurationCandidate =
        CurationCandidate(
            candidateId = "candidate",
            minedForm = "食べる",
            surface = "食べる",
            lemma = "食べる",
            reading = "たべる",
            expressionReading = "たべる",
            partOfSpeech = null,
            frequencyRank = null,
            occurrenceCount = 1L,
            defaultSentenceId = "s-0",
            sentences =
                (0 until sentenceCount).map { index ->
                    CurationSentence(
                        sentenceId = "s-$index",
                        sentence = "Sentence $index",
                        sentenceFurigana = "Sentence $index",
                        sentenceReading = "Sentence $index",
                        startTime = 0.0,
                        endTime = 1.0,
                        duration = 1.0,
                    )
                },
        )
}
