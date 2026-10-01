package com.ankiminer.android.ui.reading

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.mining.terminalResult
import com.ankiminer.android.reading.CurationPageImageDecoder
import com.ankiminer.android.ui.mining.CurationAlternativesToggle
import com.ankiminer.android.ui.mining.curationDefinitionMaxHeight
import com.ankiminer.android.ui.mining.CurationRowPosition
import com.ankiminer.android.ui.mining.CurationInlinePageImage
import com.ankiminer.android.ui.mining.curationMediaMaxHeight
import com.ankiminer.android.ui.mining.curationRowPositions
import com.ankiminer.android.ui.mining.curationVisibleSelection
import com.ankiminer.android.ui.mining.CurationCandidateRow
import com.ankiminer.android.ui.mining.CurationCandidateRowText
import com.ankiminer.android.ui.mining.CurationChrome
import com.ankiminer.android.ui.mining.CurationDefinitionPane
import com.ankiminer.android.ui.mining.CurationFilter
import com.ankiminer.android.ui.mining.CurationRowActions
import com.ankiminer.android.ui.mining.CurationSentenceChoice
import com.ankiminer.android.ui.mining.curationSentenceLayout
import com.ankiminer.android.ui.mining.minedText
import com.ankiminer.android.ui.mining.CurationSort
import com.ankiminer.android.ui.mining.DocumentReadKind
import com.ankiminer.android.ui.mining.MiningFailureAction
import com.ankiminer.android.ui.mining.CURATING_PHASE
import com.ankiminer.android.ui.mining.MiningAdvisoryLines
import com.ankiminer.android.ui.mining.MiningBottomBar
import com.ankiminer.android.ui.mining.MiningBottomBarState
import com.ankiminer.android.ui.mining.MiningPhaseScaffold
import com.ankiminer.android.ui.mining.MiningResultHeadline
import com.ankiminer.android.ui.mining.ResetMiningScrollOnTransition
import com.ankiminer.android.ui.mining.endsWithResultLine
import com.ankiminer.android.ui.mining.SETUP_PHASE
import com.ankiminer.android.ui.mining.miningFailureBannerItem
import com.ankiminer.android.ui.mining.MiningFailureCard
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
import com.ankiminer.android.ui.mining.miningResultHeadline
import com.ankiminer.android.ui.mining.miningResultItems
import com.ankiminer.android.ui.mining.rememberCurationCandidateRowTexts
import com.ankiminer.android.ui.mining.rememberClipboardWriter
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.accentTextButtonColors
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
    onReconcileFocus: (List<String>, List<String>) -> Unit,
    onSelectSentence: (String, String) -> Unit,
    onConfirmCuration: () -> Unit,
    onFinishCuration: () -> Unit = {},
    onCancel: () -> Unit,
    onRequestUndo: () -> Unit = {},
    onConfirmUndo: () -> Unit = {},
    onDismissUndoConfirmation: () -> Unit = {},
    onReturnToActiveRun: (() -> Unit)? = null,
    onMapFields: () -> Unit = {},
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
    ResetMiningScrollOnTransition(
        transitionKey = state.scrollTransitionKey(),
        listState = listState,
        revealEnd = state.runState.endsWithResultLine,
    )
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

    MiningPhaseScaffold(
        state = state,
        phaseKey = if (state.runState is MiningRunState.Curating) CURATING_PHASE else SETUP_PHASE,
        label = "reading mining phase",
        phaseTitle = { target -> stringResource(target.phaseTitle()) },
        bottomBar = {
            ReadingMiningBottomBar(
                state = state,
                onStart = onStart,
                onCancel = onCancel,
                onConfirmCuration = onConfirmCuration,
                onDismissCommandError = onDismissCommandError,
            )
        },
        modifier = modifier.testTag(ReadingMiningTestTags.SCREEN),
    ) { targetState, paneHeight ->
        val mediaMaxHeight = curationMediaMaxHeight(paneHeight)
        val definitionMaxHeight = curationDefinitionMaxHeight(paneHeight)
        val targetCuration = targetState.curation
        val pageImageDecoder = remember { CurationPageImageDecoder() }
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
        val rowPositions =
            remember(visibleCandidateIds, expandedCandidateId) {
                curationRowPositions(visibleCandidateIds, expandedCandidateId)
            }
        ReconcileCurationFocus(
            visibleCandidateIds = visibleCandidateIds,
            focusedCandidateId = targetCuration?.focusedCandidateId,
            onReconcile = onReconcileFocus,
        )
        // Scoped to the projection, not the whole protocol page: a filtered bulk action must
        // not silently reach rows the search is hiding.
        val bulkSelectionScope =
            remember(visibleCandidateIds, targetCuration?.knownCandidateIds) {
                curationBulkSelectionScope(
                    visibleCandidateIds = visibleCandidateIds,
                    knownCandidateIds = targetCuration?.knownCandidateIds.orEmpty(),
                )
            }
        val selectableVisibleCandidateIds = bulkSelectionScope.visibleCandidateIds
        if (targetState.runState is MiningRunState.Curating && targetCuration != null) {
            CurationChrome(
                selectedCount = targetCuration.selectedCount,
                runSelectedCount =
                    targetCuration.previousPageSelectedCount + targetCuration.selectedCount,
                candidateCount = targetCuration.candidates.size,
                page = targetCuration.page,
                isFinalPage = targetCuration.isFinalPage,
                query = query,
                filter = filter,
                sort = sort,
                enabled = !targetState.curationPending && !targetState.cancelPending,
                visibleSelection =
                    curationVisibleSelection(selectableVisibleCandidateIds, selectedCandidateIds),
                visibleCount = bulkSelectionScope.visibleCount,
                selectVisibleEnabled =
                    selectableVisibleCandidateIds.isNotEmpty() &&
                        !targetState.curationPending &&
                        !targetState.cancelPending,
                selectAllTestTag = ReadingMiningTestTags.SELECT_ALL,
                finishTestTag = ReadingMiningTestTags.FINISH_CURATION,
                onQueryChanged = { query = it },
                onFilterChanged = { filterName = it.name },
                onSortChanged = { sortName = it.name },
                onSetSelectionForVisible = { select ->
                    onSetSelectionForVisible(selectableVisibleCandidateIds, select)
                },
                onFinishCuration = onFinishCuration,
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
            if (targetState.runState is MiningRunState.Curating) {
                curationItems(
                    state = targetState,
                    visibleCandidates = visibleCandidates,
                    candidateRowTexts = candidateRowTexts,
                    selectedCandidateStateText = selectedCandidateStateText,
                    excludedCandidateStateText = excludedCandidateStateText,
                    includeWordTemplate = includeWordTemplate,
                    excludeWordTemplate = excludeWordTemplate,
                    expandedCandidateId = expandedCandidateId,
                    rowPositions = rowPositions,
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
                    definitionMaxHeight = definitionMaxHeight,
                    pageImageDecoder = pageImageDecoder,
                    mediaMaxHeight = mediaMaxHeight,
                )
            } else {
                setupItems(
                    state = targetState,
                    detailsExpanded = resultDetailsExpanded,
                    onToggleDetails = { resultDetailsExpanded = !resultDetailsExpanded },
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
                    onMapFields = onMapFields,
                    onRequestUndo = onRequestUndo,
                    onReturnToActiveRun = onReturnToActiveRun,
                )
            }
        }
    }
}

