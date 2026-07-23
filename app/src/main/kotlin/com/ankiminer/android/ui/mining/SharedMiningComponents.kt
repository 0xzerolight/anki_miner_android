package com.ankiminer.android.ui.mining

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.outlinedActionButtonColors

internal data class MiningResultSource(
    @param:StringRes val label: Int,
    val displayName: String?,
)

/**
 * Shared runtime-conflict card. Mining conflicts expose direct navigation back to owning run.
 */
@Composable
internal fun RuntimeConflictNotice(
    text: String,
    modifier: Modifier = Modifier,
    onReturnToActiveRun: (() -> Unit)? = null,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = text, color = MaterialTheme.colorScheme.onSurface)
            if (onReturnToActiveRun != null) {
                OutlinedButton(
                    onClick = onReturnToActiveRun,
                    colors = outlinedActionButtonColors(),
                    border = actionBorder(enabled = true),
                ) {
                    Text(stringResource(R.string.return_to_active_run))
                }
            }
        }
    }
}

@Composable
internal fun MiningProgressPanel(
    title: String,
    progress: MiningProgress?,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(testTag),
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
internal fun MiningErrorMessage(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
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

@Composable
internal fun StickyCurationActions(
    selectedCount: Int,
    page: CurationPage?,
    isFinalPage: Boolean,
    curationPending: Boolean,
    cancelPending: Boolean,
    confirmTestTag: String,
    cancelTestTag: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .imePadding(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !cancelPending,
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag(cancelTestTag),
            ) {
                Text(stringResource(R.string.cancel_mining))
            }
            Button(
                onClick = onConfirm,
                enabled = !curationPending && !cancelPending,
                modifier =
                    Modifier
                        .weight(2f)
                        .testTag(confirmTestTag),
            ) {
                Text(
                    stringResource(
                        when {
                            page == null -> R.string.confirm_curation
                            !isFinalPage -> R.string.confirm_curation_page
                            else -> R.string.confirm_curation_final_page
                        },
                        selectedCount,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun MiningResultSummary(
    result: ProcessingResult,
    sources: List<MiningResultSource>,
    partial: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val minedForms = result.minedForms.boundedResultItems(MAX_RESULT_SUMMARY_ITEMS)
    val cardIds = result.cardIds.boundedResultItems(MAX_RESULT_SUMMARY_ITEMS)
    val errors = result.errors.boundedResultItems(MAX_RESULT_ERROR_LINES)
    OutlinedCard(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(testTag),
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
            Text(stringResource(R.string.result_comprehension, result.comprehensionPercentage))
            Text(stringResource(R.string.result_elapsed, result.elapsedTime))
            sources.forEach { source ->
                Text(
                    stringResource(
                        source.label,
                        source.displayName ?: stringResource(R.string.result_unknown_file),
                    ),
                )
            }
            Text(
                stringResource(
                    R.string.result_mined_forms,
                    minedForms.summaryText(),
                ),
            )
            Text(
                stringResource(
                    R.string.result_card_ids,
                    cardIds.summaryText(),
                ),
            )
            if (errors.items.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.result_errors_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                errors.items.forEach { error ->
                    Text(
                        text = stringResource(R.string.result_error_item, error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (errors.remainingCount > 0) {
                    Text(
                        text = stringResource(R.string.result_more_items, errors.remainingCount),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DocumentReadProgressText(progress: DocumentReadProgress) {
    val resource =
        when (progress.copy) {
            DocumentReadCopy.VIDEO -> R.string.reading_video
            DocumentReadCopy.VIDEO_NAMED -> R.string.reading_video_named
            DocumentReadCopy.SUBTITLES -> R.string.reading_subtitles
            DocumentReadCopy.SUBTITLES_NAMED -> R.string.reading_subtitles_named
            DocumentReadCopy.DOCUMENT -> R.string.reading_document
            DocumentReadCopy.DOCUMENT_NAMED -> R.string.reading_document_named
        }
    Text(
        progress.displayName?.let { displayName -> stringResource(resource, displayName) }
            ?: stringResource(resource),
    )
}

@Composable
private fun BoundedResultItems<*>.summaryText(): String {
    if (items.isEmpty()) return stringResource(R.string.result_no_items)
    val shown = items.joinToString()
    return if (remainingCount > 0) {
        "$shown, ${stringResource(R.string.result_more_items, remainingCount)}"
    } else {
        shown
    }
}
