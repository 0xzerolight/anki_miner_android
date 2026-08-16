package com.ankiminer.android.ui.mining

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.dictionary.CurationDefinition
import com.ankiminer.android.mining.CurationCandidate
import com.ankiminer.android.mining.CurationPage
import com.ankiminer.android.mining.CurationSentence
import com.ankiminer.android.ui.settings.DictionaryHtml
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.ChevronGlyph
import com.ankiminer.android.ui.theme.PhaseTitle
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.selectedRowContainer

internal const val CURATION_SEARCH_TEST_TAG = "curation_search"
internal const val CURATION_FILTER_TEST_TAG = "curation_filter"
internal const val CURATION_SORT_TEST_TAG = "curation_sort"
internal const val CURATION_BULK_TEST_TAG = "curation_bulk_actions"
internal const val CURATION_TOOLS_TOGGLE_TEST_TAG = "curation_tools_toggle"

@StringRes
private fun CurationFilter.label(): Int =
    when (this) {
        CurationFilter.ALL -> R.string.curation_filter_all
        CurationFilter.SELECTED -> R.string.curation_filter_selected
        CurationFilter.EXCLUDED -> R.string.curation_filter_excluded
    }

@StringRes
private fun CurationSort.label(): Int =
    when (this) {
        CurationSort.FREQUENCY -> R.string.curation_sort_frequency
        CurationSort.OCCURRENCES -> R.string.curation_sort_occurrences
    }

/**
 * Fixed chrome above the candidate list: what phase this is, how much is selected, and every
 * control that acts on the projection.
 *
 * It is pinned rather than scrolled because all of it stays relevant for the whole page. As list
 * items, the heading and the search field left the screen after two flicks, and the focused
 * heading — the accessibility anchor for the phase — was disposed along with them.
 */
