package com.ankiminer.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteSchemeTest {
    @Test
    fun contrastRatioMatchesKnownWcagValues() {
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.001)
        assertEquals(4.54, contrastRatio(Color(0xFF767676), Color.White), 0.01)
    }

    @Test
    fun readableOnPrefersTheFirstPassingCandidate() {
        val preferred = Color(0xFF767676)

        assertEquals(
            preferred,
            readableOn(
                backgrounds = listOf(Color.White),
                candidates = listOf(preferred, Color.Black),
            ),
        )
    }

    @Test
    fun readableOnFallsBackToWhiteOrBlackWhenCandidatesFail() {
        assertEquals(
            Color.White,
            readableOn(
                backgrounds = listOf(Color.Black),
                candidates = listOf(Color.Black),
                minimum = 30.0,
            ),
        )
    }

    @Test
    fun pushedToContrastMovesTheMinimumDistance() {
        val background = Color.White
        val alreadyReadable = Color(0xFF767676)
        val candidate = Color(0xFF7A8190)

        assertEquals(
            alreadyReadable,
            pushedToContrast(
                candidate = alreadyReadable,
                backgrounds = listOf(background),
                minimum = ReadableContrast,
            ),
        )

        val pushed =
            pushedToContrast(
                candidate = candidate,
                backgrounds = listOf(background),
                minimum = ReadableContrast,
            )
        val fallback =
            listOf(Color.White, Color.Black).maxBy { fallbackCandidate ->
                contrastRatio(fallbackCandidate, background)
            }

        val pushedContrast = contrastRatio(pushed, background)
        assertTrue(
            "pushed contrast was $pushedContrast",
            pushedContrast >= ReadableContrast,
        )
        assertTrue(
            "pushed color was not closer to its candidate than the fallback",
            rgbDistanceSquared(candidate, pushed) < rgbDistanceSquared(candidate, fallback),
        )
    }

    @Test
    fun defaultsClassifyTheirPageSurfacesCorrectly() {
        assertFalse(ThemePalettes.Light.color(ThemeSlots.BACKGROUND).isDarkSurface())
        assertTrue(ThemePalettes.Dark.color(ThemeSlots.BACKGROUND).isDarkSurface())
    }

    @Test
    fun schemesKeepTheirPalettePrimaryAndBackground() {
        ThemePalettes.all.forEach { palette ->
            val scheme = palette.toColorScheme()

            assertEquals("${palette.key} primary", palette.color(ThemeSlots.PRIMARY), scheme.primary)
            assertEquals("${palette.key} background", palette.color(ThemeSlots.BACKGROUND), scheme.background)
        }
    }

    @Test
    fun surfaceContainerRampIsMonotonicAcrossAllPalettes() {
        ThemePalettes.all.forEach { palette ->
            val scheme = palette.toColorScheme()
            val ramp =
                listOf(
                    scheme.surfaceContainerLowest,
                    scheme.surfaceContainerLow,
                    scheme.surfaceContainer,
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainerHighest,
                ).map(::relativeLuminance)
            val expected =
                if (scheme.background.isDarkSurface()) {
                    ramp.sorted()
                } else {
                    ramp.sortedDescending()
                }

            assertEquals("${palette.key} surface container ramp", expected, ramp)
        }
    }

    @Test
    fun selectedRowsSeparateFromTheirUnselectedContainerAcrossAllPalettes() {
        ThemePalettes.all.forEach { palette ->
            val scheme = palette.toColorScheme()
            val ratio = contrastRatio(scheme.selectedRowContainer(), scheme.surfaceContainerLow)

            assertTrue("${palette.key} selected row separation was $ratio", ratio >= 1.20)
        }
    }

    @Test
    fun onSurfaceAndOnSurfaceVariantKeepThePalettesOwnHue() {
        ThemePalettes.all.forEach { palette ->
            val scheme = palette.toColorScheme()
            val text = palette.color(ThemeSlots.TEXT)
            val textMuted = palette.color(ThemeSlots.TEXT_MUTED)

            if (text != Color.White && text != Color.Black) {
                assertNotEquals(
                    "${palette.key} onSurface became white",
                    Color.White,
                    scheme.onSurface,
                )
                assertNotEquals(
                    "${palette.key} onSurface became black",
                    Color.Black,
                    scheme.onSurface,
                )
            }
            if (textMuted != Color.White && textMuted != Color.Black) {
                assertNotEquals(
                    "${palette.key} onSurfaceVariant became white",
                    Color.White,
                    scheme.onSurfaceVariant,
                )
                assertNotEquals(
                    "${palette.key} onSurfaceVariant became black",
                    Color.Black,
                    scheme.onSurfaceVariant,
                )
            }
        }
    }

    @Test
    fun mutedTextStaysDistinctFromBodyText() {
        val distinctCount =
            ThemePalettes.all.count { palette ->
                val scheme = palette.toColorScheme()
                scheme.onSurfaceVariant != scheme.onSurface
            }

        // A floor, not an exact count: four palettes (ayu-light, everforest-light, one-dark,
        // rose-pine-dawn) define a body text colour that is itself below AA, so it has to be
        // pushed to the 4.5:1 floor and the muted variant legitimately collapses onto it. Those
        // are allowed to coincide; a drop below this floor means the ordering clamp regressed.
        assertTrue(
            "Expected at least 24 palettes with distinct muted text; " +
                "actual $distinctCount of ${ThemePalettes.all.size}",
            distinctCount >= 24,
        )
    }

    @Test
    fun mutedTextIsNeverStrongerThanBodyText() {
        ThemePalettes.all.forEach { palette ->
            val scheme = palette.toColorScheme()
            val bodyContrast = contrastRatio(scheme.onSurface, scheme.background)
            val mutedContrast = contrastRatio(scheme.onSurfaceVariant, scheme.background)

            assertTrue(
                "${palette.key} muted contrast $mutedContrast exceeded body contrast $bodyContrast",
                mutedContrast <= bodyContrast + 0.0001,
            )
        }
    }

    private fun rgbDistanceSquared(first: Color, second: Color): Float =
        (first.red - second.red) * (first.red - second.red) +
            (first.green - second.green) * (first.green - second.green) +
            (first.blue - second.blue) * (first.blue - second.blue)

    @Test
    fun accentTextIsReadableOnEveryPageSurfaceAcrossAllPalettes() {
        ThemePalettes.all.forEach { palette ->
            val scheme = palette.toColorScheme()
            val accent = scheme.accentText()
            listOf(
                "background" to scheme.background,
                "surfaceContainerLowest" to scheme.surfaceContainerLowest,
                "surfaceContainerLow" to scheme.surfaceContainerLow,
                "surfaceContainer" to scheme.surfaceContainer,
                "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            ).forEach { (name, surface) ->
                val ratio = contrastRatio(accent, surface)
                assertTrue("${palette.key} accent text on $name was $ratio", ratio >= ReadableContrast)
            }
        }
    }

    @Test
    fun accentTextKeepsAPrimaryThatAlreadyReads() {
        val scheme = ThemePalettes.Light.toColorScheme().copy(primary = Color(0xFF1B5E20))

        assertEquals(scheme.primary, scheme.accentText())
    }

    /**
     * Stands in for Material You and dynamic schemes, which the 29-palette table never produces:
     * the Material baselines plus a pale and a deep primary that the derivation must still rescue.
     */
    @Test
    fun accentTextAndDisabledOutlineHoldOnMaterialBaselineSchemes() {
        listOf(
            "light" to lightColorScheme(),
            "dark" to darkColorScheme(),
            "light pale primary" to lightColorScheme(primary = Color(0xFFFFD54F)),
            "dark deep primary" to darkColorScheme(primary = Color(0xFF311B92)),
        ).forEach { (name, scheme) ->
            listOf(
                scheme.background,
                scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            ).forEach { surface ->
                val ratio = contrastRatio(scheme.accentText(), surface)
                assertTrue("$name accent text was $ratio", ratio >= ReadableContrast)
            }
            val disabled = disabledActionColorsFor(scheme)
            assertTrue(
                "$name disabled outline is not quieter than the enabled one",
                contrastRatio(disabled.border, scheme.background) < contrastRatio(scheme.outline, scheme.background),
            )
            assertTrue(
                "$name disabled label was ${contrastRatio(disabled.content, disabled.container)}",
                contrastRatio(disabled.content, disabled.container) >= ReadableContrast,
            )
        }
    }
}
