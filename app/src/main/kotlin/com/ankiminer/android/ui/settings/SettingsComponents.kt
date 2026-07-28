package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.anki.provider.AnkiProviderReadiness
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.ImportedAudioPack
import com.ankiminer.android.data.resources.ImportedFrequencySource
import com.ankiminer.android.data.resources.ImportedKnownWords
import com.ankiminer.android.data.resources.ImportedPitchAccent
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.LocalResourceImportResult
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.data.resources.ResourceOperationPhase
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.data.resources.ResourceProgressUnit
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.ui.theme.AdaptiveActionGroup
import com.ankiminer.android.ui.theme.AdaptivePairedActions
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.CompactLayoutWidthDp
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.forwardButtonColors
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import com.ankiminer.android.ui.theme.radioActionColors
import com.ankiminer.android.ui.theme.segmentedActionColors
import com.ankiminer.android.vm.PendingResourceReplace
import com.ankiminer.android.vm.ResourceReplaceKind
import com.ankiminer.android.vm.SettingsSaveState
import kotlinx.coroutines.launch

/**
 * A heading and its rows, with no border. Twelve of these wrapped an OutlinedCard inside an already
 * padded item, so every section — even a single status line — read as a boxed aside.
 */
@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
    ) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
internal fun SettingTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    error: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        // Only when the value is actually wrong. A permanent hint line under every field cost a
        // row each and said nothing the label did not.
        supportingText = error?.let { { Text(it) } },
        enabled = enabled,
        isError = error != null,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun NumericField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    allowNegative: Boolean = false,
    integer: Boolean = false,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Done,
) {
    val focusManager = LocalFocusManager.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    SettingTextField(
        value = value,
        // The numeric keyboard is a hint, not validation. Paste and hardware keyboards can still
        // enter malformed text; keep it visible so field-keyed validation can explain the problem.
        onChange = onChange,
        label = label,
        error = error,
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    when {
                        integer && allowNegative -> KeyboardType.Number
                        integer -> KeyboardType.Number
                        allowNegative -> KeyboardType.Decimal
                        else -> KeyboardType.Decimal
                    },
                imeAction = imeAction,
            ),
        keyboardActions =
            KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                onDone = { focusManager.clearFocus() },
            ),
        modifier =
            Modifier
                .bringIntoViewRequester(bringIntoViewRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        coroutineScope.launch { bringIntoViewRequester.bringIntoView() }
                    }
                },
    )
}

/**
 * One line. The checkbox already shows the resolved value, so the "Resolved value: On" caption is
 * gone; an override is marked on the label instead of costing a second row and a reset button.
 * Returning to the default is the category's Restore defaults action.
 */
@Composable
internal fun NullableToggle(
    label: String,
    value: Boolean?,
    desktopDefault: Boolean,
    onChange: (Boolean?) -> Unit,
) {
    val resolved = value ?: desktopDefault
    val overridden = value != null
    val overrideState = stringResource(R.string.b3_settings_android_override_state)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AnkiMinerTokens.Layout.minTouchTarget)
            .toggleable(
                value = resolved,
                role = Role.Checkbox,
                onValueChange = { onChange(it) },
            ).padding(vertical = AnkiMinerTokens.Space.line)
            .semantics { if (overridden) stateDescription = overrideState },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontWeight = if (overridden) FontWeight.Medium else null,
        )
        Checkbox(checked = resolved, onCheckedChange = null)
    }
}

@Composable
internal fun SettingsSaveStatus(
    state: SettingsSaveState,
    error: String?,
    onRetry: () -> Unit,
) {
    val label =
        when (state) {
            is SettingsSaveState.Pending -> stringResource(R.string.b3_settings_save_pending)
            is SettingsSaveState.Saving -> stringResource(R.string.b3_settings_save_saving)
            is SettingsSaveState.Saved -> stringResource(R.string.b3_settings_save_saved)
            is SettingsSaveState.Failed ->
                error ?: stringResource(R.string.b3_settings_save_failed)
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color =
            if (state is SettingsSaveState.Failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.padding(horizontal = AnkiMinerTokens.Space.group, vertical = AnkiMinerTokens.Space.related),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (state is SettingsSaveState.Failed) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.b3_retry))
                }
            }
        }
    }
}