@Composable
internal fun CurationChrome(
    title: String,
    headingModifier: Modifier,
    selectedCount: Int,
    candidateCount: Int,
    page: CurationPage?,
    query: String,
    filter: CurationFilter,
    sort: CurationSort,
    enabled: Boolean,
    visibleCount: Int,
    allVisibleSelected: Boolean,
    selectVisibleEnabled: Boolean,
    pageCandidateCount: Int?,
    selectAllTestTag: String,
    onQueryChanged: (String) -> Unit,
    onFilterChanged: (CurationFilter) -> Unit,
    onSortChanged: (CurationSort) -> Unit,
    onSetSelectionForVisible: (Boolean) -> Unit,
    onSelectWholePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var toolsExpanded by rememberSaveable { mutableStateOf(true) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhaseTitle(title, headingModifier.weight(1f))
            Text(
                text =
                    stringResource(
                        if (page == null) {
                            R.string.curation_selected_count
                        } else {
                            R.string.curation_selected_count_page
                        },
                        selectedCount,
                        candidateCount,
                    ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
            )
            val toggleDescription =
                stringResource(
                    if (toolsExpanded) {
                        R.string.curation_tools_hide
                    } else {
                        R.string.curation_tools_show
                    },
                )
            val toggleState =
                stringResource(
                    if (toolsExpanded) R.string.disclosure_expanded else R.string.disclosure_collapsed,
                )
            IconButton(
                onClick = { toolsExpanded = !toolsExpanded },
                modifier =
                    Modifier
                        .testTag(CURATION_TOOLS_TOGGLE_TEST_TAG)
                        .semantics {
                            contentDescription = toggleDescription
                            stateDescription = toggleState
                        },
            ) {
                ChevronGlyph(pointsUp = toolsExpanded)
            }
        }
        page?.let {
            Text(
                text =
                    stringResource(
                        R.string.curation_page_position,
                        it.pageIndex + 1,
                        it.pageCount,
                        it.candidateStart + 1,
                        it.candidateStart + candidateCount,
                        it.totalCandidates,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (it.pageIndex > 0) {
                Text(
                    text = stringResource(R.string.curation_previous_pages_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (toolsExpanded) {
            CurationControls(
                query = query,
                filter = filter,
                sort = sort,
                enabled = enabled,
                visibleCount = visibleCount,
                allVisibleSelected = allVisibleSelected,
                selectVisibleEnabled = selectVisibleEnabled,
                pageCandidateCount = pageCandidateCount,
                selectAllTestTag = selectAllTestTag,
                onQueryChanged = onQueryChanged,
                onFilterChanged = onFilterChanged,
                onSortChanged = onSortChanged,
                onSetSelectionForVisible = onSetSelectionForVisible,
                onSelectWholePage = onSelectWholePage,
            )
        }
    }
}

/**
 * Search, projection and bulk selection for the candidate list.
 *
 * Filter and sort trade two scrolling chip rows for two menus, and the bulk actions — previously
 * two full-width buttons — fold into a third, which is what buys the vertical room back.
 */
@Composable
private fun CurationControls(
    query: String,
    filter: CurationFilter,
    sort: CurationSort,
    enabled: Boolean,
    visibleCount: Int,
    allVisibleSelected: Boolean,
    selectVisibleEnabled: Boolean,
    pageCandidateCount: Int?,
    selectAllTestTag: String,
    onQueryChanged: (String) -> Unit,
    onFilterChanged: (CurationFilter) -> Unit,
    onSortChanged: (CurationSort) -> Unit,
    onSetSelectionForVisible: (Boolean) -> Unit,
    onSelectWholePage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
        // Placeholder instead of a floating label: the label needs the stock 56dp field to
        // clear its collapsed position, and the compact 48dp height would clip it.
        val compactSearch = LocalDensity.current.fontScale < 1.3f
        OutlinedTextField(
            value = query,
            onValueChange = { onQueryChanged(it.boundedSaveableQuery()) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (compactSearch) Modifier.height(48.dp) else Modifier)
                    .testTag(CURATION_SEARCH_TEST_TAG),
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text(stringResource(R.string.curation_search)) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            CurationMenuButton(
                label =
                    stringResource(
                        R.string.curation_filter_action,
                        stringResource(filter.label()),
                    ),
                enabled = enabled,
                testTag = CURATION_FILTER_TEST_TAG,
            ) { dismiss ->
                CurationFilter.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.label())) },
                        onClick = {
                            onFilterChanged(option)
                            dismiss()
                        },
                    )
                }
            }
            CurationMenuButton(
                label =
                    stringResource(
                        R.string.curation_sort_action,
                        stringResource(sort.label()),
                    ),
                enabled = enabled,
                testTag = CURATION_SORT_TEST_TAG,
            ) { dismiss ->
                CurationSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.label())) },
                        onClick = {
                            onSortChanged(option)
                            dismiss()
                        },
                    )
                }
            }
            CurationMenuButton(
                label = stringResource(R.string.curation_bulk_action),
                enabled = enabled,
                testTag = CURATION_BULK_TEST_TAG,
            ) { dismiss ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (allVisibleSelected) {
                                    R.string.deselect_visible
                                } else {
                                    R.string.select_visible
                                },
                                visibleCount,
                            ),
                        )
                    },
                    onClick = {
                        onSetSelectionForVisible(!allVisibleSelected)
                        dismiss()
                    },
                    modifier = Modifier.testTag(selectAllTestTag),
                    enabled = selectVisibleEnabled,
                )
                // Page-wide selection stays reachable, but named for the scope it actually reaches
                // rather than hiding behind the same action the filtered one uses.
                if (pageCandidateCount != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.curation_select_whole_page,
                                    pageCandidateCount,
                                ),
                            )
                        },
                        onClick = {
                            onSelectWholePage()
                            dismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CurationMenuButton(
    label: String,
    enabled: Boolean,
    testTag: String,
    items: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        // Visually 40dp; minimumInteractiveComponentSize keeps the 48dp touch target.
        SecondaryActionButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items { expanded = false }
        }
    }
}

/** The two lines of a candidate row, built once per page rather than per scroll frame. */
@Immutable
internal data class CurationCandidateRowText(
    val headline: AnnotatedString,
    val metadata: String,
)

@Composable
internal fun rememberCurationCandidateRowTexts(
    candidates: List<CurationCandidate>,
): Map<String, CurationCandidateRowText> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // The reading rides in the headline rather than on its own line so that a long word and its
    // reading wrap together; it needs an explicit span because the line's base style is the word's.
    val readingStyle =
        SpanStyle(
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontWeight = MaterialTheme.typography.bodyMedium.fontWeight,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    return remember(candidates, context, configuration, readingStyle) {
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
                    CurationCandidateRowText(
                        headline =
                            buildAnnotatedString {
                                append(candidate.minedForm)
                                candidate.expressionReading.takeIf {
                                    it.isNotBlank() && it != candidate.minedForm
                                }?.let { reading ->
                                    withStyle(readingStyle) {
                                        append("  ")
                                        append(reading)
                                    }
                                }
                            },
                        metadata = "$partOfSpeech · $frequency · $occurrences",
                    ),
                )
            }
        }
    }
}

