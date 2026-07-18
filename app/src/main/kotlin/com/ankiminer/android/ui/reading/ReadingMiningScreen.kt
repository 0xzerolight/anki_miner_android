package com.ankiminer.android.ui.reading

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
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
    onToggleCandidate: (String) -> Unit,
    onSelectAllCandidates: (Boolean) -> Unit,
    onSelectSentence: (String, String) -> Unit,
    onConfirmCuration: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(ReadingMiningTestTags.SCREEN),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding)
                    .navigationBarsPadding()
                    .testTag(ReadingMiningTestTags.CONTENT),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.commandError?.let { commandError ->
                item(key = "reading_command_error") {
                    ReadingErrorMessage(
                        message = stringResource(commandError.messageResource()),
                        onDismiss = onDismissCommandError,
                    )
                }
            }

            when (val runState = state.runState) {
                MiningRunState.Idle ->
                    setupItems(
                        state = state,
                        onPickSource = onPickSource,
                        onPickArchive = onPickArchive,
                        onClearSource = onClearSource,
                        onClearArchive = onClearArchive,
                        onSeriesNameChanged = onSeriesNameChanged,
                        onDismissDocumentError = onDismissDocumentError,
                        onStart = onStart,
                    )
                is MiningRunState.Starting ->
                    readingProgressItems(
                        title = R.string.starting_title,
                        progress = runState.progress,
                        canCancel = runState.cancellationToken != null || runState.runId != null,
                        cancelPending = state.cancelPending,
                        onCancel = onCancel,
                    )
                is MiningRunState.Curating ->
                    readingCurationItems(
                        state = state,
                        onToggleCandidate = onToggleCandidate,
                        onSelectAllCandidates = onSelectAllCandidates,
                        onSelectSentence = onSelectSentence,
                        onConfirmCuration = onConfirmCuration,
                        onCancel = onCancel,
                    )
                is MiningRunState.Running ->
                    readingProgressItems(
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
                        sourceDisplayName = state.source.document?.displayName,
                        archiveDisplayName = state.archive.document?.displayName,
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
                        sourceDisplayName = state.source.document?.displayName,
                        archiveDisplayName = state.archive.document?.displayName,
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
                        sourceDisplayName = state.source.document?.displayName,
                        archiveDisplayName = state.archive.document?.displayName,
                        failureMessage = runState.failure.message,
                        partial = runState.result?.cardsCreated?.let { it > 0 } == true,
                        canRetry = runState.failure.retryable && state.hasRetryableSelection(),
                        busy = state.resetPending || state.startPending,
                        onRetry = onRetry,
                        onReset = onReset,
                    )
            }
        }
    }
}

private fun LazyListScope.setupItems(
    state: ReadingMiningUiState,
    onPickSource: () -> Unit,
    onPickArchive: () -> Unit,
    onClearSource: () -> Unit,
    onClearArchive: () -> Unit,
    onSeriesNameChanged: (String) -> Unit,
    onDismissDocumentError: (ReadingDocumentSelectionError) -> Unit,
    onStart: () -> Unit,
) {
    item(key = "reading_setup_header") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.reading_mining_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.reading_mining_intro),
                style = MaterialTheme.typography.bodyLarge,
            )
            state.runtimeConflict?.let { conflict ->
                Text(
                    text = stringResource(readingRuntimeConflictMessage(conflict)),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    item(key = "reading_source") {
        ReadingDocumentCard(
            label = stringResource(R.string.reading_source_label),
            help = stringResource(R.string.reading_source_help),
            document = state.source.document,
            isResolving = state.source.isResolving,
            enabled = !state.startPending,
            pickTestTag = ReadingMiningTestTags.PICK_SOURCE,
            clearTestTag = ReadingMiningTestTags.CLEAR_SOURCE,
            onPick = onPickSource,
            onClear = onClearSource,
        )
    }
    state.source.error?.let { error ->
        item(key = "reading_source_error") {
            ReadingErrorMessage(
                message = stringResource(error.messageResource()),
                onDismiss = { onDismissDocumentError(error) },
            )
        }
    }
    if (state.acceptsArchive) {
        item(key = "reading_archive") {
            ReadingDocumentCard(
                label = stringResource(R.string.reading_archive_label),
                help = stringResource(R.string.reading_archive_help),
                document = state.archive.document,
                isResolving = state.archive.isResolving,
                enabled = !state.startPending,
                pickTestTag = ReadingMiningTestTags.PICK_ARCHIVE,
                clearTestTag = ReadingMiningTestTags.CLEAR_ARCHIVE,
                onPick = onPickArchive,
                onClear = onClearArchive,
            )
        }
        state.archive.error?.let { error ->
            item(key = "reading_archive_error") {
                ReadingErrorMessage(
                    message = stringResource(error.messageResource()),
                    onDismiss = { onDismissDocumentError(error) },
                )
            }
        }
    }
    if (state.sourceKind == ReadingSourceKindUi.SUBTITLE) {
        item(key = "reading_series_name") {
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
                supportingText = { Text(stringResource(R.string.reading_series_help)) },
            )
        }
    }
    item(key = "reading_start") {
        Button(
            onClick = onStart,
            enabled = state.canStart,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(ReadingMiningTestTags.START),
        ) {
            Text(stringResource(R.string.start_mining))
        }
    }
}

