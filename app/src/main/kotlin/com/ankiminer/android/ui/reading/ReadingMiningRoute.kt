package com.ankiminer.android.ui.reading

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ankiminer.android.ui.mining.DocumentPickRequest
import com.ankiminer.android.ui.mining.OpenDocumentNear
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
    onMapFields: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sourcePicker =
        rememberLauncherForActivityResult(OpenDocumentNear()) { uri ->
            uri?.let { viewModel.onSourcePicked(it.toString()) }
        }
    val archivePicker =
        rememberLauncherForActivityResult(OpenDocumentNear()) { uri ->
            uri?.let { viewModel.onArchivePicked(it.toString()) }
        }

    ReadingMiningScreen(
        state = state,
        onPickSource = {
            sourcePicker.launch(DocumentPickRequest(READING_SOURCE_MIME_TYPES.asList(), state.source.document?.uri))
        },
        onPickArchive = {
            archivePicker.launch(
                DocumentPickRequest(
                    MOKURO_ARCHIVE_MIME_TYPES.asList(),
                    state.archive.document?.uri ?: state.source.document?.uri,
                ),
            )
        },
        onClearSource = viewModel::clearSource,
        onClearArchive = viewModel::clearArchive,
        onSourceModeChanged = viewModel::onSourceModeChanged,
        onPastedTextChanged = viewModel::onPastedTextChanged,
        onClearPastedText = viewModel::clearPastedText,
        onSeriesNameChanged = viewModel::onSubtitleSeriesNameChanged,
        onDismissDocumentError = viewModel::dismissDocumentError,
        onDismissCommandError = viewModel::dismissCommandError,
        onStart = viewModel::start,
        onFocusCandidate = viewModel::focusCandidate,
        onSetCandidateSelected = viewModel::setCandidateSelected,
        onMarkCandidateKnown = viewModel::markCandidateKnown,
        onSetSelectionForVisible = viewModel::setSelectionForVisible,
        onReconcileFocus = viewModel::reconcileCurationFocus,
        onSelectSentence = viewModel::selectSentence,
        onConfirmCuration = viewModel::confirmCuration,
        onFinishCuration = viewModel::finishCuration,
        onCancel = viewModel::cancel,
        onRetry = viewModel::retry,
        onReset = viewModel::reset,
        onRequestUndo = viewModel::requestUndo,
        onConfirmUndo = viewModel::confirmUndo,
        onDismissUndoConfirmation = viewModel::dismissUndoConfirmation,
        onReturnToActiveRun = onReturnToActiveRun,
        onMapFields = onMapFields,
        modifier = modifier,
    )
}
