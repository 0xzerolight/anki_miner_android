package com.ankiminer.android.ui.audio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.ui.mining.DocumentPickRequest
import com.ankiminer.android.ui.mining.MediaMiningLabels
import com.ankiminer.android.ui.mining.OpenDocumentNear
import com.ankiminer.android.ui.video.SUBTITLE_MIME_TYPES
import com.ankiminer.android.ui.video.VideoMiningScreen
import com.ankiminer.android.vm.MediaMiningViewModel

internal val AUDIO_MIME_TYPES = arrayOf("audio/*", "application/octet-stream")

@Composable
fun AudioMiningRoute(
    viewModel: MediaMiningViewModel,
    onReturnToActiveRun: (() -> Unit)? = null,
    onMapFields: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audioTrackPicker by viewModel.audioTrackPickerState.collectAsStateWithLifecycle()
    val audioPicker =
        rememberLauncherForActivityResult(OpenDocumentNear()) { uri ->
            uri?.let { viewModel.onVideoPicked(it.toString()) }
        }
    val transcriptPicker =
        rememberLauncherForActivityResult(OpenDocumentNear()) { uri ->
            uri?.let { viewModel.onSubtitlePicked(it.toString()) }
        }

    // Both pickers open in the folder of the file already chosen for this run.
    val nearDocumentUri = state.video.document?.uri ?: state.subtitle.document?.uri

    VideoMiningScreen(
        state = state,
        onPickVideo = { audioPicker.launch(DocumentPickRequest(AUDIO_MIME_TYPES.asList(), nearDocumentUri)) },
        onPickSubtitle = {
            transcriptPicker.launch(DocumentPickRequest(SUBTITLE_MIME_TYPES.asList(), nearDocumentUri))
        },
        onClearVideo = viewModel::clearVideo,
        onClearSubtitle = viewModel::clearSubtitle,
        onDismissDocumentError = viewModel::dismissDocumentError,
        onDismissCommandError = viewModel::dismissCommandError,
        onDismissTimingPreviewError = viewModel::dismissTimingPreviewError,
        onSubtitleOffsetDraftChange = viewModel::setSubtitleOffsetDraft,
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
        onRequestUndo = viewModel::requestUndo,
        onConfirmUndo = viewModel::confirmUndo,
        onDismissUndoConfirmation = viewModel::dismissUndoConfirmation,
        onReturnToActiveRun = onReturnToActiveRun,
        onMapFields = onMapFields,
        labels = MediaMiningLabels.AUDIO,
        modifier = modifier,
    )
}
