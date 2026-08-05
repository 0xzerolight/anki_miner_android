package com.ankiminer.android.ui.theme

import com.ankiminer.android.data.settings.AppSettings
import com.ankiminer.android.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedThemeTest {
    @Test
    fun `light mode ignores a dark system setting`() {
        val resolved =
            resolveTheme(
                AppSettings(
                    theme = ThemeMode.LIGHT,
                    lightThemeKey = "solarized-light",
                    darkThemeKey = "catppuccin-mocha",
                ),
                systemInDarkTheme = true,
            )

        assertEquals("solarized-light", resolved.palette.key)
    }

    @Test
    fun `dark mode ignores a light system setting`() {
        val resolved =
            resolveTheme(
                AppSettings(
                    theme = ThemeMode.DARK,
                    lightThemeKey = "solarized-light",
                    darkThemeKey = "catppuccin-mocha",
                ),
                systemInDarkTheme = false,
            )

        assertEquals("catppuccin-mocha", resolved.palette.key)
    }

    @Test
    fun `system mode uses the matching palette pick`() {
        val settings =
            AppSettings(
                theme = ThemeMode.SYSTEM,
                lightThemeKey = "solarized-light",
                darkThemeKey = "catppuccin-mocha",
            )

        assertEquals("solarized-light", resolveTheme(settings, systemInDarkTheme = false).palette.key)
        assertEquals("catppuccin-mocha", resolveTheme(settings, systemInDarkTheme = true).palette.key)
    }

    @Test
    fun `unknown keys use the shipped palette for the active appearance`() {
        val settings =
            AppSettings(
                theme = ThemeMode.SYSTEM,
                lightThemeKey = "missing-light",
                darkThemeKey = "missing-dark",
            )

        assertEquals("light", resolveTheme(settings, systemInDarkTheme = false).palette.key)
        assertEquals("dark", resolveTheme(settings, systemInDarkTheme = true).palette.key)
    }

    @Test
    fun `dynamic colour setting passes through unchanged`() {
        assertEquals(
            false,
            resolveTheme(AppSettings(dynamicColorEnabled = false), systemInDarkTheme = false).dynamicColor,
        )
        assertEquals(
            true,
            resolveTheme(AppSettings(dynamicColorEnabled = true), systemInDarkTheme = false).dynamicColor,
        )
    }
}
