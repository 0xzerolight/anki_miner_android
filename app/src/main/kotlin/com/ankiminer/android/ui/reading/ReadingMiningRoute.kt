package com.ankiminer.android.ui.reading

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.vm.ReadingMiningViewModel

internal val READING_SOURCE_MIME_TYPES =
    arrayOf(
        "text/plain",
        "text/*",
        "application/epub+zip",
        "application/x-subrip",
        "application/x-ass",
        "application/x-ssa",
        "application/json",
        "application/octet-stream",
        // A self-contained Mokuro archive (.cbz/.zip with its .mokuro inside)
        // is a valid single reading source; mirror the archive picker's MIME
        // set so those files are selectable here too.
        "application/zip",
        "application/x-zip-compressed",
        "application/x-cbz",
        "application/vnd.comicbook+zip",
    )

internal val MOKURO_ARCHIVE_MIME_TYPES =
    arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        // OpenDocument exact-matches provider-reported MIME. .cbz is reported as the
        // legacy application/x-cbz by most providers and the newer vnd.comicbook+zip by
        // others; list both so the file is selectable regardless of which one is used.
        "application/x-cbz",
        "application/vnd.comicbook+zip",
        "application/octet-stream",
    )

@Composable
fun ReadingMiningRoute(
    viewModel: ReadingMiningViewModel,
    onReturnToActiveRun: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sourcePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.onSourcePicked(it.toString()) }
        }
    val archivePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.onArchivePicked(it.toString()) }
        }

    ReadingMiningScreen(
        state = state,
        onPickSource = { sourcePicker.launch(READING_SOURCE_MIME_TYPES) },
        onPickArchive = { archivePicker.launch(MOKURO_ARCHIVE_MIME_TYPES) },
        onClearSource = viewModel::clearSource,
        onClearArchive = viewModel::clearArchive,
        onSeriesNameChanged = viewModel::onSubtitleSeriesNameChanged,
        onDismissDocumentError = viewModel::dismissDocumentError,
        onDismissCommandError = viewModel::dismissCommandError,
        onStart = viewModel::start,
        onFocusCandidate = viewModel::focusCandidate,
        onSetCandidateSelected = viewModel::setCandidateSelected,
        onSetSelectionForVisible = viewModel::setSelectionForVisible,
        onSetSelectionForPage = viewModel::setSelectionForPage,
        onReconcileFocus = viewModel::reconcileCurationFocus,
        onSelectSentence = viewModel::selectSentence,
        onConfirmCuration = viewModel::confirmCuration,
        onCancel = viewModel::cancel,
        onRetry = viewModel::retry,
        onReset = viewModel::reset,
        onReturnToActiveRun = onReturnToActiveRun,
        modifier = modifier,
    )
}
