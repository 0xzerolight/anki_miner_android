package com.ankiminer.android.ui.mining

import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMiningContractsTest {
    @Test
    fun resultSummaryBoundsItemsAndReportsExactRemainder() {
        val summary = (1..101).toList().boundedResultItems(MAX_RESULT_SUMMARY_ITEMS)

        assertEquals((1..100).toList(), summary.items)
        assertEquals(1, summary.remainingCount)
    }

    @Test
    fun resultErrorsUseSmallerSharedLineBound() {
        val summary = (1..52).map { "error-$it" }.boundedResultItems(MAX_RESULT_ERROR_LINES)

        assertEquals(50, summary.items.size)
        assertEquals("error-50", summary.items.last())
        assertEquals(2, summary.remainingCount)
    }

    @Test
    fun emptyResultSummaryStaysEmptyWithoutSyntheticRemainder() {
        val summary = emptyList<String>().boundedResultItems(MAX_RESULT_SUMMARY_ITEMS)

        assertTrue(summary.items.isEmpty())
        assertEquals(0, summary.remainingCount)
    }

    @Test
    fun curationReducerPreservesPageAndEmptySelectionSemantics() {
        val request = curationRequest(page = CurationPage(1, 3, 2, 6))
        val draft = request.defaultCurationDraft().selectAll(request, selected = false)

        assertTrue(draft.matches(request))
        assertEquals(0, draft.selectedCount)
        assertEquals(emptyList<CurationSelection>(), draft.selections(request))
        assertEquals(1L, draft.pageIndex)
    }

    @Test
    fun curationReducerStartsFreshWhenPagedRequestAdvances() {
        val first = curationRequest(page = CurationPage(0, 2, 0, 2))
        val second = first.copy(page = CurationPage(1, 2, 1, 2))
        val deselected = first.defaultCurationDraft().selectAll(first, selected = false)

        val advanced = deselected.forRequest(second)

        assertFalse(deselected.matches(second))
        assertEquals(second.candidates.size, advanced.selectedCount)
        assertEquals(1L, advanced.pageIndex)
    }

    @Test
    fun pendingReducerClearsTerminalWorkWithoutClearingIndependentReset() {
        val pending =
            MiningPendingState(reset = true)
                .begin(MiningPendingAction.CURATION)
                .begin(MiningPendingAction.CANCEL)

        val terminal = pending.afterTerminalState()

        assertFalse(terminal.curation)
        assertFalse(terminal.cancel)
        assertTrue(terminal.reset)
    }

    @Test
    fun restoreMetadataRequiresUriAndDisplayNameWithoutCreatingDocument() {
        assertEquals(
            SavedDocumentSelection("content://provider/video", "episode.mkv"),
            restoredDocumentSelection("content://provider/video", "episode.mkv"),
        )
        assertNull(restoredDocumentSelection("", "episode.mkv"))
        assertNull(restoredDocumentSelection("content://provider/video", ""))
        assertNull(restoredDocumentSelection(null, "episode.mkv"))
        assertNull(restoredDocumentSelection("content://provider/video", null))
    }

    @Test
    fun progressCopySelectsSourceTypeAndIncludesOnlySafeFilename() {
        assertEquals(
            DocumentReadProgress(DocumentReadCopy.VIDEO_NAMED, "episode.mkv"),
            documentReadProgress(DocumentReadKind.VIDEO, "episode.mkv"),
        )
        assertEquals(
            DocumentReadProgress(DocumentReadCopy.SUBTITLES_NAMED, "episode.srt"),
            documentReadProgress(DocumentReadKind.SUBTITLES, "episode.srt"),
        )
        assertEquals(
            DocumentReadProgress(DocumentReadCopy.DOCUMENT_NAMED, "novel.epub"),
            documentReadProgress(DocumentReadKind.DOCUMENT, "novel.epub"),
        )
        assertEquals(
            DocumentReadProgress(DocumentReadCopy.DOCUMENT, null),
            documentReadProgress(DocumentReadKind.DOCUMENT, "folder/novel.epub"),
        )
        assertEquals(
            DocumentReadProgress(DocumentReadCopy.VIDEO, null),
            documentReadProgress(DocumentReadKind.VIDEO, null),
        )
    }

    private fun curationRequest(page: CurationPage): CurationRequest {
        val sentence =
            CurationSentence(
                sentenceId = "sentence-1",
                sentence = "猫だ。",
                sentenceFurigana = "猫だ。",
                sentenceReading = "ねこだ",
                startTime = 0.0,
                endTime = 1.0,
                duration = 1.0,
            )
        return CurationRequest(
            runId = "run",
            requestId = "request",
            candidates =
                listOf(
                    CurationCandidate(
                        candidateId = "candidate-1",
                        minedForm = "猫",
                        surface = "猫",
                        lemma = "猫",
                        reading = "ねこ",
                        expressionReading = "ねこ",
                        partOfSpeech = "名詞",
                        frequencyRank = 1,
                        occurrenceCount = 1,
                        defaultSentenceId = sentence.sentenceId,
                        sentences = listOf(sentence),
                    ),
                ),
            page = page,
        )
    }
}
