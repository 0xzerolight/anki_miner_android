package com.ankiminer.android.ui.mining

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.localization.byteProgressResource
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.MiningProgress
import com.ankiminer.android.mining.MiningProgressUnit
import com.ankiminer.android.mining.NotMinedGroup
import com.ankiminer.android.mining.NotMinedReason
import com.ankiminer.android.mining.ProcessingResult
import com.ankiminer.android.ui.theme.AdaptiveActionGroup
import com.ankiminer.android.ui.theme.AdaptivePairedActions
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.CompactOutlinedTextField
import com.ankiminer.android.ui.theme.ExitActionButton
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.accentTextButtonColors
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.disabledActionContentColor
import com.ankiminer.android.ui.theme.isCompactLayout
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import kotlinx.coroutines.launch

internal const val MINING_FAILURE_TEST_TAG = "mining_failure"
internal const val NOT_MINED_TEST_TAG = "mining_result_not_mined"
internal const val NOT_MINED_SEARCH_TEST_TAG = "mining_result_not_mined_search"

/** Stands in for the forms while the line template is formatted, so they can be styled apart. */
private const val NOT_MINED_FORMS_SLOT = "\uFFFC"

/**
 * Referential animation target. Same-phase state changes update live content without making the
 * whole UI state (including long result/candidate lists) the AnimatedContent transition target.
 */
internal class MiningPhaseTarget<S>(
    val key: String,
    val initialState: S,
)

internal data class MiningResultUndoAction(
    val noteCount: Int,
    val undoneNoteCount: Int?,
    val enabled: Boolean,
    val testTag: String,
    val onUndo: () -> Unit,
)

