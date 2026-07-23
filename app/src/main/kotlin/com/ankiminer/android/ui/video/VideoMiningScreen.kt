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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningRunState
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.mining.RuntimeWorkConflict
import com.ankiminer.android.ui.mining.DocumentReadKind
import com.ankiminer.android.ui.mining.DocumentReadProgressText
import com.ankiminer.android.ui.mining.MiningErrorMessage
import com.ankiminer.android.ui.mining.MiningProgressPanel
import com.ankiminer.android.ui.mining.MiningResultSource
import com.ankiminer.android.ui.mining.MiningResultSummary
import com.ankiminer.android.ui.mining.MiningScreenTopBar
import com.ankiminer.android.ui.mining.StickyCurationActions
import com.ankiminer.android.ui.mining.documentReadProgress

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
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { MiningScreenTopBar() },
        bottomBar = {
            if (state.runState is MiningRunState.Curating) {
                val curation = state.curation
                StickyCurationActions(
                    selectedCount = curation?.selectedCount ?: 0,
                    page = curation?.page,
                    isFinalPage = curation?.isFinalPage ?: true,
                    curationPending = state.curationPending,
                    cancelPending = state.cancelPending,
                    confirmTestTag = VideoMiningTestTags.CONFIRM_CURATION,
                    cancelTestTag = VideoMiningTestTags.CANCEL,
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.commandError?.let { commandError ->
                item(key = "command_error") {
                    MiningErrorMessage(
                        message = commandError.message(),
                        onDismiss = onDismissCommandError,
                    )
                }
            }

            when (val runState = state.runState) {
                MiningRunState.Idle ->
                    setupItems(
                        state = state,
                        onPickVideo = onPickVideo,
                        onPickSubtitle = onPickSubtitle,
                        onClearVideo = onClearVideo,
                        onClearSubtitle = onClearSubtitle,
                        onDismissDocumentError = onDismissDocumentError,
                        onStart = onStart,
                    )
                is MiningRunState.Starting ->
                    progressItems(
                        title = R.string.starting_title,
                        progress = runState.progress,
                        canCancel = runState.cancellationToken != null || runState.runId != null,
                        cancelPending = state.cancelPending,
                        onCancel = onCancel,
                    )
                is MiningRunState.Curating ->
                    curationItems(
                        state = state,
                        onToggleCandidate = onToggleCandidate,
                        onSelectAllCandidates = onSelectAllCandidates,
                        onSelectSentence = onSelectSentence,
                    )
                is MiningRunState.Running ->
                    progressItems(
                        title = R.string.running_title,
                        progress = runState.progress,
                        canCancel = true,
                        cancelPending = state.cancelPending,
                        onCancel = onCancel,
                    )
                is MiningRunState.Success ->
                    terminalItems(
                        title = R.string.success_title,
                        result = runState.result,
                        videoDisplayName = state.video.document?.displayName,
                        subtitleDisplayName = state.subtitle.document?.displayName,
                        failureMessage = null,
                        partial = false,
                        canRetry = false,
                        busy = state.resetPending,
                        onRetry = onRetry,
                        onReset = onReset,
                    )
                is MiningRunState.Cancelled ->
                    terminalItems(
                        title = R.string.cancelled_title,
                        result = runState.result,
                        videoDisplayName = state.video.document?.displayName,
                        subtitleDisplayName = state.subtitle.document?.displayName,
                        failureMessage = null,
                        partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                        canRetry = false,
                        busy = state.resetPending,
                        onRetry = onRetry,
                        onReset = onReset,
                    )
                is MiningRunState.Failed ->
                    terminalItems(
                        title = R.string.failed_title,
                        result = runState.result,
                        videoDisplayName = state.video.document?.displayName,
                        subtitleDisplayName = state.subtitle.document?.displayName,
                        failureMessage = runState.failure.message,
                        partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                        canRetry =
                            runState.failure.retryable &&
                                state.video.document != null &&
                                state.subtitle.document != null,
                        busy = state.resetPending || state.startPending,
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
    onStart: () -> Unit,
) {
    item(key = "setup_header") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.video_mining_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.video_mining_intro),
                style = MaterialTheme.typography.bodyLarge,
            )
            state.runtimeConflict?.let { conflict ->
                Text(
                    text = stringResource(runtimeConflictMessage(conflict)),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    item(key = "video_file") {
        DocumentCard(
            label = stringResource(R.string.video_file_label),
            document = state.video.document,
            isResolving = state.video.isResolving,
            enabled = !state.startPending,
            pickTestTag = VideoMiningTestTags.PICK_VIDEO,
            clearTestTag = VideoMiningTestTags.CLEAR_VIDEO,
            readKind = DocumentReadKind.VIDEO,
            onPick = onPickVideo,
            onClear = onClearVideo,
        )
    }
    if (state.video.error != null) {
        item(key = "video_file_error") {
            MiningErrorMessage(
                message = stringResource(R.string.video_file_error),
                onDismiss = { onDismissDocumentError(DocumentSelectionError.VIDEO) },
            )
        }
    }
    item(key = "subtitle_file") {
        DocumentCard(
            label = stringResource(R.string.subtitle_file_label),
            document = state.subtitle.document,
            isResolving = state.subtitle.isResolving,
            enabled = !state.startPending,
            pickTestTag = VideoMiningTestTags.PICK_SUBTITLE,
            clearTestTag = VideoMiningTestTags.CLEAR_SUBTITLE,
            readKind = DocumentReadKind.SUBTITLES,
            onPick = onPickSubtitle,
            onClear = onClearSubtitle,
        )
    }
    if (state.subtitle.error != null) {
        item(key = "subtitle_file_error") {
            MiningErrorMessage(
                message = stringResource(R.string.subtitle_file_error),
                onDismiss = { onDismissDocumentError(DocumentSelectionError.SUBTITLE) },
            )
        }
    }
    item(key = "start") {
        Button(
            onClick = onStart,
            enabled = state.canStart,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(VideoMiningTestTags.START),
        ) {
            Text(stringResource(R.string.start_mining))
        }
    }
}

@StringRes
private fun runtimeConflictMessage(conflict: RuntimeWorkConflict): Int =
    when (conflict) {
        RuntimeWorkConflict.MINING -> R.string.runtime_work_mining_active
        RuntimeWorkConflict.RESOURCE -> R.string.runtime_work_resource_active
        RuntimeWorkConflict.ANKI_SETUP -> R.string.runtime_work_anki_active
    }

private fun LazyListScope.progressItems(
    title: Int,
    progress: MiningProgress?,
    canCancel: Boolean,
    cancelPending: Boolean,
    onCancel: () -> Unit,
) {
    item(key = "progress") {
        MiningProgressPanel(
            title = stringResource(title),
            progress = progress,
            testTag = VideoMiningTestTags.PROGRESS,
        )
    }
    if (canCancel) {
        item(key = "cancel") {
            OutlinedButton(
                onClick = onCancel,
                enabled = !cancelPending,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(VideoMiningTestTags.CANCEL),
            ) {
                Text(stringResource(R.string.cancel_mining))
            }
        }
    }
}

private fun LazyListScope.curationItems(
    state: VideoMiningUiState,
    onToggleCandidate: (String) -> Unit,
    onSelectAllCandidates: (Boolean) -> Unit,
    onSelectSentence: (String, String) -> Unit,
) {
    val curation = state.curation
    val candidates = curation?.candidates.orEmpty()
    val selectedCount = candidates.count { it.selected }
    val allSelected = candidates.isNotEmpty() && selectedCount == candidates.size

    item(key = "curation_header") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.curation_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    if (curation?.page == null) {
                        R.string.curation_selected_count
                    } else {
                        R.string.curation_selected_count_page
                    },
                    selectedCount,
                    candidates.size,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            curation?.page?.let { page ->
                Text(
                    text =
                        stringResource(
                            R.string.curation_page_position,
                            page.pageIndex + 1,
                            page.pageCount,
                            page.candidateStart + 1,
                            page.candidateStart + candidates.size,
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
            OutlinedButton(
                onClick = { onSelectAllCandidates(!allSelected) },
                enabled =
                    candidates.isNotEmpty() &&
                        !state.curationPending &&
                        !state.cancelPending,
                modifier = Modifier.testTag(VideoMiningTestTags.SELECT_ALL),
            ) {
                Text(
                    stringResource(
                        if (allSelected) R.string.deselect_all else R.string.select_all,
                    ),
                )
            }
        }
    }
    // Sentence choices share the outer LazyColumn so a candidate with many occurrences does not
    // eagerly compose every row inside one card.
    candidates.forEach { candidateState ->
        val candidate = candidateState.candidate
        item(key = "candidate:${candidate.candidateId}") {
            CandidateCard(
                state = candidateState,
                enabled = !state.curationPending && !state.cancelPending,
                onToggle = { onToggleCandidate(candidate.candidateId) },
            )
        }
        items(
            items = candidate.sentences,
            key = { sentence -> "sentence:${candidate.candidateId}:${sentence.sentenceId}" },
        ) { sentence ->
            SentenceChoice(
                candidate = candidate,
                sentence = sentence,
                selected = sentence.sentenceId == candidateState.sentenceId,
                enabled = !state.curationPending && !state.cancelPending,
                onClick = { onSelectSentence(candidate.candidateId, sentence.sentenceId) },
            )
        }
    }
}

private fun LazyListScope.terminalItems(
    title: Int,
    result: ProcessingResult?,
    videoDisplayName: String?,
    subtitleDisplayName: String?,
    failureMessage: String?,
    partial: Boolean,
    canRetry: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    item(key = "terminal_header") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            failureMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
    result?.let {
        item(key = "terminal_result") {
            MiningResultSummary(
                result = it,
                sources =
                    listOf(
                        MiningResultSource(R.string.result_video, videoDisplayName),
                        MiningResultSource(R.string.result_subtitle, subtitleDisplayName),
                    ),
                partial = partial,
                testTag = VideoMiningTestTags.RESULT,
            )
        }
    }
    item(key = "terminal_actions") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRetry) {
                Button(
                    onClick = onRetry,
                    enabled = !busy,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(VideoMiningTestTags.RETRY),
                ) {
                    Text(stringResource(R.string.retry_mining))
                }
            }
            OutlinedButton(
                onClick = onReset,
                enabled = !busy,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(VideoMiningTestTags.RESET),
            ) {
                Text(stringResource(R.string.reset_mining))
            }
        }
    }
}

@Composable
private fun DocumentCard(
    label: String,
    document: SafDocument?,
    isResolving: Boolean,
    enabled: Boolean,
    pickTestTag: String,
    clearTestTag: String,
    readKind: DocumentReadKind,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (isResolving) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    DocumentReadProgressText(
                        documentReadProgress(readKind, displayName = document?.displayName),
                    )
                }
            } else {
                Text(
                    text = document?.displayName ?: stringResource(R.string.no_file_selected),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Button(
                onClick = onPick,
                enabled = enabled && !isResolving,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(pickTestTag),
            ) {
                Text(
                    stringResource(
                        if (document == null) R.string.choose_file else R.string.replace_file,
                    ),
                )
            }
            if (document != null) {
                TextButton(
                    onClick = onClear,
                    enabled = enabled && !isResolving,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(clearTestTag),
                ) {
                    Text(stringResource(R.string.remove_file))
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    state: CurationCandidateUiState,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val candidate = state.candidate
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(VideoMiningTestTags.candidate(candidate.candidateId)),
        colors =
            if (state.selected) {
                CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                )
            } else {
                CardDefaults.outlinedCardColors()
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val description =
                    stringResource(R.string.candidate_selection_description, candidate.minedForm)
                Checkbox(
                    checked = state.selected,
                    onCheckedChange = { onToggle() },
                    enabled = enabled,
                    modifier =
                        Modifier
                            .testTag(VideoMiningTestTags.candidateToggle(candidate.candidateId))
                            .semantics { contentDescription = description },
                )
                Column {
                    Text(
                        text = candidate.minedForm,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.candidate_reading, candidate.expressionReading))
                }
            }
            candidate.partOfSpeech?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.candidate_part_of_speech, it))
            }
            Text(
                candidate.frequencyRank?.let {
                    stringResource(R.string.candidate_frequency, it)
                } ?: stringResource(R.string.candidate_frequency_unknown),
            )
            Text(stringResource(R.string.candidate_occurrences, candidate.occurrenceCount))
            HorizontalDivider()
            Text(
                text = stringResource(R.string.reading_sentence_prompt),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun SentenceChoice(
    candidate: CurationCandidate,
    sentence: CurationSentence,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description =
        stringResource(R.string.sentence_selection_description, sentence.sentence)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = onClick,
                    ).testTag(
                        VideoMiningTestTags.sentence(
                            candidate.candidateId,
                            sentence.sentenceId,
                        ),
                    ).semantics { contentDescription = description }
                    .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = candidate.minedForm,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = sentence.sentence, style = MaterialTheme.typography.bodyLarge)
                if (
                    sentence.sentenceFurigana.isNotBlank() &&
                    sentence.sentenceFurigana != sentence.sentence
                ) {
                    Text(
                        text = sentence.sentenceFurigana,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
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
