package com.ankiminer.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.ankiminer.android.data.resources.ResourceFailure
import com.ankiminer.android.data.resources.ResourceOperationPhase
import com.ankiminer.android.data.resources.ResourceOperationProgress
import com.ankiminer.android.data.resources.ResourceStartupReadiness
import com.ankiminer.android.data.settings.ResourceChainSelection
import com.ankiminer.android.engine.PythonRuntimeReadiness

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
internal fun SettingsSectionHeading(title: String) {
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
internal fun SettingTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    supporting: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { Text(supporting) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun NumericField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    supporting: String,
    allowNegative: Boolean = false,
) {
    SettingTextField(
        value = value,
        onChange = { candidate ->
            if (
                candidate.isEmpty() ||
                candidate.toDoubleOrNull() != null ||
                candidate == "." ||
                (allowNegative && candidate in setOf("-", "-."))
            ) {
                onChange(candidate)
            }
        },
        label = label,
        supporting = supporting,
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
                        if (value == null) {
                            if (desktopDefault) {
                                R.string.settings_default_on
                            } else {
                                R.string.settings_default_off
                            }
                        } else {
                            R.string.settings_android_override
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Checkbox(checked = value ?: desktopDefault, onCheckedChange = null)
        }
        if (value != null) {
            TextButton(onClick = { onChange(null) }) {
                Text(stringResource(R.string.settings_default_action))
            }
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

/** Single-choice selector shared by every setting that used to fake radios with "✓" buttons. */
@Composable
internal fun <T> ChoiceSegmentedButtons(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        values.forEachIndexed { index, value ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = values.size),
                enabled = enabled,
            ) { Text(label(value)) }
        }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = index > 0,
                    onClick = { onChange(choices.swap(index, index - 1)) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_move_up)) }
                OutlinedButton(
                    enabled = index < choices.lastIndex,
                    onClick = { onChange(choices.swap(index, index + 1)) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_move_down)) }
            }
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
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description)
            Text(stringResource(if (installed) R.string.resource_installed else R.string.resource_not_installed))
            Button(onClick = action, enabled = !busy) { Text(actionLabel) }
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
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
internal fun ResourceFailureCard(
    failure: ResourceFailure,
    onDismiss: () -> Unit,
) {
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.resource_operation_stopped), style = MaterialTheme.typography.titleMedium)
            Text(failure.message)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
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
        AnkiProviderReadiness.RecoveryBlocked -> stringResource(R.string.status_recovery_attention)
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
