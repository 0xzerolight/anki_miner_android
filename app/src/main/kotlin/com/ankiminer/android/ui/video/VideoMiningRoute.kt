package com.ankiminer.android.ui.video

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.ui.mining.DocumentPickRequest
import com.ankiminer.android.ui.mining.OpenDocumentNear
import com.ankiminer.android.vm.MediaMiningViewModel

private val VIDEO_MIME_TYPES = arrayOf("video/*", "application/octet-stream")
internal val SUBTITLE_MIME_TYPES =
    arrayOf(
        "application/x-subrip",
        "application/x-ass",
        "application/x-ssa",
        "text/*",
        "application/octet-stream",
    )

@Composable
fun VideoMiningRoute(
    viewModel: MediaMiningViewModel,
    onReturnToActiveRun: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audioTrackPicker by viewModel.audioTrackPickerState.collectAsStateWithLifecycle()
    val videoPicker =
        rememberLauncherForActivityResult(OpenDocumentNear()) { uri ->
            uri?.let { viewModel.onVideoPicked(it.toString()) }
        }
    val subtitlePicker =
        rememberLauncherForActivityResult(OpenDocumentNear()) { uri ->
            uri?.let { viewModel.onSubtitlePicked(it.toString()) }
        }
    val secondarySubtitlePicker =
        rememberLauncherForActivityResult(OpenDocumentNear()) { uri ->
            uri?.let { viewModel.onSecondarySubtitlePicked(it.toString()) }
        }

    // Every picker opens in the folder of the file already chosen for this run.
    val nearDocumentUri = state.video.document?.uri ?: state.subtitle.document?.uri

    VideoMiningScreen(
        state = state,
        onPickVideo = { videoPicker.launch(DocumentPickRequest(VIDEO_MIME_TYPES.asList(), nearDocumentUri)) },
        onPickSubtitle = { subtitlePicker.launch(DocumentPickRequest(SUBTITLE_MIME_TYPES.asList(), nearDocumentUri)) },
        onClearVideo = viewModel::clearVideo,
        onClearSubtitle = viewModel::clearSubtitle,
        onDismissDocumentError = viewModel::dismissDocumentError,
        onDismissCommandError = viewModel::dismissCommandError,
        onDismissTimingPreviewError = viewModel::dismissTimingPreviewError,
        onSubtitleOffsetDraftChange = viewModel::setSubtitleOffsetDraft,
        onPickSecondarySubtitle = {
            secondarySubtitlePicker.launch(DocumentPickRequest(SUBTITLE_MIME_TYPES.asList(), nearDocumentUri))
        },
        onClearSecondarySubtitle = viewModel::clearSecondarySubtitle,
        onSecondarySubtitleOffsetDraftChange = viewModel::setSecondarySubtitleOffsetDraft,
        onTestTiming = viewModel::openTimingPreview,
        audioTrackPicker = audioTrackPicker,
        onAudioTracks = viewModel::openAudioTrackPicker,
        onSelectAudioTrack = viewModel::selectAudioTrack,
        onApplyAudioTrackPicker = viewModel::applyAudioTrackPicker,
        onDismissAudioTrackPicker = viewModel::dismissAudioTrackPicker,
        onDismissAudioTrackPickerError = viewModel::dismissAudioTrackPickerError,
        onStart = viewModel::start,
        onFocusCandidate = viewModel::focusCandidate,
        onSetCandidateSelected = viewModel::setCandidateSelected,
        onMarkCandidateKnown = viewModel::markCandidateKnown,
        onSetSelectionForVisible = viewModel::setSelectionForVisible,
        onReconcileFocus = viewModel::reconcileCurationFocus,
        onSelectSentence = viewModel::selectSentence,
        onExpandSentencePrev = viewModel::expandSentencePrev,
        onExpandSentenceNext = viewModel::expandSentenceNext,
        onResetSentenceExpansion = viewModel::resetSentenceExpansion,
        onSetClipWindow = viewModel::setClipWindow,
        onResetClipWindow = viewModel::resetClipWindow,
        onConfirmCuration = viewModel::confirmCuration,
        onFinishCuration = viewModel::finishCuration,
        onCancel = viewModel::cancel,
        onRetry = viewModel::retry,
        onReset = viewModel::reset,
        onRequestUndo = viewModel::requestUndo,
        onConfirmUndo = viewModel::confirmUndo,
        onDismissUndoConfirmation = viewModel::dismissUndoConfirmation,
        onReturnToActiveRun = onReturnToActiveRun,
        modifier = modifier,
    )
}
