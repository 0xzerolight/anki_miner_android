package com.ankiminer.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSystemTest {
    @Test
    fun colorSchemesDefineEveryRequiredRoleWithoutMaterialPurpleFallbacks() {
        ThemePalettes.all.map { it.key to it.toColorScheme() }.forEach { (paletteKey, colors) ->
            val required =
                listOf(
                    colors.primary,
                    colors.onPrimary,
                    colors.primaryContainer,
                    colors.onPrimaryContainer,
                    colors.inversePrimary,
                    colors.secondary,
                    colors.onSecondary,
                    colors.secondaryContainer,
                    colors.onSecondaryContainer,
                    colors.tertiary,
                    colors.onTertiary,
                    colors.tertiaryContainer,
                    colors.onTertiaryContainer,
                    colors.background,
                    colors.onBackground,
                    colors.surface,
                    colors.onSurface,
                    colors.surfaceVariant,
                    colors.onSurfaceVariant,
                    colors.surfaceTint,
                    colors.inverseSurface,
                    colors.inverseOnSurface,
                    colors.error,
                    colors.onError,
                    colors.errorContainer,
                    colors.onErrorContainer,
                    colors.outline,
                    colors.outlineVariant,
                    colors.scrim,
                    colors.surfaceBright,
                    colors.surfaceDim,
                    colors.surfaceContainerLowest,
                    colors.surfaceContainerLow,
                    colors.surfaceContainer,
                    colors.surfaceContainerHigh,
                    colors.surfaceContainerHighest,
                    colors.primaryFixed,
                    colors.primaryFixedDim,
                    colors.onPrimaryFixed,
                    colors.onPrimaryFixedVariant,
                    colors.secondaryFixed,
                    colors.secondaryFixedDim,
                    colors.onSecondaryFixed,
                    colors.onSecondaryFixedVariant,
                    colors.tertiaryFixed,
                    colors.tertiaryFixedDim,
                    colors.onTertiaryFixed,
                    colors.onTertiaryFixedVariant,
                )
            assertTrue("$paletteKey has an unspecified color role", required.none { it == Color.Unspecified })
            assertNotEquals(
                "$paletteKey primary used the Material baseline purple",
                Color(0xFF6750A4),
                colors.primary,
            )
        }
    }

    @Test
    fun semanticTextAndContainerPairsMeetReadableContrastTarget() {
        ThemePalettes.all.map { it.key to it.toColorScheme() }.forEach { (paletteKey, colors) ->
            val pairs =
                listOf(
                    "primary" to (colors.onPrimary to colors.primary),
                    "primaryContainer" to
                        (colors.onPrimaryContainer to colors.primaryContainer),
                    "secondary" to (colors.onSecondary to colors.secondary),
                    "secondaryContainer" to
                        (colors.onSecondaryContainer to colors.secondaryContainer),
                    "tertiary" to (colors.onTertiary to colors.tertiary),
                    "tertiaryContainer" to
                        (colors.onTertiaryContainer to colors.tertiaryContainer),
                    "background" to (colors.onBackground to colors.background),
                    "surface" to (colors.onSurface to colors.surface),
                    "surfaceVariant" to
                        (colors.onSurfaceVariant to colors.surfaceVariant),
                    "inverseSurface" to
                        (colors.inverseOnSurface to colors.inverseSurface),
                    "error" to (colors.onError to colors.error),
                    "errorContainer" to
                        (colors.onErrorContainer to colors.errorContainer),
                    "primaryFixed" to (colors.onPrimaryFixed to colors.primaryFixed),
                    "secondaryFixed" to
                        (colors.onSecondaryFixed to colors.secondaryFixed),
                    "tertiaryFixed" to
                        (colors.onTertiaryFixed to colors.tertiaryFixed),
                    // Curation rows: both fills, and both text weights that sit on them. The
                    // word is onSurface, its metadata line onSurfaceVariant.
                    "selectedRowContainer" to
                        (colors.onSurface to colors.selectedRowContainer()),
                    "selectedRowContainerVariant" to
                        (colors.onSurfaceVariant to colors.selectedRowContainer()),
                    "unselectedRowContainer" to
                        (colors.onSurface to colors.surfaceContainerLow),
                    "unselectedRowContainerVariant" to
                        (colors.onSurfaceVariant to colors.surfaceContainerLow),
                )
            pairs.forEach { (roleName, pair) ->
                assertTrue(
                    "$paletteKey $roleName contrast was ${contrastRatio(pair.first, pair.second)}",
                    contrastRatio(pair.first, pair.second) >= ReadableContrast,
                )
            }
        }
    }

    @Test
    fun outlinesSeparateFromThePageTheyAreDrawnOn() {
        ThemePalettes.all.map { it.key to it.toColorScheme() }.forEach { (paletteKey, colors) ->
            listOf(
                "background" to colors.background,
                "surfaceContainer" to colors.surfaceContainer,
            ).forEach { (surfaceName, surface) ->
                assertTrue(
                    "$paletteKey outline on $surfaceName was ${contrastRatio(colors.outline, surface)}",
                    contrastRatio(colors.outline, surface) >= SeparationContrast,
                )
            }
        }
    }

    @Test
    fun disabledActionContentAndBordersMeetReadableContrastTarget() {
        ThemePalettes.all.map { it.key to it.toColorScheme() }.forEach { (paletteKey, scheme) ->
            val colors = disabledActionColorsFor(scheme)
            assertTrue(
                "$paletteKey disabled content/container was ${contrastRatio(colors.content, colors.container)}",
                contrastRatio(colors.content, colors.container) >= ReadableContrast,
            )
            assertTrue(
                "$paletteKey disabled border/container was ${contrastRatio(colors.border, colors.container)}",
                contrastRatio(colors.border, colors.container) >= ReadableContrast,
            )
            assertTrue(
                "$paletteKey disabled content/background was ${contrastRatio(colors.content, scheme.background)}",
                contrastRatio(colors.content, scheme.background) >= ReadableContrast,
            )
            assertTrue(
                "$paletteKey disabled content/error container was ${contrastRatio(colors.content, scheme.errorContainer)}",
                contrastRatio(colors.content, scheme.errorContainer) >= ReadableContrast,
            )
            assertTrue(
                "$paletteKey disabled border/background was ${contrastRatio(colors.border, scheme.background)}",
                contrastRatio(colors.border, scheme.background) >= SeparationContrast,
            )
            assertNotEquals(
                "$paletteKey disabled and enabled action containers matched",
                colors.container,
                colors.enabledContainer,
            )
        }
    }
}
