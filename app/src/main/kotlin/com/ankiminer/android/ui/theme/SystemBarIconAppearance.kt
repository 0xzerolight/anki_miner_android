package com.ankiminer.android.ui.theme

import androidx.compose.ui.graphics.Color

internal enum class SystemBarIconAppearance {
    LIGHT,
    DARK,
}

/**
 * Decided from the page the bars sit over, not from the chosen mode: a light palette picked as the
 * dark-mode theme still needs dark icons.
 */
internal fun systemBarIconAppearance(background: Color): SystemBarIconAppearance =
    if (background.isDarkSurface()) SystemBarIconAppearance.LIGHT else SystemBarIconAppearance.DARK
