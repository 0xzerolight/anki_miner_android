package com.ankiminer.android.ui.mining

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.dictionary.CurationDefinition
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.ui.settings.DictionaryHtml
import com.ankiminer.android.ui.theme.AnkiMinerTokens

internal const val CURATION_SEARCH_TEST_TAG = "curation_search"

@Composable
internal fun CurationControls(
    query: String,
    filter: CurationFilter,
    sort: CurationSort,
    enabled: Boolean,
    onQueryChanged: (String) -> Unit,
    onFilterChanged: (CurationFilter) -> Unit,
    onSortChanged: (CurationSort) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(CURATION_SEARCH_TEST_TAG),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.curation_search)) },
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            CurationFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChanged(option) },
                    enabled = enabled,
                    label = {
                        Text(
                            stringResource(
                                when (option) {
                                    CurationFilter.ALL -> R.string.curation_filter_all
                                    CurationFilter.SELECTED -> R.string.curation_filter_selected
                                    CurationFilter.EXCLUDED -> R.string.curation_filter_excluded
                                },
                            ),
                        )
                    },
                )
            }
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            CurationSort.entries.forEach { option ->
                FilterChip(
                    selected = sort == option,
                    onClick = { onSortChanged(option) },
                    enabled = enabled,
                    label = {
                        Text(
                            stringResource(
                                when (option) {
                                    CurationSort.FREQUENCY -> R.string.curation_sort_frequency
                                    CurationSort.OCCURRENCES -> R.string.curation_sort_occurrences
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun rememberCurationCandidateHeaderTexts(
    candidates: List<CurationCandidate>,
): Map<String, AnnotatedString> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(candidates, context, configuration) {
        val resources = context.resources
        buildMap(candidates.size) {
            candidates.forEach { candidate ->
                val partOfSpeech =
                    candidate.partOfSpeech?.takeIf(String::isNotBlank)
                        ?: resources.getString(R.string.candidate_part_of_speech_unknown)
                val frequency =
                    candidate.frequencyRank?.let { rank ->
                        resources.getString(R.string.candidate_frequency_compact, rank)
                    } ?: resources.getString(R.string.candidate_frequency_unknown_compact)
                val occurrences =
                    resources.getString(
                        R.string.candidate_occurrences_compact,
                        candidate.occurrenceCount,
                    )
                put(
                    candidate.candidateId,
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(candidate.minedForm)
                        }
                        candidate.expressionReading.takeIf {
                            it.isNotBlank() && it != candidate.minedForm
                        }?.let { reading ->
                            append(" · ")
                            append(reading)
                        }
                        append(" · ")
                        append(partOfSpeech)
                        append(" · ")
                        append(frequency)
                        append(" · ")
                        append(occurrences)
                    },
                )
            }
        }
    }
}

/**
 * Keeps focus inside the visible projection when search, filter, or sort changes it.
 *
 * The previous ordering is captured here rather than in the reducer: once a row leaves the
 * projection there is no anchor left to pick a neighbour from.
 */
@Composable
internal fun ReconcileCurationFocus(
    visibleCandidateIds: List<String>,
    focusedCandidateId: String?,
    onReconcile: (visible: List<String>, previous: List<String>) -> Unit,
) {
    val previous = remember { mutableStateOf(visibleCandidateIds) }
    LaunchedEffect(visibleCandidateIds, focusedCandidateId) {
        val before = previous.value
        previous.value = visibleCandidateIds
        if (focusedCandidateId != null && focusedCandidateId !in visibleCandidateIds) {
            onReconcile(visibleCandidateIds, before)
        }
    }
}

@Composable
internal fun CurationCandidateHeader(
    headline: AnnotatedString,
    stateText: String,
    includeLabel: String,
    selected: Boolean,
    expanded: Boolean,
    animateSelection: Boolean,
    enabled: Boolean,
    toggleEnabled: Boolean,
    candidateTestTag: String,
    toggleTestTag: String,
    onFocus: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val shape =
        if (expanded) {
            RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        } else {
            MaterialTheme.shapes.medium
        }
    val targetContainerColor =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    val containerColor =
        if (animateSelection) {
            animateColorAsState(
                targetValue = targetContainerColor,
                animationSpec = tween(durationMillis = 150),
                label = "focused candidate selection",
            ).value
        } else {
            targetContainerColor
        }
    // Two targets, not one. The row opens the detail; only the checkbox includes or excludes. The
    // merged whole-row checkbox meant inspecting an included candidate silently dropped it.
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(candidateTestTag)
                .clickable(enabled = enabled, onClick = onFocus)
                .semantics(mergeDescendants = true) {
                    stateDescription = stateText
                },
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = AnkiMinerTokens.Space.group,
                    vertical = AnkiMinerTokens.Space.line,
                ),
            horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = onToggle,
                enabled = toggleEnabled,
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .testTag(toggleTestTag)
                        .semantics { contentDescription = includeLabel },
            )
            Text(
                text = headline,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CurationRowActions(
    known: Boolean,
    enabled: Boolean,
    knownTestTag: String,
    copyWordTestTag: String,
    copySentenceTestTag: String,
    onToggleKnown: (Boolean) -> Unit,
    onCopyWord: () -> Unit,
    onCopySentence: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            HorizontalDivider()
            FlowRow(
                modifier =
                    Modifier.fillMaxWidth().padding(
                        horizontal = AnkiMinerTokens.Space.group,
                        vertical = AnkiMinerTokens.Space.line,
                    ),
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            ) {
                TextButton(
                    onClick = { onToggleKnown(!known) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(knownTestTag),
                ) {
                    Text(
                        stringResource(
                            if (known) {
                                R.string.curation_known_pending
                            } else {
                                R.string.curation_add_known
                            },
                        ),
                    )
                }
                TextButton(
                    onClick = onCopyWord,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(copyWordTestTag),
                ) {
                    Text(stringResource(R.string.curation_copy_word))
                }
                TextButton(
                    onClick = onCopySentence,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(copySentenceTestTag),
                ) {
                    Text(stringResource(R.string.curation_copy_sentence))
                }
            }
        }
    }
}

@Composable
internal fun CurationDefinitionPane(
    definition: CurationDefinition,
    term: String,
    testTag: String,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(testTag),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(0.dp),
    ) {
        Column {
            HorizontalDivider()
            when (definition) {
                CurationDefinition.Loading -> DefinitionNotice(R.string.definition_loading)
                CurationDefinition.Missing -> DefinitionNotice(R.string.definition_missing)
                CurationDefinition.Unavailable ->
                    DefinitionNotice(R.string.definition_unavailable)
                is CurationDefinition.Loaded -> {
                    if (definition.matchedTerm != term) {
                        Text(
                            text =
                                stringResource(
                                    R.string.definition_fallback,
                                    term,
                                    definition.matchedTerm,
                                ),
                            modifier =
                                Modifier.padding(
                                    horizontal = AnkiMinerTokens.Space.group,
                                    vertical = AnkiMinerTokens.Space.line,
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DictionaryHtml(
                        html = definition.entries.joinToString(separator = "\n") { it.html },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 96.dp, max = 260.dp)
                                .padding(horizontal = AnkiMinerTokens.Space.group),
                        updateKey = definition.matchedTerm,
                    )
                }
            }
        }
    }
}

@Composable
private fun DefinitionNotice(
    @StringRes text: Int,
) {
    Text(
        text = stringResource(text),
        modifier = Modifier.padding(AnkiMinerTokens.Space.group),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun CurationSentenceChoice(
    candidate: CurationCandidate,
    sentence: CurationSentence,
    selected: Boolean,
    enabled: Boolean,
    isLast: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val sentenceDescription =
        stringResource(R.string.sentence_selection_description, sentence.sentence)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape =
            if (isLast) {
                RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
            } else {
                RoundedCornerShape(0.dp)
            },
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = onClick,
                        ).testTag(testTag)
                        .semantics { contentDescription = sentenceDescription }
                        .padding(horizontal = AnkiMinerTokens.Space.group, vertical = AnkiMinerTokens.Space.group),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    enabled = enabled,
                )
                Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.micro)) {
                    Text(
                        text = candidate.minedForm,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(text = sentence.sentence, style = MaterialTheme.typography.bodyMedium)
                    if (
                        sentence.sentenceFurigana.isNotBlank() &&
                        sentence.sentenceFurigana != sentence.sentence
                    ) {
                        Text(
                            text = sentence.sentenceFurigana,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
