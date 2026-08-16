package com.ankiminer.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.WordListKind
import com.ankiminer.android.data.settings.EngineDefaults
import com.ankiminer.android.ui.theme.AdaptivePairedActions
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SupportingText
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.forwardButtonColors
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import com.ankiminer.android.vm.SetupUiState

internal data class WordListRemovalConfirmation(
    val pending: WordListKind? = null,
) {
    fun request(kind: WordListKind): WordListRemovalConfirmation = copy(pending = kind)

    fun cancel(): WordListRemovalConfirmation = copy(pending = null)

    fun confirm(onRemove: (WordListKind) -> Unit): WordListRemovalConfirmation {
        pending?.let(onRemove)
        return copy(pending = null)
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
    var pendingRemoval by rememberSaveable { mutableStateOf<WordListKind?>(null) }
    val removalConfirmation = WordListRemovalConfirmation(pendingRemoval)
    pendingRemoval?.let { kind ->
        val installedLabel =
            stringResource(
                when (kind) {
                    WordListKind.BLACKLIST -> R.string.word_list_blacklist
                    WordListKind.WHITELIST -> R.string.word_list_whitelist
                },
            )
        AlertDialog(
            onDismissRequest = {
                if (!state.busy) {
                    pendingRemoval = removalConfirmation.cancel().pending
                }
            },
            title = {
                Text(stringResource(R.string.resource_delete_confirm_title, installedLabel))
            },
            text = {
                Text(stringResource(R.string.resource_delete_confirm_message, installedLabel))
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRemoval = removalConfirmation.confirm(onRemove).pending
                    },
                    enabled = !state.busy,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                ) { Text(stringResource(R.string.word_list_remove)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = removalConfirmation.cancel().pending
                    },
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
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
                onRemove = {
                    pendingRemoval = removalConfirmation.request(it).pending
                },
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
                onRemove = {
                    pendingRemoval = removalConfirmation.request(it).pending
                },
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
    // Only once a file exists. The snapshot mapper forces the engine flag off while the list is
    // absent, so a ticked box here would claim a filter that is not running.
    if (installed != null) {
        val engineDefault =
            when (kind) {
                WordListKind.BLACKLIST -> EngineDefaults.USE_BLACKLIST
                WordListKind.WHITELIST -> EngineDefaults.USE_WHITELIST
            }
        NullableToggle(toggleLabel, enabled, engineDefault, onEnabledChange)
    }
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
