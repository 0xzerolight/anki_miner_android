package com.ankiminer.android.ui.reading

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.reading.CurationPageImageDecoder
import com.ankiminer.android.ui.mining.CurationAlternativesToggle
import com.ankiminer.android.ui.mining.CurationCandidateRow
import com.ankiminer.android.ui.mining.CurationCandidateRowText
import com.ankiminer.android.ui.mining.CurationChrome
import com.ankiminer.android.ui.mining.CurationDefinitionPane
import com.ankiminer.android.ui.mining.CurationFilter
import com.ankiminer.android.ui.mining.CurationPageImagePane
import com.ankiminer.android.ui.mining.CurationRowActions
import com.ankiminer.android.ui.mining.CurationSentenceChoice
import com.ankiminer.android.ui.mining.curationSentenceLayout
import com.ankiminer.android.ui.mining.CurationSort
import com.ankiminer.android.ui.mining.DocumentReadKind
import com.ankiminer.android.ui.mining.MiningFailureAction
import com.ankiminer.android.ui.mining.MiningFailureCard
import com.ankiminer.android.ui.mining.MINING_PHASE_HEADING_TEST_TAG
import com.ankiminer.android.ui.mining.MiningPhaseTarget
import com.ankiminer.android.ui.mining.MiningProgressPanel
import com.ankiminer.android.ui.mining.MiningResultSource
import com.ankiminer.android.ui.mining.MiningResultUndoAction
import com.ankiminer.android.ui.mining.MiningUndoConfirmationDialog
import com.ankiminer.android.ui.mining.ReconcileCurationFocus
import com.ankiminer.android.ui.mining.ResetCurationScrollOnProjectionChange
import com.ankiminer.android.ui.mining.MiningSourceItem
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
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.PhaseTitle
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.segmentedActionColors

