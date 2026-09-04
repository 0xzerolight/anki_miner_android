package com.ankiminer.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Which resource panels on the Resources tab are open.
 *
 * Collapsed is the default: the tab holds four priority lists, and opening on four expanded lists
 * is the scroll that merging Dictionaries, Audio and Frequency into one tab exists to remove.
 *
 * State is hoisted out of the panels because both jump paths — a failure deep link and a
 * settings-search hit — have to open the card they scroll to, and a panel that owns its own state
 * cannot be told to open.
 *
 * Saved as the open keys, so the set survives rotation and process death. A cold start opens
 * collapsed again, which is the state a first install shows anyway; this is a view concern, not a
 * setting, and a persisted key for a disclosure would be a permanent surface for one.
 */
@Stable
internal class SettingsPanelExpansion(
    expandedKeys: Collection<String> = emptySet(),
) {
    private val expanded =
        mutableStateMapOf<String, Boolean>().apply {
            expandedKeys.forEach { key -> put(key, true) }
        }

    fun isExpanded(key: String): Boolean = expanded[key] == true

    fun setExpanded(
        key: String,
        value: Boolean,
    ) {
        expanded[key] = value
    }

    fun expand(key: String) = setExpanded(key, true)

    /** Sorted so an unchanged set saves the same value however the map was written. */
    fun expandedKeys(): List<String> = expanded.filterValues { it }.keys.sorted()

    internal companion object {
        val Saver =
            listSaver<SettingsPanelExpansion, String>(
                save = { it.expandedKeys() },
                restore = ::SettingsPanelExpansion,
            )
    }
}

@Composable
internal fun rememberSettingsPanelExpansion(): SettingsPanelExpansion =
    rememberSaveable(saver = SettingsPanelExpansion.Saver) { SettingsPanelExpansion() }
