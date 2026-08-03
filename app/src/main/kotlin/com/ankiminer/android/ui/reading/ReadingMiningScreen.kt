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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.ankiminer.android.ui.mining.CurationCandidateHeader
import com.ankiminer.android.ui.mining.CurationControls
import com.ankiminer.android.ui.mining.CurationDefinitionPane
import com.ankiminer.android.ui.mining.CurationFilter
import com.ankiminer.android.ui.mining.CurationRowActions
import com.ankiminer.android.ui.mining.CurationSentenceChoice
import com.ankiminer.android.ui.mining.CurationSort
import com.ankiminer.android.ui.mining.DocumentReadKind
import com.ankiminer.android.ui.mining.MiningFailureAction
import com.ankiminer.android.ui.mining.MiningFailureCard
import com.ankiminer.android.ui.mining.MINING_PHASE_HEADING_TEST_TAG
import com.ankiminer.android.ui.mining.MiningPhaseTarget
import com.ankiminer.android.ui.mining.MiningProgressPanel
import com.ankiminer.android.ui.mining.MiningResultSource
import com.ankiminer.android.ui.mining.ReconcileCurationFocus
import com.ankiminer.android.ui.mining.MiningSourceItem
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

@Composable
fun ReadingMiningScreen(
    state: ReadingMiningUiState,
    onPickSource: () -> Unit,
    onPickArchive: () -> Unit,
    onClearSource: () -> Unit,
    onClearArchive: () -> Unit,
    onSeriesNameChanged: (String) -> Unit,
    onDismissDocumentError: (ReadingDocumentSelectionError) -> Unit,
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

            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding)
                        .consumeWindowInsets(scaffoldPadding)
                        .testTag(ReadingMiningTestTags.CONTENT)
                        .semantics { paneTitle = phaseTitle },
                contentPadding = PaddingValues(AnkiMinerTokens.Space.content),
                verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.group),
            ) {
                when (val runState = targetState.runState) {
                    MiningRunState.Idle ->
                        setupItems(
                            state = targetState,
                            headingModifier = headingModifier,
                            onPickSource = onPickSource,
                            onPickArchive = onPickArchive,
                            onClearSource = onClearSource,
                            onClearArchive = onClearArchive,
                            onSeriesNameChanged = onSeriesNameChanged,
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
                            cancelError =
                                targetState.commandError == ReadingMiningCommandError.CANCEL,
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
                            sourceDisplayName = targetState.source.document?.displayName,
                            archiveDisplayName = targetState.archive.document?.displayName,
                            partial = false,
                            failed = false,
                            failureDetails = null,
                            canRetry = false,
                            busy = targetState.resetPending,
                            resetError =
                                targetState.commandError == ReadingMiningCommandError.RESET,
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
                            sourceDisplayName = targetState.source.document?.displayName,
                            archiveDisplayName = targetState.archive.document?.displayName,
                            partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                            failed = false,
                            failureDetails = null,
                            canRetry = false,
                            busy = targetState.resetPending,
                            resetError =
                                targetState.commandError == ReadingMiningCommandError.RESET,
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
                            sourceDisplayName = targetState.source.document?.displayName,
                            archiveDisplayName = targetState.archive.document?.displayName,
                            partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                            failed = true,
                            failureDetails = runState.failure.message,
                            canRetry =
                                runState.failure.retryable &&
                                    targetState.hasRetryableSelection(),
                            busy = targetState.resetPending || targetState.startPending,
                            resetError =
                                targetState.commandError == ReadingMiningCommandError.RESET,
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

private fun LazyListScope.setupItems(
    state: ReadingMiningUiState,
    headingModifier: Modifier,
    onPickSource: () -> Unit,
    onPickArchive: () -> Unit,
    onClearSource: () -> Unit,
    onClearArchive: () -> Unit,
    onSeriesNameChanged: (String) -> Unit,
    onDismissDocumentError: (ReadingDocumentSelectionError) -> Unit,
    onDismissCommandError: () -> Unit,
    onStart: () -> Unit,
    onReturnToActiveRun: (() -> Unit)?,
) {
    item(key = "reading_setup_header", contentType = "header") {
        Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            PhaseTitle(
                text = stringResource(R.string.reading_phase_setup_title),
                modifier = headingModifier,
            )
            state.runtimeConflict?.let { conflict ->
                RuntimeConflictNotice(
                    text = stringResource(readingRuntimeConflictMessage(conflict)),
                    onReturnToActiveRun =
                        onReturnToActiveRun.takeIf {
                            conflict == RuntimeWorkConflict.MINING
                        },
                )
            }
        }
    }
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
    item(key = "reading_start", contentType = "actions") {
        Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Button(
                onClick = onStart,
                enabled = state.canStart,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(ReadingMiningTestTags.START),
                colors = forwardButtonColors(),
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
    @StringRes title: Int,
    headingModifier: Modifier,
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
            headingModifier = headingModifier,
            title = title,
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

    item(key = "reading_curation_header", contentType = "header") {
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
    item(key = "reading_curation_controls", contentType = "actions") {
        Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            OutlinedButton(
                onClick = {
                    onSetSelectionForVisible(visibleCandidateIds, !allVisibleSelected)
                },
                enabled = selectableVisibleCandidateIds.isNotEmpty() && enabled,
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .testTag(ReadingMiningTestTags.SELECT_ALL),
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
        val candidateTestTag = ReadingMiningTestTags.candidate(candidate.candidateId)
        val toggleTestTag = ReadingMiningTestTags.candidateToggle(candidate.candidateId)
        val onToggle: (Boolean) -> Unit = { onSetCandidateSelected(candidate.candidateId, it) }
        val onFocus: () -> Unit = { onFocusCandidate(candidate.candidateId) }
        item(
            key = "reading_candidate:${candidate.candidateId}",
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
            item(
                key = "reading_actions:${candidate.candidateId}",
                contentType = "row_actions",
            ) {
                CurationRowActions(
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
                        term = candidate.minedForm,
                        testTag = ReadingMiningTestTags.DEFINITION,
                    )
                }
            }
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
    sourceDisplayName: String?,
    archiveDisplayName: String?,
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
                        stringResource(R.string.mining_failure_summary)
                    },
                diagnosticDetails = failureDetails,
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
    result?.let {
        miningResultItems(
            result = it,
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
        )
    }
    if (!failed) {
        item(key = "reading_terminal_actions", contentType = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
                OutlinedButton(
                    onClick = onReset,
                    enabled = !busy,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(ReadingMiningTestTags.RESET),
                    colors = outlinedActionButtonColors(),
                    border = actionBorder(enabled = !busy),
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
    source.document != null &&
        sourceKind != null &&
        (!acceptsArchive || archive.document == null || archiveNamesMatch)

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
        },
    )
