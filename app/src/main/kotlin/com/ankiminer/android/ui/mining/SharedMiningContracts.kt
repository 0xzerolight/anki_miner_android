package com.ankiminer.android.ui.mining

import com.ankiminer.android.anki.generated.UnicodeContractV151
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationRequest
import com.ankiminer.android.mining.CurationSelection
import com.ankiminer.android.mining.CurationSessionState
import java.nio.charset.StandardCharsets
import java.util.Locale

internal const val MAX_RESULT_SUMMARY_ITEMS = 100
internal const val MAX_RESULT_ERROR_LINES = 50
internal const val RESULT_ISSUE_PREVIEW_COUNT = 3
internal const val MAX_SAVEABLE_QUERY_LENGTH = 1024

internal fun String.boundedSaveableQuery(): String = take(MAX_SAVEABLE_QUERY_LENGTH)

internal data class BoundedResultItems<T>(
    val items: List<T>,
    val remainingCount: Int,
)

internal fun <T> List<T>.boundedResultItems(maximum: Int): BoundedResultItems<T> {
    require(maximum >= 0)
    return BoundedResultItems(
        items = take(maximum),
        remainingCount = (size - maximum).coerceAtLeast(0),
    )
}

internal data class SharedCurationDraft(
    val runId: String,
    val requestId: String,
    val pageIndex: Long?,
    val selectedCandidateIds: Set<String>,
    val sentenceIds: Map<String, String>,
    val focusedCandidateId: String?,
    val knownCandidateIds: Set<String> = emptySet(),
) {
    val selectedCount: Int
        get() = selectedCandidateIds.size

    fun matches(request: CurationRequest): Boolean =
        runId == request.runId &&
            requestId == request.requestId &&
            pageIndex == request.page?.pageIndex

    fun forRequest(request: CurationRequest): SharedCurationDraft =
        if (matches(request)) this else request.defaultCurationDraft()

    /** Moves the detail without touching inclusion. Row taps land here. */
    fun focusCandidate(
        request: CurationRequest,
        candidateId: String?,
    ): SharedCurationDraft {
        require(candidateId == null || request.candidates.any { it.candidateId == candidateId })
        return forRequest(request).copy(focusedCandidateId = candidateId)
    }

    /**
     * Changes inclusion without touching focus. The checkbox lands here, so inspecting a candidate
     * can no longer exclude it as a side effect.
     */
    fun setCandidateSelected(
        request: CurationRequest,
        candidateId: String,
        selected: Boolean,
    ): SharedCurationDraft {
        require(request.candidates.any { it.candidateId == candidateId })
        val current = forRequest(request)
        val ids = current.selectedCandidateIds.toMutableSet()
        if (selected && candidateId !in current.knownCandidateIds) {
            ids.add(candidateId)
        } else {
            ids.remove(candidateId)
        }
        return current.copy(selectedCandidateIds = ids - current.knownCandidateIds)
    }

    /**
     * Stages a known/ignore-list mark. Writes NOTHING — Python accumulates the marks and commits
     * them only when the final page resolves successfully, so abandoning a review at any point
     * leaves the list untouched.
     *
     * Marking also excludes the row from this run: the engine filtered the known list before
     * curation, so the row cannot disappear, and leaving it selected would build the very card the
     * user just declined.
     */
    fun markKnown(
        request: CurationRequest,
        candidateId: String,
        known: Boolean,
    ): SharedCurationDraft {
        require(request.candidates.any { it.candidateId == candidateId })
        val current = forRequest(request)
        val marks = current.knownCandidateIds.toMutableSet()
        if (known) marks.add(candidateId) else marks.remove(candidateId)
        return current.copy(
            knownCandidateIds = marks,
            selectedCandidateIds = current.selectedCandidateIds - candidateId,
        )
    }

    /**
     * Applies a bulk change to exactly the candidates the user can see. Selections outside
     * [visibleCandidateIds] are preserved: a filtered "Deselect all" must never silently discard
     * work on rows the current search or filter is hiding.
     */
    fun setSelectionForVisible(
        request: CurationRequest,
        visibleCandidateIds: Collection<String>,
        selected: Boolean,
    ): SharedCurationDraft {
        val candidateIds = request.candidates.mapTo(mutableSetOf()) { it.candidateId }
        val subset = visibleCandidateIds.filterTo(linkedSetOf()) { it in candidateIds }
        val current = forRequest(request)
        return current.copy(
            selectedCandidateIds =
                if (selected) {
                    (current.selectedCandidateIds + subset) - current.knownCandidateIds
                } else {
                    (current.selectedCandidateIds - subset) - current.knownCandidateIds
                },
        )
    }

    /**
     * Restores `focusedCandidateId == null || focusedCandidateId in visibleCandidateIds`.
     *
     * [previousVisibleIds] is the ordering captured *before* the change, because once a row leaves
     * the projection there is no anchor left to search from. Falls back to the next visible
     * candidate, then the previous one, then null — never silently back to the first row.
     */
    fun reconcileFocus(
        visibleCandidateIds: List<String>,
        previousVisibleIds: List<String> = visibleCandidateIds,
    ): SharedCurationDraft {
        val focused = focusedCandidateId ?: return this
        if (focused in visibleCandidateIds) return this
        val anchor = previousVisibleIds.indexOf(focused)
        if (anchor < 0) return copy(focusedCandidateId = null)
        val next = previousVisibleIds.drop(anchor + 1).firstOrNull { it in visibleCandidateIds }
        val previous = previousVisibleIds.take(anchor).lastOrNull { it in visibleCandidateIds }
        return copy(focusedCandidateId = next ?: previous)
    }

    fun selectSentence(
        request: CurationRequest,
        candidateId: String,
        sentenceId: String,
    ): SharedCurationDraft? {
        val candidate =
            request.candidates.singleOrNull { it.candidateId == candidateId }
                ?: return null
        if (candidate.sentences.none { it.sentenceId == sentenceId }) return null
        val current = forRequest(request)
        return current.copy(sentenceIds = current.sentenceIds + (candidateId to sentenceId))
    }

    fun selections(request: CurationRequest): List<CurationSelection> {
        val current = forRequest(request)
        return request.candidates.mapNotNull { candidate ->
            if (
                candidate.candidateId !in current.selectedCandidateIds ||
                candidate.candidateId in current.knownCandidateIds
            ) {
                return@mapNotNull null
            }
            CurationSelection(
                candidateId = candidate.candidateId,
                sentenceId = current.sentenceIds.getValue(candidate.candidateId),
            )
        }
    }
}

