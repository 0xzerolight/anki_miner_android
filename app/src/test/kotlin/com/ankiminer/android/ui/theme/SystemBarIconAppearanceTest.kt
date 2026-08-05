package com.ankiminer.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemBarIconAppearanceTest {
    @Test
    fun darkPaletteBackgroundUsesLightIcons() {
        assertEquals(
            SystemBarIconAppearance.LIGHT,
            systemBarIconAppearance(ThemePalettes.Dark.color(ThemeSlots.BACKGROUND)),
        )
    }

    @Test
    fun lightPaletteBackgroundUsesDarkIcons() {
        assertEquals(
            SystemBarIconAppearance.DARK,
            systemBarIconAppearance(ThemePalettes.Light.color(ThemeSlots.BACKGROUND)),
        )
    }

    @Test
    fun lightPaletteChosenForDarkThemeStillUsesDarkIcons() {
        val catppuccinLatte = ThemePalettes.requireByKey("catppuccin-latte")

        assertEquals(
            SystemBarIconAppearance.DARK,
            systemBarIconAppearance(catppuccinLatte.color(ThemeSlots.BACKGROUND)),
        )
    }

    @Test
    fun launchNeutralMatchesDefaultDarkPaletteBackground() {
        assertEquals(
            ThemePalettes.Dark.color(ThemeSlots.BACKGROUND),
            LaunchNeutral,
        )
    }
}