@Composable
internal fun InlineFailureContainer(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.padding(AnkiMinerTokens.Space.group),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line),
        ) {
            Text(message)
            AdaptiveActionGroup(
                primary = { actionModifier ->
                    TextButton(
                        onClick = onAction,
                        modifier = actionModifier,
                    ) { Text(actionLabel) }
                },
                secondary = { actionModifier ->
                    TextButton(
                        onClick = onDismiss,
                        modifier = actionModifier,
                    ) {
                        Text(stringResource(R.string.b3_dismiss))
                    }
                },
            )
        }
    }
}

/**
 * [detail] is for per-item facts the label cannot carry on its own — an entry count, a deck that is
 * no longer discoverable. It rides on the same line, so a row stays a row. It is not a slot for
 * explanatory copy.
 */
@Composable
internal fun BooleanSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    detail: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AnkiMinerTokens.Layout.minTouchTarget)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ).padding(vertical = AnkiMinerTokens.Space.line),
        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.takeIf(String::isNotBlank)?.let { SupportingText(it) }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/** Segments when they fit; full-width radio rows at compact width or large text. */
@Composable
internal fun <T> AdaptiveChoiceSelector(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val useSegments =
            maxWidth >= CompactLayoutWidthDp.dp && LocalDensity.current.fontScale < 1.3f
        if (useSegments) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                values.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = values.size,
                            ),
                        modifier = Modifier.heightIn(min = 48.dp),
                        enabled = enabled,
                        colors = segmentedActionColors(),
                    ) { Text(label(value), maxLines = 2) }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line),
            ) {
                values.forEach { value ->
                    val isSelected = value == selected
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = isSelected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(value) },
                                ).padding(horizontal = AnkiMinerTokens.Space.group, vertical = AnkiMinerTokens.Space.line),
                        horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            enabled = enabled,
                            colors = radioActionColors(),
                        )
                        Text(label(value), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Choice peer of [NullableToggle] for a setting whose null means "use the engine default".
 *
 * Renders the resolved value as the selection instead of offering a separate "use recommended
 * default" segment, which named an implementation detail and cost a third of the control's width.
 * As with [NullableToggle], an override is marked on the label and the way back is the category's
 * Restore defaults action.
 */
@Composable
internal fun <T> NullableChoice(
    label: String,
    value: T?,
    engineDefault: T,
    values: List<T>,
    optionLabel: @Composable (T) -> String,
    onChange: (T) -> Unit,
    enabled: Boolean = true,
) {
    val overridden = value != null
    val overrideState = stringResource(R.string.b3_settings_android_override_state)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { if (overridden) stateDescription = overrideState },
        verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (overridden) FontWeight.Medium else null,
        )
        AdaptiveChoiceSelector(
            values = values,
            selected = value ?: engineDefault,
            label = optionLabel,
            onSelect = onChange,
            enabled = enabled,
        )
    }
}

@Composable
internal fun ResourceChainEditor(
    choices: List<ResourceChainSelection>,
    labels: Map<String, String>,
    emptyMessage: String,
    onChange: (List<ResourceChainSelection>) -> Unit,
) {
    if (choices.isEmpty()) {
        Text(emptyMessage)
        return
    }
    choices.forEachIndexed { index, choice ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.line)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = choice.enabled,
                        role = Role.Checkbox,
                        onValueChange = { enabled ->
                            onChange(
                                choices.toMutableList().also {
                                    it[index] = choice.copy(enabled = enabled)
                                },
                            )
                        },
                    ).padding(vertical = AnkiMinerTokens.Space.line),
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            ) {
                Checkbox(
                    checked = choice.enabled,
                    onCheckedChange = null,
                )
                Column(Modifier.weight(1f)) {
                    Text(labels[choice.resourceId] ?: choice.resourceId)
                    Text(choice.resourceId, style = MaterialTheme.typography.bodySmall)
                }
            }
            AdaptivePairedActions(
                first = { actionModifier ->
                    val moveEnabled = index > 0
                    OutlinedButton(
                        enabled = moveEnabled,
                        onClick = { onChange(choices.swap(index, index - 1)) },
                        modifier = actionModifier,
                        colors = outlinedActionButtonColors(),
                        border = actionBorder(moveEnabled),
                    ) { Text(stringResource(R.string.settings_move_up)) }
                },
                second = { actionModifier ->
                    val moveEnabled = index < choices.lastIndex
                    OutlinedButton(
                        enabled = moveEnabled,
                        onClick = { onChange(choices.swap(index, index + 1)) },
                        modifier = actionModifier,
                        colors = outlinedActionButtonColors(),
                        border = actionBorder(moveEnabled),
                    ) { Text(stringResource(R.string.settings_move_down)) }
                },
            )
        }
    }
}

