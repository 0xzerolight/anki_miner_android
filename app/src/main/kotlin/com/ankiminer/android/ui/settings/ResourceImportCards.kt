package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.FrequencySourceFormat
import com.ankiminer.android.data.resources.KnownWordsSourceFormat
import com.ankiminer.android.data.resources.KnownWordsResetScope
import com.ankiminer.android.data.resources.PitchAccentSourceFormat
import com.ankiminer.android.vm.SetupUiState

@Composable
internal fun FrequencyImportCard(
    state: SetupUiState,
    onIdChanged: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onFormatChanged: (FrequencySourceFormat) -> Unit,
    onReplaceChanged: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.frequency_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.frequency_import_help))
            state.frequencySources.forEach { source ->
                Text(
                    stringResource(
                        if (source.schemaOk && source.entryCount > 0) {
                            R.string.local_resource_inventory_ok
                        } else {
                            R.string.local_resource_inventory_invalid
                        },
                        source.sourceName,
                        source.sourceId,
                        source.entryCount,
                    ),
                    color =
                        if (source.schemaOk && source.entryCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
            if (state.frequencySources.isEmpty()) Text(stringResource(R.string.frequency_none_installed))
            OutlinedTextField(
                value = state.frequencySourceId,
                onValueChange = onIdChanged,
                label = { Text(stringResource(R.string.frequency_source_id)) },
                supportingText = { Text(stringResource(R.string.local_resource_id_help)) },
                isError = !state.frequencySourceIdValid,
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.frequencySourceName,
                onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.local_resource_display_name)) },
                isError = state.frequencySourceName.isBlank(),
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ChoiceSegmentedButtons(
                values = FrequencySourceFormat.entries,
                selected = state.frequencyFormat,
                label = { frequencyFormatLabel(it) },
                onSelect = onFormatChanged,
                enabled = !state.busy,
            )
            ReplaceToggle(state.frequencyReplace, !state.busy, onReplaceChanged)
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy && state.frequencySourceIdValid && state.frequencySourceName.isNotBlank(),
            ) { Text(stringResource(R.string.frequency_choose_file)) }
        }
    }
}

@Composable
internal fun PitchImportCard(
    state: SetupUiState,
    onNameChanged: (String) -> Unit,
    onFormatChanged: (PitchAccentSourceFormat) -> Unit,
    onReplaceChanged: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pitch_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pitch_import_help))
            state.pitchAccent?.let { pitch ->
                Text(
                    stringResource(
                        if (pitch.schemaOk && pitch.entryCount > 0) {
                            R.string.pitch_inventory_ok
                        } else {
                            R.string.pitch_inventory_invalid
                        },
                        pitch.sourceName,
                        pitch.entryCount,
                    ),
                    color =
                        if (pitch.schemaOk && pitch.entryCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            } ?: Text(stringResource(R.string.pitch_none_installed))
            OutlinedTextField(
                value = state.pitchSourceName,
                onValueChange = onNameChanged,
                label = { Text(stringResource(R.string.local_resource_display_name)) },
                isError = state.pitchSourceName.isBlank(),
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ChoiceSegmentedButtons(
                values = PitchAccentSourceFormat.entries,
                selected = state.pitchFormat,
                label = { pitchFormatLabel(it) },
                onSelect = onFormatChanged,
                enabled = !state.busy,
            )
            ReplaceToggle(state.pitchReplace, !state.busy, onReplaceChanged)
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy && state.pitchSourceName.isNotBlank(),
            ) { Text(stringResource(R.string.pitch_choose_file)) }
        }
    }
}

@Composable
internal fun AudioPackImportCard(
    state: SetupUiState,
    onIdChanged: (String) -> Unit,
    onReplaceChanged: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.audio_pack_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.audio_pack_import_help))
            state.audioPacks.forEach { pack ->
                Text(
                    stringResource(
                        if (pack.contentAvailable && pack.entryCount > 0) {
                            R.string.local_resource_inventory_ok
                        } else {
                            R.string.local_resource_inventory_invalid
                        },
                        pack.sourceName,
                        pack.packId,
                        pack.entryCount,
                    ),
                    color =
                        if (pack.contentAvailable && pack.entryCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
            if (state.audioPacks.isEmpty()) Text(stringResource(R.string.audio_pack_none_installed))
            OutlinedTextField(
                value = state.audioPackId,
                onValueChange = onIdChanged,
                label = { Text(stringResource(R.string.audio_pack_id)) },
                supportingText = { Text(stringResource(R.string.local_resource_id_help)) },
                isError = !state.audioPackIdValid,
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ReplaceToggle(state.audioPackReplace, !state.busy, onReplaceChanged)
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy && state.audioPackIdValid,
            ) { Text(stringResource(R.string.audio_pack_choose_zip)) }
        }
    }
}

