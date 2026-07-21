package com.ankiminer.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemBarIconAppearanceTest {
    @Test
    fun lightAppUsesDarkIconsEvenWhenSystemThemeIsDark() {
        assertEquals(
            SystemBarIconAppearance.DARK,
            systemBarIconAppearance(darkTheme = false),
        )
    }

    @Test
    fun darkAppUsesLightIconsEvenWhenSystemThemeIsLight() {
        assertEquals(
            SystemBarIconAppearance.LIGHT,
            systemBarIconAppearance(darkTheme = true),
        )
    }
}