private const val PASTED_TEXT_MAX_LINES = 8

private fun LazyListScope.setupItems(
    state: ReadingMiningUiState,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
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
    onMapFields: () -> Unit,
    onRequestUndo: () -> Unit,
    onReturnToActiveRun: (() -> Unit)?,
) {
    val runState = state.runState
    // A run in flight keeps its inputs on screen, locked: they are what it is mining.
    val locked = runState is MiningRunState.Starting || runState is MiningRunState.Running
    val inputsEnabled = !locked && !state.startPending
    (runState as? MiningRunState.Failed)?.let { failed ->
        miningFailureBannerItem(message = failed.failure.message, key = "reading_outcome_failure")
    }
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
                enabled = inputsEnabled,
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
                enabled = inputsEnabled,
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
                                enabled = inputsEnabled,
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
                                    enabled = inputsEnabled,
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
                    enabled = inputsEnabled,
                    singleLine = true,
                    label = { Text(stringResource(R.string.reading_series_label)) },
                )
            }
        }
    } else {
        item(key = "reading_pasted_text", contentType = "actions") {
            val context = LocalContext.current
            val clipboardHasText = rememberClipboardHasText()
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.micro),
            ) {
                OutlinedTextField(
                    value = state.pastedText,
                    onValueChange = onPastedTextChanged,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(ReadingMiningTestTags.PASTE_TEXT),
                    enabled = inputsEnabled,
                    singleLine = false,
                    minLines = 6,
                    // A pasted chapter scrolls inside the field instead of pushing Mine off the screen.
                    maxLines = PASTED_TEXT_MAX_LINES,
                    // Pasted source text in the mining language: its glyphs and its direction.
                    textStyle = LocalTextStyle.current.minedText(),
                    placeholder = { Text(stringResource(R.string.reading_paste_placeholder)) },
                    trailingIcon = {
                        if (state.pastedText.isNotEmpty()) {
                            IconButton(
                                onClick = onClearPastedText,
                                enabled = inputsEnabled,
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
                )
                if (state.pastedTextTruncated) {
                    SupportingText(
                        stringResource(
                            R.string.reading_paste_counter,
                            state.pastedText.codePointCount(0, state.pastedText.length),
                        ),
                    )
                    SupportingText(stringResource(R.string.reading_paste_truncated))
                }
                if (clipboardHasText) {
                    // Replaces the field without focusing it, so no keyboard opens.
                    TextButton(
                        onClick = { context.clipboardText()?.let(onPastedTextChanged) },
                        enabled = inputsEnabled,
                        modifier =
                            Modifier
                                .align(Alignment.End)
                                .heightIn(min = 48.dp)
                                .testTag(ReadingMiningTestTags.PASTE_FROM_CLIPBOARD),
                        colors = accentTextButtonColors(),
                    ) {
                        Text(stringResource(android.R.string.paste))
                    }
                }
            }
        }
    }
    if (state.advisories.any) {
        item(key = "reading_advisories", contentType = "hint") {
            MiningAdvisoryLines(
                advisories = state.advisories,
                onMapFields = onMapFields,
                mapFieldsTestTag = ReadingMiningTestTags.MAP_FIELDS,
            )
        }
    }
    // After a background process kill only the saved receipt is left: its sentence and its Undo.
    val headline =
        miningResultHeadline(runState, state.resultDeckName)
            ?: state.restoredReceipt?.let { MiningResultHeadline.NotesAdded(it.notesAdded, it.deckName) }
    headline?.let { lead ->
        val result = runState.terminalResult
        miningResultItems(
            headline = lead,
            result = result,
            failed = runState is MiningRunState.Failed,
            detailsExpanded = detailsExpanded,
            testTag = ReadingMiningTestTags.RESULT,
            keyPrefix = "reading_terminal_result",
            onToggleDetails = onToggleDetails,
            undo =
                (result?.cardIds ?: state.restoredReceipt?.noteIds)?.takeIf { it.isNotEmpty() }?.let { noteIds ->
                    MiningResultUndoAction(
                        noteCount = noteIds.size,
                        undoneNoteCount = state.undoneNoteCount,
                        enabled = state.undoAvailable,
                        testTag = ReadingMiningTestTags.UNDO,
                        onUndo = onRequestUndo,
                    )
                },
        )
    }
    state.commandError
        ?.takeIf { it == ReadingMiningCommandError.UNDO || it == ReadingMiningCommandError.UNDO_WORDS }
        ?.let { error ->
            item(key = "reading_undo_error", contentType = "error") {
                MiningFailureCard(
                    message = error.message(),
                    primaryAction =
                        MiningFailureAction(
                            label = stringResource(R.string.dismiss_error),
                            onClick = onDismissCommandError,
                        ),
                )
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
    rowPositions: List<CurationRowPosition>,
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
    definitionMaxHeight: Dp,
    pageImageDecoder: CurationPageImageDecoder,
    mediaMaxHeight: Dp,
) {
    val curation = state.curation ?: return
    val enabled = !state.curationPending && !state.cancelPending
    visibleCandidates.forEachIndexed { index, candidate ->
        val position = rowPositions[index]
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
                position = position,
                animateSelection = animateSelection,
                enabled = enabled,
                toggleEnabled = enabled && !known,
                candidateTestTag = candidateTestTag,
                toggleTestTag = toggleTestTag,
                onFocus = onFocus,
                onToggle = onToggle,
                modifier = Modifier.padding(bottom = curationGroupGap(last = !expanded && position.endsRun)),
            )
        }
        if (expanded) {
            val layout =
                curationSentenceLayout(
                    candidate = candidate,
                    selectedSentenceId = curation.sentenceIds[candidate.candidateId],
                )
            if (!layout.disclose) {
                candidate.sentences.forEach { sentence ->
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
                            testTag = sentenceTestTag,
                            onClick = onClick,
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
                        testTag = ReadingMiningTestTags.alternativesToggle(candidate.candidateId),
                        onToggle = onToggleAlternatives,
                    )
                }
                if (alternativesOpen) {
                    layout.alternatives.forEach { sentence ->
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
                                testTag =
                                    ReadingMiningTestTags.sentence(
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
                        maxHeight = definitionMaxHeight,
                    )
                }
            }
            val pageImage = curation.pageImage
            val pageContext = layout.chosen.pageContext
            if (pageImage != null && pageContext != null) {
                item(key = "reading_page_image:${candidate.candidateId}", contentType = "media") {
                    CurationInlinePageImage(
                        containerColor = curationRowContainerColor(selected, animateSelection),
                        archivePath = pageImage.archivePath,
                        pageContext = pageContext,
                        decoder = pageImageDecoder,
                        maxContentHeight = mediaMaxHeight,
                        modifier = Modifier.testTag(ReadingMiningTestTags.pageImage(candidate.candidateId)),
                    )
                }
            }
            item(
                key = "reading_actions:${candidate.candidateId}",
                contentType = "row_actions",
            ) {
                CurationRowActions(
                    containerColor = curationRowContainerColor(selected, animateSelection),
                    known = known,
                    enabled = enabled,
                    knownTestTag = ReadingMiningTestTags.candidateKnown(candidate.candidateId),
                    copyMenuTestTag = ReadingMiningTestTags.candidateCopyMenu(candidate.candidateId),
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
                    modifier = Modifier.padding(bottom = curationGroupGap(last = true)),
                )
            }
        }
    }
}