private fun <T> List<T>.swap(
    first: Int,
    second: Int,
): List<T> =
    toMutableList().also { values ->
        val held = values[first]
        values[first] = values[second]
        values[second] = held
    }

@Composable
internal fun ResourceCard(
    title: String,
    description: String,
    installed: Boolean,
    busy: Boolean,
    action: () -> Unit,
    actionLabel: String,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    // Install/Repair on the button already says which state the resource is in, so the state line
    // is announced rather than drawn. The description is what the download actually is.
    val state =
        stringResource(
            if (installed) R.string.resource_installed else R.string.resource_not_installed,
        )
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .semantics { stateDescription = state },
    ) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            if (!installed) {
                Text(description)
            }
            inlineFailure?.invoke()
            OutlinedButton(
                onClick = action,
                enabled = !busy,
                colors = outlinedActionButtonColors(),
                border = actionBorder(enabled = !busy),
            ) { Text(actionLabel) }
        }
    }
}

/**
 * Confirms an import that would overwrite something already installed.
 *
 * Replaces the "Replace an existing resource with this ID" checkbox, which asked about a collision
 * the user could not see, described an ID the pitch card never had, and - left unticked - failed
 * only after the file had been staged, copied, validated and indexed, with no retry.
 */
@Composable
internal fun ResourceReplaceDialog(
    pending: PendingResourceReplace?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (pending.repair) {
                        R.string.dictionary_repair_confirm_title
                    } else {
                        R.string.resource_replace_confirm_title
                    },
                    pending.installedLabel,
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    // Pitch is promoted with a bare file replace and keeps no backup, unlike every
                    // other kind, so it must not promise a restore.
                    if (pending.kind == ResourceReplaceKind.PITCH) {
                        R.string.pitch_replace_confirm_message
                    } else {
                        R.string.resource_replace_confirm_message
                    },
                    pending.installedLabel,
                ),
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy, colors = forwardButtonColors()) {
                Text(
                    stringResource(
                        if (pending.repair) {
                            R.string.dictionary_repair_confirm
                        } else {
                            R.string.dictionary_replace_confirm
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun ResourceOperationCard(
    operation: ResourceOperationProgress,
    onCancel: () -> Unit,
) {
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(operation.label, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(resourcePhaseLabel(operation.phase)))
            val animatedFraction by
                animateFloatAsState(
                    targetValue = operation.fraction ?: 0f,
                    animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                    label = "resource progress",
                )
            operation.fraction?.let {
                LinearProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when (operation.unit) {
                        // Whole MiB floors to "14 of 14" well before the transfer ends, so the
                        // count has to keep a decimal.
                        ResourceProgressUnit.BYTES ->
                            stringResource(
                                R.string.progress_mebibytes,
                                operation.completed / MEBIBYTE_F,
                                operation.total / MEBIBYTE_F,
                            )
                        ResourceProgressUnit.ITEMS ->
                            stringResource(
                                R.string.progress_count,
                                operation.completed,
                                operation.total,
                            )
                    },
                )
            } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = onCancel,
                colors = outlinedActionButtonColors(),
                border = actionBorder(enabled = true),
            ) { Text(stringResource(R.string.cancel)) }
        }
    }
}

/**
 * Shows an optional linked summary without owning the persistent source failure. Callers decide
 * whether timeout/dismiss clears anything; origin-card failures pass the default no-op.
 */
@Composable
internal fun MessageSnackbarEffect(
    message: String?,
    hostState: SnackbarHostState,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    LaunchedEffect(message) {
        if (message != null) {
            val result =
                hostState.showSnackbar(
                    message = message,
                    actionLabel = actionLabel,
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) {
                onAction()
            } else {
                onDismiss()
            }
        }
    }
}