/**
 * Returns to the first result whenever the projection changes.
 *
 * A keyed lazy list re-anchors on whichever row was on top, so re-sorting a hundred candidates
 * scrolls to wherever that one row landed — which reads as a random jump. The heading used to be
 * list item 0 and absorbed this; pinned, it no longer can.
 *
 * Requested rather than scrolled: the anchoring happens during measure, so a plain scrollToItem
 * issued from composition is overwritten by it.
 */
@Composable
internal fun ResetCurationScrollOnProjectionChange(
    listState: LazyListState,
    requestId: String?,
    query: String,
    filter: CurationFilter,
    sort: CurationSort,
) {
    LaunchedEffect(requestId, query, filter, sort) {
        listState.requestScrollToItem(0)
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

/**
 * Gap below one item of a candidate group: 12dp after the last part, nothing inside.
 *
 * The list itself spaces curation items by zero so the header, actions, definition and sentences —
 * separate lazy items for virtualization's sake — can sit flush and read as one card.
 */
internal fun curationGroupGap(last: Boolean): Dp = if (last) AnkiMinerTokens.Space.group else 0.dp

/**
 * Fill for a candidate row and every part of its expanded group.
 *
 * The expanded parts are sibling lazy items rather than children of the header — 120 sentences in
 * one item would defeat virtualization — so the shared colour is what makes the stack read as one
 * card. Only the focused row animates; animating all of them would run one animation per row.
 */
@Composable
internal fun curationRowContainerColor(
    selected: Boolean,
    animateSelection: Boolean,
): Color {
    val target =
        if (selected) {
            MaterialTheme.colorScheme.selectedRowContainer()
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    return if (animateSelection) {
        animateColorAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = AnkiMinerTokens.Motion.StateMs),
            label = "focused candidate selection",
        ).value
    } else {
        target
    }
}

@Composable
internal fun CurationCandidateRow(
    text: CurationCandidateRowText,
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
    modifier: Modifier = Modifier,
) {
    val shape =
        if (expanded) {
            RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        } else {
            MaterialTheme.shapes.medium
        }
    val containerColor = curationRowContainerColor(selected, animateSelection)
    // Two targets, not one. The row opens the detail; only the checkbox includes or excludes. The
    // merged whole-row checkbox meant inspecting an included candidate silently dropped it.
    Surface(
        modifier =
            modifier
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
                    vertical = AnkiMinerTokens.Space.related,
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.micro),
            ) {
                Text(
                    text = text.headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = text.metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun CurationRowActions(
    containerColor: Color,
    known: Boolean,
    enabled: Boolean,
    knownTestTag: String,
    copyWordTestTag: String,
    copySentenceTestTag: String,
    onToggleKnown: (Boolean) -> Unit,
    onCopyWord: () -> Unit,
    onCopySentence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
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
    containerColor: Color,
    term: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(testTag),
        color = containerColor,
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

private val MinedFormHighlight = SpanStyle(fontWeight = FontWeight.Bold)

/**
 * Bolds the mined form where it occurs in its example sentence.
 *
 * Uses `surface` — the inflected form actually present in the text — rather than `minedForm`, and
 * falls back to plain text when it does not occur, which normalisation and conjugation both cause.
 */
internal fun highlightMinedForm(
    sentence: String,
    surface: String,
): AnnotatedString {
    val start = if (surface.isBlank()) -1 else sentence.indexOf(surface)
    if (start < 0) return AnnotatedString(sentence)
    return buildAnnotatedString {
        append(sentence)
        addStyle(MinedFormHighlight, start, start + surface.length)
    }
}

@Composable
internal fun CurationSentenceChoice(
    candidate: CurationCandidate,
    sentence: CurationSentence,
    containerColor: Color,
    selected: Boolean,
    enabled: Boolean,
    isLast: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sentenceDescription =
        stringResource(R.string.sentence_selection_description, sentence.sentence)
    val sentenceText =
        remember(sentence.sentence, candidate.surface) {
            highlightMinedForm(sentence.sentence, candidate.surface)
        }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
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
                    Text(text = sentenceText, style = MaterialTheme.typography.bodyMedium)
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