@StringRes
private fun readingRuntimeConflictMessage(conflict: RuntimeWorkConflict): Int =
    when (conflict) {
        RuntimeWorkConflict.MINING -> R.string.runtime_work_mining_active
        RuntimeWorkConflict.RESOURCE -> R.string.runtime_work_resource_active
        RuntimeWorkConflict.ANKI_SETUP -> R.string.runtime_work_anki_active
    }

private fun LazyListScope.readingProgressItems(
    title: Int,
    progress: MiningProgress?,
    canCancel: Boolean,
    cancelPending: Boolean,
    onCancel: () -> Unit,
) {
    item(key = "reading_progress") {
        ReadingProgressPanel(title = stringResource(title), progress = progress)
    }
    if (canCancel) {
        item(key = "reading_cancel") {
            OutlinedButton(
                onClick = onCancel,
                enabled = !cancelPending,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(ReadingMiningTestTags.CANCEL),
            ) {
                Text(stringResource(R.string.cancel_mining))
            }
        }
    }
}

private fun LazyListScope.readingCurationItems(
    state: ReadingMiningUiState,
    onToggleCandidate: (String) -> Unit,
    onSelectAllCandidates: (Boolean) -> Unit,
    onSelectSentence: (String, String) -> Unit,
    onConfirmCuration: () -> Unit,
    onCancel: () -> Unit,
) {
    val curation = state.curation
    val candidates = curation?.candidates.orEmpty()
    val selectedCount = candidates.count { it.selected }
    val allSelected = candidates.isNotEmpty() && selectedCount == candidates.size
    val enabled = !state.curationPending && !state.cancelPending

    item(key = "reading_curation_header") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.curation_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text =
                    stringResource(
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
                enabled = candidates.isNotEmpty() && enabled,
                modifier = Modifier.testTag(ReadingMiningTestTags.SELECT_ALL),
            ) {
                Text(
                    stringResource(
                        if (allSelected) R.string.deselect_all else R.string.select_all,
                    ),
                )
            }
        }
    }

    // Candidates are protocol-bounded to one page. Sentence choices are flattened into this
    // outer LazyColumn as well, so even a large individual candidate never creates an eager
    // nested column of text and radio controls.
    candidates.forEach { candidateState ->
        val candidate = candidateState.candidate
        item(key = "candidate:${candidate.candidateId}") {
            ReadingCandidateCard(
                state = candidateState,
                enabled = enabled,
                onToggle = { onToggleCandidate(candidate.candidateId) },
            )
        }
        items(
            items = candidate.sentences,
            key = { sentence -> "sentence:${candidate.candidateId}:${sentence.sentenceId}" },
        ) { sentence ->
            ReadingSentenceChoice(
                candidate = candidate,
                sentence = sentence,
                selected = sentence.sentenceId == candidateState.sentenceId,
                enabled = enabled,
                onClick = { onSelectSentence(candidate.candidateId, sentence.sentenceId) },
            )
        }
    }

    item(key = "reading_curation_actions") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConfirmCuration,
                enabled = enabled,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(ReadingMiningTestTags.CONFIRM_CURATION),
            ) {
                Text(
                    stringResource(
                        when {
                            curation?.page == null -> R.string.confirm_curation
                            curation?.isFinalPage == false -> R.string.confirm_curation_page
                            else -> R.string.confirm_curation_final_page
                        },
                        selectedCount,
                    ),
                )
            }
            OutlinedButton(
                onClick = onCancel,
                enabled = !state.cancelPending,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(ReadingMiningTestTags.CANCEL),
            ) {
                Text(stringResource(R.string.cancel_mining))
            }
        }
    }
}

private fun LazyListScope.terminalItems(
    title: Int,
    result: ProcessingResult?,
    sourceDisplayName: String?,
    archiveDisplayName: String?,
    failureMessage: String?,
    partial: Boolean,
    canRetry: Boolean,
    busy: Boolean,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    item(key = "reading_terminal_header") {
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
        item(key = "reading_terminal_result") {
            ReadingResultSummary(
                result = it,
                sourceDisplayName = sourceDisplayName,
                archiveDisplayName = archiveDisplayName,
                partial = partial,
            )
        }
    }
    item(key = "reading_terminal_actions") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRetry) {
                Button(
                    onClick = onRetry,
                    enabled = !busy,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(ReadingMiningTestTags.RETRY),
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
                        .testTag(ReadingMiningTestTags.RESET),
            ) {
                Text(stringResource(R.string.reset_mining))
            }
        }
    }
}

