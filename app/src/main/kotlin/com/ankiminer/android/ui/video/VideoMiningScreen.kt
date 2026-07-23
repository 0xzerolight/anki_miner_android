package com.ankiminer.android.ui.video

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.ui.mining.CurationCandidateHeader
import com.ankiminer.android.ui.mining.CurationControls
import com.ankiminer.android.ui.mining.CurationFilter
import com.ankiminer.android.ui.mining.CurationSentenceChoice
import com.ankiminer.android.ui.mining.CurationSort
import com.ankiminer.android.ui.mining.DocumentReadKind
import com.ankiminer.android.ui.mining.MiningFailureAction
import com.ankiminer.android.ui.mining.MiningFailureCard
import com.ankiminer.android.ui.mining.MiningProgressPanel
import com.ankiminer.android.ui.mining.MiningResultSource
import com.ankiminer.android.ui.mining.MiningSourceItem
import com.ankiminer.android.ui.mining.RuntimeConflictNotice
import com.ankiminer.android.ui.mining.SourcesCard
import com.ankiminer.android.ui.mining.StickyCurationActions
import com.ankiminer.android.ui.mining.curateCandidates
import com.ankiminer.android.ui.mining.miningResultItems
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.forwardButtonColors
import com.ankiminer.android.ui.theme.outlinedActionButtonColors

