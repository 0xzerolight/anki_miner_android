package com.ankiminer.android.ui.video

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.vm.VideoMiningViewModel

private val VIDEO_MIME_TYPES = arrayOf("video/*", "application/octet-stream")
private val SUBTITLE_MIME_TYPES =
    arrayOf(
        "application/x-subrip",
        "text/*",
        "application/octet-stream",
    )

@Composable
fun VideoMiningRoute(
    viewModel: VideoMiningViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val videoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.onVideoPicked(it.toString()) }
        }
    val subtitlePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.onSubtitlePicked(it.toString()) }
        }

    VideoMiningScreen(
        state = state,
        onPickVideo = { videoPicker.launch(VIDEO_MIME_TYPES) },
        onPickSubtitle = { subtitlePicker.launch(SUBTITLE_MIME_TYPES) },
        onClearVideo = viewModel::clearVideo,
        onClearSubtitle = viewModel::clearSubtitle,
        onDismissDocumentError = viewModel::dismissDocumentError,
        onDismissCommandError = viewModel::dismissCommandError,
        onStart = viewModel::start,
        onToggleCandidate = viewModel::toggleCandidate,
        onSelectAllCandidates = viewModel::selectAllCandidates,
        onSelectSentence = viewModel::selectSentence,
        onConfirmCuration = viewModel::confirmCuration,
        onCancel = viewModel::cancel,
        onRetry = viewModel::retry,
        onReset = viewModel::reset,
        modifier = modifier,
    )
}
