package com.ankiminer.android.ui.settings

import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.ankiminer.android.ui.theme.AnkiMinerTokens
import com.ankiminer.android.ui.theme.PrimaryActionButton
import com.ankiminer.android.vm.SetupUiState

/**
 * The one affordance that installs the pinned recommended set.
 *
 * Rendered in the wizard's dictionary step and reachable from the dictionary panel's Add menu. The
 * plan behind it is the same one the runner uses, so a satisfied set shows a disabled button rather
 * than a no-op press. A broken slot is still repaired from its own panel row, which is why
 * `installCatalogDictionary` survives with no install affordance of its own.
 */
@Composable
internal fun RecommendedResourcesCard(
    state: SetupUiState,
    onDownload: () -> Unit,
    inlineFailure: (@Composable () -> Unit)? = null,
) {
    val plan = state.recommendedPlan
    ResourceCard(
        title = stringResource(R.string.recommended_resources_title),
        description = stringResource(R.string.recommended_resources_description),
        installed = plan.isSatisfied,
        busy = state.busy,
        action = onDownload,
        actionEnabled = plan.isActionable,
        actionLabel =
            stringResource(
                if (plan.isSatisfied) {
                    R.string.recommended_resources_installed_action
                } else {
                    R.string.recommended_resources_download
                },
            ),
        inlineFailure = inlineFailure,
    )
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
            PrimaryActionButton(
                onClick = onLookup,
                enabled = state.lookupSlotId != null && state.lookupTerm.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
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
