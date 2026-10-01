package com.ankiminer.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `grouping lists the app defaults first, then families, then the rest under Other`() {
        val groups = ThemePalettes.grouped()

        assertEquals(29, groups.sumOf { it.palettes.size })
        assertEquals(ThemePaletteGroupKind.APP_DEFAULTS, groups.first().kind)
        assertEquals(listOf("light", "dark"), groups.first().palettes.map { it.key })
        val catppuccin = groups.single { it.family == "Catppuccin" }
        assertEquals(ThemePaletteGroupKind.FAMILY, catppuccin.kind)
        assertEquals(
            setOf("catppuccin-frappe", "catppuccin-latte", "catppuccin-macchiato", "catppuccin-mocha"),
            catppuccin.palettes.map { it.key }.toSet(),
        )
        val other = groups.last()
        assertEquals(ThemePaletteGroupKind.OTHER, other.kind)
        assertEquals(listOf("nord", "one-dark", "sakura", "tokyo-night"), other.palettes.map { it.key })
        assertEquals(1, groups.count { it.kind == ThemePaletteGroupKind.OTHER })
    }
}
