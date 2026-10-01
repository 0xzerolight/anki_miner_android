package com.ankiminer.android

import android.content.Intent

/** Text another app hands over: the selection menu's "Mine words" or a plain-text share. */
internal fun sharedTextFrom(
    action: String?,
    type: String?,
    processText: CharSequence?,
    sendText: CharSequence?,
): String? {
    val text =
        when (action) {
            Intent.ACTION_PROCESS_TEXT -> processText
            Intent.ACTION_SEND -> sendText.takeIf { type == "text/plain" }
            else -> null
        }
    return text?.toString()?.takeIf { it.isNotBlank() }
}