@Composable
internal fun BundledWordsetInventoryCard(state: SetupUiState) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.bundled_wordsets_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.bundled_wordsets_help))
            state.wordsets.forEach { wordset ->
                Text(stringResource(R.string.bundled_wordset_item, wordset.displayName, wordset.entryCount))
            }
            if (state.wordsets.isEmpty()) Text(stringResource(R.string.bundled_wordsets_unavailable), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun KnownWordsImportCard(
    state: SetupUiState,
    onFormatChanged: (KnownWordsSourceFormat) -> Unit,
    onImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onDismissImport: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onRemove: (String) -> Unit,
    onExport: () -> Unit,
    onReset: (KnownWordsResetScope) -> Unit,
) {
    var pendingReset by remember { mutableStateOf<KnownWordsResetScope?>(null) }
    state.knownWordsImportPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) onDismissImport() },
            title = { Text(stringResource(R.string.known_words_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.known_words_preview_summary,
                            preview.format,
                            preview.importedCount,
                            preview.totalEntries,
                        ),
                    )
                    if (preview.sampleWords.isNotEmpty()) {
                        Text(stringResource(R.string.known_words_preview_samples, preview.sampleWords.joinToString()))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmImport, enabled = !state.busy) {
                    Text(stringResource(R.string.known_words_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissImport, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    pendingReset?.let { scope ->
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = {
                Text(
                    stringResource(
                        if (scope == KnownWordsResetScope.USER) {
                            R.string.known_words_reset_user
                        } else {
                            R.string.known_words_rebuild_cache
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (scope == KnownWordsResetScope.USER) {
                            R.string.known_words_reset_user_confirmation
                        } else {
                            R.string.known_words_rebuild_cache_confirmation
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingReset = null
                        onReset(scope)
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReset = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.known_words_import_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.known_words_import_help))
            Text(
                stringResource(
                    if (state.knownWords.schemaOk) {
                        R.string.known_words_inventory
                    } else {
                        R.string.known_words_inventory_invalid
                    },
                    state.knownWords.totalCount,
                    state.knownWords.userCount,
                    state.knownWords.ankiCount,
                    state.knownWords.minedCount,
                ),
                color =
                    if (state.knownWords.schemaOk) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
            ChoiceSegmentedButtons(
                values = KnownWordsSourceFormat.entries,
                selected = state.knownWordsFormat,
                label = { knownWordsFormatLabel(it) },
                onSelect = onFormatChanged,
                enabled = !state.busy,
            )
            OutlinedButton(onClick = onImport, enabled = !state.busy) {
                Text(stringResource(R.string.known_words_choose_file))
            }
            Text(stringResource(R.string.known_words_manage_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.known_words_manage_help))
            OutlinedTextField(
                value = state.knownWordsSearch,
                onValueChange = onSearchChanged,
                label = { Text(stringResource(R.string.known_words_search)) },
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = onSearch, enabled = !state.busy) {
                Text(stringResource(R.string.known_words_search_action))
            }
            state.knownWordsPage?.let { page ->
                page.words.forEach { word ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(word, Modifier.weight(1f))
                        TextButton(onClick = { onRemove(word) }, enabled = !state.busy) {
                            Text(stringResource(R.string.known_words_remove))
                        }
                    }
                }
                if (page.hasMore) {
                    OutlinedButton(onClick = onLoadMore, enabled = !state.busy) {
                        Text(stringResource(R.string.known_words_load_more))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.known_words_export))
                }
                OutlinedButton(
                    onClick = { pendingReset = KnownWordsResetScope.USER },
                    enabled = !state.busy && state.knownWords.userCount > 0,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.known_words_reset_user)) }
            }
            OutlinedButton(
                onClick = { pendingReset = KnownWordsResetScope.CACHE },
                enabled = !state.busy && state.knownWords.ankiCount + state.knownWords.minedCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.known_words_rebuild_cache)) }
        }
    }
}
