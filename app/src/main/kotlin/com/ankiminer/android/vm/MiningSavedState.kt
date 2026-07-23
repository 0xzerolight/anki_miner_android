package com.ankiminer.android.vm

import androidx.lifecycle.SavedStateHandle
import com.ankiminer.android.media.SafDocument
import com.ankiminer.android.media.SafSelectionInventory
import com.ankiminer.android.media.SafSelectionSlot
import com.ankiminer.android.media.safSelectionRecordOrNull
import com.ankiminer.android.ui.mining.SavedDocumentSelection
import com.ankiminer.android.ui.mining.restoredDocumentSelection

internal class SavedDocumentSelectionStore(
    private val savedStateHandle: SavedStateHandle,
    keyPrefix: String,
    private val inventory: SafSelectionInventory? = null,
    private val inventorySlot: SafSelectionSlot? = null,
) {
    private val uriKey = "$keyPrefix.uri"
    private val displayNameKey = "$keyPrefix.displayName"

    fun restore(): SavedDocumentSelection? {
        val durable = inventory?.selection(requireNotNull(inventorySlot))
        val restored =
            durable?.let { SavedDocumentSelection(uri = it.uri, displayName = it.displayName) }
                ?: if (inventory == null) {
                    restoredDocumentSelection(
                        uri = savedStateHandle[uriKey],
                        displayName = savedStateHandle[displayNameKey],
                    )
                } else {
                    null
                }
        if (restored != null) {
            savedStateHandle[uriKey] = restored.uri
            savedStateHandle[displayNameKey] = restored.displayName
        }
        return restored
    }

    fun save(document: SafDocument): Boolean {
        val durableRecord =
            safSelectionRecordOrNull(uri = document.uri, displayName = document.displayName)
        if (inventory != null && durableRecord == null) return false
        inventory?.putSelection(requireNotNull(inventorySlot), requireNotNull(durableRecord))
        savedStateHandle[uriKey] = document.uri
        savedStateHandle[displayNameKey] = document.displayName
        return true
    }

    fun clear() {
        inventory?.putSelection(requireNotNull(inventorySlot), null)
        savedStateHandle.remove<String>(uriKey)
        savedStateHandle.remove<String>(displayNameKey)
    }
}

internal class SavedTextValueStore(
    private val savedStateHandle: SavedStateHandle,
    private val savedStateKey: String,
    private val inventory: SafSelectionInventory? = null,
    private val inventorySlot: SafSelectionSlot? = null,
) {
    fun restore(): String {
        val value =
            inventory?.text(requireNotNull(inventorySlot))
                ?: if (inventory == null) savedStateHandle[savedStateKey] else null
        if (value != null) savedStateHandle[savedStateKey] = value
        return value.orEmpty()
    }

    fun save(value: String) {
        inventory?.putText(requireNotNull(inventorySlot), value.takeIf(String::isNotBlank))
        if (value.isBlank()) {
            savedStateHandle.remove<String>(savedStateKey)
        } else {
            savedStateHandle[savedStateKey] = value
        }
    }

    fun clear() {
        inventory?.putText(requireNotNull(inventorySlot), null)
        savedStateHandle.remove<String>(savedStateKey)
    }
}
