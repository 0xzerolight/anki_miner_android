package com.ankiminer.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.ThemeMode
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

/** How a run of palettes is headed in the theme picker. */
internal enum class ThemePaletteGroupKind {
    /** The app's own Light and Dark: listed first, with no header. */
    APP_DEFAULTS,

    /** One upstream theme family, headed by its name. */
    FAMILY,

    /** Every palette with no family, under one "Other" header. */
    OTHER,
}

internal data class ThemePaletteGroup(
    val kind: ThemePaletteGroupKind,
    /** The family name for [ThemePaletteGroupKind.FAMILY]; null otherwise. */
    val family: String?,
    val palettes: List<ThemePalette>,
)

internal object ThemePalettes {
    val all: List<ThemePalette> = GeneratedThemePalettes.all
    val byKey: Map<String, ThemePalette> = all.associateBy { it.key }
    val Light: ThemePalette = requireByKey("light")
    val Dark: ThemePalette = requireByKey("dark")

    fun requireByKey(key: String): ThemePalette =
        byKey[key] ?: error("Unknown theme key: $key")

    fun grouped(): List<ThemePaletteGroup> {
        val defaults = listOf(Light, Dark)
        val families = linkedMapOf<String, MutableList<ThemePalette>>()
        val others = mutableListOf<ThemePalette>()
        for (palette in all) {
            if (palette in defaults) continue
            val family = palette.family
            if (family == null) {
                others += palette
            } else {
                families.getOrPut(family) { mutableListOf() } += palette
            }
        }
        return buildList {
            add(ThemePaletteGroup(ThemePaletteGroupKind.APP_DEFAULTS, null, defaults))
            families.forEach { (family, palettes) ->
                add(ThemePaletteGroup(ThemePaletteGroupKind.FAMILY, family, palettes))
            }
            if (others.isNotEmpty()) add(ThemePaletteGroup(ThemePaletteGroupKind.OTHER, null, others))
        }
    }
}

/** The palette the shell paints with, plus whether device colours replace it. */
internal data class ResolvedTheme(
    val palette: ThemePalette,
    val dynamicColor: Boolean,
)

/**
 * A store that survived validation cannot hold an unknown key, but a shell read that fell back to
 * defaults can still name one, so an unrecognised key resolves to the shipped palette rather than
 * throwing on the first frame.
 */
internal fun resolveTheme(
    settings: AppSettings,
    systemInDarkTheme: Boolean,
): ResolvedTheme {
    val useDark =
        when (settings.theme) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> systemInDarkTheme
        }
    val key = if (useDark) settings.darkThemeKey else settings.lightThemeKey
    val fallback = if (useDark) ThemePalettes.Dark else ThemePalettes.Light
    return ResolvedTheme(
        palette = ThemePalettes.byKey[key] ?: fallback,
        dynamicColor = settings.dynamicColorEnabled,
    )
}
