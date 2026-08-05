package com.ankiminer.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.ankiminer.android.ui.theme.generated.GeneratedThemePalettes

internal data class ThemePalette(
    val key: String,
    val displayName: String,
    val family: String?,
    val variant: String?,
    val colors: Map<String, Color>,
) {
    /** Name shown inside a family group; the display name when the theme stands alone. */
    val variantName: String get() = variant ?: displayName
}

internal fun ThemePalette.color(slot: String): Color =
    colors[slot] ?: error("Theme $key has no slot $slot")

internal object ThemeSlots {
    const val PRIMARY = "primary"
    const val PRIMARY_LIGHT = "primary-light"
    const val PRIMARY_DARK = "primary-dark"
    const val SECONDARY = "secondary"
    const val BACKGROUND = "background"
    const val TEXT = "text"
    const val TEXT_MUTED = "text-muted"
    const val TEXT_DISABLED = "text-disabled"
    const val TEXT_ON_PRIMARY = "text-on-primary"
    const val BORDER = "border"
    const val BORDER_SUBTLE = "border-subtle"
    const val ERROR = "error"
    const val INFO = "info"
    const val TOOLTIP_BG = "tooltip-bg"
    const val TOOLTIP_TEXT = "tooltip-text"
    const val BADGE_ERROR_BG = "badge-error-bg"
    const val BADGE_ERROR_TEXT = "badge-error-text"
    const val BADGE_INFO_BG = "badge-info-bg"
    const val BADGE_INFO_TEXT = "badge-info-text"
    const val TABLE_SELECTED_BG = "table-selected-bg"
    const val TABLE_SELECTED_TEXT = "table-selected-text"
}

internal object ThemePalettes {
    val all: List<ThemePalette> = GeneratedThemePalettes.all
    val byKey: Map<String, ThemePalette> = all.associateBy { it.key }
    val Light: ThemePalette = requireByKey("light")
    val Dark: ThemePalette = requireByKey("dark")

    fun requireByKey(key: String): ThemePalette =
        byKey[key] ?: error("Unknown theme key: $key")

    fun grouped(): List<Pair<String?, List<ThemePalette>>> {
        val groups = mutableListOf<Pair<String?, MutableList<ThemePalette>>>()
        val familyGroups = mutableMapOf<String, MutableList<ThemePalette>>()

        for (palette in all) {
            val family = palette.family
            if (family == null) {
                groups += null to mutableListOf(palette)
            } else {
                val group =
                    familyGroups.getOrPut(family) {
                        mutableListOf<ThemePalette>().also { groups += family to it }
                    }
                group += palette
            }
        }
        return groups.map { (family, palettes) -> family to palettes.toList() }
    }
}
