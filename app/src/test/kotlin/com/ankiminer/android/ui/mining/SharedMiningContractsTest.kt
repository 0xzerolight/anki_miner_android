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
    fun deselectingFocusedCandidateCollapsesItsSentences() {
        val request = curationRequest(page = CurationPage(0, 2, 0, 2))
        val initial = request.defaultCurationDraft()

        val deselected = initial.toggleCandidate(request, request.candidates.single().candidateId)

        assertTrue(deselected.selectedCandidateIds.isEmpty())
        assertNull(deselected.focusedCandidateId)
    }

    @Test
    fun selectingExcludedCandidateFocusesOnlyThatCandidate() {
        val request = curationRequest(page = CurationPage(0, 2, 0, 2))
        val excluded = request.defaultCurationDraft().selectAll(request, selected = false)

        val selected = excluded.toggleCandidate(request, request.candidates.single().candidateId)

        assertEquals(setOf("candidate-1"), selected.selectedCandidateIds)
        assertEquals("candidate-1", selected.focusedCandidateId)
    }

    @Test
    fun candidateSearchFilterAndSortNeverMutateSelection() {
        val selected = candidate("selected", "猫", frequency = 10, occurrences = 2)
        val excluded = candidate("excluded", "犬", frequency = 2, occurrences = 8)
        val unknown = candidate("unknown", "鳥", frequency = null, occurrences = 1)
        val selectedIds = setOf(selected.candidateId, unknown.candidateId)

        assertEquals(
            listOf("excluded", "selected", "unknown"),
            curateCandidates(
                candidates = listOf(selected, excluded, unknown),
                selectedCandidateIds = selectedIds,
                query = "",
                filter = CurationFilter.ALL,
                sort = CurationSort.FREQUENCY,
            ).map(CurationCandidate::candidateId),
        )
        assertEquals(
            listOf("selected", "unknown"),
            curateCandidates(
                candidates = listOf(selected, excluded, unknown),
                selectedCandidateIds = selectedIds,
                query = "",
                filter = CurationFilter.SELECTED,
                sort = CurationSort.OCCURRENCES,
            ).map(CurationCandidate::candidateId),
        )
        assertEquals(
            listOf("excluded"),
            curateCandidates(
                candidates = listOf(selected, excluded, unknown),
                selectedCandidateIds = selectedIds,
                query = "いぬ",
                filter = CurationFilter.EXCLUDED,
                sort = CurationSort.OCCURRENCES,
            ).map(CurationCandidate::candidateId),
        )
        assertEquals(setOf("selected", "unknown"), selectedIds)
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
        return CurationRequest(
            runId = "run",
            requestId = "request",
            candidates =
                listOf(
                    candidate("candidate-1", "猫", frequency = 1, occurrences = 1),
                ),
            page = page,
        )
    }

    private fun candidate(
        id: String,
        form: String,
        frequency: Long?,
        occurrences: Long,
    ): CurationCandidate {
        val reading =
            when (form) {
                "犬" -> "いぬ"
                "鳥" -> "とり"
                else -> "ねこ"
            }
        val sentence =
            CurationSentence(
                sentenceId = "sentence-$id",
                sentence = "$form だ。",
                sentenceFurigana = "$form だ。",
                sentenceReading = "${reading}だ",
                startTime = 0.0,
                endTime = 1.0,
                duration = 1.0,
            )
        return CurationCandidate(
            candidateId = id,
            minedForm = form,
            surface = form,
            lemma = form,
            reading = reading,
            expressionReading = reading,
            partOfSpeech = "名詞",
            frequencyRank = frequency,
            occurrenceCount = occurrences,
            defaultSentenceId = sentence.sentenceId,
            sentences = listOf(sentence),
        )
    }
}