@Composable
private fun ReadingDocumentCard(
    label: String,
    help: String,
    document: SafDocument?,
    isResolving: Boolean,
    enabled: Boolean,
    pickTestTag: String,
    clearTestTag: String,
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
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(text = help, style = MaterialTheme.typography.bodySmall)
            if (isResolving) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.reading_file))
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
private fun ReadingProgressPanel(
    title: String,
    progress: MiningProgress?,
) {
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(ReadingMiningTestTags.PROGRESS),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            progress?.description?.takeIf(String::isNotBlank)?.let { description ->
                Text(text = description, style = MaterialTheme.typography.bodyLarge)
            }
            val fraction = progress?.fraction
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (progress != null && progress.total > 0) {
                Text(stringResource(R.string.progress_count, progress.current, progress.total))
            }
        }
    }
}

@Composable
private fun ReadingCandidateCard(
    state: ReadingCurationCandidateUiState,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val candidate = state.candidate
    val includeDescription =
        stringResource(R.string.candidate_selection_description, candidate.minedForm)
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(ReadingMiningTestTags.candidate(candidate.candidateId)),
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
                Checkbox(
                    checked = state.selected,
                    onCheckedChange = { onToggle() },
                    enabled = enabled,
                    modifier =
                        Modifier
                            .testTag(
                                ReadingMiningTestTags.candidateToggle(candidate.candidateId),
                            ).semantics {
                                contentDescription = includeDescription
                            },
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
            candidate.partOfSpeech?.takeIf(String::isNotBlank)?.let {
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
private fun ReadingSentenceChoice(
    candidate: CurationCandidate,
    sentence: CurationSentence,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val sentenceDescription =
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
                        ReadingMiningTestTags.sentence(
                            candidate.candidateId,
                            sentence.sentenceId,
                        ),
                    ).semantics {
                        contentDescription = sentenceDescription
                    }.padding(12.dp),
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
private fun ReadingResultSummary(
    result: ProcessingResult,
    sourceDisplayName: String?,
    archiveDisplayName: String?,
    partial: Boolean,
) {
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(ReadingMiningTestTags.RESULT),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (partial) {
                Text(
                    text = stringResource(R.string.partial_result_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(stringResource(R.string.result_cards_created, result.cardsCreated))
            Text(stringResource(R.string.result_new_words, result.newWordsFound))
            Text(stringResource(R.string.result_total_words, result.totalWordsFound))
            Text(
                stringResource(
                    R.string.result_comprehension,
                    result.comprehensionPercentage,
                ),
            )
            Text(stringResource(R.string.result_elapsed, result.elapsedTime))
            Text(
                stringResource(
                    R.string.result_reading_source,
                    sourceDisplayName ?: stringResource(R.string.result_unknown_file),
                ),
            )
            archiveDisplayName?.let {
                Text(stringResource(R.string.result_reading_archive, it))
            }
            Text(
                stringResource(
                    R.string.result_mined_forms,
                    result.minedForms.boundedSummary(),
                ),
            )
            Text(
                stringResource(
                    R.string.result_card_ids,
                    result.cardIds.boundedSummary(),
                ),
            )
            if (result.errors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.result_errors_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                result.errors.take(MAX_RESULT_ERROR_LINES).forEach { error ->
                    Text(
                        text = stringResource(R.string.result_error_item, error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (result.errors.size > MAX_RESULT_ERROR_LINES) {
                    Text(
                        text =
                            stringResource(
                                R.string.result_more_items,
                                result.errors.size - MAX_RESULT_ERROR_LINES,
                            ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingErrorMessage(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(message)
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dismiss_error))
            }
        }
    }
}

private fun ReadingMiningUiState.hasRetryableSelection(): Boolean =
    source.document != null &&
        sourceKind != null &&
        (!acceptsArchive || archive.document == null || archiveNamesMatch)

@StringRes
private fun ReadingDocumentSelectionError.messageResource(): Int =
    when (this) {
        ReadingDocumentSelectionError.SOURCE_ACCESS -> R.string.reading_source_access_error
        ReadingDocumentSelectionError.SOURCE_TYPE -> R.string.reading_source_type_error
        ReadingDocumentSelectionError.ARCHIVE_ACCESS -> R.string.reading_archive_access_error
        ReadingDocumentSelectionError.ARCHIVE_TYPE -> R.string.reading_archive_type_error
        ReadingDocumentSelectionError.ARCHIVE_NAME -> R.string.reading_archive_name_error
    }

@StringRes
private fun ReadingMiningCommandError.messageResource(): Int =
    when (this) {
        ReadingMiningCommandError.START -> R.string.start_error
        ReadingMiningCommandError.CURATION -> R.string.curation_error
        ReadingMiningCommandError.CANCEL -> R.string.cancel_error
        ReadingMiningCommandError.RESET -> R.string.reset_error
    }

@Composable
private fun List<*>.boundedSummary(): String {
    if (isEmpty()) return stringResource(R.string.result_no_items)
    val shown = take(MAX_RESULT_SUMMARY_ITEMS).joinToString()
    val remaining = size - MAX_RESULT_SUMMARY_ITEMS
    return if (remaining > 0) {
        "$shown, ${stringResource(R.string.result_more_items, remaining)}"
    } else {
        shown
    }
}

private const val MAX_RESULT_SUMMARY_ITEMS = 100
private const val MAX_RESULT_ERROR_LINES = 50