@Composable
fun VideoMiningScreen(
    state: VideoMiningUiState,
    onPickVideo: () -> Unit,
    onPickSubtitle: () -> Unit,
    onClearVideo: () -> Unit,
    onClearSubtitle: () -> Unit,
    onDismissDocumentError: (DocumentSelectionError) -> Unit,
    onDismissCommandError: () -> Unit,
    onStart: () -> Unit,
    onToggleCandidate: (String) -> Unit,
    onSelectAllCandidates: (Boolean) -> Unit,
    onSelectSentence: (String, String) -> Unit,
    onConfirmCuration: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onReturnToActiveRun: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val curation = state.curation
    var query by rememberSaveable(curation?.requestId) { mutableStateOf("") }
    var filterName by
        rememberSaveable(curation?.requestId) {
            mutableStateOf(CurationFilter.ALL.name)
        }
    var sortName by
        rememberSaveable(curation?.requestId) {
            mutableStateOf(CurationSort.FREQUENCY.name)
        }
    var resultDetailsExpanded by
        rememberSaveable(state.scrollTransitionKey()) {
            mutableStateOf(false)
        }
    val filter =
        remember(filterName) {
            CurationFilter.entries.firstOrNull { it.name == filterName } ?: CurationFilter.ALL
        }
    val sort =
        remember(sortName) {
            CurationSort.entries.firstOrNull { it.name == sortName } ?: CurationSort.FREQUENCY
        }
    val selectionProjectionKey =
        if (filter == CurationFilter.ALL) emptySet() else curation?.selectedCandidateIds.orEmpty()
    val visibleCandidates =
        remember(curation?.candidates, selectionProjectionKey, query, filter, sort) {
            curateCandidates(
                candidates = curation?.candidates.orEmpty(),
                selectedCandidateIds = curation?.selectedCandidateIds.orEmpty(),
                query = query,
                filter = filter,
                sort = sort,
            )
        }
    val selectedCandidateIds = curation?.selectedCandidateIds.orEmpty()
    val expandedCandidateId =
        curation?.focusedCandidateId
            ?.takeIf { focused ->
                focused in selectedCandidateIds &&
                    visibleCandidates.any { it.candidateId == focused }
            } ?: visibleCandidates.firstOrNull {
            it.candidateId in selectedCandidateIds
        }?.candidateId

    ResetMiningScrollOnTransition(state = state, listState = listState)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (state.runState is MiningRunState.Curating) {
                StickyCurationActions(
                    selectedCount = curation?.selectedCount ?: 0,
                    page = curation?.page,
                    isFinalPage = curation?.isFinalPage ?: true,
                    curationPending = state.curationPending,
                    cancelPending = state.cancelPending,
                    requiresCancelConfirmation = curation?.hasSelectionToLose == true,
                    commandErrorMessage =
                        state.commandError
                            ?.takeIf {
                                it == MiningCommandError.CURATION ||
                                    it == MiningCommandError.CANCEL
                            }?.message(),
                    confirmTestTag = VideoMiningTestTags.CONFIRM_CURATION,
                    cancelTestTag = VideoMiningTestTags.CANCEL,
                    onDismissCommandError = onDismissCommandError,
                    onConfirm = onConfirmCuration,
                    onCancel = onCancel,
                )
            }
        },
    ) { scaffoldPadding ->
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding)
                    .consumeWindowInsets(scaffoldPadding)
                    .testTag(VideoMiningTestTags.CONTENT),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val runState = state.runState) {
                MiningRunState.Idle ->
                    setupItems(
                        state = state,
                        onPickVideo = onPickVideo,
                        onPickSubtitle = onPickSubtitle,
                        onClearVideo = onClearVideo,
                        onClearSubtitle = onClearSubtitle,
                        onDismissDocumentError = onDismissDocumentError,
                        onDismissCommandError = onDismissCommandError,
                        onStart = onStart,
                        onReturnToActiveRun = onReturnToActiveRun,
                    )
                is MiningRunState.Starting ->
                    progressItems(
                        progress = runState.progress,
                        canCancel = runState.cancellationToken != null || runState.runId != null,
                        cancelPending = state.cancelPending,
                        cancelError = state.commandError == MiningCommandError.CANCEL,
                        onDismissCommandError = onDismissCommandError,
                        onCancel = onCancel,
                    )
                is MiningRunState.Curating ->
                    curationItems(
                        state = state,
                        visibleCandidates = visibleCandidates,
                        expandedCandidateId = expandedCandidateId,
                        query = query,
                        filter = filter,
                        sort = sort,
                        onQueryChanged = { query = it },
                        onFilterChanged = { filterName = it.name },
                        onSortChanged = { sortName = it.name },
                        onToggleCandidate = onToggleCandidate,
                        onSelectAllCandidates = onSelectAllCandidates,
                        onSelectSentence = onSelectSentence,
                    )
                is MiningRunState.Running ->
                    progressItems(
                        progress = runState.progress,
                        canCancel = true,
                        cancelPending = state.cancelPending,
                        cancelError = state.commandError == MiningCommandError.CANCEL,
                        onDismissCommandError = onDismissCommandError,
                        onCancel = onCancel,
                    )
                is MiningRunState.Success ->
                    terminalItems(
                        title = R.string.success_title,
                        result = runState.result,
                        videoDisplayName = state.video.document?.displayName,
                        subtitleDisplayName = state.subtitle.document?.displayName,
                        partial = false,
                        failed = false,
                        failureDetails = null,
                        canRetry = false,
                        busy = state.resetPending,
                        resetError = state.commandError == MiningCommandError.RESET,
                        detailsExpanded = resultDetailsExpanded,
                        onToggleDetails = {
                            resultDetailsExpanded = !resultDetailsExpanded
                        },
                        onDismissCommandError = onDismissCommandError,
                        onRetry = onRetry,
                        onReset = onReset,
                    )
                is MiningRunState.Cancelled ->
                    terminalItems(
                        title = R.string.cancelled_title,
                        result = runState.result,
                        videoDisplayName = state.video.document?.displayName,
                        subtitleDisplayName = state.subtitle.document?.displayName,
                        partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                        failed = false,
                        failureDetails = null,
                        canRetry = false,
                        busy = state.resetPending,
                        resetError = state.commandError == MiningCommandError.RESET,
                        detailsExpanded = resultDetailsExpanded,
                        onToggleDetails = {
                            resultDetailsExpanded = !resultDetailsExpanded
                        },
                        onDismissCommandError = onDismissCommandError,
                        onRetry = onRetry,
                        onReset = onReset,
                    )
                is MiningRunState.Failed ->
                    terminalItems(
                        title = R.string.failed_title,
                        result = runState.result,
                        videoDisplayName = state.video.document?.displayName,
                        subtitleDisplayName = state.subtitle.document?.displayName,
                        partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                        failed = true,
                        failureDetails = runState.failure.message,
                        canRetry =
                            runState.failure.retryable &&
                                state.video.document != null &&
                                state.subtitle.document != null,
                        busy = state.resetPending || state.startPending,
                        resetError = state.commandError == MiningCommandError.RESET,
                        detailsExpanded = resultDetailsExpanded,
                        onToggleDetails = {
                            resultDetailsExpanded = !resultDetailsExpanded
                        },
                        onDismissCommandError = onDismissCommandError,
                        onRetry = onRetry,
                        onReset = onReset,
                    )
            }
        }
    }
}

