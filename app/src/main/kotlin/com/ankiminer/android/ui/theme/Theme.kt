package com.ankiminer.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Neutral launch color shared with both XML starting-window themes. */
val LaunchNeutral = Color(0xFF17211F)

internal val LightColors =
    lightColorScheme(
        primary = Color(0xFF006A61),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF9EF2E7),
        onPrimaryContainer = Color(0xFF00201C),
        inversePrimary = Color(0xFF53DBCC),
        secondary = Color(0xFF4A635F),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFCDE8E2),
        onSecondaryContainer = Color(0xFF06201C),
        tertiary = Color(0xFF46617A),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFCFE5FF),
        onTertiaryContainer = Color(0xFF001D33),
        background = Color(0xFFF5FBF8),
        onBackground = Color(0xFF171D1B),
        surface = Color(0xFFF5FBF8),
        onSurface = Color(0xFF171D1B),
        surfaceVariant = Color(0xFFDAE5E1),
        onSurfaceVariant = Color(0xFF3F4946),
        surfaceTint = Color(0xFF006A61),
        inverseSurface = Color(0xFF2C322F),
        inverseOnSurface = Color(0xFFECF2EF),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF6F7976),
        outlineVariant = Color(0xFFBEC9C5),
        scrim = Color.Black,
        surfaceBright = Color(0xFFF5FBF8),
        surfaceDim = Color(0xFFD5DBD8),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFEFF5F2),
        surfaceContainer = Color(0xFFE9EFEC),
        surfaceContainerHigh = Color(0xFFE3E9E6),
        surfaceContainerHighest = Color(0xFFDDE4E0),
        primaryFixed = Color(0xFF74F8E8),
        primaryFixedDim = Color(0xFF53DBCC),
        onPrimaryFixed = Color(0xFF00201C),
        onPrimaryFixedVariant = Color(0xFF005048),
        secondaryFixed = Color(0xFFCDE8E2),
        secondaryFixedDim = Color(0xFFB1CCC6),
        onSecondaryFixed = Color(0xFF06201C),
        onSecondaryFixedVariant = Color(0xFF334B47),
        tertiaryFixed = Color(0xFFCFE5FF),
        tertiaryFixedDim = Color(0xFFAFCBE8),
        onTertiaryFixed = Color(0xFF001D33),
        onTertiaryFixedVariant = Color(0xFF2E4A61),
    )

internal val DarkColors =
    darkColorScheme(
        primary = Color(0xFF53DBCC),
        onPrimary = Color(0xFF003731),
        primaryContainer = Color(0xFF005048),
        onPrimaryContainer = Color(0xFF74F8E8),
        inversePrimary = Color(0xFF006A61),
        secondary = Color(0xFFB1CCC6),
        onSecondary = Color(0xFF1C3531),
        secondaryContainer = Color(0xFF334B47),
        onSecondaryContainer = Color(0xFFCDE8E2),
        tertiary = Color(0xFFAFCBE8),
        onTertiary = Color(0xFF173349),
        tertiaryContainer = Color(0xFF2E4A61),
        onTertiaryContainer = Color(0xFFCFE5FF),
        background = Color(0xFF0F1513),
        onBackground = Color(0xFFDEE4E1),
        surface = Color(0xFF0F1513),
        onSurface = Color(0xFFDEE4E1),
        surfaceVariant = Color(0xFF3F4946),
        onSurfaceVariant = Color(0xFFBEC9C5),
        surfaceTint = Color(0xFF53DBCC),
        inverseSurface = Color(0xFFDEE4E1),
        inverseOnSurface = Color(0xFF2C322F),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF89938F),
        outlineVariant = Color(0xFF3F4946),
        scrim = Color.Black,
        surfaceBright = Color(0xFF353B39),
        surfaceDim = Color(0xFF0F1513),
        surfaceContainerLowest = Color(0xFF0A100E),
        surfaceContainerLow = Color(0xFF171D1B),
        surfaceContainer = Color(0xFF1B211F),
        surfaceContainerHigh = Color(0xFF252B29),
        surfaceContainerHighest = Color(0xFF303634),
        primaryFixed = Color(0xFF74F8E8),
        primaryFixedDim = Color(0xFF53DBCC),
        onPrimaryFixed = Color(0xFF00201C),
        onPrimaryFixedVariant = Color(0xFF005048),
        secondaryFixed = Color(0xFFCDE8E2),
        secondaryFixedDim = Color(0xFFB1CCC6),
        onSecondaryFixed = Color(0xFF06201C),
        onSecondaryFixedVariant = Color(0xFF334B47),
        tertiaryFixed = Color(0xFFCFE5FF),
        tertiaryFixedDim = Color(0xFFAFCBE8),
        onTertiaryFixed = Color(0xFF001D33),
        onTertiaryFixedVariant = Color(0xFF2E4A61),
    )

/** Theme follows the persisted app setting, never the system theme; dark is the default. */
@Composable
fun AnkiMinerTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AnkiMinerTypography,
        shapes = AnkiMinerShapes,
    ) {
        // Published explicitly so disabled tokens never have to infer the active theme by
        // comparing a color against DarkColors.background.
        CompositionLocalProvider(
            LocalDisabledActionColors provides
                if (darkTheme) DarkDisabledActionColors else LightDisabledActionColors,
            content = content,
        )
    }
}