internal data class MiningFailureAction(
    val label: String,
    val testTag: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

internal data class MiningSourceItem(
    val label: String,
    val document: SafDocument?,
    val isResolving: Boolean,
    val enabled: Boolean,
    val pickTestTag: String,
    val clearTestTag: String,
    val readKind: DocumentReadKind,
    val onPick: () -> Unit,
    val onClear: () -> Unit,
)

internal enum class ResultIssueTone {
    WARNING,
    FAILURE,
}

@Composable
internal fun RuntimeConflictNotice(
    text: String,
    modifier: Modifier = Modifier,
    onReturnToActiveRun: (() -> Unit)? = null,
) {
    // A conflict is a warning state, so fill earns its place here. A plain outlined box would have
    // read like every other section on the screen.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(AnkiMinerTokens.Space.content),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            Text(text = text)
            if (onReturnToActiveRun != null) {
                ExitActionButton(onClick = onReturnToActiveRun) {
                    Text(stringResource(R.string.return_to_active_run))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MiningProgressPanel(
    progress: MiningProgress?,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val fraction = progress?.fraction?.coerceIn(0f, 1f)
    val animatedFraction by
        animateFloatAsState(
            targetValue = fraction ?: 0f,
            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
            label = "mining progress",
        )
    val percentage = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
    val coarsePercentage = percentage?.let { (it / 10) * 10 }
    val stage =
        progress?.description
            ?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.mining_progress_working)
    val announcement =
        remember(stage, coarsePercentage) {
            coarsePercentage?.let { "$stage, $it%" } ?: stage
        }

    // Progress is the whole content of this phase, not an aside, so it needs no container of its
    // own; the list's inset already positions it.
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.group),
    ) {
        Text(
            text = stage,
            modifier =
                Modifier.clearAndSetSemantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announcement
                },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (fraction == null) {
            LinearProgressIndicator(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
            )
        } else {
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        val count =
            progress?.takeIf { it.total > 0 }?.let { current ->
                when (current.unit) {
                    MiningProgressUnit.ITEMS ->
                        stringResource(R.string.progress_count, current.current, current.total)
                    // Raw byte counts read as nonsense next to an item count, and a fixed
                    // mebibyte scale reads "0.0 of 0.0 MiB" for the sub-megabyte reading sources
                    // that are the ordinary case.
                    MiningProgressUnit.BYTES ->
                        byteProgressResource(current.current, current.total).let {
                            stringResource(it.resourceId, *it.formatArguments.toTypedArray())
                        }
                }
            }
        // The stage's own count, no percent: the count restarts each stage while the bar is the whole
        // run, so "0 of 751 · 80%" contradicted itself. Always one line tall, so Cancel never moves.
        Text(
            text = count.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun MiningFailureCard(
    message: String,
    modifier: Modifier = Modifier,
    diagnosticDetails: String? = null,
    primaryAction: MiningFailureAction? = null,
    secondaryAction: MiningFailureAction? = null,
) {
    var detailsExpanded by rememberSaveable(diagnosticDetails) { mutableStateOf(false) }
    val actionColors =
        ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            disabledContentColor = disabledActionContentColor(),
        )
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    error(message)
                }
                .testTag(MINING_FAILURE_TEST_TAG),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(AnkiMinerTokens.Space.content),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            if (detailsExpanded && diagnosticDetails != null) {
                Text(
                    text = diagnosticDetails,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (diagnosticDetails != null) {
                if (detailsExpanded) {
                    AdaptivePairedActions(
                        first = { actionModifier ->
                            TextButton(
                                onClick = { detailsExpanded = false },
                                modifier = actionModifier,
                                colors = actionColors,
                            ) {
                                Text(stringResource(R.string.hide_details))
                            }
                        },
                        second = { actionModifier ->
                            CopyDiagnosticsButton(
                                diagnostics = diagnosticDetails,
                                modifier = actionModifier,
                                colors = actionColors,
                            )
                        },
                    )
                } else {
                    TextButton(
                        onClick = { detailsExpanded = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = actionColors,
                    ) {
                        Text(stringResource(R.string.details))
                    }
                }
            }
            val mainAction = primaryAction ?: secondaryAction
            val alternateAction = secondaryAction.takeIf { primaryAction != null }
            if (mainAction != null) {
                if (alternateAction != null) {
                    AdaptiveActionGroup(
                        primary = { actionModifier ->
                            MiningFailureButton(
                                action = mainAction,
                                modifier = actionModifier,
                            )
                        },
                        secondary = { actionModifier ->
                            MiningFailureButton(
                                action = alternateAction,
                                modifier = actionModifier,
                            )
                        },
                    )
                } else {
                    AdaptiveActionGroup(
                        primary = { actionModifier ->
                            MiningFailureButton(
                                action = mainAction,
                                modifier = actionModifier,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MiningFailureButton(
    action: MiningFailureAction,
    modifier: Modifier,
) {
    OutlinedButton(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier =
            modifier.then(
                action.testTag?.let { Modifier.testTag(it) } ?: Modifier,
            ),
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                disabledContentColor = disabledActionContentColor(),
            ),
        border = actionBorder(enabled = action.enabled),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(action.label)
    }
}

@Composable
internal fun StickyCurationActions(
    selectedCount: Int,
    page: CurationPage?,
    isFinalPage: Boolean,
    curationPending: Boolean,
    cancelPending: Boolean,
    requiresCancelConfirmation: Boolean,
    commandErrorMessage: String?,
    confirmTestTag: String,
    cancelTestTag: String,
    onDismissCommandError: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCancelConfirmation by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(cancelPending) {
        if (cancelPending) showCancelConfirmation = false
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AnkiMinerTokens.Space.content, vertical = AnkiMinerTokens.Space.related),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            commandErrorMessage?.let { error ->
                MiningFailureCard(
                    message = error,
                    primaryAction =
                        MiningFailureAction(
                            label = stringResource(R.string.dismiss_error),
                            onClick = onDismissCommandError,
                        ),
                )
            }
            // Side-by-side at any width: the curation labels are short in every locale, and the
            // stacked pair cost a full button row on small screens. fontScale >= 1.3 still stacks.
            AdaptiveActionGroup(
                stackWidthThreshold = 0.dp,
                primary = { actionModifier ->
                    PrimaryActionButton(
                        onClick = onConfirm,
                        enabled = !curationPending && !cancelPending,
                        modifier = actionModifier.testTag(confirmTestTag),
                    ) {
                        Text(
                            text =
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
                },
                secondary = { actionModifier ->
                    SecondaryActionButton(
                        onClick = {
                            if (requiresCancelConfirmation) {
                                showCancelConfirmation = true
                            } else {
                                onCancel()
                            }
                        },
                        enabled = !cancelPending,
                        modifier = actionModifier.testTag(cancelTestTag),
                    ) {
                        if (cancelPending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.cancelling),
                                modifier = Modifier.padding(start = AnkiMinerTokens.Space.related),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.cancel_mining),
                            )
                        }
                    }
                },
            )
        }
    }

    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = { Text(stringResource(R.string.cancel_mining_confirmation_title)) },
            text = { Text(stringResource(R.string.cancel_mining_confirmation_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelConfirmation = false
                        onCancel()
                    },
                    colors = accentTextButtonColors(),
                ) {
                    Text(stringResource(R.string.cancel_mining_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCancelConfirmation = false },
                    colors = accentTextButtonColors(),
                ) {
                    Text(stringResource(R.string.cancel_mining_keep))
                }
            },
        )
    }
}

@Composable
internal fun SourcesCard(
    sources: List<MiningSourceItem>,
    modifier: Modifier = Modifier,
) {
    // Dividers already group these rows; a border around them only added a second edge inside the
    // list's own inset.
    val stack = isCompactLayout()
    Column(modifier.fillMaxWidth()) {
        sources.forEachIndexed { index, source ->
            // "No file selected" is no longer drawn; it stays here so the empty slot is
            // still announced.
            val emptyState = stringResource(R.string.no_file_selected)
            val rowState =
                if (source.document == null) {
                    Modifier.semantics { stateDescription = emptyState }
                } else {
                    Modifier
                }
            if (stack) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(AnkiMinerTokens.Space.content).then(rowState),
                    verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                ) {
                    Text(
                        text = source.label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    SourceSupportingContent(source)
                    SourceRowActions(source = source)
                }
            } else {
                ListItem(
                    modifier = Modifier.fillMaxWidth().then(rowState),
                    supportingContent = { SourceSupportingContent(source) },
                    trailingContent = {
                        SourceRowActions(source = source)
                    },
                    headlineContent = {
                        Text(
                            text = source.label,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                )
            }
            if (index != sources.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun SourceSupportingContent(source: MiningSourceItem) {
    if (source.isResolving) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            DocumentReadProgressText(
                documentReadProgress(
                    source.readKind,
                    source.document?.displayName,
                ),
            )
        }
    } else {
        // An empty slot needs no visible caption; the Choose button already says so. The state
        // still reaches TalkBack through the row's stateDescription.
        source.document?.displayName?.let { displayName ->
            Text(displayName, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SourceRowActions(
    source: MiningSourceItem,
    modifier: Modifier = Modifier,
) {
    val actionEnabled = source.enabled && !source.isResolving
    // The visible label is a bare verb; the target lives in semantics so a screen reader still
    // distinguishes "Choose video" from "Choose subtitles".
    val pickDescription =
        stringResource(
            if (source.document == null) {
                R.string.choose_file_description
            } else {
                R.string.replace_file_description
            },
            source.label,
        )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line),
    ) {
        OutlinedButton(
            onClick = source.onPick,
            enabled = actionEnabled,
            modifier =
                Modifier
                    .testTag(source.pickTestTag)
                    .semantics { contentDescription = pickDescription },
            colors = outlinedActionButtonColors(),
            border = actionBorder(enabled = actionEnabled),
            shape = MaterialTheme.shapes.small,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Text(
                stringResource(
                    if (source.document == null) R.string.choose_file else R.string.replace_file,
                ),
            )
        }
        if (source.document != null) {
            IconButton(
                onClick = source.onClear,
                enabled = actionEnabled,
                modifier =
                    Modifier
                        .size(48.dp)
                        .testTag(source.clearTestTag),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = disabledActionContentColor(),
                    ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_remove),
                    contentDescription = stringResource(R.string.remove_file),
                )
            }
        }
    }
}

internal fun LazyListScope.miningResultItems(
    headline: MiningResultHeadline,
    result: ProcessingResult?,
    failed: Boolean,
    detailsExpanded: Boolean,
    testTag: String,
    keyPrefix: String,
    onToggleDetails: () -> Unit,
    undo: MiningResultUndoAction? = null,
) {
    val issues = result?.errors.orEmpty().boundedResultItems(MAX_RESULT_ERROR_LINES)
    val issueTone = if (failed) ResultIssueTone.FAILURE else ResultIssueTone.WARNING
    item(
        key = "$keyPrefix:summary",
        contentType = "header",
    ) {
        MiningResultSummary(
            headline = headline,
            issuePreview =
                if (detailsExpanded) emptyList() else issues.items.take(RESULT_ISSUE_PREVIEW_COUNT),
            issueTone = issueTone,
            detailsExpanded = detailsExpanded,
            hasDetails = result != null,
            onToggleDetails = onToggleDetails,
            testTag = testTag,
            undo = undo,
        )
    }
    if (!detailsExpanded || result == null) return

    item(
        key = "$keyPrefix:details",
        contentType = "candidate",
    ) {
        ResultDetails(result)
    }
    items(
        count = issues.items.size,
        key = { index -> "$keyPrefix:issue:$index:${issues.items[index].hashCode()}" },
        contentType = { "sentence" },
    ) { index ->
        ResultIssueRow(message = issues.items[index], tone = issueTone)
    }
    if (issues.remainingCount > 0) {
        item(
            key = "$keyPrefix:issues_remaining",
            contentType = "actions",
        ) {
            Text(
                text = stringResource(R.string.result_more_items, issues.remainingCount),
                color =
                    if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
private fun MiningResultSummary(
    headline: MiningResultHeadline,
    issuePreview: List<String>,
    issueTone: ResultIssueTone,
    detailsExpanded: Boolean,
    hasDetails: Boolean,
    onToggleDetails: () -> Unit,
    testTag: String,
    undo: MiningResultUndoAction?,
) {
    // No card: one sentence and Undo, under the inputs it reports on.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        val undoButton: (@Composable () -> Unit)? =
            if (undo != null && undo.undoneNoteCount == null) {
                {
                    SecondaryActionButton(
                        onClick = undo.onUndo,
                        enabled = undo.enabled,
                        modifier = Modifier.testTag(undo.testTag),
                    ) {
                        Text(stringResource(R.string.undo_mining_run, undo.noteCount))
                    }
                }
            } else {
                null
            }
        // Narrow or large text: Undo goes under the sentence, which would otherwise wrap per letter.
        if (isCompactLayout()) {
            Text(
                text = headline.text(),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            undoButton?.invoke()
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = headline.text(),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                undoButton?.invoke()
            }
        }
        undo?.undoneNoteCount?.let { deleted ->
            Text(
                text = stringResource(R.string.undo_done, deleted),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        issuePreview.forEach { issue ->
            ResultIssueRow(message = issue, tone = issueTone)
        }
        if (hasDetails) {
            TextButton(
                onClick = onToggleDetails,
                modifier = Modifier.align(Alignment.End),
                colors = accentTextButtonColors(),
            ) {
                Text(
                    stringResource(
                        if (detailsExpanded) R.string.hide_details else R.string.details,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MiningResultHeadline.text(): String =
    when (this) {
        is MiningResultHeadline.NotesAdded -> {
            val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (deckName != null) {
                pluralStringResource(R.plurals.result_notes_added_to_deck, quantity, count, deckName)
            } else {
                pluralStringResource(R.plurals.result_notes_added, quantity, count)
            }
        }
        MiningResultHeadline.CancelledNothingAdded -> stringResource(R.string.result_cancelled_nothing_added)
        MiningResultHeadline.NothingAdded -> stringResource(R.string.result_nothing_added)
    }

@Composable
internal fun MiningUndoConfirmationDialog(
    noteCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmTestTag: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.undo_confirm_title)) },
        text = { Text(stringResource(R.string.undo_confirm_message, noteCount)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = confirmTestTag?.let { Modifier.testTag(it) } ?: Modifier,
                colors = accentTextButtonColors(),
            ) {
                Text(stringResource(R.string.undo_confirm_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = accentTextButtonColors()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Counts as plain lines and the not-mined report; mined forms and note ids stay in Copy diagnostics only. */
@Composable
private fun ResultDetails(result: ProcessingResult) {
    val skipped = (result.newWordsFound - result.cardsCreated).coerceAtLeast(0)
    val formsText = result.minedForms.boundedResultItems(MAX_RESULT_SUMMARY_ITEMS).summaryText()
    val idsText = result.cardIds.boundedResultItems(MAX_RESULT_SUMMARY_ITEMS).summaryText()
    val diagnostics =
        buildString {
            appendLine("created=${result.cardsCreated}")
            appendLine("new=${result.newWordsFound}")
            appendLine("total=${result.totalWordsFound}")
            appendLine("comprehension=${result.comprehensionPercentage}")
            appendLine("elapsed=${result.elapsedTime}")
            appendLine("forms=$formsText")
            appendLine("note_ids=$idsText")
            result.errors.boundedResultItems(MAX_RESULT_ERROR_LINES).items.forEach { issue ->
                appendLine("issue=$issue")
            }
            result.notMined.orEmpty().notMinedDiagnostics().forEach(::appendLine)
        }.trim()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        DetailLine(
            R.string.result_metric_skipped_new,
            stringResource(R.string.result_metric_skipped_new_value, skipped, result.newWordsFound),
        )
        DetailLine(
            R.string.result_metric_comprehension,
            stringResource(R.string.result_metric_percent_value, result.comprehensionPercentage),
        )
        DetailLine(
            R.string.result_metric_elapsed,
            stringResource(R.string.result_metric_elapsed_value, result.elapsedTime),
        )
        result.notMined?.takeIf { it.isNotEmpty() }?.let { NotMinedReportSection(it) }
        CopyDiagnosticsButton(diagnostics = diagnostics)
    }
}

/**
 * Why the run's other words made no card, one line per reason in pipeline order (desktop's
 * Activity Log "Not mined" block). Failures (no dictionary entry, media, AnkiDroid) read in the
 * error colour; the user's own settings stay quiet.
 */
@Composable
private fun NotMinedReportSection(groups: List<NotMinedGroup>) {
    val section = remember(groups) { NotMinedSection(groups) }
    var query by rememberSaveable(groups) { mutableStateOf("") }
    val lines = remember(section, query) { section.lines(query) }
    // The forms are mined text; the reason around them stays in the interface language.
    val formsStyle = MaterialTheme.typography.bodyMedium.minedText().toSpanStyle()
    Column(
        modifier = Modifier.fillMaxWidth().testTag(NOT_MINED_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        Text(
            text = pluralStringResource(R.plurals.result_not_mined_header, section.wordCount, section.wordCount),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
        )
        CompactOutlinedTextField(
            value = query,
            onValueChange = { query = it.boundedSaveableQuery() },
            modifier = Modifier.fillMaxWidth().testTag(NOT_MINED_SEARCH_TEST_TAG),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.result_not_mined_search)) },
        )
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.result_not_mined_no_match),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        lines.forEach { line -> NotMinedLineText(line, formsStyle) }
    }
}

@Composable
private fun NotMinedLineText(
    line: NotMinedLine,
    formsStyle: SpanStyle,
) {
    val label = stringResource(line.reason.label())
    val template = stringResource(R.string.result_not_mined_line, label, line.count, NOT_MINED_FORMS_SLOT)
    val more = if (line.hiddenCount > 0) stringResource(R.string.result_more_items, line.hiddenCount) else null
    val slot = template.indexOf(NOT_MINED_FORMS_SLOT)
    val text =
        buildAnnotatedString {
            append(template.substring(0, slot))
            withStyle(formsStyle) { append(line.forms.joinToString()) }
            if (more != null) {
                append(", ")
                append(more)
            }
            append(template.substring(slot + NOT_MINED_FORMS_SLOT.length))
        }
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().testTag("${NOT_MINED_TEST_TAG}_${line.reason.wire}"),
        color = if (line.reason.failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** The reason's line label: desktop's wording with Android's Settings page names. */
@StringRes
internal fun NotMinedReason.label(): Int =
    when (this) {
        NotMinedReason.WORD_TYPE -> R.string.result_not_mined_reason_word_type
        NotMinedReason.SOUND_EFFECT -> R.string.result_not_mined_reason_sound_effect
        NotMinedReason.KANA_ONLY -> R.string.result_not_mined_reason_kana_only
        NotMinedReason.SCRIPT -> R.string.result_not_mined_reason_script
        NotMinedReason.KNOWN -> R.string.result_not_mined_reason_known
        NotMinedReason.NO_DEFINITION -> R.string.result_not_mined_reason_no_definition
        NotMinedReason.UNRANKED -> R.string.result_not_mined_reason_unranked
        NotMinedReason.FREQUENCY -> R.string.result_not_mined_reason_frequency
        NotMinedReason.BLACKLIST -> R.string.result_not_mined_reason_blacklist
        NotMinedReason.SCRIPT_FILTER -> R.string.result_not_mined_reason_script_filter
        NotMinedReason.NAME_LIST -> R.string.result_not_mined_reason_name_list
        NotMinedReason.OCCURRENCE -> R.string.result_not_mined_reason_occurrence
        NotMinedReason.ONE_PER_SENTENCE -> R.string.result_not_mined_reason_one_per_sentence
        NotMinedReason.I_PLUS_ONE -> R.string.result_not_mined_reason_i_plus_one
        NotMinedReason.SENTENCE_LENGTH -> R.string.result_not_mined_reason_sentence_length
        NotMinedReason.SAME_CARD -> R.string.result_not_mined_reason_same_card
        NotMinedReason.MEDIA_FAILED -> R.string.result_not_mined_reason_media_failed
        NotMinedReason.ANKI_DUPLICATE -> R.string.result_not_mined_reason_anki_duplicate
        NotMinedReason.ANKI_FAILED -> R.string.result_not_mined_reason_anki_failed
    }

@Composable
private fun DetailLine(
    @StringRes label: Int,
    value: String,
) {
    Text(
        text = stringResource(R.string.result_detail_line, stringResource(label), value),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ResultIssueRow(
    message: String,
    tone: ResultIssueTone,
) {
    val text = stringResource(R.string.result_error_item, message)
    if (tone == ResultIssueTone.WARNING) {
        // A warning on a finished run is a quiet line; only a failure gets the red surface.
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { error(message) },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = AnkiMinerTokens.Space.group, vertical = AnkiMinerTokens.Space.group),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CopyDiagnosticsButton(
    diagnostics: String,
    modifier: Modifier = Modifier,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.textButtonColors(),
) {
    val copy = rememberClipboardWriter()
    TextButton(
        onClick = { copy("Anki Miner diagnostics", diagnostics, null) },
        modifier = modifier,
        colors = colors,
    ) {
        Text(stringResource(R.string.copy_diagnostics))
    }
}

/**
 * The single clipboard writer in the app. [confirmation] is shown only below API 33, where the
 * system provides no clipboard confirmation of its own; on 33+ an app toast would duplicate it.
 */
@Composable
internal fun rememberClipboardWriter(): (label: String, text: String, confirmation: String?) -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, context, scope) {
        { label, text, confirmation ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
                if (confirmation != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
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