private fun LazyListScope.setupItems(
    state: VideoMiningUiState,
    onPickVideo: () -> Unit,
    onPickSubtitle: () -> Unit,
    onClearVideo: () -> Unit,
    onClearSubtitle: () -> Unit,
    onDismissDocumentError: (DocumentSelectionError) -> Unit,
    onDismissCommandError: () -> Unit,
    onStart: () -> Unit,
    onReturnToActiveRun: (() -> Unit)?,
) {
    item(key = "setup_header", contentType = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.video_mining_intro),
                style = MaterialTheme.typography.bodyLarge,
            )
            state.runtimeConflict?.let { conflict ->
                RuntimeConflictNotice(
                    text = stringResource(runtimeConflictMessage(conflict)),
                    onReturnToActiveRun =
                        onReturnToActiveRun.takeIf {
                            conflict == RuntimeWorkConflict.MINING
                        },
                )
            }
        }
    }
    item(key = "sources", contentType = "candidate") {
        SourcesCard(
            sources =
                listOf(
                    MiningSourceItem(
                        label = stringResource(R.string.video_file_label),
                        document = state.video.document,
                        isResolving = state.video.isResolving,
                        enabled = !state.startPending,
                        pickTestTag = VideoMiningTestTags.PICK_VIDEO,
                        clearTestTag = VideoMiningTestTags.CLEAR_VIDEO,
                        readKind = DocumentReadKind.VIDEO,
                        onPick = onPickVideo,
                        onClear = onClearVideo,
                    ),
                    MiningSourceItem(
                        label = stringResource(R.string.subtitle_file_label),
                        document = state.subtitle.document,
                        isResolving = state.subtitle.isResolving,
                        enabled = !state.startPending,
                        pickTestTag = VideoMiningTestTags.PICK_SUBTITLE,
                        clearTestTag = VideoMiningTestTags.CLEAR_SUBTITLE,
                        readKind = DocumentReadKind.SUBTITLES,
                        onPick = onPickSubtitle,
                        onClear = onClearSubtitle,
                    ),
                ),
        )
    }
    state.video.error?.let {
        item(key = "video_file_error", contentType = "actions") {
            MiningFailureCard(
                message = stringResource(R.string.video_file_error),
                primaryAction =
                    MiningFailureAction(
                        label = stringResource(R.string.dismiss_error),
                        onClick = { onDismissDocumentError(DocumentSelectionError.VIDEO) },
                    ),
            )
        }
    }
    state.subtitle.error?.let {
        item(key = "subtitle_file_error", contentType = "actions") {
            MiningFailureCard(
                message = stringResource(R.string.subtitle_file_error),
                primaryAction =
                    MiningFailureAction(
                        label = stringResource(R.string.dismiss_error),
                        onClick = { onDismissDocumentError(DocumentSelectionError.SUBTITLE) },
                    ),
            )
        }
    }
    item(key = "start", contentType = "actions") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                enabled = state.canStart,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(VideoMiningTestTags.START),
                colors = forwardButtonColors(),
            ) {
                Text(stringResource(R.string.start_mining))
            }
            if (state.commandError == MiningCommandError.START) {
                MiningFailureCard(
                    message = state.commandError.message(),
                    primaryAction =
                        MiningFailureAction(
                            label = stringResource(R.string.dismiss_error),
                            onClick = onDismissCommandError,
                        ),
                )
            }
        }
    }
}

