package com.ankiminer.android.ui.video

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.player.CurationPreviewPlayer
import com.ankiminer.android.player.ExoCurationPreviewPlayer
import com.ankiminer.android.ui.mining.CurationCandidateHeader
import com.ankiminer.android.ui.mining.CurationControls
import com.ankiminer.android.ui.mining.CurationDefinitionPane
import com.ankiminer.android.ui.mining.CurationFilter
import com.ankiminer.android.ui.mining.CurationRowActions
import com.ankiminer.android.ui.mining.CurationSentenceChoice
import com.ankiminer.android.ui.mining.CurationSort
import com.ankiminer.android.ui.mining.CurationVideoPreview
import com.ankiminer.android.ui.mining.DocumentReadKind
import com.ankiminer.android.ui.mining.MiningFailureAction
import com.ankiminer.android.ui.mining.MiningFailureCard
import com.ankiminer.android.ui.mining.MINING_PHASE_HEADING_TEST_TAG
import com.ankiminer.android.ui.mining.MiningPhaseTarget
import com.ankiminer.android.ui.mining.MiningProgressPanel
import com.ankiminer.android.ui.mining.MiningResultSource
import com.ankiminer.android.ui.mining.MiningSourceItem
import com.ankiminer.android.ui.mining.ReconcileCurationFocus
import com.ankiminer.android.ui.mining.RuntimeConflictNotice
import com.ankiminer.android.ui.mining.SourcesCard
import com.ankiminer.android.ui.mining.StickyCurationActions
import com.ankiminer.android.ui.mining.curateCandidates
import com.ankiminer.android.ui.mining.miningResultItems
import com.ankiminer.android.ui.mining.rememberCurationCandidateHeaderTexts
import com.ankiminer.android.ui.mining.rememberClipboardWriter
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.ExitActionButton
import com.ankiminer.android.ui.theme.PhaseTitle
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.forwardButtonColors
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import java.io.File
import kotlinx.coroutines.delay

