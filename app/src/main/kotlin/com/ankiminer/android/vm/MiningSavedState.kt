package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.ui.mining.SavedDocumentSelection
import com.ankiminer.android.ui.mining.restoredDocumentSelection

internal class SavedDocumentSelectionStore(
    private val savedStateHandle: SavedStateHandle,
    keyPrefix: String,
) {
    private val uriKey = "$keyPrefix.uri"
    private val displayNameKey = "$keyPrefix.displayName"

    fun restore(): SavedDocumentSelection? =
        restoredDocumentSelection(
            uri = savedStateHandle[uriKey],
            displayName = savedStateHandle[displayNameKey],
        )

    fun save(document: SafDocument) {
        savedStateHandle[uriKey] = document.uri
        savedStateHandle[displayNameKey] = document.displayName
    }

    fun clear() {
        savedStateHandle.remove<String>(uriKey)
        savedStateHandle.remove<String>(displayNameKey)
    }
}
