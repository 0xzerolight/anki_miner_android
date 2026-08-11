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
        val draft =
            request.defaultCurationDraft().setSelectionForVisible(
                request,
                request.candidates.map { it.candidateId },
                selected = false,
            )

        assertTrue(draft.matches(request))
        assertEquals(0, draft.selectedCount)
        assertEquals(emptyList<CurationSelection>(), draft.selections(request))
        assertEquals(1L, draft.pageIndex)
        assertNull(draft.focusedCandidateId)
    }

    @Test
    fun curationReducerStartsFreshWhenPagedRequestAdvances() {
        val first = curationRequest(page = CurationPage(0, 2, 0, 2))
        val second = first.copy(page = CurationPage(1, 2, 1, 2))
        val deselected =
            first.defaultCurationDraft().setSelectionForVisible(
                first,
                first.candidates.map { it.candidateId },
                selected = false,
            )

        val advanced = deselected.forRequest(second)

        assertFalse(deselected.matches(second))
        assertEquals(second.candidates.size, advanced.selectedCount)
        assertEquals(1L, advanced.pageIndex)
    }

    @Test
    fun markingACandidateKnownExcludesItFromTheRun() {
        val request = curationRequest()
        val firstId = request.candidates.first().candidateId

        val draft = request.defaultCurationDraft().markKnown(request, firstId, known = true)

        assertEquals(setOf(firstId), draft.knownCandidateIds)
        assertFalse(firstId in draft.selectedCandidateIds)
    }

    @Test
    fun unmarkingLeavesTheCandidateExcludedUntilTheUserReincludesIt() {
        val request = curationRequest()
        val firstId = request.candidates.first().candidateId

        val draft =
            request.defaultCurationDraft()
                .markKnown(request, firstId, known = true)
                .markKnown(request, firstId, known = false)

        assertTrue(draft.knownCandidateIds.isEmpty())
        assertFalse(firstId in draft.selectedCandidateIds)
        assertTrue(
            firstId in
                draft
                    .setCandidateSelected(request, firstId, selected = true)
                    .selectedCandidateIds,
        )
    }

    @Test
    fun markedCandidateCannotBeSelected() {
        val request = curationRequest()
        val firstId = request.candidates.first().candidateId

        val draft = request.defaultCurationDraft().markKnown(request, firstId, known = true)

        assertFalse(
            firstId in
                draft
                    .setCandidateSelected(request, firstId, selected = true)
                    .selectedCandidateIds,
        )
    }

    @Test
    fun bulkVisibleSelectionSkipsKnownCandidates() {
        val request = curationRequest()
        val firstId = request.candidates.first().candidateId

        val draft =
            request.defaultCurationDraft()
                .markKnown(request, firstId, known = true)
                .setSelectionForVisible(
                    request,
                    request.candidates.map { it.candidateId },
                    selected = true,
                )

        assertFalse(firstId in draft.selectedCandidateIds)
    }

    @Test
    fun bulkSelectionScopeCountsOnlyEligibleCandidates() {
        val scope =
            curationBulkSelectionScope(
                visibleCandidateIds = listOf("known", "visible"),
                pageCandidateIds = listOf("known", "visible", "hidden"),
                knownCandidateIds = setOf("known"),
            )

        assertEquals(listOf("visible"), scope.visibleCandidateIds)
        assertEquals(1, scope.visibleCount)
        assertEquals(2, scope.pageCandidateCount)
    }

    @Test
    fun oversizedSaveableQueryIsTruncatedAtTheInputBoundary() {
        val query = "猫".repeat(MAX_SAVEABLE_QUERY_LENGTH + 17)

        val bounded = query.boundedSaveableQuery()

        assertEquals(MAX_SAVEABLE_QUERY_LENGTH, bounded.length)
        assertEquals(query.take(MAX_SAVEABLE_QUERY_LENGTH), bounded)
    }

    @Test
    fun selectionsNeverIncludeAMarkedCandidate() {
        val request = curationRequest()
        val firstId = request.candidates.first().candidateId

        val draft = request.defaultCurationDraft().markKnown(request, firstId, known = true)

        assertTrue(draft.selections(request).none { it.candidateId == firstId })
    }

    @Test
    fun knownMarksSurviveASessionStateRoundTrip() {
        val request = curationRequest()
        val firstId = request.candidates.first().candidateId
        val draft = request.defaultCurationDraft().markKnown(request, firstId, known = true)

        assertEquals(
            draft,
            draft.toCurationSessionState(previousPageSelectedCount = 0).draftFor(request),
        )
    }

    @Test
    fun sessionStateNamingAnUnknownKnownCandidateIsRejected() {
        val request = curationRequest()
        val state =
            request.defaultCurationDraft()
                .toCurationSessionState(previousPageSelectedCount = 0)
                .copy(knownCandidateIds = setOf("candidate_${"f".repeat(32)}"))

        assertNull(state.draftFor(request))
    }

    @Test
    fun deselectingACandidateLeavesItsDetailOpen() {
        val request = curationRequest(page = CurationPage(0, 2, 0, 2))
        val candidateId = request.candidates.single().candidateId
        val initial = request.defaultCurationDraft().focusCandidate(request, candidateId)

        val deselected = initial.setCandidateSelected(request, candidateId, selected = false)

        assertTrue(deselected.selectedCandidateIds.isEmpty())
        assertEquals(candidateId, deselected.focusedCandidateId)
    }

    @Test
    fun focusingACandidateDoesNotChangeInclusion() {
        val request = curationRequest(page = CurationPage(0, 2, 0, 2))
        val candidateId = request.candidates.single().candidateId
        val excluded =
            request.defaultCurationDraft().setSelectionForVisible(
                request,
                listOf(candidateId),
                selected = false,
            )

        val focused = excluded.focusCandidate(request, candidateId)

        assertTrue(focused.selectedCandidateIds.isEmpty())
        assertEquals(candidateId, focused.focusedCandidateId)
    }

    @Test
    fun bulkChangeAppliesOnlyToVisibleCandidatesAndKeepsHiddenSelections() {
        val request =
            curationRequest(
                candidates =
                    listOf(
                        candidate("visible", "\u732b", frequency = 1, occurrences = 1),
                        candidate("hidden", "\u72ac", frequency = 2, occurrences = 1),
                    ),
            )
        val all = request.defaultCurationDraft()

        val deselected =
            all.setSelectionForVisible(request, listOf("visible"), selected = false)

        assertEquals(setOf("hidden"), deselected.selectedCandidateIds)
    }

    @Test
    fun focusFallsForwardThenBackThenClearsWhenItLeavesTheProjection() {
        val ids = listOf("a", "b", "c")
        val draft =
            SharedCurationDraft(
                runId = "run",
                requestId = "request",
                pageIndex = null,
                selectedCandidateIds = emptySet(),
                sentenceIds = emptyMap(),
                focusedCandidateId = "b",
            )

        assertEquals("c", draft.reconcileFocus(listOf("a", "c"), ids).focusedCandidateId)
        assertEquals("a", draft.reconcileFocus(listOf("a"), ids).focusedCandidateId)
        assertNull(draft.reconcileFocus(emptyList(), ids).focusedCandidateId)
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
    fun candidateSearchMatchesCanonicallyEquivalentJapaneseText() {
        val candidate = candidate("decomposed-query", "ば", frequency = 1, occurrences = 1)

        val matches =
            curateCandidates(
                candidates = listOf(candidate),
                selectedCandidateIds = emptySet(),
                query = "は\u3099",
                filter = CurationFilter.ALL,
                sort = CurationSort.FREQUENCY,
            )

        assertEquals(listOf(candidate), matches)
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

    private fun curationRequest(
        page: CurationPage? = null,
        candidates: List<CurationCandidate> =
            listOf(candidate("candidate-1", "猫", frequency = 1, occurrences = 1)),
    ): CurationRequest {
        return CurationRequest(
            runId = "run",
            requestId = "request",
            candidates = candidates,
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