@Composable
fun ReadingMiningScreen(
    state: ReadingMiningUiState,
    onPickSource: () -> Unit,
    onPickArchive: () -> Unit,
    onClearSource: () -> Unit,
    onClearArchive: () -> Unit,
    onSourceModeChanged: (ReadingSourceMode) -> Unit,
    onPastedTextChanged: (String) -> Unit,
    onClearPastedText: () -> Unit,
    onSeriesNameChanged: (String) -> Unit,
    onDismissDocumentError: (ReadingDocumentSelectionError) -> Unit,
    onDismissCommandError: () -> Unit,
    onStart: () -> Unit,
    onFocusCandidate: (String?) -> Unit,
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
    onRequestUndo: () -> Unit = {},
    onConfirmUndo: () -> Unit = {},
    onDismissUndoConfirmation: () -> Unit = {},
    onReturnToActiveRun: (() -> Unit)? = null,
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
            confirmTestTag = ReadingMiningTestTags.UNDO_CONFIRM,
        )
    }

    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(ReadingMiningTestTags.SCREEN),
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
                                it == ReadingMiningCommandError.CURATION ||
                                    it == ReadingMiningCommandError.CANCEL
                            }?.message(),
                    confirmTestTag = ReadingMiningTestTags.CONFIRM_CURATION,
                    cancelTestTag = ReadingMiningTestTags.CANCEL,
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
            label = "reading mining phase",
        ) { target ->
            val targetState =
                if (target === phaseTarget) {
                    state
                } else {
                    target.initialState
                }
            val targetCuration = targetState.curation
            // A mokuro volume whose blocks are ALL malformed produces zero pageContexts across
            // every candidate/sentence; without this the pane would stay mounted showing nothing
            // but the "missing" placeholder for the whole run. A page with partial coverage still
            // keeps the pane's existing stay-mounted behavior once any pageContext exists.
            val curationHasPageContext =
                remember(targetCuration?.candidates) {
                    targetCuration?.candidates.orEmpty().any { candidate ->
                        candidate.sentences.any { it.pageContext != null }
                    }
                }
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
            val phaseTitle = stringResource(targetState.phaseTitle())
            val terminalSourceDisplayName =
                when (targetState.sourceMode) {
                    ReadingSourceMode.FILE -> targetState.source.document?.displayName
                    ReadingSourceMode.PASTED_TEXT ->
                        stringResource(R.string.reading_source_mode_text)
                }
            val terminalArchiveDisplayName =
                targetState.archive.document?.displayName.takeIf {
                    targetState.sourceMode == ReadingSourceMode.FILE
                }
            val headingFocusRequester = remember(target.key) { FocusRequester() }
            val headingModifier =
                Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .testTag(MINING_PHASE_HEADING_TEST_TAG)
            LaunchedEffect(target.key) {
                headingFocusRequester.requestFocus()
            }

            // The pane title and insets belong to the whole phase, the CONTENT tag and its scroll
            // semantics only to the list — every performScrollToNode resolves against that node.
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
                    targetCuration?.pageImage != null &&
                    curationHasPageContext
                ) {
                    key(targetCuration.runId) {
                        CurationPageImageSlot(
                            curation = targetCuration,
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
                        selectAllTestTag = ReadingMiningTestTags.SELECT_ALL,
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
                                top = AnkiMinerTokens.Space.content,
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
                            .testTag(ReadingMiningTestTags.CONTENT),
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
                                onPickSource = onPickSource,
                                onPickArchive = onPickArchive,
                                onClearSource = onClearSource,
                                onClearArchive = onClearArchive,
                                onSourceModeChanged = onSourceModeChanged,
                                onPastedTextChanged = onPastedTextChanged,
                                onClearPastedText = onClearPastedText,
                                onSeriesNameChanged = onSeriesNameChanged,
                                onDismissDocumentError = onDismissDocumentError,
                                onDismissCommandError = onDismissCommandError,
                                onStart = onStart,
                                onReturnToActiveRun = onReturnToActiveRun,
                            )
                        is MiningRunState.Starting ->
                            progressItems(
                                progress = runState.progress,
                                canCancel =
                                    runState.cancellationToken != null || runState.runId != null,
                                cancelPending = targetState.cancelPending,
                                cancelError =
                                    targetState.commandError == ReadingMiningCommandError.CANCEL,
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
                                copy = copy,
                                wordLabel = wordLabel,
                                sentenceLabel = sentenceLabel,
                                copiedWord = copiedWord,
                                copiedSentence = copiedSentence,
                            )
                        is MiningRunState.Running ->
                            progressItems(
                                progress = runState.progress,
                                canCancel = true,
                                cancelPending = targetState.cancelPending,
                                cancelError =
                                    targetState.commandError == ReadingMiningCommandError.CANCEL,
                                onDismissCommandError = onDismissCommandError,
                                onCancel = onCancel,
                            )
                        is MiningRunState.Success ->
                            terminalItems(
                                title = R.string.success_title,
                                headingModifier = headingModifier,
                                result = runState.result,
                                sourceDisplayName = terminalSourceDisplayName,
                                archiveDisplayName = terminalArchiveDisplayName,
                                partial = false,
                                failed = false,
                                failureDetails = null,
                                canRetry = false,
                                busy = targetState.resetPending,
                                resetError =
                                    targetState.commandError == ReadingMiningCommandError.RESET,
                                undoAvailable = targetState.undoAvailable,
                                undoneNoteCount = targetState.undoneNoteCount,
                                undoError = targetState.commandError == ReadingMiningCommandError.UNDO,
                                undoWordsError =
                                    targetState.commandError == ReadingMiningCommandError.UNDO_WORDS,
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
                                headingModifier = headingModifier,
                                result = runState.result,
                                sourceDisplayName = terminalSourceDisplayName,
                                archiveDisplayName = terminalArchiveDisplayName,
                                partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                                failed = false,
                                failureDetails = null,
                                canRetry = false,
                                busy = targetState.resetPending,
                                resetError =
                                    targetState.commandError == ReadingMiningCommandError.RESET,
                                undoAvailable = targetState.undoAvailable,
                                undoneNoteCount = targetState.undoneNoteCount,
                                undoError = targetState.commandError == ReadingMiningCommandError.UNDO,
                                undoWordsError =
                                    targetState.commandError == ReadingMiningCommandError.UNDO_WORDS,
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
                                headingModifier = headingModifier,
                                result = runState.result,
                                sourceDisplayName = terminalSourceDisplayName,
                                archiveDisplayName = terminalArchiveDisplayName,
                                partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                                failed = true,
                                failureDetails = runState.failure.message,
                                canRetry =
                                    runState.failure.retryable &&
                                        targetState.hasRetryableSelection(),
                                busy = targetState.resetPending || targetState.startPending,
                                resetError =
                                    targetState.commandError == ReadingMiningCommandError.RESET,
                                undoAvailable = targetState.undoAvailable,
                                undoneNoteCount = targetState.undoneNoteCount,
                                undoError = targetState.commandError == ReadingMiningCommandError.UNDO,
                                undoWordsError =
                                    targetState.commandError == ReadingMiningCommandError.UNDO_WORDS,
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
}

@Composable
private fun CurationPageImageSlot(
    curation: ReadingCurationUiState,
    modifier: Modifier = Modifier,
) {
    val pageImage = curation.pageImage ?: return
    val decoder = remember { CurationPageImageDecoder() }
    // The page pane and the curation controls are both pinned, so at large font scales they
    // compete for a viewport that cannot hold either in full — same rationale as
    // VideoMiningScreen's CurationPlayerSlot. Start folded and let the user open it; the toggle
    // below still persists whatever they choose.
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

    CurationPageImagePane(
        archivePath = pageImage.archivePath,
        pageContext = selectedSentence?.pageContext,
        collapsed = collapsed,
        onToggleCollapsed = { collapsed = !collapsed },
        decoder = decoder,
        modifier = modifier,
    )
}

private fun LazyListScope.setupItems(
    state: ReadingMiningUiState,
    onPickSource: () -> Unit,
    onPickArchive: () -> Unit,
    onClearSource: () -> Unit,
    onClearArchive: () -> Unit,
    onSourceModeChanged: (ReadingSourceMode) -> Unit,
    onPastedTextChanged: (String) -> Unit,
    onClearPastedText: () -> Unit,
    onSeriesNameChanged: (String) -> Unit,
    onDismissDocumentError: (ReadingDocumentSelectionError) -> Unit,
    onDismissCommandError: () -> Unit,
    onStart: () -> Unit,
    onReturnToActiveRun: (() -> Unit)?,
) {
    state.runtimeConflict?.let { conflict ->
        item(key = "reading_setup_conflict", contentType = "header") {
            RuntimeConflictNotice(
                text = stringResource(readingRuntimeConflictMessage(conflict)),
                onReturnToActiveRun =
                    onReturnToActiveRun.takeIf { conflict == RuntimeWorkConflict.MINING },
            )
        }
    }
    item(key = "reading_source_mode", contentType = "actions") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.sourceMode == ReadingSourceMode.FILE,
                onClick = { onSourceModeChanged(ReadingSourceMode.FILE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .testTag(ReadingMiningTestTags.SOURCE_MODE_FILE),
                enabled = !state.startPending,
                colors = segmentedActionColors(),
            ) {
                Text(stringResource(R.string.reading_source_mode_file))
            }
            SegmentedButton(
                selected = state.sourceMode == ReadingSourceMode.PASTED_TEXT,
                onClick = { onSourceModeChanged(ReadingSourceMode.PASTED_TEXT) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .testTag(ReadingMiningTestTags.SOURCE_MODE_TEXT),
                enabled = !state.startPending,
                colors = segmentedActionColors(),
            ) {
                Text(stringResource(R.string.reading_source_mode_text))
            }
        }
    }
    if (state.sourceMode == ReadingSourceMode.FILE) {
        item(key = "reading_sources", contentType = "candidate") {
            SourcesCard(
                sources =
                    buildList {
                        add(
                            MiningSourceItem(
                                label = stringResource(R.string.reading_source_label),
                                document = state.source.document,
                                isResolving = state.source.isResolving,
                                enabled = !state.startPending,
                                pickTestTag = ReadingMiningTestTags.PICK_SOURCE,
                                clearTestTag = ReadingMiningTestTags.CLEAR_SOURCE,
                                readKind = DocumentReadKind.DOCUMENT,
                                onPick = onPickSource,
                                onClear = onClearSource,
                            ),
                        )
                        if (state.acceptsArchive) {
                            add(
                                MiningSourceItem(
                                    label = stringResource(R.string.reading_archive_label),
                                    document = state.archive.document,
                                    isResolving = state.archive.isResolving,
                                    enabled = !state.startPending,
                                    pickTestTag = ReadingMiningTestTags.PICK_ARCHIVE,
                                    clearTestTag = ReadingMiningTestTags.CLEAR_ARCHIVE,
                                    readKind = DocumentReadKind.DOCUMENT,
                                    onPick = onPickArchive,
                                    onClear = onClearArchive,
                                ),
                            )
                        }
                    },
            )
        }
        state.source.error?.let { error ->
            item(key = "reading_source_error", contentType = "actions") {
                MiningFailureCard(
                    message = stringResource(error.messageResource()),
                    primaryAction =
                        MiningFailureAction(
                            label = stringResource(R.string.dismiss_error),
                            onClick = { onDismissDocumentError(error) },
                        ),
                )
            }
        }
        if (state.acceptsArchive) {
            state.archive.error?.let { error ->
                item(key = "reading_archive_error", contentType = "actions") {
                    MiningFailureCard(
                        message = stringResource(error.messageResource()),
                        primaryAction =
                            MiningFailureAction(
                                label = stringResource(R.string.dismiss_error),
                                onClick = { onDismissDocumentError(error) },
                            ),
                    )
                }
            }
        }
        if (state.sourceKind == ReadingSourceKindUi.SUBTITLE) {
            item(key = "reading_series_name", contentType = "actions") {
                OutlinedTextField(
                    value = state.subtitleSeriesName,
                    onValueChange = onSeriesNameChanged,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(ReadingMiningTestTags.SERIES_NAME),
                    enabled = !state.startPending,
                    singleLine = true,
                    label = { Text(stringResource(R.string.reading_series_label)) },
                )
            }
        }
    } else {
        item(key = "reading_pasted_text", contentType = "actions") {
            OutlinedTextField(
                value = state.pastedText,
                onValueChange = onPastedTextChanged,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(ReadingMiningTestTags.PASTE_TEXT),
                enabled = !state.startPending,
                singleLine = false,
                minLines = 6,
                placeholder = { Text(stringResource(R.string.reading_paste_placeholder)) },
                trailingIcon = {
                    if (state.pastedText.isNotEmpty()) {
                        IconButton(
                            onClick = onClearPastedText,
                            enabled = !state.startPending,
                            modifier = Modifier.testTag(ReadingMiningTestTags.CLEAR_PASTED_TEXT),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_remove),
                                contentDescription =
                                    stringResource(R.string.reading_paste_clear),
                            )
                        }
                    }
                },
                supportingText = {
                    Column {
                        Text(
                            stringResource(
                                R.string.reading_paste_counter,
                                state.pastedText.codePointCount(0, state.pastedText.length),
                            ),
                        )
                        if (state.pastedTextTruncated) {
                            Text(stringResource(R.string.reading_paste_truncated))
                        }
                    }
                },
            )
        }
    }
    item(key = "reading_start", contentType = "actions") {
        Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            PrimaryActionButton(
                onClick = onStart,
                enabled = state.canStart,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(ReadingMiningTestTags.START),
            ) {
                Text(stringResource(R.string.start_mining))
            }
            if (state.commandError == ReadingMiningCommandError.START) {
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
    item(key = "reading_progress", contentType = "header") {
        MiningProgressPanel(
            progress = progress,
            testTag = ReadingMiningTestTags.PROGRESS,
        )
    }
    if (canCancel) {
        item(key = "reading_cancel", contentType = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
                MiningCancelButton(
                    cancelPending = cancelPending,
                    testTag = ReadingMiningTestTags.CANCEL,
                    onCancel = onCancel,
                )
                if (cancelError) {
                    MiningFailureCard(
                        message = ReadingMiningCommandError.CANCEL.message(),
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
    state: ReadingMiningUiState,
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
        val candidateTestTag = ReadingMiningTestTags.candidate(candidate.candidateId)
        val toggleTestTag = ReadingMiningTestTags.candidateToggle(candidate.candidateId)
        val onToggle: (Boolean) -> Unit = { onSetCandidateSelected(candidate.candidateId, it) }
        val onFocus: () -> Unit = {
            onFocusCandidate(candidate.candidateId.takeUnless { expanded })
        }
        item(
            key = "reading_candidate:${candidate.candidateId}",
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
            item(
                key = "reading_actions:${candidate.candidateId}",
                contentType = "row_actions",
            ) {
                CurationRowActions(
                    containerColor = curationRowContainerColor(selected, animateSelection),
                    known = known,
                    enabled = enabled,
                    knownTestTag = ReadingMiningTestTags.candidateKnown(candidate.candidateId),
                    copyWordTestTag =
                        ReadingMiningTestTags.candidateCopyWord(candidate.candidateId),
                    copySentenceTestTag =
                        ReadingMiningTestTags.candidateCopySentence(candidate.candidateId),
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
                    key = "reading_definition:${candidate.candidateId}",
                    contentType = "definition",
                ) {
                    CurationDefinitionPane(
                        definition = definition,
                        containerColor =
                            curationRowContainerColor(selected, animateSelection),
                        term = candidate.minedForm,
                        testTag = ReadingMiningTestTags.DEFINITION,
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
                        ReadingMiningTestTags.sentence(
                            candidate.candidateId,
                            sentence.sentenceId,
                        )
                    val onClick = {
                        onSelectSentence(candidate.candidateId, sentence.sentenceId)
                    }
                    item(
                        key = "reading_sentence:${candidate.candidateId}:${sentence.sentenceId}",
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
                    key = "reading_chosen:${candidate.candidateId}",
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
                        testTag = ReadingMiningTestTags.chosenSentence(candidate.candidateId),
                        onClick = {
                            onSelectSentence(candidate.candidateId, layout.chosen.sentenceId)
                        },
                    )
                }
                item(
                    key = "reading_alts:${candidate.candidateId}",
                    contentType = "alternatives_toggle",
                ) {
                    CurationAlternativesToggle(
                        alternativeCount = layout.alternatives.size,
                        expanded = alternativesOpen,
                        containerColor =
                            curationRowContainerColor(selected, animateSelection),
                        enabled = enabled,
                        isLast = !alternativesOpen,
                        testTag = ReadingMiningTestTags.alternativesToggle(candidate.candidateId),
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
                            key = "reading_sentence:${candidate.candidateId}:${sentence.sentenceId}",
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
                                    ReadingMiningTestTags.sentence(
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
    headingModifier: Modifier,
    result: ProcessingResult?,
    sourceDisplayName: String?,
    archiveDisplayName: String?,
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
    item(key = "reading_terminal_header", contentType = "header") {
        PhaseTitle(
            text = stringResource(title),
            modifier = headingModifier,
        )
    }
    if (failed) {
        item(key = "reading_terminal_failure", contentType = "header") {
            MiningFailureCard(
                message =
                    if (resetError) {
                        ReadingMiningCommandError.RESET.message()
                    } else {
                        failureDetails ?: stringResource(title)
                    },
                primaryAction =
                    if (canRetry) {
                        MiningFailureAction(
                            label = stringResource(R.string.retry_mining),
                            testTag = ReadingMiningTestTags.RETRY,
                            enabled = !busy,
                            onClick = onRetry,
                        )
                    } else {
                        null
                    },
                secondaryAction =
                    MiningFailureAction(
                        label = stringResource(R.string.reset_mining),
                        testTag = ReadingMiningTestTags.RESET,
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
                buildList {
                    add(MiningResultSource(R.string.result_reading_source, sourceDisplayName))
                    archiveDisplayName?.let { archive ->
                        add(MiningResultSource(R.string.result_reading_archive, archive))
                    }
                },
            partial = partial,
            failed = failed,
            detailsExpanded = detailsExpanded,
            testTag = ReadingMiningTestTags.RESULT,
            keyPrefix = "reading_terminal_result",
            onToggleDetails = onToggleDetails,
            undo =
                finalResult.cardIds.takeIf { it.isNotEmpty() }?.let { cardIds ->
                    MiningResultUndoAction(
                        noteCount = cardIds.size,
                        undoneNoteCount = undoneNoteCount,
                        enabled = undoAvailable,
                        testTag = ReadingMiningTestTags.UNDO,
                        onUndo = onRequestUndo,
                    )
                },
        )
    }
    if (undoError || undoWordsError) {
        item(key = "reading_terminal_undo_error", contentType = "error") {
            MiningFailureCard(
                message =
                    if (undoError) {
                        ReadingMiningCommandError.UNDO.message()
                    } else {
                        ReadingMiningCommandError.UNDO_WORDS.message()
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
        item(key = "reading_terminal_actions", contentType = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
                SecondaryActionButton(
                    onClick = onReset,
                    enabled = !busy,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(ReadingMiningTestTags.RESET),
                ) {
                    Text(stringResource(R.string.reset_mining))
                }
                if (resetError) {
                    MiningFailureCard(
                        message = ReadingMiningCommandError.RESET.message(),
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
    state: ReadingMiningUiState,
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

private fun ReadingMiningUiState.scrollTransitionKey(): String =
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

private fun ReadingMiningUiState.phaseKey(): String =
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
private fun ReadingMiningUiState.phaseTitle(): Int =
    when (runState) {
        MiningRunState.Idle -> R.string.reading_phase_setup_title
        is MiningRunState.Starting -> R.string.starting_title
        is MiningRunState.Curating -> R.string.curation_title
        is MiningRunState.Running -> R.string.running_title
        is MiningRunState.Success -> R.string.success_title
        is MiningRunState.Cancelled -> R.string.cancelled_title
        is MiningRunState.Failed -> R.string.failed_title
    }

private fun ReadingMiningUiState.hasRetryableSelection(): Boolean =
    when (sourceMode) {
        ReadingSourceMode.FILE ->
            source.document != null &&
                sourceKind != null &&
                (!acceptsArchive || archive.document == null || archiveNamesMatch)
        ReadingSourceMode.PASTED_TEXT -> pastedText.isNotBlank()
    }

@StringRes
private fun readingRuntimeConflictMessage(conflict: RuntimeWorkConflict): Int =
    when (conflict) {
        RuntimeWorkConflict.MINING -> R.string.runtime_work_mining_active
        RuntimeWorkConflict.RESOURCE -> R.string.runtime_work_resource_active
        RuntimeWorkConflict.ANKI_SETUP -> R.string.runtime_work_anki_active
    }

@StringRes
private fun ReadingDocumentSelectionError.messageResource(): Int =
    when (this) {
        ReadingDocumentSelectionError.SOURCE_ACCESS -> R.string.reading_source_access_error
        ReadingDocumentSelectionError.SOURCE_TYPE -> R.string.reading_source_type_error
        ReadingDocumentSelectionError.ARCHIVE_ACCESS -> R.string.reading_archive_access_error
        ReadingDocumentSelectionError.ARCHIVE_TYPE -> R.string.reading_archive_type_error
        ReadingDocumentSelectionError.ARCHIVE_NAME -> R.string.reading_archive_name_error
    }

@Composable
private fun ReadingMiningCommandError.message(): String =
    stringResource(
        when (this) {
            ReadingMiningCommandError.START -> R.string.start_error
            ReadingMiningCommandError.CURATION -> R.string.curation_error
            ReadingMiningCommandError.CANCEL -> R.string.cancel_error
            ReadingMiningCommandError.RESET -> R.string.reset_error
            ReadingMiningCommandError.UNDO -> R.string.undo_failed
            ReadingMiningCommandError.UNDO_WORDS -> R.string.undo_words_failed
        },
    )
