package com.ankiminer.android.ui.theme

internal enum class SystemBarIconAppearance {
    LIGHT,
    DARK,
}

internal fun systemBarIconAppearance(darkTheme: Boolean): SystemBarIconAppearance =
    if (darkTheme) SystemBarIconAppearance.LIGHT else SystemBarIconAppearance.DARK