@Composable
internal fun LocalImportResultCard(result: LocalResourceImportResult) {
    val summary =
        when (result) {
            is ImportedFrequencySource ->
                stringResource(R.string.frequency_import_result, result.sourceName, result.entryCount, result.skippedMalformed)
            is ImportedPitchAccent ->
                stringResource(R.string.pitch_import_result, result.sourceName, result.entryCount, result.skippedMalformed)
            is ImportedAudioPack ->
                stringResource(R.string.audio_pack_import_result, result.sourceName, result.entryCount)
            is ImportedKnownWords ->
                stringResource(R.string.known_words_import_result, result.importedCount, result.newRowCount, result.totalEntries)
        }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(stringResource(R.string.local_import_complete), style = MaterialTheme.typography.titleMedium)
            Text(summary)
        }
    }
}

@Composable
internal fun frequencyFormatLabel(format: FrequencySourceFormat): String =
    stringResource(
        when (format) {
            FrequencySourceFormat.YOMITAN_ZIP -> R.string.resource_format_yomitan_zip
            FrequencySourceFormat.CSV -> R.string.resource_format_csv
            FrequencySourceFormat.TSV -> R.string.resource_format_tsv
            FrequencySourceFormat.TEXT -> R.string.resource_format_text
        },
    )

@Composable
internal fun pitchFormatLabel(format: PitchAccentSourceFormat): String =
    stringResource(
        when (format) {
            PitchAccentSourceFormat.YOMITAN_ZIP -> R.string.resource_format_yomitan_zip
            PitchAccentSourceFormat.CSV -> R.string.resource_format_csv
            PitchAccentSourceFormat.TSV -> R.string.resource_format_tsv
        },
    )

@Composable
internal fun knownWordsFormatLabel(format: KnownWordsSourceFormat): String =
    stringResource(
        when (format) {
            KnownWordsSourceFormat.JSON -> R.string.resource_format_json
            KnownWordsSourceFormat.CSV -> R.string.resource_format_csv
            KnownWordsSourceFormat.TSV -> R.string.resource_format_tsv
            KnownWordsSourceFormat.TEXT -> R.string.resource_format_text
        },
    )

@Composable
internal fun pythonStatus(value: PythonRuntimeReadiness): String =
    when (value) {
        PythonRuntimeReadiness.Pending -> stringResource(R.string.status_queued)
        PythonRuntimeReadiness.Starting -> stringResource(R.string.status_starting)
        is PythonRuntimeReadiness.Ready -> stringResource(R.string.status_ready)
        PythonRuntimeReadiness.Failed -> stringResource(R.string.status_failed_restart)
    }

@Composable
internal fun ankiStatus(value: AnkiProviderReadiness): String =
    when (value) {
        AnkiProviderReadiness.NotChecked -> stringResource(R.string.status_not_checked)
        AnkiProviderReadiness.NotInstalled -> stringResource(R.string.status_install_ankidroid)
        AnkiProviderReadiness.Uninitialized -> stringResource(R.string.status_initialize_ankidroid)
        is AnkiProviderReadiness.Incompatible -> stringResource(R.string.status_incompatible_api)
        AnkiProviderReadiness.PermissionDenied -> stringResource(R.string.status_database_permission)
        is AnkiProviderReadiness.Ready -> stringResource(R.string.status_ready_api, value.apiSpecVersion)
    }

@Composable
internal fun resourceStartupStatus(value: ResourceStartupReadiness): String =
    stringResource(
        when (value) {
            ResourceStartupReadiness.PENDING -> R.string.status_pending
            ResourceStartupReadiness.RECOVERING -> R.string.status_recovering
            ResourceStartupReadiness.READY -> R.string.status_ready
            ResourceStartupReadiness.FAILED -> R.string.status_failed
        },
    )

@StringRes
private fun resourcePhaseLabel(value: ResourceOperationPhase): Int =
    when (value) {
        ResourceOperationPhase.PREPARING -> R.string.resource_phase_preparing
        ResourceOperationPhase.DOWNLOADING -> R.string.resource_phase_downloading
        ResourceOperationPhase.VERIFYING -> R.string.resource_phase_verifying
        ResourceOperationPhase.INSTALLING -> R.string.resource_phase_installing
        ResourceOperationPhase.IMPORTING -> R.string.resource_phase_importing
        ResourceOperationPhase.FINALIZING -> R.string.resource_phase_finalizing
        ResourceOperationPhase.REFRESHING -> R.string.resource_phase_refreshing
        ResourceOperationPhase.CANCELLING -> R.string.resource_phase_cancelling
    }

private const val MEBIBYTE_F = 1024f * 1024f
