package com.ankiminer.android.mining

import com.ankiminer.android.media.SafSelectionSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
                ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
                failureIsTransient = false,
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
    fun stagedProgressComposesWithinItsOwnBandInsteadOfRestarting() {
        // The engine stopped blending stages into one percentage, so each stage
        // restarts the item counts. Without composition a five-stage run would
        // drive the bar 0..1 five times.
        val firstStageHalf =
            MiningProgress(current = 1, total = 2, description = "Parsing", stage = MiningStage(1, 5, "Parsing"))
        assertEquals(0.1f, firstStageHalf.fraction)

        val lastStageDone =
            MiningProgress(current = 4, total = 4, description = "Creating", stage = MiningStage(5, 5, "Creating"))
        assertEquals(1.0f, lastStageDone.fraction)

        // A stage boundary never moves the bar backwards: entering stage 3 sits at
        // the end of stage 2's band.
        val stageEntered =
            MiningProgress(current = 0, total = 0, description = "Extracting", stage = MiningStage(3, 5, "Extracting"))
        assertEquals(0.4f, stageEntered.fraction)

        // Unstaged progress keeps the raw item fraction.
        assertEquals(0.5f, MiningProgress(current = 1, total = 2, description = "Parsing").fraction)
    }

    @Test
    fun fractionNeverRendersBelowItsFloor() {
        // Stage 3/5 fresh sub-cycle restarting at 0 must plateau at the floor, not jump back.
        val progress =
            MiningProgress(0, 10, "audio", stage = MiningStage(3, 5, "Media"), fractionFloor = 0.6f)

        assertEquals(0.6f, progress.fraction)
    }

    @Test
    fun zeroTotalStageWithStageCompleteFillsItsBand() {
        val progress =
            MiningProgress(0, 0, "parse", stage = MiningStage(3, 5, "Media"), stageComplete = true)

        assertEquals(0.6f, progress.fraction!!, 1e-6f)
    }

    @Test
    fun withinStageOverflowClampsToTheBandEndInsteadOfThrowing() {
        val progress = MiningProgress(12, 10, "media", stage = MiningStage(3, 5, "Media"))

        assertEquals(0.6f, progress.fraction!!, 1e-6f) // (3-1)*0.2 + 1.0*0.2
    }

    @Test
    fun stagelessOverflowClampsToOne() {
        assertEquals(1f, MiningProgress(12, 10, "copy").fraction)
    }

    @Test
    fun stagelessDeterminateProgressHonorsItsFloor() {
        val progress = MiningProgress(0, 10, "copy", fractionFloor = 0.6f)

        assertEquals(0.6f, progress.fraction)
    }

    @Test
    fun stagelessZeroTotalStaysIndeterminateDespiteAFloor() {
        // Flooring null into a number would render a determinate bar for work
        // whose size is unknown.
        assertNull(MiningProgress(0, 0, "copy", fractionFloor = 0.6f).fraction)
    }

    @Test
    fun stageRejectsAnIndexOutsideItsTotal() {
        assertThrows(IllegalArgumentException::class.java) { MiningStage(0, 5, "Parsing") }
        assertThrows(IllegalArgumentException::class.java) { MiningStage(6, 5, "Parsing") }
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

    @Test
    fun foregroundRunIdentityRetainsWorkKindBeforePythonRegisters() {
        val token = MiningCancellationToken("cancel_0123456789abcdef0123456789abcdef")

        MiningRunKind.entries.forEach { kind ->
            assertEquals(
                kind,
                MiningRunKind.fromForegroundRunId(token.foregroundRunId(kind)),
            )
        }
        assertNull(MiningRunKind.fromForegroundRunId(token.value))
        assertNull(MiningRunKind.fromForegroundRunId("video-cancel_invalid"))
    }

    @Test
    fun miningRunKindWireValuesRemainStable() {
        assertEquals(
            setOf("video", "reading", "audio"),
            MiningRunKind.entries.map { it.wireValue }.toSet(),
        )
    }

    @Test
    fun miningLanesMapToExpectedRunKindsAndSafSlots() {
        assertEquals(false, MiningLane.VIDEO.audioOnly)
        assertEquals(MiningRunKind.VIDEO, MiningLane.VIDEO.runKind)
        assertEquals(SafSelectionSlot.VIDEO, MiningLane.VIDEO.documentSlot)
        assertEquals(SafSelectionSlot.VIDEO_SUBTITLE, MiningLane.VIDEO.subtitleSlot)

        assertEquals(true, MiningLane.AUDIO.audioOnly)
        assertEquals(MiningRunKind.AUDIO, MiningLane.AUDIO.runKind)
        assertEquals(SafSelectionSlot.AUDIO, MiningLane.AUDIO.documentSlot)
        assertEquals(SafSelectionSlot.AUDIO_SUBTITLE, MiningLane.AUDIO.subtitleSlot)
    }

    @Test
    fun miningLanesUseDisjointSafSlots() {
        assertNotEquals(MiningLane.VIDEO.documentSlot, MiningLane.AUDIO.documentSlot)
        assertNotEquals(MiningLane.VIDEO.subtitleSlot, MiningLane.AUDIO.subtitleSlot)
    }

    @Test
    fun curationSentencePageContextDefaultsToNullForNonMangaCallSites() {
        assertNull(request().candidates.single().sentences.single().pageContext)
    }

    @Test
    fun curationPageContextRejectsAnEmptyImageEntry() {
        assertThrows(IllegalArgumentException::class.java) {
            CurationPageContext(
                imageEntry = "",
                blockBox = CurationBlockBox(0, 0, 1, 1),
                locationLabel = "Page 1",
            )
        }
    }

    @Test
    fun curatingPageImageDefaultsToNullForVideoAndAudioLanes() {
        assertNull(MiningRunState.Curating(request()).pageImage)
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
            ankiWriteState = AnkiWriteState.NOTE_WRITE_CONFIRMED,
            failureIsTransient = false,
        )
}
