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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationClipWindow
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.player.CurationPreviewPlayer
import com.ankiminer.android.player.ExoCurationPreviewPlayer
import com.ankiminer.android.ui.mining.ClipWindowSeconds
import com.ankiminer.android.ui.mining.CurationAlternativesToggle
import com.ankiminer.android.ui.mining.CurationCandidateRow
import com.ankiminer.android.ui.mining.CurationCandidateRowText
import com.ankiminer.android.ui.mining.CurationChrome
import com.ankiminer.android.ui.mining.CurationClipControls
import com.ankiminer.android.ui.mining.CurationDefinitionPane
import com.ankiminer.android.ui.mining.CurationExpansionControls
import com.ankiminer.android.ui.mining.CurationFilter
import com.ankiminer.android.ui.mining.CurationRowActions
import com.ankiminer.android.ui.mining.CurationSentenceChoice
import com.ankiminer.android.ui.mining.curationSentenceLayout
import com.ankiminer.android.ui.mining.CurationSort
import com.ankiminer.android.ui.mining.CurationVideoPreview
import com.ankiminer.android.ui.mining.DocumentReadKind
import com.ankiminer.android.ui.mining.MiningFailureAction
import com.ankiminer.android.ui.mining.MiningFailureCard
import com.ankiminer.android.ui.mining.MINING_PHASE_HEADING_TEST_TAG
import com.ankiminer.android.ui.mining.MediaMiningLabels
import com.ankiminer.android.ui.mining.MiningPhaseTarget
import com.ankiminer.android.ui.mining.MiningProgressPanel
import com.ankiminer.android.ui.mining.MiningResultSource
import com.ankiminer.android.ui.mining.MiningResultUndoAction
import com.ankiminer.android.ui.mining.MiningSourceItem
import com.ankiminer.android.ui.mining.MiningUndoConfirmationDialog
import com.ankiminer.android.ui.mining.ReconcileCurationFocus
import com.ankiminer.android.ui.mining.ResetCurationScrollOnProjectionChange
import com.ankiminer.android.ui.mining.RuntimeConflictNotice
import com.ankiminer.android.ui.mining.SourcesCard
import com.ankiminer.android.ui.mining.StickyCurationActions
import com.ankiminer.android.ui.mining.curateCandidates
import com.ankiminer.android.ui.mining.curationBulkSelectionScope
import com.ankiminer.android.ui.mining.curationGroupGap
import com.ankiminer.android.ui.mining.curationRowContainerColor
import com.ankiminer.android.ui.mining.miningResultItems
import com.ankiminer.android.ui.mining.rememberCurationCandidateRowTexts
import com.ankiminer.android.ui.mining.rememberClipboardWriter
import com.ankiminer.android.ui.settings.NumericField
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.PhaseTitle
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.ui.theme.SecondaryActionButton
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
    onDismissTimingPreviewError: () -> Unit = {},
    onStart: () -> Unit,
    onFocusCandidate: (String?) -> Unit,
    onSetCandidateSelected: (String, Boolean) -> Unit,
    onMarkCandidateKnown: (String, Boolean) -> Unit,
    onSetSelectionForVisible: (List<String>, Boolean) -> Unit,
    onSetSelectionForPage: (Boolean) -> Unit,
    onReconcileFocus: (List<String>, List<String>) -> Unit,
    onSelectSentence: (String, String) -> Unit,
    onExpandSentencePrev: (String) -> Unit = {},
    onExpandSentenceNext: (String) -> Unit = {},
    onResetSentenceExpansion: (String) -> Unit = {},
    onSetClipWindow: (String, CurationClipWindow) -> Unit = { _, _ -> },
    onResetClipWindow: (String) -> Unit = {},
    onConfirmCuration: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onRequestUndo: () -> Unit = {},
    onConfirmUndo: () -> Unit = {},
    onDismissUndoConfirmation: () -> Unit = {},
    onSubtitleOffsetDraftChange: (String) -> Unit = {},
    onTestTiming: () -> Unit = {},
    audioTrackPicker: AudioTrackPickerState? = null,
    onAudioTracks: () -> Unit = {},
    onSelectAudioTrack: (Long?) -> Unit = {},
    onApplyAudioTrackPicker: () -> Unit = {},
    onDismissAudioTrackPicker: () -> Unit = {},
    onDismissAudioTrackPickerError: () -> Unit = {},
    onReturnToActiveRun: (() -> Unit)? = null,
    labels: MediaMiningLabels = MediaMiningLabels.VIDEO,
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
    var alternativesOpen by
        rememberSaveable(curation?.requestId, curation?.focusedCandidateId) {
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
    ResetCurationScrollOnProjectionChange(
        listState = listState,
        requestId = curation?.requestId,
        query = query,
        filter = filter,
        sort = sort,
    )

    state.undoConfirmationNoteCount?.let { noteCount ->
        MiningUndoConfirmationDialog(
            noteCount = noteCount,
            onConfirm = onConfirmUndo,
            onDismiss = onDismissUndoConfirmation,
            confirmTestTag = VideoMiningTestTags.UNDO_CONFIRM,
        )
    }

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
            val candidateRowTexts =
                rememberCurationCandidateRowTexts(visibleCandidates)
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
            // Scoped to the projection, not the whole protocol page: a filtered bulk action must
            // not silently reach rows the search is hiding.
            val bulkSelectionScope =
                remember(
                    visibleCandidateIds,
                    targetCuration?.candidates,
                    targetCuration?.knownCandidateIds,
                ) {
                    curationBulkSelectionScope(
                        visibleCandidateIds = visibleCandidateIds,
                        pageCandidateIds =
                            targetCuration?.candidates.orEmpty().map { it.candidateId },
                        knownCandidateIds = targetCuration?.knownCandidateIds.orEmpty(),
                    )
                }
            val selectableVisibleCandidateIds = bulkSelectionScope.visibleCandidateIds
            val allVisibleSelected =
                selectableVisibleCandidateIds.isNotEmpty() &&
                    selectedCandidateIds.containsAll(selectableVisibleCandidateIds)
            val phaseTitle = stringResource(targetState.phaseTitle(labels))
            val headingFocusRequester = remember(target.key) { FocusRequester() }
            val headingModifier =
                Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .testTag(MINING_PHASE_HEADING_TEST_TAG)
            LaunchedEffect(target.key) {
                headingFocusRequester.requestFocus()
            }

            // Hoisted so the trim row's play button (mounted separately, inside curationItems)
            // can reach the same player instance the preview surface below is bound to. The
            // DisposableEffect that releases it stays keyed on runId, so release still happens
            // exactly once per instance - only the call site moved, not the lifecycle.
            val player: CurationPreviewPlayer? =
                if (targetState.runState is MiningRunState.Curating && targetCuration?.player != null) {
                    key(targetCuration.runId) {
                        val context = LocalContext.current
                        val hoisted = remember(targetCuration.runId) { playerFactory(context) }
                        DisposableEffect(targetCuration.runId) { onDispose { hoisted.release() } }
                        hoisted
                    }
                } else {
                    null
                }
            val clipPlaying = if (player != null) player.isPlaying.collectAsState().value else false
            val onPlayClipRange: (ClipWindowSeconds) -> Unit = { window ->
                player?.playRange(window.startSeconds, window.endSeconds)
            }
            val onStopClipRange: () -> Unit = { player?.pause() }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding)
                        .consumeWindowInsets(scaffoldPadding)
                        .semantics { paneTitle = phaseTitle },
            ) {
                if (player != null && targetCuration != null) {
                    key(targetCuration.runId) {
                        CurationPlayerSlot(
                            curation = targetCuration,
                            player = player,
                            modifier =
                                Modifier.padding(
                                    start = AnkiMinerTokens.Space.content,
                                    top = AnkiMinerTokens.Space.content,
                                    end = AnkiMinerTokens.Space.content,
                                ),
                        )
                    }
                }
                if (targetState.runState is MiningRunState.Curating && targetCuration != null) {
                    CurationChrome(
                        title = phaseTitle,
                        headingModifier = headingModifier,
                        selectedCount = targetCuration.selectedCount,
                        candidateCount = targetCuration.candidates.size,
                        page = targetCuration.page,
                        query = query,
                        filter = filter,
                        sort = sort,
                        enabled = !targetState.curationPending && !targetState.cancelPending,
                        visibleCount = bulkSelectionScope.visibleCount,
                        allVisibleSelected = allVisibleSelected,
                        selectVisibleEnabled =
                            selectableVisibleCandidateIds.isNotEmpty() &&
                                !targetState.curationPending &&
                                !targetState.cancelPending,
                        pageCandidateCount = bulkSelectionScope.pageCandidateCount,
                        selectAllTestTag = VideoMiningTestTags.SELECT_ALL,
                        onQueryChanged = { query = it },
                        onFilterChanged = { filterName = it.name },
                        onSortChanged = { sortName = it.name },
                        onSetSelectionForVisible = { select ->
                            onSetSelectionForVisible(selectableVisibleCandidateIds, select)
                        },
                        onSelectWholePage = { onSetSelectionForPage(true) },
                        modifier =
                            Modifier.padding(
                                start = AnkiMinerTokens.Space.content,
                                top = AnkiMinerTokens.Space.related,
                                end = AnkiMinerTokens.Space.content,
                            ),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag(VideoMiningTestTags.CONTENT),
                    contentPadding =
                        if (targetState.runState is MiningRunState.Curating) {
                            // The pinned chrome above already separates the list; a tighter top
                            // inset gives the candidate rows the space back on small screens.
                            PaddingValues(
                                start = AnkiMinerTokens.Space.content,
                                top = AnkiMinerTokens.Space.related,
                                end = AnkiMinerTokens.Space.content,
                                bottom = AnkiMinerTokens.Space.content,
                            )
                        } else {
                            PaddingValues(AnkiMinerTokens.Space.content)
                        },
                    // Curation pays its own gaps per item, so an expanded candidate can close ranks
                    // with its detail and read as one card.
                    verticalArrangement =
                        if (targetState.runState is MiningRunState.Curating) {
                            Arrangement.Top
                        } else {
                            Arrangement.spacedBy(AnkiMinerTokens.Space.group)
                        },
                ) {
                    when (val runState = targetState.runState) {
                        MiningRunState.Idle ->
                            setupItems(
                                state = targetState,
                                labels = labels,
                                headingModifier = headingModifier,
                                onPickVideo = onPickVideo,
                                onPickSubtitle = onPickSubtitle,
                                onClearVideo = onClearVideo,
                                onClearSubtitle = onClearSubtitle,
                                onDismissDocumentError = onDismissDocumentError,
                                onDismissCommandError = onDismissCommandError,
                                onDismissTimingPreviewError = onDismissTimingPreviewError,
                                onSubtitleOffsetDraftChange = onSubtitleOffsetDraftChange,
                                onTestTiming = onTestTiming,
                                onAudioTracks = onAudioTracks,
                                onDismissAudioTrackPickerError = onDismissAudioTrackPickerError,
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
                                visibleCandidates = visibleCandidates,
                                candidateRowTexts = candidateRowTexts,
                                selectedCandidateStateText = selectedCandidateStateText,
                                excludedCandidateStateText = excludedCandidateStateText,
                                includeWordTemplate = includeWordTemplate,
                                excludeWordTemplate = excludeWordTemplate,
                                expandedCandidateId = expandedCandidateId,
                                alternativesOpen = alternativesOpen,
                                onToggleAlternatives = { alternativesOpen = !alternativesOpen },
                                onFocusCandidate = onFocusCandidate,
                                onSetCandidateSelected = onSetCandidateSelected,
                                onMarkCandidateKnown = onMarkCandidateKnown,
                                onSelectSentence = onSelectSentence,
                                onExpandSentencePrev = onExpandSentencePrev,
                                onExpandSentenceNext = onExpandSentenceNext,
                                onResetSentenceExpansion = onResetSentenceExpansion,
                                clipPlaying = clipPlaying,
                                onSetClipWindow = onSetClipWindow,
                                onResetClipWindow = onResetClipWindow,
                                onPlayClipRange = onPlayClipRange,
                                onStopClipRange = onStopClipRange,
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
                                labels = labels,
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
                                undoAvailable = targetState.undoAvailable,
                                undoneNoteCount = targetState.undoneNoteCount,
                                undoError = targetState.commandError == MiningCommandError.UNDO,
                                undoWordsError =
                                    targetState.commandError == MiningCommandError.UNDO_WORDS,
                                detailsExpanded = resultDetailsExpanded,
                                onToggleDetails = {
                                    resultDetailsExpanded = !resultDetailsExpanded
                                },
                                onDismissCommandError = onDismissCommandError,
                                onRetry = onRetry,
                                onReset = onReset,
                                onRequestUndo = onRequestUndo,
                            )
                        is MiningRunState.Cancelled ->
                            terminalItems(
                                title = R.string.cancelled_title,
                                labels = labels,
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
                                undoAvailable = targetState.undoAvailable,
                                undoneNoteCount = targetState.undoneNoteCount,
                                undoError = targetState.commandError == MiningCommandError.UNDO,
                                undoWordsError =
                                    targetState.commandError == MiningCommandError.UNDO_WORDS,
                                detailsExpanded = resultDetailsExpanded,
                                onToggleDetails = {
                                    resultDetailsExpanded = !resultDetailsExpanded
                                },
                                onDismissCommandError = onDismissCommandError,
                                onRetry = onRetry,
                                onReset = onReset,
                                onRequestUndo = onRequestUndo,
                            )
                        is MiningRunState.Failed ->
                            terminalItems(
                                title = R.string.failed_title,
                                labels = labels,
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
                                undoAvailable = targetState.undoAvailable,
                                undoneNoteCount = targetState.undoneNoteCount,
                                undoError = targetState.commandError == MiningCommandError.UNDO,
                                undoWordsError =
                                    targetState.commandError == MiningCommandError.UNDO_WORDS,
                                detailsExpanded = resultDetailsExpanded,
                                onToggleDetails = {
                                    resultDetailsExpanded = !resultDetailsExpanded
                                },
                                onDismissCommandError = onDismissCommandError,
                                onRetry = onRetry,
                                onReset = onReset,
                                onRequestUndo = onRequestUndo,
                            )
                    }
                }
            }
        }
    }

    audioTrackPicker?.let {
        AudioTrackPickerDialog(it, onSelectAudioTrack, onApplyAudioTrackPicker, onDismissAudioTrackPicker)
    }
}