private fun LazyListScope.progressItems(
    progress: MiningProgress?,
    canCancel: Boolean,
    cancelPending: Boolean,
    cancelError: Boolean,
    onDismissCommandError: () -> Unit,
    onCancel: () -> Unit,
) {
    item(key = "progress", contentType = "header") {
        MiningProgressPanel(
            progress = progress,
            testTag = VideoMiningTestTags.PROGRESS,
        )
    }
    if (canCancel) {
        item(key = "cancel", contentType = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MiningCancelButton(
                    cancelPending = cancelPending,
                    testTag = VideoMiningTestTags.CANCEL,
                    onCancel = onCancel,
                )
                if (cancelError) {
                    MiningFailureCard(
                        message = MiningCommandError.CANCEL.message(),
                        primaryAction =
                            MiningFailureAction(
                                label = stringResource(R.string.dismiss_error),
                                onClick = onDismissCommandError,
                            ),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.curationItems(
    state: VideoMiningUiState,
    visibleCandidates: List<CurationCandidate>,
    expandedCandidateId: String?,
    query: String,
    filter: CurationFilter,
    sort: CurationSort,
    onQueryChanged: (String) -> Unit,
    onFilterChanged: (CurationFilter) -> Unit,
    onSortChanged: (CurationSort) -> Unit,
    onToggleCandidate: (String) -> Unit,
    onSelectAllCandidates: (Boolean) -> Unit,
    onSelectSentence: (String, String) -> Unit,
) {
    val curation = state.curation ?: return
    val enabled = !state.curationPending && !state.cancelPending
    val allSelected =
        curation.candidates.isNotEmpty() &&
            curation.selectedCandidateIds.size == curation.candidates.size

    item(key = "curation_header", contentType = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.curation_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text =
                    stringResource(
                        if (curation.page == null) {
                            R.string.curation_selected_count
                        } else {
                            R.string.curation_selected_count_page
                        },
                        curation.selectedCount,
                        curation.candidates.size,
                    ),
                style = MaterialTheme.typography.bodyLarge,
            )
            curation.page?.let { page ->
                Text(
                    text =
                        stringResource(
                            R.string.curation_page_position,
                            page.pageIndex + 1,
                            page.pageCount,
                            page.candidateStart + 1,
                            page.candidateStart + curation.candidates.size,
                            page.totalCandidates,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (page.pageIndex > 0) {
                    Text(
                        text = stringResource(R.string.curation_previous_pages_saved),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
    item(key = "curation_controls", contentType = "actions") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onSelectAllCandidates(!allSelected) },
                enabled = curation.candidates.isNotEmpty() && enabled,
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .testTag(VideoMiningTestTags.SELECT_ALL),
                colors = outlinedActionButtonColors(),
                border = actionBorder(curation.candidates.isNotEmpty() && enabled),
            ) {
                Text(
                    stringResource(
                        if (allSelected) R.string.deselect_all else R.string.select_all,
                    ),
                )
            }
            CurationControls(
                query = query,
                filter = filter,
                sort = sort,
                enabled = enabled,
                onQueryChanged = onQueryChanged,
                onFilterChanged = onFilterChanged,
                onSortChanged = onSortChanged,
            )
        }
    }
    visibleCandidates.forEach { candidate ->
        val selected = candidate.candidateId in curation.selectedCandidateIds
        val expanded = selected && candidate.candidateId == expandedCandidateId
        item(
            key = "candidate:${candidate.candidateId}",
            contentType = "candidate",
        ) {
            CurationCandidateHeader(
                candidate = candidate,
                selected = selected,
                expanded = expanded,
                enabled = enabled,
                candidateTestTag = VideoMiningTestTags.candidate(candidate.candidateId),
                toggleTestTag = VideoMiningTestTags.candidateToggle(candidate.candidateId),
                onToggle = { onToggleCandidate(candidate.candidateId) },
            )
        }
        if (expanded) {
            candidate.sentences.forEachIndexed { index, sentence ->
                item(
                    key = "sentence:${candidate.candidateId}:${sentence.sentenceId}",
                    contentType = "sentence",
                ) {
                    CurationSentenceChoice(
                        candidate = candidate,
                        sentence = sentence,
                        selected =
                            sentence.sentenceId == curation.sentenceIds[candidate.candidateId],
                        enabled = enabled,
                        isLast = index == candidate.sentences.lastIndex,
                        testTag =
                            VideoMiningTestTags.sentence(
                                candidate.candidateId,
                                sentence.sentenceId,
                            ),
                        onClick = {
                            onSelectSentence(candidate.candidateId, sentence.sentenceId)
                        },
                    )
                }
            }
        }
    }
}

private fun LazyListScope.terminalItems(
    title: Int,
    result: ProcessingResult?,
    videoDisplayName: String?,
    subtitleDisplayName: String?,
    partial: Boolean,
    failed: Boolean,
    failureDetails: String?,
    canRetry: Boolean,
    busy: Boolean,
    resetError: Boolean,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onDismissCommandError: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    if (failed) {
        item(key = "terminal_failure", contentType = "header") {
            MiningFailureCard(
                message =
                    if (resetError) {
                        MiningCommandError.RESET.message()
                    } else {
                        stringResource(R.string.mining_failure_summary)
                    },
                diagnosticDetails = failureDetails,
                primaryAction =
                    if (canRetry) {
                        MiningFailureAction(
                            label = stringResource(R.string.retry_mining),
                            testTag = VideoMiningTestTags.RETRY,
                            enabled = !busy,
                            onClick = onRetry,
                        )
                    } else {
                        null
                    },
                secondaryAction =
                    MiningFailureAction(
                        label = stringResource(R.string.reset_mining),
                        testTag = VideoMiningTestTags.RESET,
                        enabled = !busy,
                        onClick = onReset,
                    ),
            )
        }
    } else {
        item(key = "terminal_header", contentType = "header") {
            Text(
                text = stringResource(title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    result?.let {
        miningResultItems(
            result = it,
            sources =
                listOf(
                    MiningResultSource(R.string.result_video, videoDisplayName),
                    MiningResultSource(R.string.result_subtitle, subtitleDisplayName),
                ),
            partial = partial,
            failed = failed,
            detailsExpanded = detailsExpanded,
            testTag = VideoMiningTestTags.RESULT,
            keyPrefix = "terminal_result",
            onToggleDetails = onToggleDetails,
        )
    }
    if (!failed) {
        item(key = "terminal_actions", contentType = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReset,
                    enabled = !busy,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(VideoMiningTestTags.RESET),
                    colors = outlinedActionButtonColors(),
                    border = actionBorder(enabled = !busy),
                ) {
                    Text(stringResource(R.string.reset_mining))
                }
                if (resetError) {
                    MiningFailureCard(
                        message = MiningCommandError.RESET.message(),
                        primaryAction =
                            MiningFailureAction(
                                label = stringResource(R.string.dismiss_error),
                                onClick = onDismissCommandError,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun MiningCancelButton(
    cancelPending: Boolean,
    testTag: String,
    onCancel: () -> Unit,
) {
    OutlinedButton(
        onClick = onCancel,
        enabled = !cancelPending,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(testTag),
        colors = outlinedActionButtonColors(),
        border = actionBorder(enabled = !cancelPending),
    ) {
        if (cancelPending) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.cancelling))
            }
        } else {
            Text(stringResource(R.string.cancel_mining))
        }
    }
}

@Composable
private fun ResetMiningScrollOnTransition(
    state: VideoMiningUiState,
    listState: LazyListState,
) {
    val transitionKey = state.scrollTransitionKey()
    var appliedKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(transitionKey) {
        if (appliedKey != null && appliedKey != transitionKey) {
            listState.scrollToItem(0)
        }
        appliedKey = transitionKey
    }
}

private fun VideoMiningUiState.scrollTransitionKey(): String =
    when (val current = runState) {
        MiningRunState.Idle -> "idle"
        is MiningRunState.Starting -> "starting:${current.runId.orEmpty()}"
        is MiningRunState.Curating ->
            "curating:${current.request.runId}:${current.request.requestId}:" +
                current.request.page?.pageIndex
        is MiningRunState.Running -> "running:${current.runId}"
        is MiningRunState.Success -> "success:${current.runId}"
        is MiningRunState.Cancelled -> "cancelled:${current.runId.orEmpty()}"
        is MiningRunState.Failed -> "failed:${current.runId.orEmpty()}"
    }

@StringRes
private fun runtimeConflictMessage(conflict: RuntimeWorkConflict): Int =
    when (conflict) {
        RuntimeWorkConflict.MINING -> R.string.runtime_work_mining_active
        RuntimeWorkConflict.RESOURCE -> R.string.runtime_work_resource_active
        RuntimeWorkConflict.ANKI_SETUP -> R.string.runtime_work_anki_active
    }

@Composable
private fun MiningCommandError.message(): String =
    stringResource(
        when (this) {
            MiningCommandError.START -> R.string.start_error
            MiningCommandError.CURATION -> R.string.curation_error
            MiningCommandError.CANCEL -> R.string.cancel_error
            MiningCommandError.RESET -> R.string.reset_error
        },
    )
