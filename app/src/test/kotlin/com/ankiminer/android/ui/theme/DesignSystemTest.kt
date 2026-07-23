package com.ankiminer.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSystemTest {
    @Test
    fun colorSchemesDefineEveryRequiredRoleWithoutMaterialPurpleFallbacks() {
        listOf(LightColors, DarkColors).forEach { colors ->
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
            assertTrue(required.none { it == Color.Unspecified })
            assertNotEquals(Color(0xFF6750A4), colors.primary)
        }
    }

    @Test
    fun semanticTextAndContainerPairsMeetReadableContrastTarget() {
        listOf("light" to LightColors, "dark" to DarkColors).forEach { (schemeName, colors) ->
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
                    "tertiaryFixed" to (colors.onTertiaryFixed to colors.tertiaryFixed),
                )
            pairs.forEach { (roleName, pair) ->
                assertTrue(
                    "$schemeName $roleName contrast was ${contrast(pair.first, pair.second)}",
                    contrast(pair.first, pair.second) >= 4.5,
                )
            }
        }
    }

    @Test
    fun disabledActionContentAndBordersMeetReadableContrastTarget() {
        listOf(
            LightDisabledActionColors to LightColors,
            DarkDisabledActionColors to DarkColors,
        ).forEach { (colors, scheme) ->
            assertTrue(contrast(colors.content, colors.container) >= 4.5)
            assertTrue(contrast(colors.border, colors.container) >= 4.5)
            assertTrue(contrast(colors.content, scheme.background) >= 4.5)
            assertTrue(contrast(colors.content, scheme.errorContainer) >= 4.5)
            assertTrue(contrast(colors.border, scheme.background) >= 3.0)
            assertTrue(colors.container != colors.enabledContainer)
        }
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val normalized = value.toDouble()
            return if (normalized <= 0.04045) {
                normalized / 12.92
            } else {
                ((normalized + 0.055) / 1.055).pow(2.4)
            }
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
