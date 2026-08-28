package com.ankiminer.android.ui.audio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.ui.mining.MediaMiningLabels
import com.ankiminer.android.ui.video.SUBTITLE_MIME_TYPES
import com.ankiminer.android.ui.video.VideoMiningScreen
import com.ankiminer.android.vm.MediaMiningViewModel

internal val AUDIO_MIME_TYPES = arrayOf("audio/*", "application/octet-stream")

@Composable
fun AudioMiningRoute(
    viewModel: MediaMiningViewModel,
    onReturnToActiveRun: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audioTrackPicker by viewModel.audioTrackPickerState.collectAsStateWithLifecycle()
    val audioPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.onVideoPicked(it.toString()) }
        }
    val transcriptPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.onSubtitlePicked(it.toString()) }
        }

    VideoMiningScreen(
        state = state,
        onPickVideo = { audioPicker.launch(AUDIO_MIME_TYPES) },
        onPickSubtitle = { transcriptPicker.launch(SUBTITLE_MIME_TYPES) },
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
        onSetSelectionForPage = viewModel::setSelectionForPage,
        onReconcileFocus = viewModel::reconcileCurationFocus,
        onSelectSentence = viewModel::selectSentence,
        onExpandSentencePrev = viewModel::expandSentencePrev,
        onExpandSentenceNext = viewModel::expandSentenceNext,
        onResetSentenceExpansion = viewModel::resetSentenceExpansion,
        onSetClipWindow = viewModel::setClipWindow,
        onResetClipWindow = viewModel::resetClipWindow,
        onConfirmCuration = viewModel::confirmCuration,
        onCancel = viewModel::cancel,
        onRetry = viewModel::retry,
        onReset = viewModel::reset,
        onRequestUndo = viewModel::requestUndo,
        onConfirmUndo = viewModel::confirmUndo,
        onDismissUndoConfirmation = viewModel::dismissUndoConfirmation,
        onReturnToActiveRun = onReturnToActiveRun,
        labels = MediaMiningLabels.AUDIO,
        modifier = modifier,
    )
}
