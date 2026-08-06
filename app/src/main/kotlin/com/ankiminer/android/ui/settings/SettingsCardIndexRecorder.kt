package com.ankiminer.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Records card positions in Compose snapshot state because a `LazyColumn` content lambda may run
 * during layout, after a `LaunchedEffect` first reads an index. Observable writes let that caller's
 * `snapshotFlow` resume when the card is emitted.
 */
internal class SettingsCardIndexRecorder {
    private data class Card(
        val category: SettingsCategory,
        val key: String,
    )

    private val indices = mutableStateMapOf<Card, Int>()
    private val nextIndices = mutableMapOf<SettingsCategory, Int>()

    /** Highlighted card key, or null. Cleared by the caller once the flash has been seen. */
    var highlightedKey: String? by mutableStateOf<String?>(null)

    fun begin(category: SettingsCategory) {
        nextIndices[category] = FIRST_CARD_INDEX
        indices.keys
            .filter { it.category == category }
            .forEach { indices.remove(it) }
    }

    fun record(
        category: SettingsCategory,
        key: String,
    ): Int {
        val index = nextIndices.getOrPut(category) { FIRST_CARD_INDEX }
        nextIndices[category] = index + 1
        indices[Card(category, key)] = index
        return index
    }

    fun indexOf(
        category: SettingsCategory,
        key: String,
    ): Int? = indices[Card(category, key)]

    internal companion object {
        /** The header occupies 0 and the sticky tab strip 1. */
        const val FIRST_CARD_INDEX = 2
    }
}
