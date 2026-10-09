package com.ankiminer.android.ui.reading

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.text.Html
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the clipboard holds text, read from its description only: no content read, no system toast.
 * A text-typed content-URI clip still shows the button, and its Paste then does nothing, because
 * [clipboardText] never reads a URI. Reading the item here would toast on every resume (Android 12+).
 */
@Composable
internal fun rememberClipboardHasText(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    var hasText by remember(manager) { mutableStateOf(manager.hasText()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(manager, lifecycle) {
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener { hasText = manager.hasText() }
        // Android 10+ hides the clipboard from an app without focus, so look again on return.
        val resumeObserver =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) hasText = manager.hasText()
            }
        manager?.addPrimaryClipChangedListener(clipListener)
        lifecycle.addObserver(resumeObserver)
        onDispose {
            manager?.removePrimaryClipChangedListener(clipListener)
            lifecycle.removeObserver(resumeObserver)
        }
    }
    return hasText
}

private fun ClipboardManager?.hasText(): Boolean =
    this?.primaryClipDescription?.let {
        it.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || it.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
    } == true

/**
 * The clipboard's first item as plain text, or null. Never `coerceToText`: for an item that holds
 * only a content URI it reads the whole stream, unbounded, on the calling thread, which is the main
 * thread here. A URI-only clip therefore pastes nothing.
 */
internal fun Context.clipboardText(): String? {
    val item =
        getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?: return null
    return clipItemPasteText(item.text, item.htmlText) { html ->
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
    }
}

/** A clip item's paste text: its plain text, else its HTML rendered as plain text, else null. */
internal fun clipItemPasteText(
    text: CharSequence?,
    htmlText: String?,
    htmlToPlainText: (String) -> CharSequence,
): String? = (text ?: htmlText?.let(htmlToPlainText))?.toString()?.takeIf { it.isNotEmpty() }
