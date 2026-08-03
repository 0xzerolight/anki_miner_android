package com.ankiminer.android.ui.video

import androidx.compose.runtime.Immutable
import com.ankiminer.android.dictionary.CurationDefinition
import com.ankiminer.android.engine.SubtitleCue
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.ENGINE_DEFAULT_SUBTITLE_OFFSET
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.RuntimeWorkConflict

enum class DocumentSelectionError {
    VIDEO,
    AUDIO_TYPE,
    SUBTITLE,
}

enum class MiningCommandError {
    START,
    CURATION,
    CANCEL,
    RESET,
}

enum class TimingPreviewError {
    BUSY,
    TOKENIZER_REQUIRED,
    OPEN,
}

data class DocumentSlotState(
    val document: SafDocument? = null,
    val isResolving: Boolean = false,
    val error: DocumentSelectionError? = null,
)

@Immutable
data class CurationPlayerUiState(
    val videoPath: String,
    val cues: List<SubtitleCue>,
    val cuesUnavailable: Boolean,
    val audioOnly: Boolean = false,
)

@Immutable
data class CurationUiState(
    val runId: String,
    val requestId: String,
    val candidates: List<CurationCandidate>,
    val selectedCandidateIds: Set<String>,
    val sentenceIds: Map<String, String>,
    val focusedCandidateId: String?,
    val knownCandidateIds: Set<String> = emptySet(),
    val previousPageSelectedCount: Int = 0,
    val page: CurationPage? = null,
    val definition: CurationDefinition? = null,
    val player: CurationPlayerUiState? = null,
) {
    val selectedCount: Int
        get() = selectedCandidateIds.size

    val isFinalPage: Boolean
        get() = page?.let { it.pageIndex == it.pageCount - 1 } ?: true

    val hasSelectionToLose: Boolean
        get() = selectedCount > 0 || previousPageSelectedCount > 0
}

data class VideoMiningUiState(
    val video: DocumentSlotState = DocumentSlotState(),
    val subtitle: DocumentSlotState = DocumentSlotState(),
    val subtitleOffsetDraft: String = "",
    val subtitleOffsetDraftInvalid: Boolean = false,
    val effectiveSubtitleOffset: Double = ENGINE_DEFAULT_SUBTITLE_OFFSET,
    val runState: MiningRunState = MiningRunState.Idle,
    val curation: CurationUiState? = null,
    val startPending: Boolean = false,
    val curationPending: Boolean = false,
    val cancelPending: Boolean = false,
    val resetPending: Boolean = false,
    val commandError: MiningCommandError? = null,
    val runtimeConflict: RuntimeWorkConflict? = null,
    val timingPreviewPending: Boolean = false,
    val timingPreviewError: TimingPreviewError? = null,
) {
    val canStart: Boolean
        get() =
            runState == MiningRunState.Idle &&
                video.document != null &&
                subtitle.document != null &&
                !video.isResolving &&
                !subtitle.isResolving &&
                !subtitleOffsetDraftInvalid &&
                !startPending &&
                !timingPreviewPending &&
                runtimeConflict == null

    val canTestTiming: Boolean
        get() =
            runState == MiningRunState.Idle &&
                video.document != null &&
                subtitle.document != null &&
                !subtitleOffsetDraftInvalid &&
                !startPending &&
                !timingPreviewPending
}
