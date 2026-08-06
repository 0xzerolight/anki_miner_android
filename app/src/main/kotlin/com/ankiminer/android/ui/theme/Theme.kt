package com.ankiminer.android.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Neutral launch color shared with both XML starting-window themes. */
val LaunchNeutral = Color(0xFF0F172A)

internal fun dynamicColorSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Applies the selected [palette], or its luminance-matched Material You scheme when enabled.
 *
 * Internal because [ThemePalette] is: the theme is only ever applied by this app's own shell and
 * its test source sets, which are friend modules and can see both.
 */
@Composable
internal fun AnkiMinerTheme(
    palette: ThemePalette = ThemePalettes.Dark,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme =
        remember(palette, dynamicColor) {
            if (!dynamicColor || !dynamicColorSupported()) {
                palette.toColorScheme()
            } else if (palette.color(ThemeSlots.BACKGROUND).isDarkSurface()) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AnkiMinerTypography,
        shapes = AnkiMinerShapes,
    ) {
        CompositionLocalProvider(
            LocalDisabledActionColors provides
                disabledActionColorsFor(colorScheme),
            content = content,
        )
    }
}
