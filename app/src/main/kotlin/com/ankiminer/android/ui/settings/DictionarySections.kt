package com.ankiminer.android.ui.settings

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ankiminer.android.R
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.forwardButtonColors
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import com.ankiminer.android.vm.SetupUiState

@Composable
internal fun CatalogReplaceDialog(
    state: SetupUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pendingReplace =
        state.pendingReplaceResourceId?.let { pending ->
            state.catalogDictionaries.firstOrNull { it.resource.resourceId == pending }
        } ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (pendingReplace.needsRepair) {
                        R.string.dictionary_repair_confirm_title
                    } else {
                        R.string.dictionary_replace_confirm_title
                    },
                ),
            )
        },
        text = { Text(stringResource(R.string.dictionary_replace_confirm_message)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (pendingReplace.needsRepair) {
                            R.string.dictionary_repair_confirm
                        } else {
                            R.string.dictionary_replace_confirm
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun CatalogDictionaryCards(
    state: SetupUiState,
    onInstall: (String) -> Unit,
    inlineFailure: @Composable (String) -> Unit = {},
) {
    state.catalogDictionaries.forEach { status ->
        ResourceCard(
            title =
                stringResource(
                    if (status.resource.slotId == "jmdict") {
                        R.string.jmdict_resource_title
                    } else {
                        R.string.jitendex_resource_title
                    },
                ),
            description =
                stringResource(
                    if (status.resource.slotId == "jmdict") {
                        R.string.jmdict_resource_description
                    } else {
                        R.string.jitendex_resource_description
                    },
                ),
            installed = status.installed,
            busy = state.busy,
            action = { onInstall(status.resource.resourceId) },
            actionLabel = stringResource(
                when {
                    status.needsRepair -> R.string.dictionary_repair
                    status.installed -> R.string.dictionary_replace
                    else -> R.string.dictionary_install
                },
            ),
            inlineFailure = { inlineFailure(status.resource.resourceId) },
        )
    }
}

@Composable
internal fun CustomDictionaryImportCard(
    state: SetupUiState,
    onSlotChanged: (String) -> Unit,
    onReplaceChanged: (Boolean) -> Unit,
    onImport: () -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(stringResource(R.string.custom_dictionary_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.customSlotId,
                onValueChange = onSlotChanged,
                label = { Text(stringResource(R.string.custom_dictionary_slot)) },
                supportingText = {
                    Text(
                        if (state.customSlotValid) {
                            stringResource(R.string.custom_dictionary_slot_help)
                        } else {
                            stringResource(R.string.custom_dictionary_slot_invalid)
                        },
                    )
                },
                isError = !state.customSlotValid,
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ReplaceToggle(state.customReplace, !state.busy, onReplaceChanged)
            inlineFailure?.invoke()
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy && state.customSlotValid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = outlinedActionButtonColors(),
                border = actionBorder(enabled = !state.busy && state.customSlotValid),
            ) {
                Text(stringResource(R.string.custom_dictionary_choose))
            }
        }
    }
}

@Composable
internal fun DictionaryInventoryCard(state: SetupUiState) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(
                stringResource(R.string.dictionary_inventory_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.dictionaries.isEmpty()) {
                Text(stringResource(R.string.dictionary_inventory_empty))
            } else {
                state.dictionaries.forEach { dictionary ->
                    Text(
                        stringResource(
                            if (dictionary.isUsable) {
                                R.string.dictionary_inventory_valid
                            } else {
                                R.string.dictionary_inventory_invalid
                            },
                            dictionary.sourceName,
                            dictionary.slotId,
                            dictionary.entryCount,
                        ),
                        color =
                            if (dictionary.isUsable) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                }
            }
        }
    }
}

@Composable
internal fun DictionaryLookupCard(
    state: SetupUiState,
    onTermChanged: (String) -> Unit,
    onSelectSlot: (String) -> Unit,
    onLookup: () -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(
                stringResource(R.string.dictionary_test_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
                verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related),
            ) {
                state.dictionaries.filter { it.isUsable }.forEach { dictionary ->
                    FilterChip(
                        selected = dictionary.slotId == state.lookupSlotId,
                        onClick = { onSelectSlot(dictionary.slotId) },
                        enabled = !state.busy,
                        label = { Text(dictionary.slotId) },
                    )
                }
            }
            OutlinedTextField(
                value = state.lookupTerm,
                onValueChange = onTermChanged,
                label = { Text(stringResource(R.string.dictionary_term)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onLookup,
                enabled = state.lookupSlotId != null && state.lookupTerm.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = forwardButtonColors(),
            ) { Text(stringResource(R.string.dictionary_render_html)) }
            inlineFailure?.invoke()
            state.lookup?.let { result ->
                Text(stringResource(R.string.dictionary_lookup_label, result.slotId, result.term))
                DictionaryHtml(
                    html = result.html,
                    modifier = Modifier.fillMaxWidth().height(360.dp),
                    updateKey = state.lookupTerm,
                )
            }
        }
    }
}

@Composable
internal fun DictionaryHtml(
    html: String,
    modifier: Modifier = Modifier,
    updateKey: Any? = null,
    webViewFactory: (Context) -> WebView = { context -> WebView(context) },
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            webViewFactory(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.domStorageEnabled = false
                settings.databaseEnabled = false
                setNetworkAvailable(false)
            }
        },
        update = { webView ->
            // Keep unrelated lookup edits observable to this update block without reloading the
            // rendered result. AndroidView may update for any captured state change.
            updateKey?.hashCode()
            // The engine renderer's HTML is loaded byte-for-byte; JavaScript, file/content access,
            // and all network subresources remain disabled for user-imported dictionaries.
            if (webView.tag != html) {
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                webView.tag = html
            }
        },
        onRelease = WebView::destroy,
    )
}
