package com.ankiminer.android.ui.settings

import android.content.Context
import android.view.MotionEvent
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ankiminer.android.R
import com.ankiminer.android.data.resources.ResourceFailureOrigin
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.SecondaryActionButton
import com.ankiminer.android.ui.theme.actionBorder
import com.ankiminer.android.ui.theme.forwardButtonColors
import com.ankiminer.android.ui.theme.outlinedActionButtonColors
import com.ankiminer.android.vm.SetupUiState

/**
 * Bundled dictionary install cards. An installed dictionary has nothing to offer, so its card is
 * hidden behind a disclosure rather than sitting permanently at the top of the tab.
 *
 * The disclosure and its state live here, not in the caller, because the onboarding wizard's
 * dictionary step is a bare call to this composable — without it that step renders empty on a
 * re-run with both dictionaries already installed.
 */
@Composable
internal fun CatalogDictionaryCards(
    state: SetupUiState,
    onInstall: (String) -> Unit,
    inlineFailure: @Composable (String) -> Unit = {},
) {
    var showInstalled by rememberSaveable { mutableStateOf(false) }
    // A failed *replace* leaves the dictionary installed, so filtering on installed alone would
    // hide the card that owns the failure message.
    val failureTargetId =
        state.failure
            ?.takeIf { it.origin == ResourceFailureOrigin.CATALOG_DICTIONARY }
            ?.retry
            ?.targetId
    val visible =
        state.catalogDictionaries.filter {
            showInstalled || !it.installed || it.resource.resourceId == failureTargetId
        }
    val installedAreHidden =
        !showInstalled &&
            state.catalogDictionaries.any {
                it.installed && it.resource.resourceId != failureTargetId
            }
    visible.forEach { status ->
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
    if (visible.isEmpty() || installedAreHidden) {
        SecondaryActionButton(
            onClick = { showInstalled = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.dictionary_catalog_reinstall))
        }
    }
}

@Composable
internal fun CustomDictionaryImportCard(
    state: SetupUiState,
    onImport: () -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(stringResource(R.string.custom_dictionary_title), style = MaterialTheme.typography.titleMedium)
            inlineFailure?.invoke()
            OutlinedButton(
                onClick = onImport,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = outlinedActionButtonColors(),
                border = actionBorder(enabled = !state.busy),
            ) {
                Text(stringResource(R.string.custom_dictionary_choose))
            }
        }
    }
}

/**
 * Every occupied dictionary slot, with Replace and Remove buttons for each.
 *
 * The priority editor above lists the healthy slots with the same names and counts, but it cannot
 * remove one, and a broken slot never reaches it. This card also says *which* slot is broken: an
 * unusable dictionary raises a fatal `dictionary_resource_invalid` inventory failure whose message
 * does not name the slot, so row-scoped actions are the only safe way to target it.
 */
@Composable
internal fun DictionaryInventoryCard(
    state: SetupUiState,
    onReplace: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val installed = state.dictionaries.filter { it.occupied }
    if (installed.isEmpty()) return
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(AnkiMinerTokens.Space.content), verticalArrangement = Arrangement.spacedBy(AnkiMinerTokens.Space.related)) {
            Text(
                stringResource(R.string.dictionary_inventory_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            installed.forEachIndexed { index, dictionary ->
                if (index > 0) HorizontalDivider()
                val invalid = !dictionary.isUsable
                Text(
                    stringResource(
                        if (invalid) {
                            R.string.dictionary_inventory_invalid
                        } else {
                            R.string.local_resource_installed
                        },
                        dictionary.sourceName,
                        dictionary.slotId,
                        dictionary.entryCount,
                    ),
                    color =
                        if (invalid) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                )
                OutlinedButton(
                    onClick = { onReplace(dictionary.slotId) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = outlinedActionButtonColors(),
                    border = actionBorder(!state.busy),
                ) { Text(stringResource(R.string.dictionary_replace)) }
                OutlinedButton(
                    onClick = { onRemove(dictionary.slotId) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = outlinedActionButtonColors(),
                    border = actionBorder(!state.busy),
                ) { Text(stringResource(R.string.resource_remove)) }
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

private fun cssHex(color: Color): String = "#%06X".format(0xFFFFFF and color.toArgb())

/**
 * Wraps the engine renderer's HTML fragment in a theme envelope; the fragment itself is untouched.
 *
 * Known limitation: an imported dictionary's own CSS may set light backgrounds on entry elements
 * (the renderer's scoper allows background properties) — those patches keep the dictionary
 * author's colors, as Yomitan does; only the page canvas and default text follow the app theme.
 */
internal fun themedDictionaryHtml(
    fragment: String,
    surface: Color,
    onSurface: Color,
    accent: Color,
): String {
    val scheme = if (surface.luminance() < 0.5f) "dark" else "light"
    return buildString {
        append("<meta charset=\"utf-8\">")
        append("<meta name=\"color-scheme\" content=\"").append(scheme).append("\">")
        append("<style>:root{color-scheme:").append(scheme).append("}")
        append("body{background-color:").append(cssHex(surface))
        append(";color:").append(cssHex(onSurface)).append("}")
        append("a{color:").append(cssHex(accent)).append("}</style>")
        append(fragment)
    }
}

/**
 * WebView that claims vertical drags from Compose ancestors while its own content overflows.
 *
 * Inside a LazyColumn item the ancestor scrollable otherwise consumes the drag and cancels the
 * interop view's touch stream, leaving clipped definitions unreachable. Disallow-intercept is
 * reset by the framework at the end of each gesture, so short definitions keep list scrolling.
 */
internal open class DictionaryWebView(context: Context) : WebView(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            (canScrollVertically(1) || canScrollVertically(-1))
        ) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onTouchEvent(event)
    }
}

@Composable
internal fun DictionaryHtml(
    html: String,
    modifier: Modifier = Modifier,
    updateKey: Any? = null,
    webViewFactory: (Context) -> WebView = { context -> DictionaryWebView(context) },
) {
    val scheme = MaterialTheme.colorScheme
    val themedHtml = themedDictionaryHtml(html, scheme.surface, scheme.onSurface, scheme.primary)
    val surfaceArgb = scheme.surface.toArgb()
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
                // Themed before first paint so a slow load never flashes a white page.
                setBackgroundColor(surfaceArgb)
            }
        },
        update = { webView ->
            // Keep unrelated lookup edits observable to this update block without reloading the
            // rendered result. AndroidView may update for any captured state change.
            updateKey?.hashCode()
            webView.setBackgroundColor(surfaceArgb)
            // The engine renderer's HTML rides behind a theme envelope prefix; JavaScript,
            // file/content access, and all network subresources remain disabled for
            // user-imported dictionaries. A palette switch reloads once via the tag mismatch.
            if (webView.tag != themedHtml) {
                webView.loadDataWithBaseURL(null, themedHtml, "text/html", "UTF-8", null)
                webView.tag = themedHtml
            }
        },
        onRelease = WebView::destroy,
    )
}
