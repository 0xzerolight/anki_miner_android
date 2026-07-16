package com.ankiminer.android.mining

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MiningModelsTest {
    @Test
    fun processingResultRetainsEveryDesktopFieldWithoutNarrowingCounts() {
        val result =
            ProcessingResult(
                totalWordsFound = Long.MAX_VALUE,
                newWordsFound = 4_000_000_000L,
                cardsCreated = 3,
                errors = listOf("one error"),
                elapsedTime = 9.25,
                comprehensionPercentage = 87.5,
                cardIds = listOf(Long.MAX_VALUE),
                videoFile = "episode.mkv",
                subtitleFile = "episode.ass",
                minedForms = listOf("遣る"),
            )

        assertEquals(Long.MAX_VALUE, result.totalWordsFound)
        assertEquals(4_000_000_000L, result.newWordsFound)
        assertEquals(3L, result.cardsCreated)
        assertEquals(listOf("one error"), result.errors)
        assertEquals(9.25, result.elapsedTime, 0.0)
        assertEquals(87.5, result.comprehensionPercentage, 0.0)
        assertEquals(listOf(Long.MAX_VALUE), result.cardIds)
        assertEquals("episode.mkv", result.videoFile)
        assertEquals("episode.ass", result.subtitleFile)
        assertEquals(listOf("遣る"), result.minedForms)
    }

    @Test
    fun zeroTotalProgressIsIndeterminate() {
        val progress = MiningProgress(current = 0, total = 0, description = "Starting")

        assertNull(progress.fraction)
    }

    @Test
    fun terminalStageProgressAcceptsDesktopEmptyDescription() {
        val progress = MiningProgress(current = 100, total = 100, description = "")

        assertEquals(1.0f, progress.fraction)
        assertEquals("", progress.description)
    }

    @Test
    fun runIdentityAndTerminalStateCoverEveryRepositoryState() {
        val result = result()
        val failure = MiningFailure("stopped", retryable = true)

        assertNull(MiningRunState.Idle.runId)
        assertNull(MiningRunState.Starting(null, null).runId)
        assertEquals("run", MiningRunState.Starting("run", null).runId)
        assertEquals("run", MiningRunState.Curating(request()).runId)
        assertEquals(
            "run",
            MiningRunState.Running("run", MiningProgress(1, 1, "Done")).runId,
        )
        assertSame(result, MiningRunState.Success("run", result).result)
        assertSame(result, MiningRunState.Cancelled("run", result).result)
        assertSame(result, MiningRunState.Failed("run", failure, result).result)
        assertEquals(true, MiningRunState.Failed(null, failure, null).isTerminal)
    }

    private fun request(): CurationRequest =
        CurationRequest(
            runId = "run",
            requestId = "request",
            candidates =
                listOf(
                    CurationCandidate(
                        candidateId = "candidate",
                        minedForm = "食べる",
                        surface = "食べる",
                        lemma = "食べる",
                        reading = "たべる",
                        expressionReading = "たべる",
                        partOfSpeech = "動詞",
                        frequencyRank = 10,
                        occurrenceCount = 1,
                        defaultSentenceId = "sentence",
                        sentences =
                            listOf(
                                CurationSentence(
                                    sentenceId = "sentence",
                                    sentence = "魚を食べる。",
                                    sentenceFurigana = "魚を食べる。",
                                    sentenceReading = "さかなをたべる",
                                    startTime = 0.0,
                                    endTime = 1.0,
                                    duration = 1.0,
                                ),
                            ),
                    ),
                ),
        )

    private fun result(): ProcessingResult =
        ProcessingResult(
            totalWordsFound = 1,
            newWordsFound = 1,
            cardsCreated = 1,
            errors = emptyList(),
            elapsedTime = 1.0,
            comprehensionPercentage = 50.0,
            cardIds = listOf(1),
            videoFile = "video.mkv",
            subtitleFile = "subtitle.srt",
            minedForms = listOf("食べる"),
        )
}
