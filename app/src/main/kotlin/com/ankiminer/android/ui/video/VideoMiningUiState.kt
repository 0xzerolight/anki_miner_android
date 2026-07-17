package com.ankiminer.android.ui.video

import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.MiningRunState

enum class DocumentSelectionError {
    VIDEO,
    SUBTITLE,
}

enum class MiningCommandError {
    START,
    CURATION,
    CANCEL,
    RESET,
}

data class DocumentSlotState(
    val document: SafDocument? = null,
    val isResolving: Boolean = false,
    val error: DocumentSelectionError? = null,
)

data class CurationCandidateUiState(
    val candidate: CurationCandidate,
    val selected: Boolean,
    val sentenceId: String,
)

data class CurationUiState(
    val runId: String,
    val requestId: String,
    val candidates: List<CurationCandidateUiState>,
    val page: CurationPage? = null,
) {
    val selectedCount: Int
        get() = candidates.count { it.selected }

    val isFinalPage: Boolean
        get() = page?.let { it.pageIndex == it.pageCount - 1 } ?: true
}

data class VideoMiningUiState(
    val video: DocumentSlotState = DocumentSlotState(),
    val subtitle: DocumentSlotState = DocumentSlotState(),
    val runState: MiningRunState = MiningRunState.Idle,
    val curation: CurationUiState? = null,
    val startPending: Boolean = false,
    val curationPending: Boolean = false,
    val cancelPending: Boolean = false,
    val resetPending: Boolean = false,
    val commandError: MiningCommandError? = null,
) {
    val canStart: Boolean
        get() =
            runState == MiningRunState.Idle &&
                video.document != null &&
                subtitle.document != null &&
                !video.isResolving &&
                !subtitle.isResolving &&
                !startPending
}