@Composable
private fun ReadingMiningBottomBar(
    state: ReadingMiningUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onConfirmCuration: () -> Unit,
    onDismissCommandError: () -> Unit,
) {
    val cancelError = state.commandError?.takeIf { it == ReadingMiningCommandError.CANCEL }?.message()
    when (val runState = state.runState) {
        is MiningRunState.Curating -> {
            val curation = state.curation
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
        is MiningRunState.Starting ->
            MiningBottomBar(
                state =
                    MiningBottomBarState.Progress(
                        progress = runState.progress,
                        canCancel = runState.cancellationToken != null || runState.runId != null,
                        cancelPending = state.cancelPending,
                        progressTestTag = ReadingMiningTestTags.PROGRESS,
                        cancelTestTag = ReadingMiningTestTags.CANCEL,
                        onCancel = onCancel,
                    ),
                commandErrorMessage = cancelError,
                onDismissCommandError = onDismissCommandError,
            )
        is MiningRunState.Running ->
            MiningBottomBar(
                state =
                    MiningBottomBarState.Progress(
                        progress = runState.progress,
                        canCancel = true,
                        cancelPending = state.cancelPending,
                        progressTestTag = ReadingMiningTestTags.PROGRESS,
                        cancelTestTag = ReadingMiningTestTags.CANCEL,
                        onCancel = onCancel,
                    ),
                commandErrorMessage = cancelError,
                onDismissCommandError = onDismissCommandError,
            )
        else ->
            MiningBottomBar(
                state =
                    MiningBottomBarState.Mine(
                        enabled = state.canStart,
                        testTag = ReadingMiningTestTags.START,
                        onMine = onStart,
                    ),
                commandErrorMessage =
                    state.commandError?.takeIf { it == ReadingMiningCommandError.START }?.message(),
                onDismissCommandError = onDismissCommandError,
            )
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
            ReadingMiningCommandError.UNDO -> R.string.undo_failed
            ReadingMiningCommandError.UNDO_WORDS -> R.string.undo_words_failed
        },
    )
