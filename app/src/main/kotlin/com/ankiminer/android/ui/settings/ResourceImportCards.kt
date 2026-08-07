package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.ui.theme.AdaptivePairedActions
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.vm.SetupUiState
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.forwardButtonColors
import com.ankiminer.android.ui.theme.outlinedActionButtonColors

/**
 * One installed slot and its Remove button.
 *
 * The id is in the label because the id -- not the display name -- is what the priority chain and
 * the engine key on, and a duplicate import differs from its twin only by id.
 */
@Composable
private fun InstalledResourceRow(
    detail: String,
    invalid: Boolean,
    busy: Boolean,
    onRemove: () -> Unit,
) {
    Text(
        detail,
        color =
            if (invalid) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
    )
    OutlinedButton(
        onClick = onRemove,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = outlinedActionButtonColors(),
        border = actionBorder(!busy),
    ) { Text(stringResource(R.string.resource_remove)) }
}

@Composable
internal fun FrequencyImportCard(
    state: SetupUiState,
    onImport: () -> Unit,
    onRemove: (String) -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(stringResource(R.string.frequency_import_title), style = MaterialTheme.typography.titleMedium)
            // Every installed source is listed here, not only broken ones: the priority editor
            // shows the healthy ones but cannot remove them, and an unusable source is dropped
            // from the chain entirely, so this card is the only place either can be deleted.
            state.frequencySources.forEachIndexed { index, source ->
                if (index > 0) HorizontalDivider()
                val invalid = !(source.schemaOk && source.entryCount > 0)
                InstalledResourceRow(
                    detail =
                        stringResource(
                            if (invalid) {
                                R.string.local_resource_inventory_invalid
                            } else {
                                R.string.local_resource_installed
                            },
                            source.sourceName,
                            source.sourceId,
                            source.entryCount,
                        ),
                    invalid = invalid,
                    busy = state.busy,
                    onRemove = { onRemove(source.sourceId) },
                )
            }
            if (state.frequencySources.isEmpty()) Text(stringResource(R.string.frequency_none_installed))
            inlineFailure?.invoke()
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = outlinedActionButtonColors(),
                border = actionBorder(!state.busy),
            ) { Text(stringResource(R.string.frequency_choose_file)) }
        }
    }
}

@Composable
internal fun PitchImportCard(
    state: SetupUiState,
    onImport: () -> Unit,
    onRemove: (String) -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(stringResource(R.string.pitch_import_title), style = MaterialTheme.typography.titleMedium)
            // Every installed source, healthy or not, for the reason the frequency card gives:
            // the priority editor lists but cannot remove, and a broken source is not listed
            // there at all.
            state.pitchSources.forEachIndexed { index, source ->
                if (index > 0) HorizontalDivider()
                val invalid = !(source.schemaOk && source.entryCount > 0)
                InstalledResourceRow(
                    detail =
                        stringResource(
                            if (invalid) {
                                R.string.local_resource_inventory_invalid
                            } else {
                                R.string.local_resource_installed
                            },
                            source.sourceName,
                            source.sourceId,
                            source.entryCount,
                        ),
                    invalid = invalid,
                    busy = state.busy,
                    onRemove = { onRemove(source.sourceId) },
                )
            }
            if (state.pitchSources.isEmpty()) Text(stringResource(R.string.pitch_none_installed))
            inlineFailure?.invoke()
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = outlinedActionButtonColors(),
                border = actionBorder(!state.busy),
            ) { Text(stringResource(R.string.pitch_choose_file)) }
        }
    }
}

@Composable
internal fun AudioPackImportCard(
    state: SetupUiState,
    onImport: () -> Unit,
    onRemove: (String) -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(stringResource(R.string.audio_pack_import_title), style = MaterialTheme.typography.titleMedium)
            // Same as frequency and pitch: the priority editor lists the healthy packs but
            // cannot remove them, and a broken pack never reaches it.
            state.audioPacks.forEachIndexed { index, pack ->
                if (index > 0) HorizontalDivider()
                val invalid = !(pack.contentAvailable && pack.entryCount > 0)
                InstalledResourceRow(
                    detail =
                        stringResource(
                            if (invalid) {
                                R.string.local_resource_inventory_invalid
                            } else {
                                R.string.local_resource_installed
                            },
                            pack.sourceName,
                            pack.packId,
                            pack.entryCount,
                        ),
                    invalid = invalid,
                    busy = state.busy,
                    onRemove = { onRemove(pack.packId) },
                )
            }
            if (state.audioPacks.isEmpty()) Text(stringResource(R.string.audio_pack_none_installed))
            Text(stringResource(R.string.audio_pack_archive_guidance))
            inlineFailure?.invoke()
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = outlinedActionButtonColors(),
                border = actionBorder(!state.busy),
            ) { Text(stringResource(R.string.audio_pack_choose_zip)) }
        }
    }
}