internal fun CurationRequest.defaultCurationDraft(): SharedCurationDraft =
    SharedCurationDraft(
        runId = runId,
        requestId = requestId,
        pageIndex = page?.pageIndex,
        selectedCandidateIds = candidates.mapTo(linkedSetOf()) { it.candidateId },
        sentenceIds = candidates.associate { it.candidateId to it.defaultSentenceId },
        focusedCandidateId = null,
        knownCandidateIds = emptySet(),
    )

internal fun CurationSessionState.draftFor(
    request: CurationRequest,
): SharedCurationDraft? {
    if (
        runId != request.runId ||
        requestId != request.requestId ||
        pageIndex != request.page?.pageIndex
    ) {
        return null
    }
    val candidateIds = request.candidates.mapTo(linkedSetOf()) { it.candidateId }
    if (
        !candidateIds.containsAll(selectedCandidateIds) ||
        !candidateIds.containsAll(knownCandidateIds) ||
        selectedCandidateIds.any { it in knownCandidateIds }
    ) {
        return null
    }
    val validSentenceIds =
        request.candidates.associate { candidate ->
            candidate.candidateId to candidate.sentences.mapTo(hashSetOf()) { it.sentenceId }
        }
    if (
        sentenceIds.keys != candidateIds ||
        sentenceIds.any { (candidateId, sentenceId) ->
            sentenceId !in validSentenceIds.getValue(candidateId)
        } ||
        (focusedCandidateId != null && focusedCandidateId !in candidateIds)
    ) {
        return null
    }
    return SharedCurationDraft(
        runId = runId,
        requestId = requestId,
        pageIndex = pageIndex,
        selectedCandidateIds = selectedCandidateIds.toSet(),
        sentenceIds = sentenceIds.toMap(),
        focusedCandidateId = focusedCandidateId,
        knownCandidateIds = knownCandidateIds.toSet(),
    )
}

internal fun SharedCurationDraft.toCurationSessionState(
    previousPageSelectedCount: Int,
): CurationSessionState =
    CurationSessionState(
        runId = runId,
        requestId = requestId,
        pageIndex = pageIndex,
        selectedCandidateIds = selectedCandidateIds.toSet(),
        sentenceIds = sentenceIds.toMap(),
        focusedCandidateId = focusedCandidateId,
        previousPageSelectedCount = previousPageSelectedCount,
        knownCandidateIds = knownCandidateIds.toSet(),
    )

internal enum class CurationFilter {
    ALL,
    SELECTED,
    EXCLUDED,
}

internal enum class CurationSort {
    FREQUENCY,
    OCCURRENCES,
}

internal data class CurationBulkSelectionScope(
    val visibleCandidateIds: List<String>,
    val pageCandidateCount: Int?,
) {
    val visibleCount: Int
        get() = visibleCandidateIds.size
}

internal fun curationBulkSelectionScope(
    visibleCandidateIds: List<String>,
    pageCandidateIds: List<String>,
    knownCandidateIds: Set<String>,
): CurationBulkSelectionScope {
    val selectableVisible = visibleCandidateIds.filterNot(knownCandidateIds::contains)
    val selectablePageCount = pageCandidateIds.count { it !in knownCandidateIds }
    return CurationBulkSelectionScope(
        visibleCandidateIds = selectableVisible,
        pageCandidateCount = selectablePageCount.takeIf { selectableVisible.size < it },
    )
}

/**
 * Pure presentation projection. Candidate identity and selection storage stay untouched while
 * local search/filter/sort state changes.
 */
