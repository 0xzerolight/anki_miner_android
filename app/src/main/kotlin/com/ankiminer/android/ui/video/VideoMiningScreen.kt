package com.ankiminer.android.ui.video

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationClipWindow
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.mining.terminalResult
import com.ankiminer.android.player.CurationPreviewPlayer
import com.ankiminer.android.player.ExoCurationPreviewPlayer
import com.ankiminer.android.ui.mining.ClipWindowSeconds
import com.ankiminer.android.ui.mining.LocalMiningContentStyle
import com.ankiminer.android.ui.mining.CurationAlternativesToggle
import com.ankiminer.android.ui.mining.curationDefinitionMaxHeight
import com.ankiminer.android.ui.mining.CurationRowPosition
import com.ankiminer.android.ui.mining.CurationInlinePreview
import com.ankiminer.android.ui.mining.CurationPlayerEffects
import com.ankiminer.android.ui.mining.curationMediaMaxHeight
import com.ankiminer.android.ui.mining.curationRowPositions
import com.ankiminer.android.ui.mining.curationVisibleSelection
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
import com.ankiminer.android.ui.mining.DocumentReadKind
import com.ankiminer.android.ui.mining.MiningFailureAction
import com.ankiminer.android.ui.mining.MiningFailureCard
import com.ankiminer.android.ui.mining.MediaMiningLabels
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
import com.ankiminer.android.ui.mining.miningResultHeadline
import com.ankiminer.android.ui.mining.miningResultItems
import com.ankiminer.android.ui.mining.rememberCurationCandidateRowTexts
import com.ankiminer.android.ui.mining.rememberClipboardWriter
import com.ankiminer.android.ui.mining.translationFor
import com.ankiminer.android.ui.settings.NumericField
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import com.ankiminer.android.ui.theme.accentTextButtonColors
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.SecondaryActionButton


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
    onReconcileFocus: (List<String>, List<String>) -> Unit,
    onSelectSentence: (String, String) -> Unit,
    onExpandSentencePrev: (String) -> Unit = {},
    onExpandSentenceNext: (String) -> Unit = {},
    onResetSentenceExpansion: (String) -> Unit = {},
    onSetClipWindow: (String, CurationClipWindow) -> Unit = { _, _ -> },
    onResetClipWindow: (String) -> Unit = {},
    onConfirmCuration: () -> Unit,
    onFinishCuration: () -> Unit = {},
    onCancel: () -> Unit,
    onRequestUndo: () -> Unit = {},
    onConfirmUndo: () -> Unit = {},
    onDismissUndoConfirmation: () -> Unit = {},
    onSubtitleOffsetDraftChange: (String) -> Unit = {},
    onPickSecondarySubtitle: () -> Unit = {},
    onClearSecondarySubtitle: () -> Unit = {},
    onSecondarySubtitleOffsetDraftChange: (String) -> Unit = {},
    onTestTiming: () -> Unit = {},
    audioTrackPicker: AudioTrackPickerState? = null,
    onAudioTracks: () -> Unit = {},
    onSelectAudioTrack: (Long?) -> Unit = {},
    onApplyAudioTrackPicker: () -> Unit = {},
    onDismissAudioTrackPicker: () -> Unit = {},
    onDismissAudioTrackPickerError: () -> Unit = {},
    onReturnToActiveRun: (() -> Unit)? = null,
    onMapFields: () -> Unit = {},
    labels: MediaMiningLabels = MediaMiningLabels.VIDEO,
    playerFactory: (Context) -> CurationPreviewPlayer =
        LocalMiningContentStyle.current.audioTrackCodes.let { codes ->
            { context -> ExoCurationPreviewPlayer(context, codes) }
        },
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
            confirmTestTag = VideoMiningTestTags.UNDO_CONFIRM,
        )
    }

    MiningPhaseScaffold(
        state = state,
        phaseKey = if (state.runState is MiningRunState.Curating) CURATING_PHASE else SETUP_PHASE,
        label = "video mining phase",
        phaseTitle = { target -> stringResource(target.phaseTitle(labels)) },
        bottomBar = {
            VideoMiningBottomBar(
                state = state,
                onStart = onStart,
                onCancel = onCancel,
                onConfirmCuration = onConfirmCuration,
                onDismissCommandError = onDismissCommandError,
            )
        },
        modifier = modifier,
    ) { targetState, paneHeight ->
        val mediaMaxHeight = curationMediaMaxHeight(paneHeight)
        val definitionMaxHeight = curationDefinitionMaxHeight(paneHeight)
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

        val playerState = targetCuration?.player
        if (player != null && targetCuration != null && playerState != null) {
            key(targetCuration.runId) {
                val focused =
                    targetCuration.candidates.firstOrNull {
                        it.candidateId == targetCuration.focusedCandidateId
                    }
                val selectedSentenceId =
                    focused?.let { targetCuration.sentenceIds[it.candidateId] ?: it.defaultSentenceId }
                val selectedSentence =
                    focused?.sentences?.firstOrNull { it.sentenceId == selectedSentenceId }
                CurationPlayerEffects(
                    player = player,
                    videoPath = playerState.videoPath,
                    audioTrackOverride = playerState.audioTrackOverride,
                    focusedCandidateId = targetCuration.focusedCandidateId,
                    selectedSentenceId = selectedSentenceId,
                    // "+ Previous line" and reset move the start and so the key;
                    // "+ Next line" does not reseek.
                    seekTarget =
                        targetCuration.expansionPreview?.startTime ?: selectedSentence?.startTime,
                )
            }
        }
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
                selectAllTestTag = VideoMiningTestTags.SELECT_ALL,
                finishTestTag = VideoMiningTestTags.FINISH_CURATION,
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
                    definitionMaxHeight = definitionMaxHeight,
                    player = player,
                    mediaMaxHeight = mediaMaxHeight,
                )
            } else {
                setupItems(
                    state = targetState,
                    labels = labels,
                    detailsExpanded = resultDetailsExpanded,
                    onToggleDetails = { resultDetailsExpanded = !resultDetailsExpanded },
                    onPickVideo = onPickVideo,
                    onPickSubtitle = onPickSubtitle,
                    onClearVideo = onClearVideo,
                    onClearSubtitle = onClearSubtitle,
                    onDismissDocumentError = onDismissDocumentError,
                    onDismissCommandError = onDismissCommandError,
                    onDismissTimingPreviewError = onDismissTimingPreviewError,
                    onSubtitleOffsetDraftChange = onSubtitleOffsetDraftChange,
                    onPickSecondarySubtitle = onPickSecondarySubtitle,
                    onClearSecondarySubtitle = onClearSecondarySubtitle,
                    onSecondarySubtitleOffsetDraftChange = onSecondarySubtitleOffsetDraftChange,
                    onTestTiming = onTestTiming,
                    onAudioTracks = onAudioTracks,
                    onDismissAudioTrackPickerError = onDismissAudioTrackPickerError,
                    onMapFields = onMapFields,
                    onRequestUndo = onRequestUndo,
                    onReturnToActiveRun = onReturnToActiveRun,
                )
            }
        }
    }

    audioTrackPicker?.let {
        AudioTrackPickerDialog(it, onSelectAudioTrack, onApplyAudioTrackPicker, onDismissAudioTrackPicker)
    }
}

