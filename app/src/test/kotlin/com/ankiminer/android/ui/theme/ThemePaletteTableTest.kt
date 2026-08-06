package com.ankiminer.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTableTest {
    @Test
    fun `all palettes have distinct keys`() {
        assertEquals(29, ThemePalettes.all.size)
        assertEquals(29, ThemePalettes.all.map { it.key }.toSet().size)
    }

    @Test
    fun `light and dark palette keys remain stable`() {
        assertEquals("light", ThemePalettes.Light.key)
        assertEquals("dark", ThemePalettes.Dark.key)
    }

    @Test
    fun `all palette slots are opaque specified colors`() {
        for (palette in ThemePalettes.all) {
            assertEquals("${palette.key} slot count", 46, palette.colors.size)
            for ((slot, color) in palette.colors) {
                assertNotEquals("${palette.key}: $slot is unspecified", Color.Unspecified, color)
                assertEquals("${palette.key}: $slot must be opaque", 1f, color.alpha, 0f)
            }
        }
    }

    @Test
    fun `grouping keeps families together and standalone themes separate`() {
        val groups = ThemePalettes.grouped()

        assertEquals(29, groups.sumOf { it.second.size })
        val catppuccin = groups.single { it.first == "Catppuccin" }
        assertEquals(
            setOf(
                "catppuccin-frappe",
                "catppuccin-latte",
                "catppuccin-macchiato",
                "catppuccin-mocha",
            ),
            catppuccin.second.map { it.key }.toSet(),
        )
        assertTrue(
            groups.any { (family, palettes) ->
                family == null && palettes.map { it.key } == listOf("nord")
            },
        )
    }
}