@Composable
internal fun BundledWordsetInventoryCard(state: SetupUiState) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(stringResource(R.string.bundled_wordsets_title), style = MaterialTheme.typography.titleMedium)
            state.wordsets.forEach { wordset ->
                Text(stringResource(R.string.bundled_wordset_item, wordset.displayName, wordset.entryCount))
            }
            if (state.wordsets.isEmpty()) Text(stringResource(R.string.bundled_wordsets_unavailable), color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The blacklist and whitelist files. Unlike every other import here the file itself is what the
 * engine reads, once per run, so it stays installed until the user removes it.
 */
@Composable
internal fun WordListImportCard(
    state: SetupUiState,
    blacklistEnabled: Boolean?,
    whitelistEnabled: Boolean?,
    onImport: (WordListKind) -> Unit,
    onRemove: (WordListKind) -> Unit,
    onBlacklistEnabledChange: (Boolean?) -> Unit,
    onWhitelistEnabledChange: (Boolean?) -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(AnkiMinerTokens.Space.content),
            verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
        ) {
            Text(
                stringResource(R.string.word_lists_title),
                style = MaterialTheme.typography.titleMedium,
            )
            SupportingText(stringResource(R.string.word_lists_format))
            WordListRow(
                state = state,
                kind = WordListKind.BLACKLIST,
                title = stringResource(R.string.word_list_blacklist),
                toggleLabel = stringResource(R.string.settings_use_blacklist),
                enabled = blacklistEnabled,
                onImport = onImport,
                onRemove = onRemove,
                onEnabledChange = onBlacklistEnabledChange,
            )
            HorizontalDivider()
            WordListRow(
                state = state,
                kind = WordListKind.WHITELIST,
                title = stringResource(R.string.word_list_whitelist),
                toggleLabel = stringResource(R.string.settings_use_whitelist),
                enabled = whitelistEnabled,
                onImport = onImport,
                onRemove = onRemove,
                onEnabledChange = onWhitelistEnabledChange,
            )
            SupportingText(stringResource(R.string.word_list_whitelist_scope))
            inlineFailure?.invoke()
        }
    }
}

@Composable
private fun WordListRow(
    state: SetupUiState,
    kind: WordListKind,
    title: String,
    toggleLabel: String,
    enabled: Boolean?,
    onImport: (WordListKind) -> Unit,
    onRemove: (WordListKind) -> Unit,
    onEnabledChange: (Boolean?) -> Unit,
) {
    val installed = state.wordLists.firstOrNull { it.kind == kind }
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(
        if (installed == null) {
            stringResource(R.string.word_list_absent)
        } else {
            stringResource(R.string.word_list_installed, installed.entryCount)
        },
    )
    // The toggle only takes effect once a file exists; without one the snapshot keeps it off.
    NullableToggle(toggleLabel, enabled, false, onEnabledChange)
    AdaptivePairedActions(
        first = { modifier ->
            OutlinedButton(
                onClick = { onImport(kind) },
                enabled = !state.busy,
                modifier = modifier,
                colors = outlinedActionButtonColors(),
                border = actionBorder(!state.busy),
            ) { Text(stringResource(R.string.word_list_choose_file)) }
        },
        second = { modifier ->
            OutlinedButton(
                onClick = { onRemove(kind) },
                enabled = !state.busy && installed != null,
                modifier = modifier,
                colors = outlinedActionButtonColors(),
                border = actionBorder(!state.busy && installed != null),
            ) { Text(stringResource(R.string.word_list_remove)) }
        },
    )
}

@Composable
internal fun KnownWordsImportCard(
    state: SetupUiState,
    onImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onDismissImport: () -> Unit,
    onManage: () -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    state.knownWordsImportPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) onDismissImport() },
            title = { Text(stringResource(R.string.known_words_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
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
                Button(
                    onClick = onConfirmImport,
                    enabled = !state.busy,
                    colors = forwardButtonColors(),
                ) {
                    Text(stringResource(R.string.known_words_import_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onDismissImport,
                    enabled = !state.busy,
                    colors = outlinedActionButtonColors(),
                    border = actionBorder(!state.busy),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(stringResource(R.string.known_words_import_title), style = MaterialTheme.typography.titleMedium)
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
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = outlinedActionButtonColors(),
                border = actionBorder(!state.busy),
            ) {
                Text(stringResource(R.string.known_words_choose_file))
            }
            inlineFailure?.invoke()
            OutlinedButton(
                onClick = onManage,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = outlinedActionButtonColors(),
                border = actionBorder(!state.busy),
            ) { Text(stringResource(R.string.b3_known_words_manage)) }
        }
    }
}