@Composable
private fun CurationPlayerSlot(
    curation: CurationUiState,
    player: CurationPreviewPlayer,
    modifier: Modifier = Modifier,
) {
    val playerState = curation.player ?: return
    val videoUri = remember(playerState.videoPath) { Uri.fromFile(File(playerState.videoPath)) }
    // The player and the curation controls are both pinned, so at large font scales they compete
    // for a viewport that cannot hold either in full — and the controls are the ones that get
    // clipped, which puts sort and bulk selection out of reach. Start folded and let the user
    // open it; the toggle below still persists whatever they choose.
    val startCollapsed = LocalDensity.current.fontScale >= 1.3f
    var collapsed by rememberSaveable(curation.runId) { mutableStateOf(startCollapsed) }

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
        audioOnly = playerState.audioOnly,
        audioTrackOverride = playerState.audioTrackOverride,
        notice =
            if (playerState.cuesUnavailable) {
                { CuesUnavailableNotice() }
            } else {
                null
            },
        modifier = modifier,
    )

    // Line expansion widens the window: "+ Previous line"/reset move the start and snap the
    // preview there; "+ Next line" leaves the start (and so the key) unchanged - no reseek.
    val seekTarget = curation.expansionPreview?.startTime ?: selectedSentence?.startTime
    LaunchedEffect(curation.focusedCandidateId, selectedSentenceId, seekTarget) {
        delay(CURATION_SEEK_DEBOUNCE_MS)
        player.seekTo(seekTarget ?: return@LaunchedEffect)
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
    labels: MediaMiningLabels,
    headingModifier: Modifier,
    onPickVideo: () -> Unit,
    onPickSubtitle: () -> Unit,
    onClearVideo: () -> Unit,
    onClearSubtitle: () -> Unit,
    onDismissDocumentError: (DocumentSelectionError) -> Unit,
    onDismissCommandError: () -> Unit,
    onDismissTimingPreviewError: () -> Unit,
    onSubtitleOffsetDraftChange: (String) -> Unit,
    onTestTiming: () -> Unit,
    onAudioTracks: () -> Unit,
    onDismissAudioTrackPickerError: () -> Unit,
    onStart: () -> Unit,
    onReturnToActiveRun: (() -> Unit)?,
) {
    item(key = "setup_header", contentType = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            PhaseTitle(
                text = stringResource(labels.setupTitle),
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
                        label = stringResource(labels.fileLabel),
                        document = state.video.document,
                        isResolving = state.video.isResolving,
                        enabled = !state.startPending && !state.timingPreviewPending,
                        pickTestTag = VideoMiningTestTags.PICK_VIDEO,
                        clearTestTag = VideoMiningTestTags.CLEAR_VIDEO,
                        readKind = DocumentReadKind.VIDEO,
                        onPick = onPickVideo,
                        onClear = onClearVideo,
                    ),
                    MiningSourceItem(
                        label = stringResource(labels.transcriptLabel),
                        document = state.subtitle.document,
                        isResolving = state.subtitle.isResolving,
                        enabled = !state.startPending && !state.timingPreviewPending,
                        pickTestTag = VideoMiningTestTags.PICK_SUBTITLE,
                        clearTestTag = VideoMiningTestTags.CLEAR_SUBTITLE,
                        readKind = DocumentReadKind.SUBTITLES,
                        onPick = onPickSubtitle,
                        onClear = onClearSubtitle,
                    ),
                ),
        )
    }
    item(key = "subtitle_offset", contentType = "field") {
        NumericField(
            value = state.subtitleOffsetDraft,
            onChange = onSubtitleOffsetDraftChange,
            label = stringResource(labels.subtitleOffsetLabel),
            allowNegative = true,
            enabled = !state.timingPreviewPending,
            error =
                stringResource(R.string.b3_validation_numeric_incomplete)
                    .takeIf { state.subtitleOffsetDraftInvalid },
            modifier = Modifier.testTag(VideoMiningTestTags.SUBTITLE_OFFSET_FIELD),
            placeholder = {
                Text(
                    stringResource(
                        R.string.video_subtitle_offset_placeholder,
                        state.effectiveSubtitleOffset.toString(),
                    ),
                )
            },
        )
    }
    state.video.error?.let { error ->
        item(key = "video_file_error", contentType = "actions") {
            MiningFailureCard(
                message =
                    stringResource(
                        when (error) {
                            DocumentSelectionError.VIDEO -> labels.fileError
                            DocumentSelectionError.AUDIO_TYPE ->
                                R.string.audio_selection_error_type
                            DocumentSelectionError.SUBTITLE -> R.string.subtitle_file_error
                        },
                    ),
                primaryAction =
                    MiningFailureAction(
                        label = stringResource(R.string.dismiss_error),
                        onClick = { onDismissDocumentError(error) },
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
            SecondaryActionButton(
                onClick = onTestTiming,
                enabled = state.canTestTiming,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(VideoMiningTestTags.TEST_TIMING),
            ) {
                Text(stringResource(R.string.timing_preview_test_action))
            }
            SecondaryActionButton(
                onClick = onAudioTracks,
                enabled = state.canPickAudioTracks,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(VideoMiningTestTags.AUDIO_TRACKS),
            ) {
                if (state.audioTrackProbePending) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.audio_tracks_button))
                    }
                } else {
                    Text(stringResource(R.string.audio_tracks_button))
                }
            }
            if (state.audioFieldUnmapped) {
                MiningFailureCard(
                    message = stringResource(R.string.audio_field_unmapped_warning),
                )
            }
            if (state.expressionAudioFieldUnmapped) {
                MiningFailureCard(
                    message = stringResource(R.string.expression_audio_field_unmapped_warning),
                )
            }
            if (state.unusableAudioPackInstalled) {
                MiningFailureCard(
                    message = stringResource(R.string.audio_pack_unusable_warning),
                )
            }
            PrimaryActionButton(
                onClick = onStart,
                enabled = state.canStart,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(VideoMiningTestTags.START),
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
            state.timingPreviewError?.let { error ->
                MiningFailureCard(
                    message = stringResource(error.messageResource()),
                    primaryAction =
                        MiningFailureAction(
                            label = stringResource(R.string.dismiss_error),
                            onClick = onDismissTimingPreviewError,
                        ),
                )
            }
            state.audioTrackPickerError?.let { error ->
                MiningFailureCard(
                    message = stringResource(error.messageResource()),
                    primaryAction =
                        MiningFailureAction(
                            label = stringResource(R.string.dismiss_error),
                            onClick = onDismissAudioTrackPickerError,
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
    visibleCandidates: List<CurationCandidate>,
    candidateRowTexts: Map<String, CurationCandidateRowText>,
    selectedCandidateStateText: String,
    excludedCandidateStateText: String,
    includeWordTemplate: String,
    excludeWordTemplate: String,
    expandedCandidateId: String?,
    alternativesOpen: Boolean,
    onToggleAlternatives: () -> Unit,
    onFocusCandidate: (String?) -> Unit,
    onSetCandidateSelected: (String, Boolean) -> Unit,
    onMarkCandidateKnown: (String, Boolean) -> Unit,
    onSelectSentence: (String, String) -> Unit,
    onExpandSentencePrev: (String) -> Unit,
    onExpandSentenceNext: (String) -> Unit,
    onResetSentenceExpansion: (String) -> Unit,
    clipPlaying: Boolean,
    onSetClipWindow: (String, CurationClipWindow) -> Unit,
    onResetClipWindow: (String) -> Unit,
    onPlayClipRange: (ClipWindowSeconds) -> Unit,
    onStopClipRange: () -> Unit,
    copy: (String, String, String?) -> Unit,
    wordLabel: String,
    sentenceLabel: String,
    copiedWord: String,
    copiedSentence: String,
) {
    val curation = state.curation ?: return
    val enabled = !state.curationPending && !state.cancelPending
    visibleCandidates.forEach { candidate ->
        val selected = candidate.candidateId in curation.selectedCandidateIds
        val known = candidate.candidateId in curation.knownCandidateIds
        val expanded = candidate.candidateId == expandedCandidateId
        val animateSelection = candidate.candidateId == curation.focusedCandidateId
        val rowText = candidateRowTexts.getValue(candidate.candidateId)
        val stateText =
            if (selected) {
                selectedCandidateStateText
            } else {
                excludedCandidateStateText
            }
        val candidateTestTag = VideoMiningTestTags.candidate(candidate.candidateId)
        val toggleTestTag = VideoMiningTestTags.candidateToggle(candidate.candidateId)
        val onToggle: (Boolean) -> Unit = { onSetCandidateSelected(candidate.candidateId, it) }
        val onFocus: () -> Unit = {
            onFocusCandidate(candidate.candidateId.takeUnless { expanded })
        }
        item(
            key = "candidate:${candidate.candidateId}",
            contentType = "candidate",
        ) {
            CurationCandidateRow(
                text = rowText,
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
                modifier = Modifier.padding(bottom = curationGroupGap(last = !expanded)),
            )
        }
        if (expanded) {
            item(key = "actions:${candidate.candidateId}", contentType = "row_actions") {
                CurationRowActions(
                    containerColor = curationRowContainerColor(selected, animateSelection),
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
            if (curation.player != null) {
                item(
                    key = "expansion:${candidate.candidateId}",
                    contentType = "expansion",
                ) {
                    val expansion = curation.lineExpansions[candidate.candidateId]
                    CurationExpansionControls(
                        containerColor = curationRowContainerColor(selected, animateSelection),
                        linesBefore = expansion?.linesBefore ?: 0,
                        linesAfter = expansion?.linesAfter ?: 0,
                        preview = curation.expansionPreview,
                        surface = candidate.surface,
                        enabled = enabled,
                        expandPrevTestTag = VideoMiningTestTags.candidateExpandPrev(candidate.candidateId),
                        expandNextTestTag = VideoMiningTestTags.candidateExpandNext(candidate.candidateId),
                        resetTestTag = VideoMiningTestTags.candidateExpandReset(candidate.candidateId),
                        previewTestTag = VideoMiningTestTags.expansionPreview(candidate.candidateId),
                        onExpandPrev = { onExpandSentencePrev(candidate.candidateId) },
                        onExpandNext = { onExpandSentenceNext(candidate.candidateId) },
                        onReset = { onResetSentenceExpansion(candidate.candidateId) },
                    )
                }
            }
            // Trim row needs player for its play button. This is the render site's own
            // precondition — ViewModel upholding it separately (clipWindowFor) is not a
            // reason to drop it here; screen-constructed states (tests) can set
            // clipWindow without player.
            curation.clipWindow?.takeIf { curation.player != null }?.let { clipWindow ->
                item(key = "clip:${candidate.candidateId}", contentType = "clip") {
                    CurationClipControls(
                        containerColor = curationRowContainerColor(selected, animateSelection),
                        state = clipWindow,
                        enabled = enabled,
                        playing = clipPlaying,
                        sliderTestTag = VideoMiningTestTags.candidateClipSlider(candidate.candidateId),
                        playTestTag = VideoMiningTestTags.candidateClipPlay(candidate.candidateId),
                        resetTestTag = VideoMiningTestTags.candidateClipReset(candidate.candidateId),
                        readoutTestTag = VideoMiningTestTags.candidateClipReadout(candidate.candidateId),
                        onWindowChange = { onSetClipWindow(candidate.candidateId, it) },
                        onReset = { onResetClipWindow(candidate.candidateId) },
                        onPlay = onPlayClipRange,
                        onStop = onStopClipRange,
                    )
                }
            }
            curation.definition?.let { definition ->
                item(
                    key = "definition:${candidate.candidateId}",
                    contentType = "definition",
                ) {
                    CurationDefinitionPane(
                        definition = definition,
                        containerColor =
                            curationRowContainerColor(selected, animateSelection),
                        term = candidate.minedForm,
                        testTag = VideoMiningTestTags.DEFINITION,
                    )
                }
            }
            val layout =
                curationSentenceLayout(
                    candidate = candidate,
                    selectedSentenceId = curation.sentenceIds[candidate.candidateId],
                )
            if (!layout.disclose) {
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
                            containerColor =
                                curationRowContainerColor(selected, animateSelection),
                            selected =
                                sentence.sentenceId == curation.sentenceIds[candidate.candidateId],
                            enabled = enabled,
                            isLast = index == candidate.sentences.lastIndex,
                            testTag = sentenceTestTag,
                            onClick = onClick,
                            modifier =
                                Modifier.padding(
                                    bottom =
                                        curationGroupGap(last = index == candidate.sentences.lastIndex),
                                ),
                            selectable = layout.selectable,
                        )
                    }
                }
            } else {
                item(
                    key = "chosen:${candidate.candidateId}",
                    contentType = "sentence",
                ) {
                    CurationSentenceChoice(
                        candidate = candidate,
                        sentence = layout.chosen,
                        containerColor =
                            curationRowContainerColor(selected, animateSelection),
                        selected = true,
                        enabled = enabled,
                        isLast = false,
                        testTag = VideoMiningTestTags.chosenSentence(candidate.candidateId),
                        onClick = {
                            onSelectSentence(candidate.candidateId, layout.chosen.sentenceId)
                        },
                    )
                }
                item(
                    key = "alts:${candidate.candidateId}",
                    contentType = "alternatives_toggle",
                ) {
                    CurationAlternativesToggle(
                        alternativeCount = layout.alternatives.size,
                        expanded = alternativesOpen,
                        containerColor =
                            curationRowContainerColor(selected, animateSelection),
                        enabled = enabled,
                        isLast = !alternativesOpen,
                        testTag = VideoMiningTestTags.alternativesToggle(candidate.candidateId),
                        onToggle = onToggleAlternatives,
                        modifier =
                            Modifier.padding(
                                bottom = curationGroupGap(last = !alternativesOpen),
                            ),
                    )
                }
                if (alternativesOpen) {
                    layout.alternatives.forEachIndexed { index, sentence ->
                        item(
                            key = "sentence:${candidate.candidateId}:${sentence.sentenceId}",
                            contentType = "sentence",
                        ) {
                            CurationSentenceChoice(
                                candidate = candidate,
                                sentence = sentence,
                                containerColor =
                                    curationRowContainerColor(selected, animateSelection),
                                selected = false,
                                enabled = enabled,
                                isLast = index == layout.alternatives.lastIndex,
                                testTag =
                                    VideoMiningTestTags.sentence(
                                        candidate.candidateId,
                                        sentence.sentenceId,
                                    ),
                                onClick = {
                                    onSelectSentence(candidate.candidateId, sentence.sentenceId)
                                },
                                modifier =
                                    Modifier.padding(
                                        bottom =
                                            curationGroupGap(
                                                last = index == layout.alternatives.lastIndex,
                                            ),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.terminalItems(
    title: Int,
    labels: MediaMiningLabels,
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
    undoAvailable: Boolean,
    undoneNoteCount: Int?,
    undoError: Boolean,
    undoWordsError: Boolean,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onDismissCommandError: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onRequestUndo: () -> Unit,
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
                        failureDetails ?: stringResource(title)
                    },
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
    result?.let { finalResult ->
        miningResultItems(
            result = finalResult,
            sources =
                listOf(
                    MiningResultSource(labels.resultSource, videoDisplayName),
                    MiningResultSource(R.string.result_subtitle, subtitleDisplayName),
                ),
            partial = partial,
            failed = failed,
            detailsExpanded = detailsExpanded,
            testTag = VideoMiningTestTags.RESULT,
            keyPrefix = "terminal_result",
            onToggleDetails = onToggleDetails,
            undo =
                finalResult.cardIds.takeIf { it.isNotEmpty() }?.let { cardIds ->
                    MiningResultUndoAction(
                        noteCount = cardIds.size,
                        undoneNoteCount = undoneNoteCount,
                        enabled = undoAvailable,
                        testTag = VideoMiningTestTags.UNDO,
                        onUndo = onRequestUndo,
                    )
                },
        )
    }
    if (undoError || undoWordsError) {
        item(key = "terminal_undo_error", contentType = "error") {
            MiningFailureCard(
                message =
                    if (undoError) {
                        MiningCommandError.UNDO.message()
                    } else {
                        MiningCommandError.UNDO_WORDS.message()
                    },
                primaryAction =
                    MiningFailureAction(
                        label = stringResource(R.string.dismiss_error),
                        onClick = onDismissCommandError,
                    ),
            )
        }
    }
    if (!failed) {
        item(key = "terminal_actions", contentType = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
                SecondaryActionButton(
                    onClick = onReset,
                    enabled = !busy,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(VideoMiningTestTags.RESET),
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
    SecondaryActionButton(
        onClick = onCancel,
        enabled = !cancelPending,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(testTag),
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
private fun VideoMiningUiState.phaseTitle(labels: MediaMiningLabels): Int =
    when (runState) {
        MiningRunState.Idle -> labels.setupTitle
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
            MiningCommandError.UNDO -> R.string.undo_failed
            MiningCommandError.UNDO_WORDS -> R.string.undo_words_failed
        },
    )

@StringRes
private fun TimingPreviewError.messageResource(): Int =
    when (this) {
        TimingPreviewError.BUSY -> R.string.timing_preview_busy
        TimingPreviewError.TOKENIZER_REQUIRED -> R.string.timing_preview_tokenizer_required
        TimingPreviewError.OPEN -> R.string.timing_preview_open_error
    }

@StringRes
private fun AudioTrackPickerError.messageResource(): Int =
    when (this) {
        // Same string as TimingPreviewError.BUSY: both surface the exclusive-runtime-lease refusal.
        AudioTrackPickerError.BUSY -> R.string.timing_preview_busy
        AudioTrackPickerError.PROBE -> R.string.audio_tracks_probe_error
    }