private const val CURATION_SEEK_DEBOUNCE_MS = 150L

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
    onFocusCandidate: (String) -> Unit,
    onSetCandidateSelected: (String, Boolean) -> Unit,
    onMarkCandidateKnown: (String, Boolean) -> Unit,
    onSetSelectionForVisible: (List<String>, Boolean) -> Unit,
    onSetSelectionForPage: (Boolean) -> Unit,
    onReconcileFocus: (List<String>, List<String>) -> Unit,
    onSelectSentence: (String, String) -> Unit,
    onConfirmCuration: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onReturnToActiveRun: (() -> Unit)? = null,
    playerFactory: (Context) -> CurationPreviewPlayer = { ExoCurationPreviewPlayer(it) },
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val curation = state.curation
    val copy = rememberClipboardWriter()
    val wordLabel = stringResource(R.string.curation_copy_word)
    val sentenceLabel = stringResource(R.string.curation_copy_sentence)
    val copiedWord = stringResource(R.string.curation_copied_word)
    val copiedSentence = stringResource(R.string.curation_copied_sentence)
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
    val phaseKey = state.phaseKey()
    val phaseTarget =
        remember(phaseKey) {
            MiningPhaseTarget(
                key = phaseKey,
                initialState = state,
            )
        }
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
        AnimatedContent(
            targetState = phaseTarget,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                (
                    fadeIn(tween(durationMillis = 150)) togetherWith
                        fadeOut(tween(durationMillis = 90))
                ) using null
            },
            contentKey = { target -> target.key },
            label = "video mining phase",
        ) { target ->
            val targetState =
                if (target === phaseTarget) {
                    state
                } else {
                    target.initialState
                }
            val targetCuration = targetState.curation
            val selectionProjectionKey =
                if (filter == CurationFilter.ALL) {
                    emptySet()
                } else {
                    targetCuration?.selectedCandidateIds.orEmpty()
                }
            val visibleCandidates =
                remember(
                    targetCuration?.candidates,
                    selectionProjectionKey,
                    query,
                    filter,
                    sort,
                ) {
                    curateCandidates(
                        candidates = targetCuration?.candidates.orEmpty(),
                        selectedCandidateIds = targetCuration?.selectedCandidateIds.orEmpty(),
                        query = query,
                        filter = filter,
                        sort = sort,
                    )
                }
            val candidateHeaderTexts =
                rememberCurationCandidateHeaderTexts(visibleCandidates)
            val selectedCandidateStateText = stringResource(R.string.candidate_state_selected)
            val excludedCandidateStateText = stringResource(R.string.candidate_state_excluded)
            // Raw templates: formatting per row is cheap, a resource lookup per row is not.
            val includeWordTemplate = stringResource(R.string.curation_include_word)
            val excludeWordTemplate = stringResource(R.string.curation_exclude_word)
            val selectedCandidateIds = targetCuration?.selectedCandidateIds.orEmpty()
            val visibleCandidateIds =
                remember(visibleCandidates) { visibleCandidates.map { it.candidateId } }
            // Detail follows focus only. Requiring selection too is what made inspecting an
            // included candidate exclude it.
            val expandedCandidateId =
                targetCuration?.focusedCandidateId?.takeIf { it in visibleCandidateIds }
            ReconcileCurationFocus(
                visibleCandidateIds = visibleCandidateIds,
                focusedCandidateId = targetCuration?.focusedCandidateId,
                onReconcile = onReconcileFocus,
            )
            val phaseTitle = stringResource(targetState.phaseTitle())
            val headingFocusRequester = remember(target.key) { FocusRequester() }
            val headingModifier =
                Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .testTag(MINING_PHASE_HEADING_TEST_TAG)
            LaunchedEffect(target.key) {
                headingFocusRequester.requestFocus()
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding)
                        .consumeWindowInsets(scaffoldPadding)
                        .semantics { paneTitle = phaseTitle },
            ) {
                if (
                    targetState.runState is MiningRunState.Curating &&
                    targetCuration?.player != null
                ) {
                    key(targetCuration.runId) {
                        CurationPlayerSlot(
                            curation = targetCuration,
                            playerFactory = playerFactory,
                            modifier =
                                Modifier.padding(
                                    start = AnkiMinerTokens.Space.content,
                                    top = AnkiMinerTokens.Space.content,
                                    end = AnkiMinerTokens.Space.content,
                                ),
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag(VideoMiningTestTags.CONTENT),
                    contentPadding = PaddingValues(AnkiMinerTokens.Space.content),
                    verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.group),
                ) {
                    when (val runState = targetState.runState) {
                        MiningRunState.Idle ->
                            setupItems(
                                state = targetState,
                                headingModifier = headingModifier,
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
                                title = R.string.starting_title,
                                headingModifier = headingModifier,
                                canCancel =
                                    runState.cancellationToken != null || runState.runId != null,
                                cancelPending = targetState.cancelPending,
                                cancelError = targetState.commandError == MiningCommandError.CANCEL,
                                onDismissCommandError = onDismissCommandError,
                                onCancel = onCancel,
                            )
                        is MiningRunState.Curating ->
                            curationItems(
                                state = targetState,
                                headingModifier = headingModifier,
                                visibleCandidates = visibleCandidates,
                                candidateHeaderTexts = candidateHeaderTexts,
                                selectedCandidateStateText = selectedCandidateStateText,
                                excludedCandidateStateText = excludedCandidateStateText,
                                includeWordTemplate = includeWordTemplate,
                                excludeWordTemplate = excludeWordTemplate,
                                expandedCandidateId = expandedCandidateId,
                                query = query,
                                filter = filter,
                                sort = sort,
                                onQueryChanged = { query = it },
                                onFilterChanged = { filterName = it.name },
                                onSortChanged = { sortName = it.name },
                                onFocusCandidate = onFocusCandidate,
                                onSetCandidateSelected = onSetCandidateSelected,
                                onMarkCandidateKnown = onMarkCandidateKnown,
                                onSetSelectionForVisible = onSetSelectionForVisible,
                                onSetSelectionForPage = onSetSelectionForPage,
                                onReconcileFocus = onReconcileFocus,
                                onSelectSentence = onSelectSentence,
                                copy = copy,
                                wordLabel = wordLabel,
                                sentenceLabel = sentenceLabel,
                                copiedWord = copiedWord,
                                copiedSentence = copiedSentence,
                            )
                        is MiningRunState.Running ->
                            progressItems(
                                progress = runState.progress,
                                title = R.string.running_title,
                                headingModifier = headingModifier,
                                canCancel = true,
                                cancelPending = targetState.cancelPending,
                                cancelError = targetState.commandError == MiningCommandError.CANCEL,
                                onDismissCommandError = onDismissCommandError,
                                onCancel = onCancel,
                            )
                        is MiningRunState.Success ->
                            terminalItems(
                                title = R.string.success_title,
                                headingModifier = headingModifier,
                                result = runState.result,
                                videoDisplayName = targetState.video.document?.displayName,
                                subtitleDisplayName = targetState.subtitle.document?.displayName,
                                partial = false,
                                failed = false,
                                failureDetails = null,
                                canRetry = false,
                                busy = targetState.resetPending,
                                resetError = targetState.commandError == MiningCommandError.RESET,
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
                                headingModifier = headingModifier,
                                result = runState.result,
                                videoDisplayName = targetState.video.document?.displayName,
                                subtitleDisplayName = targetState.subtitle.document?.displayName,
                                partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                                failed = false,
                                failureDetails = null,
                                canRetry = false,
                                busy = targetState.resetPending,
                                resetError = targetState.commandError == MiningCommandError.RESET,
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
                                headingModifier = headingModifier,
                                result = runState.result,
                                videoDisplayName = targetState.video.document?.displayName,
                                subtitleDisplayName = targetState.subtitle.document?.displayName,
                                partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                                failed = true,
                                failureDetails = runState.failure.message,
                                canRetry =
                                    runState.failure.retryable &&
                                        targetState.video.document != null &&
                                        targetState.subtitle.document != null,
                                busy = targetState.resetPending || targetState.startPending,
                                resetError = targetState.commandError == MiningCommandError.RESET,
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
    }
}

@Composable
private fun CurationPlayerSlot(
    curation: CurationUiState,
    playerFactory: (Context) -> CurationPreviewPlayer,
    modifier: Modifier = Modifier,
) {
    val playerState = curation.player ?: return
    val context = LocalContext.current
    val player = remember(curation.runId) { playerFactory(context) }
    val videoUri = remember(playerState.videoPath) { Uri.fromFile(File(playerState.videoPath)) }
    var collapsed by rememberSaveable(curation.runId) { mutableStateOf(false) }

    DisposableEffect(curation.runId) {
        onDispose { player.release() }
    }

    val focusedCandidate =
        curation.candidates.firstOrNull { it.candidateId == curation.focusedCandidateId }
    val selectedSentenceId =
        focusedCandidate?.let { candidate ->
            curation.sentenceIds[candidate.candidateId] ?: candidate.defaultSentenceId
        }
    val selectedSentence =
        focusedCandidate?.sentences?.firstOrNull { it.sentenceId == selectedSentenceId }

    CurationVideoPreview(
        player = player,
        videoUri = videoUri,
        cues = playerState.cues.takeUnless { playerState.cuesUnavailable }.orEmpty(),
        overlayOffsetSeconds = 0.0,
        collapsed = collapsed,
        onToggleCollapsed = { collapsed = !collapsed },
        notice =
            if (playerState.cuesUnavailable) {
                { CuesUnavailableNotice() }
            } else {
                null
            },
        modifier = modifier,
    )

    LaunchedEffect(curation.focusedCandidateId, selectedSentenceId) {
        val sentence = selectedSentence ?: return@LaunchedEffect
        delay(CURATION_SEEK_DEBOUNCE_MS)
        player.seekTo(sentence.startTime)
    }
}

@Composable
private fun CuesUnavailableNotice() {
    Text(
        text = stringResource(R.string.curation_preview_cues_unavailable),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AnkiMinerTokens.Space.related,
                    vertical = AnkiMinerTokens.Space.micro,
                ).testTag(VideoMiningTestTags.CUES_UNAVAILABLE),
        color = MaterialTheme.colorScheme.error,
        maxLines = 1,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun LazyListScope.setupItems(
    state: VideoMiningUiState,
    headingModifier: Modifier,
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
        Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            PhaseTitle(
                text = stringResource(R.string.video_phase_setup_title),
                modifier = headingModifier,
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
        Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
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
    @StringRes title: Int,
    headingModifier: Modifier,
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
            headingModifier = headingModifier,
            title = title,
        )
    }
    if (canCancel) {
        item(key = "cancel", contentType = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
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
    headingModifier: Modifier,
    visibleCandidates: List<CurationCandidate>,
    candidateHeaderTexts: Map<String, AnnotatedString>,
    selectedCandidateStateText: String,
    excludedCandidateStateText: String,
    includeWordTemplate: String,
    excludeWordTemplate: String,
    expandedCandidateId: String?,
    query: String,
    filter: CurationFilter,
    sort: CurationSort,
    onQueryChanged: (String) -> Unit,
    onFilterChanged: (CurationFilter) -> Unit,
    onSortChanged: (CurationSort) -> Unit,
    onFocusCandidate: (String) -> Unit,
    onSetCandidateSelected: (String, Boolean) -> Unit,
    onMarkCandidateKnown: (String, Boolean) -> Unit,
    onSetSelectionForVisible: (List<String>, Boolean) -> Unit,
    onSetSelectionForPage: (Boolean) -> Unit,
    onReconcileFocus: (List<String>, List<String>) -> Unit,
    onSelectSentence: (String, String) -> Unit,
    copy: (String, String, String?) -> Unit,
    wordLabel: String,
    sentenceLabel: String,
    copiedWord: String,
    copiedSentence: String,
) {
    val curation = state.curation ?: return
    val enabled = !state.curationPending && !state.cancelPending
    // Scoped to the projection, not the whole protocol page: a filtered bulk action must not
    // silently reach rows the search is hiding.
    val visibleCandidateIds = visibleCandidates.map { it.candidateId }
    val selectableVisibleCandidateIds =
        visibleCandidateIds.filterNot(curation.knownCandidateIds::contains)
    val allVisibleSelected =
        selectableVisibleCandidateIds.isNotEmpty() &&
            curation.selectedCandidateIds.containsAll(selectableVisibleCandidateIds)

    item(key = "curation_header", contentType = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            PhaseTitle(
                text = stringResource(R.string.curation_title),
                modifier = headingModifier,
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
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
        Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            OutlinedButton(
                onClick = {
                    onSetSelectionForVisible(visibleCandidateIds, !allVisibleSelected)
                },
                enabled = selectableVisibleCandidateIds.isNotEmpty() && enabled,
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .testTag(VideoMiningTestTags.SELECT_ALL),
                colors = outlinedActionButtonColors(),
                border = actionBorder(selectableVisibleCandidateIds.isNotEmpty() && enabled),
            ) {
                Text(
                    stringResource(
                        if (allVisibleSelected) {
                            R.string.deselect_visible
                        } else {
                            R.string.select_visible
                        },
                        visibleCandidates.size,
                    ),
                )
            }
            // Page-wide selection stays reachable, but named for the scope it actually reaches
            // rather than hiding behind the same "Select all" the filtered action used to use.
            if (visibleCandidateIds.size < curation.candidates.size) {
                ExitActionButton(
                    onClick = { onSetSelectionForPage(true) },
                    enabled = enabled,
                ) {
                    Text(
                        stringResource(
                            R.string.curation_select_whole_page,
                            curation.candidates.size,
                        ),
                    )
                }
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
        val known = candidate.candidateId in curation.knownCandidateIds
        val expanded = candidate.candidateId == expandedCandidateId
        val animateSelection = candidate.candidateId == curation.focusedCandidateId
        val headline = candidateHeaderTexts.getValue(candidate.candidateId)
        val stateText =
            if (selected) {
                selectedCandidateStateText
            } else {
                excludedCandidateStateText
            }
        val candidateTestTag = VideoMiningTestTags.candidate(candidate.candidateId)
        val toggleTestTag = VideoMiningTestTags.candidateToggle(candidate.candidateId)
        val onToggle: (Boolean) -> Unit = { onSetCandidateSelected(candidate.candidateId, it) }
        val onFocus: () -> Unit = { onFocusCandidate(candidate.candidateId) }
        item(
            key = "candidate:${candidate.candidateId}",
            contentType = "candidate",
        ) {
            CurationCandidateHeader(
                headline = headline,
                stateText = stateText,
                includeLabel =
                    if (selected) {
                        excludeWordTemplate.format(candidate.minedForm)
                    } else {
                        includeWordTemplate.format(candidate.minedForm)
                    },
                selected = selected,
                expanded = expanded,
                animateSelection = animateSelection,
                enabled = enabled,
                toggleEnabled = enabled && !known,
                candidateTestTag = candidateTestTag,
                toggleTestTag = toggleTestTag,
                onFocus = onFocus,
                onToggle = onToggle,
            )
        }
        if (expanded) {
            item(key = "actions:${candidate.candidateId}", contentType = "row_actions") {
                CurationRowActions(
                    known = known,
                    enabled = enabled,
                    knownTestTag = VideoMiningTestTags.candidateKnown(candidate.candidateId),
                    copyWordTestTag = VideoMiningTestTags.candidateCopyWord(candidate.candidateId),
                    copySentenceTestTag =
                        VideoMiningTestTags.candidateCopySentence(candidate.candidateId),
                    onToggleKnown = { marked ->
                        onMarkCandidateKnown(candidate.candidateId, marked)
                    },
                    onCopyWord = { copy(wordLabel, candidate.minedForm, copiedWord) },
                    onCopySentence = {
                        val chosen =
                            candidate.sentences.firstOrNull { sentence ->
                                sentence.sentenceId ==
                                    curation.sentenceIds[candidate.candidateId]
                            } ?: candidate.sentences.first()
                        copy(sentenceLabel, chosen.sentence, copiedSentence)
                    },
                )
            }
            curation.definition?.let { definition ->
                item(
                    key = "definition:${candidate.candidateId}",
                    contentType = "definition",
                ) {
                    CurationDefinitionPane(
                        definition = definition,
                        term = candidate.minedForm,
                        testTag = VideoMiningTestTags.DEFINITION,
                    )
                }
            }
            candidate.sentences.forEachIndexed { index, sentence ->
                val sentenceTestTag =
                    VideoMiningTestTags.sentence(
                        candidate.candidateId,
                        sentence.sentenceId,
                    )
                val onClick = {
                    onSelectSentence(candidate.candidateId, sentence.sentenceId)
                }
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
                        testTag = sentenceTestTag,
                        onClick = onClick,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.terminalItems(
    title: Int,
    headingModifier: Modifier,
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
    item(key = "terminal_header", contentType = "header") {
        PhaseTitle(
            text = stringResource(title),
            modifier = headingModifier,
        )
    }
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
            Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
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
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
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

private fun VideoMiningUiState.phaseKey(): String =
    when (runState) {
        MiningRunState.Idle -> "idle"
        is MiningRunState.Starting -> "starting"
        is MiningRunState.Curating -> "curating"
        is MiningRunState.Running -> "running"
        is MiningRunState.Success -> "success"
        is MiningRunState.Cancelled -> "cancelled"
        is MiningRunState.Failed -> "failed"
    }

@StringRes
private fun VideoMiningUiState.phaseTitle(): Int =
    when (runState) {
        MiningRunState.Idle -> R.string.video_phase_setup_title
        is MiningRunState.Starting -> R.string.starting_title
        is MiningRunState.Curating -> R.string.curation_title
        is MiningRunState.Running -> R.string.running_title
        is MiningRunState.Success -> R.string.success_title
        is MiningRunState.Cancelled -> R.string.cancelled_title
        is MiningRunState.Failed -> R.string.failed_title
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