@StringRes
private fun DocumentSelectionError.messageResource(labels: MediaMiningLabels): Int =
    when (this) {
        DocumentSelectionError.VIDEO -> labels.fileError
        DocumentSelectionError.AUDIO_TYPE -> R.string.audio_selection_error_type
        DocumentSelectionError.SUBTITLE -> R.string.subtitle_file_error
        DocumentSelectionError.SECONDARY_SUBTITLE -> R.string.secondary_subtitle_file_error
        DocumentSelectionError.SUBTITLE_TYPE,
        DocumentSelectionError.SECONDARY_SUBTITLE_TYPE,
        -> R.string.subtitle_selection_error_type
    }

private fun LazyListScope.setupItems(
    state: VideoMiningUiState,
    labels: MediaMiningLabels,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onPickVideo: () -> Unit,
    onPickSubtitle: () -> Unit,
    onClearVideo: () -> Unit,
    onClearSubtitle: () -> Unit,
    onDismissDocumentError: (DocumentSelectionError) -> Unit,
    onDismissCommandError: () -> Unit,
    onDismissTimingPreviewError: () -> Unit,
    onSubtitleOffsetDraftChange: (String) -> Unit,
    onPickSecondarySubtitle: () -> Unit,
    onClearSecondarySubtitle: () -> Unit,
    onSecondarySubtitleOffsetDraftChange: (String) -> Unit,
    onTestTiming: () -> Unit,
    onAudioTracks: () -> Unit,
    onDismissAudioTrackPickerError: () -> Unit,
    onMapFields: () -> Unit,
    onRequestUndo: () -> Unit,
    onReturnToActiveRun: (() -> Unit)?,
) {
    val runState = state.runState
    // A run in flight keeps its inputs on screen, locked: they are what it is mining.
    val locked = runState is MiningRunState.Starting || runState is MiningRunState.Running
    val sourcesEnabled = state.canEditSources
    (runState as? MiningRunState.Failed)?.let { failed ->
        miningFailureBannerItem(message = failed.failure.message, key = "outcome_failure")
    }
    state.runtimeConflict?.let { conflict ->
        item(key = "setup_conflict", contentType = "header") {
            RuntimeConflictNotice(
                text = stringResource(runtimeConflictMessage(conflict)),
                onReturnToActiveRun =
                    onReturnToActiveRun.takeIf { conflict == RuntimeWorkConflict.MINING },
            )
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
                        enabled = sourcesEnabled,
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
                        enabled = sourcesEnabled,
                        pickTestTag = VideoMiningTestTags.PICK_SUBTITLE,
                        clearTestTag = VideoMiningTestTags.CLEAR_SUBTITLE,
                        readKind = DocumentReadKind.SUBTITLES,
                        onPick = onPickSubtitle,
                        onClear = onClearSubtitle,
                    ),
                ) +
                    listOfNotNull(
                        MiningSourceItem(
                            label = stringResource(R.string.video_secondary_subtitle_label),
                            document = state.secondarySubtitle.document,
                            isResolving = state.secondarySubtitle.isResolving,
                            enabled = sourcesEnabled,
                            pickTestTag = VideoMiningTestTags.PICK_SECONDARY_SUBTITLE,
                            clearTestTag = VideoMiningTestTags.CLEAR_SECONDARY_SUBTITLE,
                            readKind = DocumentReadKind.SUBTITLES,
                            onPick = onPickSecondarySubtitle,
                            onClear = onClearSecondarySubtitle,
                        ).takeIf { state.secondarySubtitleEnabled },
                    ),
        )
    }
    if (state.video.document == null && state.subtitle.document == null) {
        item(key = "setup_hint", contentType = "hint") {
            SupportingText(
                text = stringResource(labels.setupHint),
                modifier = Modifier.testTag(VideoMiningTestTags.SETUP_HINT),
            )
        }
    }
    if (state.subtitle.document != null) {
        item(key = "subtitle_offset", contentType = "field") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                verticalAlignment = Alignment.Top,
            ) {
                NumericField(
                    value = state.subtitleOffsetDraft,
                    onChange = onSubtitleOffsetDraftChange,
                    label = stringResource(labels.subtitleOffsetLabel),
                    allowNegative = true,
                    enabled = !locked && !state.timingPreviewPending,
                    error =
                        stringResource(R.string.b3_validation_numeric_incomplete)
                            .takeIf { state.subtitleOffsetDraftInvalid },
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag(VideoMiningTestTags.SUBTITLE_OFFSET_FIELD),
                    placeholder = {
                        Text(
                            stringResource(
                                R.string.video_subtitle_offset_placeholder,
                                state.effectiveSubtitleOffset.toString(),
                            ),
                        )
                    },
                )
                // Test timing lives with the offset it tests (desktop A05).
                TextButton(
                    onClick = onTestTiming,
                    enabled = state.canTestTiming,
                    modifier =
                        Modifier
                            .padding(top = AnkiMinerTokens.Space.related)
                            .heightIn(min = 48.dp)
                            .testTag(VideoMiningTestTags.TEST_TIMING),
                    colors = accentTextButtonColors(),
                ) {
                    Text(stringResource(R.string.timing_preview_test_action))
                }
            }
        }
    }
    if (state.secondarySubtitleEnabled) {
        item(key = "secondary_subtitle_offset", contentType = "field") {
            NumericField(
                value = state.secondarySubtitleOffsetDraft,
                onChange = onSecondarySubtitleOffsetDraftChange,
                label = stringResource(R.string.video_secondary_subtitle_offset_label),
                allowNegative = true,
                integer = true,
                enabled = !locked && !state.timingPreviewPending,
                error =
                    stringResource(R.string.video_secondary_subtitle_offset_error)
                        .takeIf { state.secondarySubtitleOffsetDraftInvalid },
                modifier = Modifier.testTag(VideoMiningTestTags.SECONDARY_SUBTITLE_OFFSET_FIELD),
            )
        }
    }
    state.video.error?.let { error ->
        item(key = "video_file_error", contentType = "actions") {
            MiningFailureCard(
                message = stringResource(error.messageResource(labels)),
                primaryAction =
                    MiningFailureAction(
                        label = stringResource(R.string.dismiss_error),
                        onClick = { onDismissDocumentError(error) },
                    ),
            )
        }
    }
    state.subtitle.error?.let { error ->
        item(key = "subtitle_file_error", contentType = "actions") {
            MiningFailureCard(
                message = stringResource(error.messageResource(labels)),
                primaryAction =
                    MiningFailureAction(
                        label = stringResource(R.string.dismiss_error),
                        onClick = { onDismissDocumentError(error) },
                    ),
            )
        }
    }
    state.secondarySubtitle.error?.takeIf { state.secondarySubtitleEnabled }?.let { error ->
        item(key = "secondary_subtitle_file_error", contentType = "actions") {
            MiningFailureCard(
                message = stringResource(error.messageResource(labels)),
                primaryAction =
                    MiningFailureAction(
                        label = stringResource(R.string.dismiss_error),
                        onClick = { onDismissDocumentError(error) },
                    ),
            )
        }
    }
    // No probe on pick: it would take the runtime lease before the user asked for tracks.
    if (labels.showsAudioTracks && state.video.document != null) {
        item(key = "audio_tracks", contentType = "actions") {
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
        }
    }
    if (state.advisories.any) {
        item(key = "advisories", contentType = "hint") {
            MiningAdvisoryLines(
                advisories = state.advisories,
                onMapFields = onMapFields,
                mapFieldsTestTag = VideoMiningTestTags.MAP_FIELDS,
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
            testTag = VideoMiningTestTags.RESULT,
            keyPrefix = "terminal_result",
            onToggleDetails = onToggleDetails,
            undo =
                (result?.cardIds ?: state.restoredReceipt?.noteIds)?.takeIf { it.isNotEmpty() }?.let { noteIds ->
                    MiningResultUndoAction(
                        noteCount = noteIds.size,
                        undoneNoteCount = state.undoneNoteCount,
                        enabled = state.undoAvailable,
                        testTag = VideoMiningTestTags.UNDO,
                        onUndo = onRequestUndo,
                    )
                },
        )
    }
    state.commandError
        ?.takeIf { it == MiningCommandError.UNDO || it == MiningCommandError.UNDO_WORDS }
        ?.let { error ->
            item(key = "undo_error", contentType = "error") {
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
    state.timingPreviewError?.let { error ->
        item(key = "timing_preview_error", contentType = "error") {
            MiningFailureCard(
                message = stringResource(error.messageResource()),
                primaryAction =
                    MiningFailureAction(
                        label = stringResource(R.string.dismiss_error),
                        onClick = onDismissTimingPreviewError,
                    ),
            )
        }
    }
    state.audioTrackPickerError?.let { error ->
        item(key = "audio_track_picker_error", contentType = "error") {
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

private fun LazyListScope.curationItems(
    state: VideoMiningUiState,
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
    definitionMaxHeight: Dp,
    player: CurationPreviewPlayer?,
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
                        VideoMiningTestTags.sentence(
                            candidate.candidateId,
                            sentence.sentenceId,
                        )
                    val onClick = {
                        onSelectSentence(candidate.candidateId, sentence.sentenceId)
                    }
                    val chosen = sentence.sentenceId == curation.sentenceIds[candidate.candidateId]
                    item(
                        key = "sentence:${candidate.candidateId}:${sentence.sentenceId}",
                        contentType = "sentence",
                    ) {
                        CurationSentenceChoice(
                            candidate = candidate,
                            sentence = sentence,
                            containerColor =
                                curationRowContainerColor(selected, animateSelection),
                            selected = chosen,
                            enabled = enabled,
                            testTag = sentenceTestTag,
                            onClick = onClick,
                            selectable = layout.selectable,
                            translation =
                                if (chosen) {
                                    sentence.translationFor(curation.lineExpansions[candidate.candidateId])
                                } else {
                                    sentence.translation
                                },
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
                        testTag = VideoMiningTestTags.chosenSentence(candidate.candidateId),
                        onClick = {
                            onSelectSentence(candidate.candidateId, layout.chosen.sentenceId)
                        },
                        translation =
                            layout.chosen.translationFor(curation.lineExpansions[candidate.candidateId]),
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
                        testTag = VideoMiningTestTags.alternativesToggle(candidate.candidateId),
                        onToggle = onToggleAlternatives,
                    )
                }
                if (alternativesOpen) {
                    layout.alternatives.forEach { sentence ->
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
                                testTag =
                                    VideoMiningTestTags.sentence(
                                        candidate.candidateId,
                                        sentence.sentenceId,
                                    ),
                                onClick = {
                                    onSelectSentence(candidate.candidateId, sentence.sentenceId)
                                },
                                translation = sentence.translation,
                            )
                        }
                    }
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
                        maxHeight = definitionMaxHeight,
                    )
                }
            }
            val inlinePlayer = curation.player
            if (player != null && inlinePlayer != null) {
                item(key = "media:${candidate.candidateId}", contentType = "media") {
                    CurationInlinePreview(
                        containerColor = curationRowContainerColor(selected, animateSelection),
                        player = player,
                        playerState = inlinePlayer,
                        maxSurfaceHeight = mediaMaxHeight,
                        cuesUnavailableTestTag = VideoMiningTestTags.CUES_UNAVAILABLE,
                    )
                }
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
            item(key = "actions:${candidate.candidateId}", contentType = "row_actions") {
                CurationRowActions(
                    containerColor = curationRowContainerColor(selected, animateSelection),
                    known = known,
                    enabled = enabled,
                    knownTestTag = VideoMiningTestTags.candidateKnown(candidate.candidateId),
                    copyMenuTestTag = VideoMiningTestTags.candidateCopyMenu(candidate.candidateId),
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
                    modifier = Modifier.padding(bottom = curationGroupGap(last = true)),
                )
            }
        }
    }
}

@Composable
private fun VideoMiningBottomBar(
    state: VideoMiningUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onConfirmCuration: () -> Unit,
    onDismissCommandError: () -> Unit,
) {
    val cancelError = state.commandError?.takeIf { it == MiningCommandError.CANCEL }?.message()
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
        is MiningRunState.Starting ->
            MiningBottomBar(
                state =
                    MiningBottomBarState.Progress(
                        progress = runState.progress,
                        canCancel = runState.cancellationToken != null || runState.runId != null,
                        cancelPending = state.cancelPending,
                        progressTestTag = VideoMiningTestTags.PROGRESS,
                        cancelTestTag = VideoMiningTestTags.CANCEL,
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
                        progressTestTag = VideoMiningTestTags.PROGRESS,
                        cancelTestTag = VideoMiningTestTags.CANCEL,
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
                        testTag = VideoMiningTestTags.START,
                        onMine = onStart,
                    ),
                commandErrorMessage = state.commandError?.takeIf { it == MiningCommandError.START }?.message(),
                onDismissCommandError = onDismissCommandError,
            )
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