internal fun curateCandidates(
    candidates: List<CurationCandidate>,
    selectedCandidateIds: Set<String>,
    query: String,
    filter: CurationFilter,
    sort: CurationSort,
): List<CurationCandidate> {
    val normalizedQuery = query.trim().normalizedCurationSearchText()
    val filtered =
        candidates.asSequence()
            .filter { candidate ->
                when (filter) {
                    CurationFilter.ALL -> true
                    CurationFilter.SELECTED -> candidate.candidateId in selectedCandidateIds
                    CurationFilter.EXCLUDED -> candidate.candidateId !in selectedCandidateIds
                }
            }.filter { candidate ->
                normalizedQuery.isEmpty() ||
                    candidate.searchableCurationText().contains(normalizedQuery)
            }
    val comparator =
        when (sort) {
            CurationSort.FREQUENCY ->
                compareBy<CurationCandidate>(
                    { it.frequencyRank == null },
                    { it.frequencyRank ?: Long.MAX_VALUE },
                    { it.minedForm },
                )
            CurationSort.OCCURRENCES ->
                compareByDescending<CurationCandidate> { it.occurrenceCount }
                    .thenBy { it.frequencyRank ?: Long.MAX_VALUE }
                    .thenBy { it.minedForm }
        }
    return filtered.sortedWith(comparator).toList()
}

private fun CurationCandidate.searchableCurationText(): String =
    listOfNotNull(
        minedForm,
        surface,
        lemma,
        reading,
        expressionReading,
        partOfSpeech,
    ).joinToString(separator = "\n")
        .normalizedCurationSearchText()

private fun String.normalizedCurationSearchText(): String =
    (UnicodeContractV151.normalizeNfc(this) ?: this).lowercase(Locale.ROOT)

internal enum class MiningPendingAction {
    START,
    CURATION,
    CANCEL,
    RESET,
}

internal data class MiningPendingState(
    val start: Boolean = false,
    val curation: Boolean = false,
    val cancel: Boolean = false,
    val reset: Boolean = false,
) {
    fun begin(action: MiningPendingAction): MiningPendingState = set(action, true)

    fun complete(action: MiningPendingAction): MiningPendingState = set(action, false)

    fun beginRetry(): MiningPendingState = copy(start = true, reset = true)

    fun afterTerminalState(): MiningPendingState =
        copy(start = false, curation = false, cancel = false)

    private fun set(
        action: MiningPendingAction,
        value: Boolean,
    ): MiningPendingState =
        when (action) {
            MiningPendingAction.START -> copy(start = value)
            MiningPendingAction.CURATION -> copy(curation = value)
            MiningPendingAction.CANCEL -> copy(cancel = value)
            MiningPendingAction.RESET -> copy(reset = value)
        }
}

internal data class SavedDocumentSelection(
    val uri: String,
    val displayName: String,
)

internal fun restoredDocumentSelection(
    uri: String?,
    displayName: String?,
): SavedDocumentSelection? {
    if (uri.isNullOrBlank() || displayName.isNullOrBlank()) return null
    return SavedDocumentSelection(uri = uri, displayName = displayName)
}

internal enum class DocumentReadKind {
    VIDEO,
    SUBTITLES,
    DOCUMENT,
}

internal enum class DocumentReadCopy {
    VIDEO,
    VIDEO_NAMED,
    SUBTITLES,
    SUBTITLES_NAMED,
    DOCUMENT,
    DOCUMENT_NAMED,
}

internal data class DocumentReadProgress(
    val copy: DocumentReadCopy,
    val displayName: String?,
)

internal fun documentReadProgress(
    kind: DocumentReadKind,
    displayName: String?,
): DocumentReadProgress =
    displayName?.takeIf(::isSafeProgressDisplayName).let { safeDisplayName ->
        DocumentReadProgress(
            copy =
                when (kind) {
                    DocumentReadKind.VIDEO ->
                        if (safeDisplayName == null) {
                            DocumentReadCopy.VIDEO
                        } else {
                            DocumentReadCopy.VIDEO_NAMED
                        }
                    DocumentReadKind.SUBTITLES ->
                        if (safeDisplayName == null) {
                            DocumentReadCopy.SUBTITLES
                        } else {
                            DocumentReadCopy.SUBTITLES_NAMED
                        }
                    DocumentReadKind.DOCUMENT ->
                        if (safeDisplayName == null) {
                            DocumentReadCopy.DOCUMENT
                        } else {
                            DocumentReadCopy.DOCUMENT_NAMED
                        }
                },
            displayName = safeDisplayName,
        )
    }

private fun isSafeProgressDisplayName(displayName: String): Boolean =
    displayName.isNotBlank() &&
        displayName != "." &&
        displayName != ".." &&
        !displayName.contains('/') &&
        !displayName.contains('\\') &&
        displayName.none { character -> Character.isISOControl(character) } &&
        displayName.toByteArray(StandardCharsets.UTF_8).size <= MAX_PROGRESS_NAME_UTF8_BYTES

private const val MAX_PROGRESS_NAME_UTF8_BYTES = 255
