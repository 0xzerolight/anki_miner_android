package com.ankiminer.android.ui.links

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import com.ankiminer.android.R
import com.ankiminer.android.diagnostics.log.AppLog
import com.ankiminer.android.diagnostics.log.LogComponent

/**
 * Opens [uri] with [open], returning false instead of throwing when nothing can handle it.
 *
 * Compose's AndroidUriHandler rethrows ActivityNotFoundException as IllegalArgumentException, and
 * a handler may reject the caller with SecurityException. Either one escaping a click lambda kills
 * the process, and with it any mining run or import, on a device or profile with no browser.
 */
internal fun openExternalLink(
    uri: String,
    open: (String) -> Unit,
): Boolean =
    try {
        open(uri)
        true
    } catch (failure: IllegalArgumentException) {
        linkUnavailable(failure)
    } catch (failure: ActivityNotFoundException) {
        linkUnavailable(failure)
    } catch (failure: SecurityException) {
        linkUnavailable(failure)
    }

private fun linkUnavailable(failure: RuntimeException): Boolean {
    AppLog.w(LogComponent.UI, "open_link", failure, "outcome" to "fail")
    return false
}

/** The click handler for every outbound link: opens it, or says that no app can. */
@Composable
internal fun rememberExternalLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    return remember(uriHandler, context) {
        { uri ->
            if (!openExternalLink(uri, uriHandler::openUri)) {
                Toast.makeText(context, R.string.link_open_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
}
