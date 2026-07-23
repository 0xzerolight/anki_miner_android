package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.engine.PythonRuntimeReadiness
import com.ankiminer.android.ui.theme.AdaptiveActionGroup
import com.ankiminer.android.ui.theme.AdaptivePairedActions
import com.ankiminer.android.ui.theme.CompactLayoutWidthDp
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import com.ankiminer.android.ui.theme.radioActionColors
import com.ankiminer.android.ui.theme.segmentedActionColors
import com.ankiminer.android.vm.SettingsSaveState
import kotlinx.coroutines.launch

@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

@Composable
internal fun SettingTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    supporting: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { Text(error ?: supporting) },
        enabled = enabled,
        isError = error != null,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun NumericField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    supporting: String,
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
        supporting = supporting,
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

@Composable
internal fun NullableToggle(
    label: String,
    value: Boolean?,
    desktopDefault: Boolean,
    onChange: (Boolean?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = value ?: desktopDefault,
                    role = Role.Checkbox,
                    onValueChange = { onChange(it) },
                ).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label)
                Text(
                    stringResource(
                        R.string.b3_settings_resolved_value,
                        stringResource(
                            if (value ?: desktopDefault) {
                                R.string.b3_settings_value_on
                            } else {
                                R.string.b3_settings_value_off
                            },
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Checkbox(checked = value ?: desktopDefault, onCheckedChange = null)
        }
        if (value != null) {
            TextButton(onClick = { onChange(null) }) {
                Text(stringResource(R.string.b3_settings_use_recommended_default))
            }
        }
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
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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

@Composable
internal fun BooleanSetting(
    label: String,
    help: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            ).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(help, style = MaterialTheme.typography.bodySmall)
        }
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                                ).padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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

/** Compatibility entry point for settings sections migrated before the adaptive selector existed. */
@Composable
internal fun <T> ChoiceSegmentedButtons(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    AdaptiveChoiceSelector(
        values = values,
        selected = selected,
        label = label,
        onSelect = onSelect,
        enabled = enabled,
        modifier = modifier,
    )
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
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    ).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (installed) {
                Text(stringResource(R.string.resource_installed))
            } else {
                Text(description)
                Text(stringResource(R.string.resource_not_installed))
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

@Composable
internal fun ReplaceToggle(
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onChange,
            ).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(stringResource(R.string.local_resource_replace), Modifier.padding(top = 12.dp))
    }
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(operation.label, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(resourcePhaseLabel(operation.phase)))
            operation.fraction?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(
                        R.string.progress_mebibytes,
                        operation.completedBytes / (1024 * 1024),
                        operation.totalBytes / (1024 * 1024),
                    ),
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        ResourceOperationPhase.REFRESHING -> R.string.resource_phase_refreshing
        ResourceOperationPhase.CANCELLING -> R.string.resource_phase_cancelling
    }
